package com.roombrowser.engine.webview

import com.roombrowser.engine.GoogleAuthNavigation

/**
 * Keeps every page-world document-start script OUT of Google's domains.
 *
 * WHY THIS EXISTS. The scripts this engine installs (the profile's
 * adjustments, the vault bridge, the wallet provider, the DevTools console
 * patch and the device-identity shim) each patch the page world of EVERY page
 * they are registered for -- redefined accessors, a `window.ethereum`, a
 * wrapped `console`, claimed RAM and GPU strings. On an ordinary site that is
 * the point of the script. On a Google page it is a self-inflicted anomaly:
 * Google's anti-abuse grades the client that is asking to authenticate, and a
 * page whose natives carry the app's fingerprints grades WORSE than a page
 * that carries none. The user's own field report made the comparison
 * concrete: a bare WebView wrapper with NO page-world injection of any kind
 * signs in where this app -- with every wire-level lever pulled -- is still
 * refused. The user's scope decision widens this from the three sign-in
 * hosts to EVERYTHING Google-account-related: every `google.com` and
 * `youtube.com` host, at any subdomain depth, runs none of this app's
 * scripts. Wire identity is necessary; it is not sufficient while the page
 * world still announces the app.
 *
 * THE SHAPE. Every script this engine installs is already a self-contained
 * IIFE that talks to the app through `window.*` members only -- none depends
 * on top-level declarations reaching the page's global scope -- so an outer
 * IIFE that returns immediately on a Google-owned host changes nothing
 * anywhere else. `wrap` is the one function the session composes with: the
 * script text it already had, plus an early return the guarded hosts never
 * survive past.
 *
 * The domain list is [GoogleAuthNavigation.GOOGLE_OWNED_DOMAINS] itself,
 * never a copy that can drift, and the regex is built from it at first use.
 * Any subdomain depth matches (`storage.cloud.google.com`), but only on the
 * domain's own boundary: a page on `accounts.google.com.evil.example` or
 * `notgoogle.com` matches nothing here and gets whatever the profile's
 * scripts do -- which on an attacker's page is its own problem, not a
 * re-issued identity.
 */
object SignInHostGuard {

    /**
     * A JS regex SOURCE matching Google-owned hosts, derived from
     * [GoogleAuthNavigation.GOOGLE_OWNED_DOMAINS] so the two lists cannot
     * disagree. Sorted so the built pattern is stable across runs and
     * testable as a literal. Dots are escaped; the scheme is pinned to
     * https; each domain accepts any subdomain depth and the alternation is
     * anchored with `(\/|$)` so a longer hostname that merely CONTAINS a
     * listed domain cannot match.
     */
    val jsPattern: String =
        "^https:\\/\\/(" +
            GoogleAuthNavigation.GOOGLE_OWNED_DOMAINS
                .map { "([^.\\/]+\\.)*${it.replace(".", "\\.")}" }
                .sorted()
                .joinToString("|") +
            ")(\\/|$)"

    private val jsRegexLiteral = "/$jsPattern/"

    /**
     * [script] guarded: on a Google-owned host the wrapper returns before the
     * script's first statement runs, and the page world sees no patch at all.
     * Everywhere else the wrapper is one extra IIFE frame around a script
     * that was already an IIFE -- behaviourally invisible.
     */
    fun wrap(script: String): String =
        "(function(){if($jsRegexLiteral.test(location.href))return;" + script + "})();"
}
