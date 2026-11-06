package com.roombrowser.engine.webview

import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.roombrowser.engine.GoogleAuthNavigation
import java.net.HttpURLConnection
import java.net.URL

/**
 * The PRE-EMPTIVE half of the X-Requested-With answer: when a request in a
 * Google context is about to go out CARRYING the provider's package stamp,
 * re-issue it over a plain connection with the stamp REWRITTEN to Chrome's
 * applicationId and hand the response back in the engine's place.
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
 *  * A main frame (any host -- the page-started navigation no `loadUrl`
 *    lever reached), a resource whose OWN host is Google's (sign-in host or
 *    anything Google-owned), or a resource of a page that is Google-owned
 *    -- the gstatic fonts and googleapis scripts of a login page, which the
 *    first cut left stamped and which the grader could read anyway.
 *  * Only when the request actually carries a non-blank stamp. A main frame
 *    that went out through `loadUrl`'s override is already stamped with
 *    exactly the value it should carry.
 *
 * THE EXECUTION. A plain `HttpURLConnection` re-issues the GET with the
 * request's own headers minus the ones the connection re-derives for itself
 * (`Host`, `Content-Length`) or manages transparently (`Accept-Encoding`),
 * with the stamp REWRITTEN to [GoogleAuthNavigation.CHROME_STAMP] instead of
 * dropped -- absence was the v1.0.193/194 answer and the refusal outlived
 * it, while the bare wrapper that signs in sends a KNOWN browser package
 * everywhere. The re-issued request carries the jar's own `Cookie` header
 * (read fresh PER HOP, so the re-issued GET sees exactly the session state
 * the engine's native request would have seen) and every hop's `Set-Cookie`
 * is fed back into [CookieManager], so the session's cookie state stays
 * exactly what the engine would have built.
 *
 * REDIRECTS ARE FOLLOWED BY HAND, one hop per pass. Java's transparent
 * following never surfaces the intermediate responses, and their Set-Cookie
 * headers are exactly the sign-in flow's own state -- `__Host-GAPS`,
 * `__Secure-ENID` and friends, set on the 302 chain into the identifier
 * page. The transparent version dropped every one of them: the identifier
 * form still rendered (its own hop's cookies made it), but the form POST
 * the cleaner must never touch -- a `WebResourceRequest` carries no body,
 * so there is nothing to re-issue it WITH -- rode a session missing its
 * flow state, and Google's front end answers that with the generic
 * "Something went wrong" page instead of the password step. Run 673's live
 * screen dump pinned it end to end: form rendered, address typed, submit
 * clicked, error page back. Per-hop capture is the browser behavior the
 * jar always needed. (The quiet bonus survives: a hop the ENGINE would
 * have re-stamped is followed by code that stamps Chrome.)
 *
 * THE SET-COOKIE MERGE, and the jar poisoning it closes. Java's
 * `headerFields` splits every header value on commas -- including the comma
 * inside a cookie's own `Expires=Wed, 21 Oct ...` date -- so one legitimate
 * `Set-Cookie` arrives as TWO fragments, and feeding the fragments to
 * `setCookie` verbatim manufactures a junk cookie (a name that is a date
 * fragment, no value, the real cookie stripped of its expiry). v1.0.193
 * through v1.0.195 shipped exactly that: weeks of sign-in attempts slowly
 * seeded Google's jar with debris until requests native to the session (the
 * form POSTs this object never touches) carried a Cookie header Google's
 * front end answers with the 400 "malformed request" page. The fragments
 * are now re-joined before they reach the jar ([mergeSetCookieFragments],
 * the rule: a fragment carrying no `=` at all is the date/attribute tail of
 * the previous `Set-Cookie`, never a cookie of its own).
 *
 * EVERY failure mode answers `null`, which returns the request to the
 * engine untouched -- the behaviour this object was added on top of. The
 * cleaner can only fail back into the world it found.
 */
object RequestedWithCleaner {

    /** The header this whole subsystem exists to keep off the wire. */
    const val HEADER = "X-Requested-With"

