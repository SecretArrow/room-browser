package com.roombrowser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The measurement half of the sign-in diagnostics: what the device's WebView
 * provider IS, and what Google serves THIS app's identity when asked for the
 * sign-in page.
 *
 * WHY THIS LIVES IN THE ENGINE MODULE. Every line here needs the rendering
 * engine's own types — `WebView`, `WebSettings`, `CookieManager` — and the
 * architecture boundary (`EngineBoundaryTest`) bans those imports from every
 * module above this one. The diagnostics screen is app UI and stays there;
 * everything that touches the engine crosses this facade instead, exactly
 * like the sessions and the identity headers do.
 *
 * READ-ONLY, ALWAYS. The probe loads the sign-in page, reads what rendered,
 * and destroys the WebView. It never submits a form, never types into one,
 * and never touches an account — it answers "would Google let THIS app's
 * requests reach the sign-in page", nothing more.
 */
object SignInDiagnostics {

    /** The sign-in URL the probe loads. `hl=en` so the refusal text is the stable English one. */
    const val PROBE_URL =
        "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.google.com%2F&hl=en"

    /** How long the probe waits for Google before reporting inconclusive. */
    const val PROBE_TIMEOUT_MS = 25_000L

    /**
     * The refusal strings, matched lowercase against the rendered page text.
     * English first (the probe pins `hl=en`), then the Indonesian renderings —
     * the device locale can still win for interstitials Google localises before
     * the `hl` parameter is read.
     *
     * The `400.` entries are the OTHER refusal — the front end's
     * "400. That's an error. The server cannot process the request because it
     * is malformed. It should not be retried." page, which is not a browser
     * grading at all: it is the REQUEST being rejected, almost always because
     * the cookie jar Google is being handed is corrupted or oversized. They
     * are matched by their stable numeric prefix (the one part Google does
     * not localise), followed by the first words of each major rendering.
     */
    private val BLOCK_MARKERS = listOf(
        "this browser or app may not be secure",
        "sign in with a supported browser",
        "browser atau aplikasi ini mungkin tidak aman",
        "masuk dengan browser yang didukung",
        "400. that's an error",
        "400. terjadi error",
        "400. se ha producido un error",
        "400. ocorreu um erro",
        "400. une erreur s'est produite",
        "400. es ist ein fehler",
    )

    /** What the live probe saw when Google answered. */
    data class ProbeOutcome(
        /** true = refusal served, false = sign-in form rendered, null = inconclusive. */
        val blocked: Boolean?,
        val marker: String?,
        val formSeen: Boolean,
        val finalUrl: String,
        val note: String,
    )

    /** How big the Google side of this profile's jar is, in one read. */
    data class GoogleCookieFootprint(
        /** UTF-8 bytes of every cookie Google would be handed, combined. */
        val totalBytes: Int,
        /** Distinct cookie names across the Google hosts read. */
        val names: Int,
    )

    /**
     * The Google side of this profile's cookie jar, as one number the check
     * screen can judge: every cookie a request to the accounts host or the
     * search host would carry (the `.google.com` domain cookies included in
     * both), counted in bytes and distinct names. Null when the platform
     * refused the read.
     *
     * WHY IT IS A NUMBER. Google's front end answers a request whose cookie
     * header is oversized or corrupted with the 400-malformed page — the
     * failure that outlived every identity fix because it lives in the jar,
     * not the headers. A fresh profile sits around two hundred bytes; a jar
     * deep in debris measures in the tens of kilobytes.
     */
    fun googleCookieFootprint(): GoogleCookieFootprint? = runCatching {
        val cookieManager = CookieManager.getInstance()
        val jars = listOf(
            "https://accounts.google.com/",
            "https://www.google.com/",
        ).mapNotNull { cookieManager.getCookie(it) }
        val pairs = jars
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        GoogleCookieFootprint(
            totalBytes = pairs.sumOf { it.toByteArray(Charsets.UTF_8).size },
            names = pairs.map { it.substringBefore('=').trim() }.distinct().size,
        )
    }.getOrNull()

