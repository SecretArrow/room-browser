package com.roombrowser.engine.webview

/**
 * What this process has actually SEEN on the wire about `X-Requested-With`.
 *
 * WHY A MEASUREMENT AND NOT A CAPABILITY CHECK. The provider's feature flag
 * cannot answer the question this exists to answer. `REQUESTED_WITH_HEADER_ALLOW_LIST`
 * is supported only on providers that still HAVE the header, and Chromium
 * discontinued the header itself ("deprecated in late 2022, and its use
 * discontinued", per androidx's own deprecation note). So a modern provider
 * reports the feature UNSUPPORTED because there is nothing left to suppress --
 * which is the exact same answer an ancient provider gives for the opposite
 * reason. Reading that flag as "this device's WebView is too old" told people
 * to update a WebView that was already current, and hid the real state.
 *
 * The header is added by the engine to the request it is about to make, and
 * `WebViewClient.shouldInterceptRequest` is handed that request's headers
 * before it goes out, so the session can simply look. What it saw is recorded
 * here, process-wide, because the behaviour belongs to the installed provider
 * rather than to one tab.
 *
 * ABSENT IS ONLY EVIDENCE WHEN NOTHING ELSE EXPLAINS IT. A provider that still
 * supports the allow-list may list the header in the intercept callback and
 * then drop it on the way out; a provider that stopped adding the header lists
 * nothing. So [WebViewEngineHost.embeddedBrowserHeaderObservation] treats a
 * PRESENT sighting as conclusive and an ABSENT one as conclusive only when the
 * provider does not claim the opt-out either. This object holds the raw
 * sightings and nothing else.
 *
 * MAIN FRAME AND SUBRESOURCES ARE RECORDED SEPARATELY, because since the
 * session began issuing every main-frame navigation with the
 * `X-Requested-With: ""` override (`loadUrl`'s additional headers -- the one
 * lever the provider has honoured on every version since 71), the header's
 * presence on a SUBRESOURCE request no longer describes what Google's sign-in
 * refusal actually reads, which is the MAIN DOCUMENT request. The screen's
 * check is answered from the main-frame sightings ([sawMainFramePresent] /
 * [sawMainFrameAbsent]); the aggregate sightings stay because a subresource
 * stamp is still a package-name leak worth knowing about.
 */
internal object RequestedWithProbe {

    @Volatile
    private var sawPresent: Boolean = false

    @Volatile
    private var sawAbsent: Boolean = false

    @Volatile
    private var sawMainFramePresent: Boolean = false

    @Volatile
    private var sawMainFrameAbsent: Boolean = false

    /**
     * Record one request's headers. Called from `shouldInterceptRequest`, which
     * runs on a WebView background thread for every sub-resource of every
     * engine, so this must stay allocation-light and lock-free.
     */
    fun record(headers: Map<String, String>?, isForMainFrame: Boolean) {
        if (headers == null) return
        val present = headers.keys.any { it.equals(HEADER, ignoreCase = true) }
        if (present) {
            sawPresent = true
            if (isForMainFrame) sawMainFramePresent = true
        } else {
            sawAbsent = true
            if (isForMainFrame) sawMainFrameAbsent = true
        }
    }

    /** True once a request was seen carrying the header. Never clears. */
    fun sawPresent(): Boolean = sawPresent

    /** True once a request was seen WITHOUT the header. Never clears. */
    fun sawAbsent(): Boolean = sawAbsent

    /** True once a MAIN-FRAME request was seen carrying the header. */
    fun sawMainFramePresent(): Boolean = sawMainFramePresent

    /** True once a MAIN-FRAME request was seen without the header. */
    fun sawMainFrameAbsent(): Boolean = sawMainFrameAbsent

    /** Test seam: the sightings are process-wide by design, which a JVM test cannot otherwise sequence. */
    fun resetForTest() {
        sawPresent = false
        sawAbsent = false
        sawMainFramePresent = false
        sawMainFrameAbsent = false
    }

    private const val HEADER = "X-Requested-With"
}
