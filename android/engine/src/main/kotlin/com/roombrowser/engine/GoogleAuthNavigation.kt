package com.roombrowser.engine

/**
 * The main-frame navigations that must be re-issued with the identity headers.
 *
 * WHY THIS EXISTS. `loadUrl(String, Map)` additional headers are the one
 * X-Requested-With lever the platform has honoured on every WebView since 71:
 * a value the app supplies wins over the engine's own `applicationId` stamp for
 * that main-frame request. But the lever only reaches requests the app itself
 * issues. A navigation the PAGE starts — the Sign in button on google.com, a
 * `location.href = "https://accounts.google.com/…"` — arrives at
 * `shouldOverrideUrlLoading` with no app headers anywhere near it, so on the
 * providers that still stamp the header the document that announces
 * "an app is embedding a browser" would go out exactly there.
 *
 * WHAT THIS ANSWERS. Whether a navigation is one of those, narrowly: main
 * frame only (a hidden iframe must never be able to trigger a re-issue, the
 * same rule the external-scheme handoff applies), GET only (a form POST
 * re-issued as a fresh GET would silently lose its payload — and re-issuing a
 * POST through `loadUrl` is not even expressible), https only (Google's
 * sign-in surface is https; anything else is not this feature's business),
 * and a host that actually is Google's sign-in surface. The host list is an
 * EQUALITY against the URL's host, never a suffix match: a navigation to
 * `accounts.google.com.evil.example` is somebody else's page, and re-issuing
 * it through the engine would be an XSS-grade mistake.
 *
 * THE GUARD. A re-issue is issued BY this engine, so the re-issued load's own
 * `shouldOverrideUrlLoading` fires again with the same URL. Without a memory
 * of what this session just re-issued, the callback would re-issue it again
 * and the navigation would never run. The guard window is deliberately short
 * and deliberately per-URL: a user who taps the same sign-in link twice more
 * than five seconds apart gets two real re-issues, and a DIFFERENT sign-in
 * navigation inside the window is never mistaken for the loop's own echo.
 */
object GoogleAuthNavigation {