    /**
     * Erase the Google-owned side of this profile's cookie jar — every host
     * in [GoogleSignInRecovery.GOOGLE_COOKIE_CLEAR_HOSTS], with the same
     * per-site expiry plan the session's 400 recovery drives. True when the
     * platform accepted the writes.
     */
    fun clearGoogleCookies(): Boolean = runCatching {
        val cookieManager = CookieManager.getInstance()
        GoogleSignInRecovery.GOOGLE_COOKIE_CLEAR_HOSTS.forEach { host ->
            val url = "https://$host/"
            SiteCookieClearer.expiryRequests(url, cookieManager.getCookie(url))
                .forEach { cookieManager.setCookie(it.url, it.header) }
        }
        cookieManager.flush()
        true
    }.getOrDefault(false)

    /**
     * The installed provider, as "label version" — what the diagnostics row
     * shows the user — or null when this device reports none (`getCurrent
     * WebViewPackage` needs API 26 and can still refuse on odd providers).
     *
     * `WebViewPackageInfo` exposes the provider's package name and version
     * directly (API 26+); the label comes from PackageManager against that
     * package, with the package name as the fallback the reader still
     * recognises when the label lookup is refused.
     */
    fun webViewProviderIdentity(context: Context): String? {
        val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            ?: return null
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(provider.packageName, 0)
            ).toString()
        }.getOrNull() ?: provider.packageName
        val version = provider.versionName
        return "$label ${version ?: ""}".trim()
    }

    /**
     * The provider's stock user agent — the string every identity decision in
     * this app starts from. Wrapped so the caller above the facade never
     * needs `WebSettings` itself.
     */
    fun stockUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)

    /**
     * The page-side probe: marker scan, sign-in-form detection, landing URL.
     */
    private fun probeScript(): String {
        val markersJson = JSONArray(BLOCK_MARKERS).toString()
        return """
            (function() {
              var t = ((document.body && document.body.innerText) || '').toLowerCase();
              var markers = $markersJson;
              var hit = null;
              for (var i = 0; i < markers.length; i++) {
                if (t.indexOf(markers[i]) >= 0) { hit = markers[i]; break; }
              }
              return {
                hit: hit,
                form: !!document.querySelector('input[type=email]'),
                url: location.href
              };
            })()
        """.trimIndent()
    }

    /**
     * The live probe. Runs a bare WebView on the main thread with the engine's
     * default identity stack and reads what Google serves. Never submits,
     * never types, destroys the WebView before answering.
     */
    suspend fun runProbe(context: Context): ProbeOutcome =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val webView = WebView(context)
                val timeout = Runnable {
                    settle(
                        cont, main, webView, ProbeOutcome(
                            blocked = null,
                            marker = null,
                            formSeen = false,
                            finalUrl = webView.url ?: PROBE_URL,
                            note = "Timed out after ${PROBE_TIMEOUT_MS / 1000} s with no finished " +
                                "load — the network never answered, or the page never stopped " +
                                "redirecting. Check connectivity and run the check again.",
                        )
                    )
                }
                webView.settings.apply {
                    javaScriptEnabled = true
                    // The engine's defaults, reproduced: cookies on, third-party
                    // cookies on (a privacy profile that blocks them would test a
                    // different question than the one this screen asks).
                    domStorageEnabled = true
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(probeScript()) { result ->
                            val outcome = runCatching {
                                // An object result arrives as a JSON object literal;
                                // a null arrives as the four characters "null".
                                val json = JSONObject(result)
                                // org.json's optString answers the STRING "null"
                                // for a JSON-null value (the sentinel's toString),
                                // so an absent marker must be read with isNull
                                // FIRST — the string "null" as a marker once turned
                                // a clean page into a false "BLOCKED" verdict.
                                val hit = if (json.isNull("hit")) {
                                    null
                                } else {
                                    json.optString("hit").takeIf { it.isNotEmpty() }
                                }
                                val form = json.optBoolean("form", false)
                                val landed = if (json.isNull("url")) {
                                    url
                                } else {
                                    json.optString("url").ifBlank { url }
                                }
                                when {
                                    hit != null && hit.startsWith("400.") -> ProbeOutcome(
                                        blocked = true,
                                        marker = hit,
                                        formSeen = form,
                                        finalUrl = landed,
                                        note = "Google answered the request itself with the 400 " +
                                            "'malformed' page — this is not the browser-refusal " +
                                            "grading, it is the request being rejected, and it " +
                                            "almost always means the Google cookies this profile " +
                                            "hands over are corrupted or oversized. Use 'Clear " +
                                            "Google cookies' below, then run the check again; the " +
                                            "app also does this automatically when a sign-in load " +
                                            "is answered 400.",
                                    )
                                    hit != null -> ProbeOutcome(
                                        blocked = true,
                                        marker = hit,
                                        formSeen = form,
                                        finalUrl = landed,
                                        note = "Google served the refusal page to this app's requests. " +
                                            "First remedies, in order: update Android System WebView " +
                                            "and Chrome from the Play Store (an old provider is the one " +
                                            "cause no app code can work around), turn any VPN off, and " +
                                            "check the device clock below. If Chrome itself signs in fine " +
                                            "on this device while this probe keeps failing, the block is " +
                                            "attached to this app's traffic — re-read the identity rows " +
                                            "above, and report the marker and URL shown here.",
                                    )
                                    form -> ProbeOutcome(
                                        blocked = false,
                                        marker = null,
                                        formSeen = true,
                                        finalUrl = landed,
                                        note = "The real sign-in form rendered with no refusal text. " +
                                            "Sign-in inside this browser should work here; if you still " +
                                            "saw the refusal in a tab, it was likely Google's risk engine " +
                                            "cooling down after earlier attempts — wait a while and try.",
                                    )
                                    else -> ProbeOutcome(
                                        blocked = false,
                                        marker = null,
                                        formSeen = false,
                                        finalUrl = landed,
                                        note = "No refusal text and no sign-in form on the landing page " +
                                            "(Google may have signed the probe straight through, or served " +
                                            "an interstitial it did not match). If sign-in still fails in " +
                                            "a tab, run the check again from that state.",
                                    )
                                }
                            }.getOrElse {
                                ProbeOutcome(
                                    blocked = null,
                                    marker = null,
                                    formSeen = false,
                                    finalUrl = url,
                                    note = "The probe page finished but its answer could not be read.",
                                )
                            }
                            settle(cont, main, webView, outcome)
                        }
                    }
                }
                main.postDelayed(timeout, PROBE_TIMEOUT_MS)
                // The same lever the engine's own loads use: the identity header
                // resolved by the shared policy — which for a Google host is the
                // Chrome stamp v1.0.195 ships. The probe must read the same
                // wire story the engine sends, or its verdict grades a request
                // the engine never makes.
                runCatching {
                    webView.loadUrl(
                        PROBE_URL,
                        mapOf("X-Requested-With" to GoogleAuthNavigation.identityHeaderValueFor(PROBE_URL))
                    )
                }.onFailure {
                        settle(
                            cont, main, webView, ProbeOutcome(
                                blocked = null, marker = null, formSeen = false,
                                finalUrl = PROBE_URL,
                                note = "The probe WebView could not start on this device.",
                            )
                        )
                    }
            }
        }

    private fun settle(
        cont: kotlinx.coroutines.CancellableContinuation<ProbeOutcome>,
        main: Handler,
        webView: WebView,
        outcome: ProbeOutcome,
    ) {
        if (cont.isActive) {
            cont.resume(outcome) { }
        }
        runCatching { webView.stopLoading() }
        main.post { runCatching { webView.destroy() } }
    }
}