    /**
     * Request headers the re-issue never copies: the two whose values the
     * connection re-derives for itself (`Host`, `Content-Length`), and
     * `Accept-Encoding`, which must be absent so the connection adds its own
     * and decompresses transparently -- a copied value would hand back a gzip
     * body the response object would mislabel. The stamp is NOT here: it
     * travels, rewritten, per [reissueHeaders].
     */
    internal val DROPPED_REQUEST_HEADERS =
        setOf("host", "content-length", "accept-encoding")

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
    fun shouldClean(request: WebResourceRequest, pageUrl: String?): Boolean =
        shouldClean(
            request.isForMainFrame,
            request.url.toString(),
            request.method,
            request.requestHeaders,
            pageUrl,
        )

    /**
     * The pure core of [shouldClean]. Takes nullable values for testability.
     * [pageUrl] is the main frame's live URL, read off the session's volatile
     * anchor; it widens the sub-resource scope to every asset a Google-owned
     * page pulls (the fonts and scripts of the login page), and it is null
     * for a main-frame request, whose own URL is [url].
     */
    fun shouldClean(
        isForMainFrame: Boolean,
        url: String,
        method: String,
        requestHeaders: Map<String, String?>,
        pageUrl: String? = null,
    ): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        if (!stamped(requestHeaders)) return false
        if (isForMainFrame) return true
        if (GoogleAuthNavigation.googleContext(url)) return true
        return pageUrl != null && GoogleAuthNavigation.isGoogleOwnedHost(pageUrl)
    }

    /** Whether the request carries a non-blank stamp under any spelling. */
    fun stamped(requestHeaders: Map<String, String?>): Boolean =
        requestHeaders.any { (name, value) ->
            name.equals(HEADER, ignoreCase = true) && !value.isNullOrBlank()
        }

    /**
     * The headers the re-issue carries: the request's own, minus the ones
     * the connection re-derives or manages ([DROPPED_REQUEST_HEADERS]), with
     * every non-blank stamp REWRITTEN to [GoogleAuthNavigation.CHROME_STAMP].
     * A blank stamp -- the app's own loadUrl override echoing through -- is
     * left absent, not upgraded to a claim nobody asked for.
     *
     * Pure so the rewrite is testable without a socket; [execute] is the only
     * caller and must never reach the connection with headers this function
     * did not produce.
     */
    internal fun mergeSetCookieFragments(values: List<String>): List<String> {
        val merged = mutableListOf<String>()
        for (raw in values) {
            val piece = raw.trim()
            if (piece.isEmpty()) continue
            // A fragment that does not OPEN like a Set-Cookie is the tail of
            // the previous one (Java split `Expires=Wed, 21 Oct 2026 ...` at
            // the comma inside the date). It is never a cookie of its own:
            // every Set-Cookie opens with a bare token before the first `=`,
            // while a date tail opens with text full of spaces -- and may
            // still carry `=` further in (`...; domain=.google.com`), which
            // is why a bare contains('=') test would misjoin nothing and
            // plant the tail as debris instead.
            if (!opensLikeSetCookie(piece) && merged.isNotEmpty()) {
                merged[merged.size - 1] = merged.last() + ", " + piece
                continue
            }
            merged.add(piece)
        }
        // The very first fragment was a tail with nothing to join: junk.
        return merged.filter { opensLikeSetCookie(it) }
    }

    /** Whether [piece] opens like a Set-Cookie: a token, then `=`. */
    private fun opensLikeSetCookie(piece: String): Boolean {
        val eq = piece.indexOf('=')
        if (eq <= 0) return false
        return piece.substring(0, eq).none { it.isWhitespace() || it == ';' }
    }

    internal fun reissueHeaders(requestHeaders: Map<String, String?>): Map<String, String?> {
        val rebuilt = LinkedHashMap<String, String?>()
        for ((name, value) in requestHeaders) {
            if (name.equals(HEADER, ignoreCase = true)) {
                if (!value.isNullOrBlank()) rebuilt[name] = GoogleAuthNavigation.CHROME_STAMP
                continue
            }
            if (value.isNullOrEmpty()) continue
            if (name.lowercase() in DROPPED_REQUEST_HEADERS) continue
            rebuilt[name] = value
        }
        return rebuilt
    }

    /**
     * Re-issue [url] with the stamp rewritten (per [reissueHeaders]) and build
     * the response the engine would have delivered. `null` on ANY failure --
     * connection refused, timeout, a non-2xx that the engine's own machinery
     * should present -- always falling back to the native request.
     */
    fun execute(url: String, requestHeaders: Map<String, String?>): WebResourceResponse? {
        var hopUrl = url
        repeat(MAX_REDIRECT_HOPS) {
            val connection = URL(hopUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                // One hop per pass -- see the class KDoc: the transparent
                // following was where the sign-in flow's state cookies died.
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                for ((name, value) in reissueHeaders(requestHeaders)) {
                    runCatching { connection.setRequestProperty(name, value) }
                }

                // The re-issued request carries the jar's own view of THIS
                // hop's URL, freshly read per hop -- a cookie an earlier hop
                // set reaches the next one exactly as a browser's would. The
                // request headers the WebView hands to shouldInterceptRequest
                // do not include `Cookie`, so this is the only way the claim
                // above is true. A jar that has nothing for the URL adds no
                // header.
                if (requestHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                    runCatching {
                        CookieManager.getInstance().getCookie(hopUrl)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { connection.setRequestProperty("Cookie", it) }
                    }
                }

                val status = connection.responseCode
                val headerFields = connection.headerFields.orEmpty()

                // EVERY hop's Set-Cookie enters the jar under the URL that
                // answered it -- the redirect hops the transparent following
                // made invisible, and the non-2xx finals too (flow state
                // sometimes rides an error page's headers).
                val cookieManager = CookieManager.getInstance()
                var captured = 0
                for ((name, values) in headerFields) {
                    if (!name.equals("Set-Cookie", ignoreCase = true)) continue
                    // Java already split on the commas INSIDE the dates;
                    // re-join before feeding the jar, or every Google
                    // response plants debris cookies that grow into the
                    // 400-malformed jar.
                    for (cookie in mergeSetCookieFragments(values.orEmpty())) {
                        // setCookie's own return is not part of the contract
                        // here (older API levels declare it void): the lambda
                        // answers true itself, so the Result is always
                        // Boolean whether or not the platform said anything.
                        val stored = runCatching {
                            cookieManager.setCookie(hopUrl, cookie)
                            true
                        }.getOrDefault(false)
                        if (stored) captured++
                    }
                }
                Log.i(
                    "RoomGoogleTrace",
                    "hop status=" + status +
                        " setCookieCaptured=" + captured +
                        " url=" + hopUrl
                )

                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    runCatching { connection.inputStream?.close() }
                    connection.disconnect()
                    if (location.isNullOrBlank()) return null
                    hopUrl = resolveRedirect(hopUrl, location) ?: return null
                    return@repeat
                }
                if (status < 200 || status > 299) {
                    runCatching { connection.disconnect() }
                    return null
                }

                val contentType = connection.contentType
                val mime = contentType?.substringBefore(';')?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: guessMime(hopUrl)
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
        // A chain longer than MAX_REDIRECT_HOPS is a loop; fall back to the
        // engine rather than spin.
        return null
    }

    /** The cap on redirect hops one re-issued GET may follow. */
    internal const val MAX_REDIRECT_HOPS = 10

    /**
     * [location] resolved against [baseUrl]: absolute locations pass through,
     * protocol-relative and relative ones resolve against the hop that named
     * them. Null for anything unusable -- the caller falls back to the engine
     * rather than follow a location it cannot form a URL from.
     */
    internal fun resolveRedirect(baseUrl: String, location: String): String? {
        val trimmed = location.trim()
        // A blank location is not "stay here": URL(context, "") would answer
        // the base URL itself and the caller would re-request the hop it
        // just followed. Blank declines.
        if (trimmed.isEmpty()) return null
        return runCatching { URL(URL(baseUrl), trimmed).toString() }.getOrNull()
    }

    /** A content type the server did not say, guessed from the URL's extension. */
    private fun guessMime(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)?.lowercase()
        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }
}