    /**
     * The hosts whose main-frame navigations are re-issued with the identity
     * headers. Deliberately small: the sign-in surfaces a user reaches by
     * clicking, not every host Google operates. Server-side redirects INTO
     * these hosts (an OAuth 302 chain) do not pass through
     * `shouldOverrideUrlLoading` at all, so no host list can cover them —
     * that gap is the provider's stamp on engine-internal redirects, and it
     * is documented where the headers are defined rather than hidden here.
     */
    val SIGN_IN_HOSTS: Set<String> = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "myaccount.google.com",
    )

    /**
     * The domains this edition keeps PAGE-WORLD bare: no app-installed
     * document-start script of any kind runs on a page whose host is one of
     * these domains or any subdomain of them. The user mandate behind the
     * scope: everything that can carry a Google account surface -- not just
     * the three sign-in hosts -- must read to Google exactly like a bare
     * WebView wrapper, because every page-world fingerprint this app installs
     * somewhere Google can grade is a tell the wrapper never has.
     *
     * Deliberately distinct from [SIGN_IN_HOSTS]: that list drives the WIRE
     * levers (which requests are re-issued, which stamped requests are
     * cleaned) and stays exactly as small as the observed refusal surface;
     * this list drives the SCRIPT guard and is as wide as the account
     * surfaces. A host can be bare without being re-issued, and widening
     * bareness costs nothing -- the scripts were already self-contained
     * IIFEs, so "install nothing" is their correct behaviour on any host
     * this app has no business touching.
     */
    val GOOGLE_OWNED_DOMAINS: Set<String> = setOf(
        "google.com",
        "youtube.com",
    )

    /**
     * Whether [url] lives on a Google-owned domain: the host IS one of
     * [GOOGLE_OWNED_DOMAINS] or a subdomain of one. Suffix-with-boundary, so
     * `notgoogle.com` and `google.com.evil.example` are both somebody else's
     * page -- the same equality discipline [SIGN_IN_HOSTS] applies, one level
     * up. https-only through [hostOf], which answers null for anything else.
     */
    fun isGoogleOwnedHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return GOOGLE_OWNED_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /** How long a re-issued URL remembers it was re-issued. See the KDoc above. */
    const val GUARD_WINDOW_MS = 5_000L

    /**
     * The host of an https URL, lower-cased and stripped of port, userinfo,
     * path, query and fragment — or null for anything that is not https, whose
     * host is therefore nobody this feature re-issues for. Pure string work so
     * the whole object stays JVM-testable.
     */
    fun hostOf(url: String): String? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val authority = url.substring("https://".length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val host = authority.substringAfterLast('@').substringBeforeLast(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /** Whether [url] is one of the sign-in surfaces, by exact host. */
    fun isSignInTarget(url: String): Boolean =
        hostOf(url)?.let { it in SIGN_IN_HOSTS } == true

    /**
     * Whether a sub-resource request belongs to a Google sign-in flow.
     *
     * True when EITHER side of the request is a sign-in host: the page being
     * rendered is one (the resource is Google's own sign-in machinery), or
     * the resource itself is (an OAuth iframe embedded in another site's
     * page). Content blocking that touches either side can break the
     * challenge scripts and cookie handoffs the flow depends on, so the
     * blocker asks this first and stands down for the whole context.
     *
     * [pageUrl] may be null — a session mid-teardown has no committed URL —
     * and null means only the resource's own host can answer.
     */
    fun isSignInContext(pageUrl: String?, resourceUrl: String): Boolean =
        (pageUrl != null && isSignInTarget(pageUrl)) || isSignInTarget(resourceUrl)

    /**
     * Whether the session should abandon this navigation and re-issue it
     * itself, with the identity headers attached.
     *
     * [lastReissuedUrl] / [lastReissuedAtMs] are the session's memory of the
     * most recent re-issue it performed; [nowMs] is injected rather than read
     * here so the guard window is testable without a clock.
     */
    fun shouldReissueWithIdentityHeaders(
        url: String,
        isForMainFrame: Boolean,
        method: String,
        lastReissuedUrl: String?,
        lastReissuedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isForMainFrame) return false
        if (!method.equals("GET", ignoreCase = true)) return false
        if (!isSignInTarget(url)) return false
        return !echoesReissue(url, lastReissuedUrl, lastReissuedAtMs, nowMs)
    }

    /**
     * Whether a document that has STARTED loading on a sign-in host is one the
     * session must re-issue itself, because it arrived on a path the app never
     * issued: a SERVER-SIDE REDIRECT.
     *
     * THE GAP THIS CLOSES. `shouldReissueWithIdentityHeaders` covers only
     * navigations that pass through `shouldOverrideUrlLoading` — and a 302
     * chain the engine follows on its own never does. Open gmail.com signed
     * out and Google's own server answers `accounts.google.com/ServiceLogin`:
     * the WebView follows that redirect itself, the document request goes out
     * carrying the provider's `X-Requested-With` stamp, and Google's refusal
     * page is the response to the request nobody could clean. The click on a
     * Sign in button was covered; the redirect that a hundred Google
     * properties perform before any click was not — and that redirect is the
     * everyday route into sign-in.
     *
     * The window fires from `onPageStarted`, which DOES observe redirected
     * documents. Honesty about what that buys: by the time it fires the
     * stamped request has already been sent, so this is a re-issue, not a
     * prevention — the clean load replaces the stamped one before its REFUSAL
     * can render, and Google evaluates the re-issued request on its own
     * merits. One stamped request still reaches Google's logs; what stops is
     * the user being shown the refusal page because of it.
     *
     * THE GUARD IS THE SAME MEMORY the shouldOverrideUrlLoading re-issue
     * writes: [lastReissuedUrl] / [lastReissuedAtMs]. Three kinds of load must
     * NOT re-issue again here — this session's own re-issued load (its
     * onPageStarted fires for the same URL), an app-issued load that already
     * went out with the identity headers (`loadUri` stamps the memory for
     * sign-in targets for exactly this reason), and the echo of either. The
     * window is [GUARD_WINDOW_MS] and per-URL, for the same reasons as above.
     *
     * MAIN FRAME ONLY is enforced by the caller: `onPageStarted` is a
     * main-frame callback, so no extra flag is needed here — but the caller
     * must not wire a sub-frame signal into this.
     */
    fun shouldReissueAfterRedirect(
        url: String,
        lastReissuedUrl: String?,
        lastReissuedAtMs: Long,
        nowMs: Long,
    ): Boolean = isSignInTarget(url) && !echoesReissue(url, lastReissuedUrl, lastReissuedAtMs, nowMs)

    /** Whether [url] is this session's own recent re-issue, by the guard window. */
    private fun echoesReissue(
        url: String,
        lastReissuedUrl: String?,
        lastReissuedAtMs: Long,
        nowMs: Long,
    ): Boolean = lastReissuedUrl != null &&
        url == lastReissuedUrl &&
        nowMs - lastReissuedAtMs < GUARD_WINDOW_MS
}
