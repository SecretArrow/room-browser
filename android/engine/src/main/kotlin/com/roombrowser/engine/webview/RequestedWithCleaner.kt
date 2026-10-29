package com.roombrowser.engine.webview

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.MimeTypeMap
import com.roombrowser.engine.GoogleAuthNavigation
import java.net.HttpURLConnection
import java.net.URL

/**
 * The PRE-EMPTIVE half of the X-Requested-With answer: when a request to one
 * of Google's sign-in surfaces is about to go out CARRYING the provider's
 * package stamp, re-issue it over a plain connection without the stamp and
 * hand the response back in the engine's place.
 *
 * WHY THE ENGINE NEEDED A SECOND HALF. The levers that existed all reach
 * requests the APP issues (`loadUrl(uri, identityHeaders)`) or navigations
 * the page starts (`shouldOverrideUrlLoading` re-issue) -- and the redirect
 * gap they cannot close is documented on [GoogleAuthNavigation
 * .shouldReissueAfterRedirect]: a CROSS-ORIGIN server 302 re-requests without
 * the app's headers, so the engine re-stamps it, and that one request is
 * exactly the `accounts.google.com/ServiceLogin` document a hundred Google
 * properties redirect into. The onPageStarted re-issue answers it AFTER the
 * fact -- the stamped request has already reached Google's logs, and the
 * field reports (plus the user's own comparison: a bare wrapper signs in,
 * this app is refused on the same device, network and account) say one
 * stamped request is enough for the refusal to win the grading.
 *
 * `shouldInterceptRequest` observes EVERY request before it goes out --
 * including the redirect hop nobody could reach, and including the sign-in
 * hosts' own sub-resources (the OAuth iframes, the XHR beacons) that the
 * main-frame levers never touched. This object turns that observation into
 * the one lever the platform never shipped: a request that would have gone
 * out stamped goes out clean instead.
 *
 * THE NARROWNESS, deliberately strict:
 *  * GET only. A form POST re-issued from `shouldInterceptRequest` would
 *    silently lose its body -- `WebResourceRequest` does not carry one -- so
 *    POSTs are never touched. The refusal grades the document GETs; the
 *    password POST rides a session the clean GETs already established.
 *  * Main frame, or a resource whose OWN host is a sign-in host. Sub-resources
 *    on other hosts (the gstatic fonts, the googleapis scripts) are left to
 *    the engine untouched -- the fast path this design buys back.
 *  * Only when the request actually carries a non-empty stamp. A main frame
 *    that went out through `loadUrl`'s empty override is already clean.
 *
 * THE EXECUTION. A plain `HttpURLConnection` re-issues the GET with the
 * request's own headers minus the ones that would either re-introduce the
 * stamp (`X-Requested-With`) or double-encode the body (`Accept-Encoding`,
 * which the connection then manages transparently). The request's `Cookie`
 * header is copied verbatim -- it IS the engine's jar view -- and every
 * response `Set-Cookie` is fed back into [CookieManager], so the session's
 * cookie state stays exactly what the engine would have built. Redirects are
 * followed by the connection itself, which is a quiet bonus: a hop the
 * ENGINE would have re-stamped is re-issued by code that never stamps.
 *
 * EVERY failure mode answers `null`, which returns the request to the
 * engine untouched -- the behaviour this object was added on top of. The
 * cleaner can only fail back into the world it found.
 */
object RequestedWithCleaner {

    /** The header this whole subsystem exists to keep off the wire. */
    const val HEADER = "X-Requested-With"

    /**
     * Request headers the re-issue never copies: the stamp itself, the two
     * whose values the connection re-derives for itself (`Host`,
     * `Content-Length`), and `Accept-Encoding`, which must be absent so the
     * connection adds its own and decompresses transparently -- a copied
     * value would hand back a gzip body the response object would mislabel.
     */
    internal val DROPPED_REQUEST_HEADERS =
        setOf("x-requested-with", "host", "content-length", "accept-encoding")

    /**
     * Response headers never copied onto the rebuilt response: the three
     * whose values describe the wire body the connection already unwound
     * (`Content-Encoding`, `Content-Length`, `Transfer-Encoding`), and
     * `Set-Cookie`, which is consumed into the cookie jar instead.
     */
    internal val DROPPED_RESPONSE_HEADERS =
        setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie")

    /**
     * Whether [request] is one this object must replace. Pure and JVM-testable:
     * the decision is the whole policy, and the execution below must never be
     * reachable for a request this function declined.
     */
    fun shouldClean(request: WebResourceRequest): Boolean =
        shouldClean(request.isForMainFrame, request.url.toString(), request.method, request.requestHeaders)

    /** The pure core of [shouldClean]. Takes nullable values for testability. */
    fun shouldClean(
        isForMainFrame: Boolean,
        url: String,
        method: String,
        requestHeaders: Map<String, String?>,
    ): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        if (!isForMainFrame && !GoogleAuthNavigation.isSignInTarget(url)) return false
        return stamped(requestHeaders)
    }

    /** Whether the request carries a non-empty stamp under any spelling. */
    fun stamped(requestHeaders: Map<String, String?>): Boolean =
        requestHeaders.any { (name, value) ->
            name.equals(HEADER, ignoreCase = true) && !value.isNullOrEmpty()
        }

    /**
     * Re-issue [url] without the stamp and build the response the engine
     * would have delivered. `null` on ANY failure -- connection refused,
     * timeout, a non-2xx that the engine's own machinery should present --
     * always falling back to the native request.
     */
    fun execute(url: String, requestHeaders: Map<String, String?>): WebResourceResponse? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            for ((name, value) in requestHeaders) {
                if (value.isNullOrEmpty()) continue
                if (name.lowercase() in DROPPED_REQUEST_HEADERS) continue
                runCatching { connection.setRequestProperty(name, value) }
            }

            val status = connection.responseCode
            if (status < 200 || status > 299) {
                runCatching { connection.disconnect() }
                return null
            }

            // The FINAL url after the connection's own redirects -- cookies the
            // redirect responses set belong to the hosts that set them, and the
            // response headers describe the last hop.
            val finalUrl = connection.url.toString()
            val headerFields = connection.headerFields.orEmpty()

            val cookieManager = CookieManager.getInstance()
            for ((name, values) in headerFields) {
                if (!name.equals("Set-Cookie", ignoreCase = true)) continue
                for (cookie in values.orEmpty()) {
                    runCatching { cookieManager.setCookie(finalUrl, cookie) }
                }
            }

            val contentType = connection.contentType
            val mime = contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: guessMime(url)
            val encoding = contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val responseHeaders = headerFields
                .filterKeys { it != null && it.lowercase() !in DROPPED_RESPONSE_HEADERS }
                .mapKeys { it.key.orEmpty() }
                .mapValues { it.value?.joinToString(", ").orEmpty() }

            val response = WebResourceResponse(mime, encoding, connection.inputStream)
            response.responseHeaders = responseHeaders
            return response
        } catch (_: Throwable) {
            runCatching { connection.disconnect() }
            return null
        }
    }

    /** A content type the server did not say, guessed from the URL's extension. */
    private fun guessMime(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)?.lowercase()
        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }
}
