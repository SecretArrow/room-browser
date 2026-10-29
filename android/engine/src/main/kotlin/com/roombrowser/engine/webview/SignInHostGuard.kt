package com.roombrowser.engine.webview

import com.roombrowser.engine.GoogleAuthNavigation

/**
 * Keeps every page-world document-start script OUT of Google's sign-in hosts.
 *
 * WHY THIS EXISTS. The four scripts this engine installs (the profile's
 * adjustments, the vault bridge, the wallet provider and the DevTools console
 * patch) each patch the page world of EVERY page they are registered for --
 * redefined accessors, a `window.ethereum`, a wrapped `console`, a
 * `postMessage` bridge. On an ordinary site that is the point of the script.
 * On `accounts.google.com` it is a self-inflicted anomaly: Google's sign-in
 * anti-abuse grades the client that is asking to authenticate, and a page
 * whose natives carry the app's fingerprints grades WORSE than a page that
 * carries none. The user's own field report made the comparison concrete: a
 * bare WebView wrapper with NO page-world injection of any kind signs in
 * where this app -- with every wire-level lever pulled -- is still refused.
 * Wire identity is necessary; it is not sufficient while the page world still
 * announces the app.
 *
 * THE SHAPE. Every script this engine installs is already a self-contained
 * IIFE that talks to the app through `window.*` members only -- none depends
 * on top-level declarations reaching the page's global scope -- so an outer
 * IIFE that returns immediately on a sign-in host changes nothing anywhere
 * else. `wrap` is the one function the session composes with: the script text
 * it already had, plus an early return the sign-in hosts never survive past.
 *
 * The host list is [GoogleAuthNavigation.SIGN_IN_HOSTS] itself, never a copy
 * that can drift from the re-issue list, and the regex is built from it at
 * first use. Exact hosts only, the same equality rule everywhere: a page on
 * `accounts.google.com.evil.example` matches nothing here and gets whatever
 * the profile's scripts do -- which on an attacker's page is its own problem,
 * not a re-issued identity.
 */
object SignInHostGuard {

    /**
     * A JS regex SOURCE matching the sign-in hosts, derived from
     * [GoogleAuthNavigation.SIGN_IN_HOSTS] so the two lists cannot disagree.
     * Sorted so the built pattern is stable across runs and testable as a
     * literal. Dots are escaped; the scheme is pinned to https; the host
     * alternation is anchored with `(\/|$)` so a longer hostname that merely
     * STARTS with a listed host cannot match.
     */
    val jsPattern: String =
        "^https:\\/\\/(" +
            GoogleAuthNavigation.SIGN_IN_HOSTS
                .map { it.replace(".", "\\.") }
                .sorted()
                .joinToString("|") +
            ")(\\/|$)"

    private val jsRegexLiteral = "/$jsPattern/"

    /**
     * [script] guarded: on a sign-in host the wrapper returns before the
     * script's first statement runs, and the page world sees no patch at all.
     * Everywhere else the wrapper is one extra IIFE frame around a script
     * that was already an IIFE -- behaviourally invisible.
     */
    fun wrap(script: String): String =
        "(function(){if($jsRegexLiteral.test(location.href))return;" + script + "})();"
}
