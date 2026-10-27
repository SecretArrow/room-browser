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
        val echoed = lastReissuedUrl != null &&
            url == lastReissuedUrl &&
            nowMs - lastReissuedAtMs < GUARD_WINDOW_MS
        return !echoed
    }
}
