package com.roombrowser.engine

/**
 * The automatic answer to Google's sign-in refusal — the "Couldn't sign you
 * in. This browser or app may not be secure" card and the help-article
 * redirect behind it.
 *
 * WHY THIS EXISTS. Every identity lever the engine owns is already pulled
 * before the first request goes out: the `wv` marker stripped
 * (`UserAgents.webViewNeutralUserAgent`), the `Sec-CH-UA*` headers derived
 * from the very UA string the session installed
 * (`WebViewClientHints.forUserAgent`), the Chrome version tuned to the engine
 * the phone actually runs (`UserAgents.engineTunedUserAgent`), and the
 * provider's `X-Requested-With` stamp overridden on every main-frame load
 * (`GoogleAuthNavigation`). The field reports say the refusal still happens —
 * and a GeckoView field test failed too, which means the refusal is NOT keyed
 * to one header this app can reach. What the app CAN still do is stop showing
 * the dead end: detect the refusal, and spend the two recovery levers that do
 * not require Google's cooperation, in the order that is safe to undo.
 *
 * THE LADDER, and why this order.
 *
 * 1. DESKTOP_RETRY. The refusal's embedded-browser checks target the MOBILE
 *    WebView identity; with a Windows desktop UA — and the client hints that
 *    say Windows with it — Google serves the ordinary desktop sign-in, which
 *    is what every Android browser's "Desktop site" toggle rides on. The
 *    desktop retry is also the harmless first move: it changes how the sign-in
 *    page RENDERS, it touches no stored data, and it reverses itself the
 *    moment the session leaves the sign-in hosts.
 * 2. COOKIE_RETRY. A refusal can poison the flow's own cookies — a challenge
 *    cookie Google set on the failed attempt keeps grading every retry as the
 *    same failed client, which is the "stuck in a loop" shape of this bug.
 *    The sign-in hosts' cookies are erased with the existing per-site
 *    eraser (`SiteCookieClearer`, the same one the app's "clear cookies for
 *    this site" button drives) and the sign-in page reloads, desktop identity
 *    still on: clean jar + desktop identity is the strongest combination
 *    available in-process. This step is deliberately SECOND because erasing
 *    cookies signs the profile out of Google properties it was signed into —
 *    a real cost, only justified after the free move failed.
 * 3. ADVICE. No third automatic lever exists in-process. Anything left is
 *    outside the app's reach — an IP Google's risk engine distrusts (VPN,
 *    carrier NAT, datacenter), an account flagged for verification, or a
 *    check only the account owner can pass — so the engine reports the
 *    refusal pattern upward and the app says so honestly instead of looping.
 *
 * THE SHAPE. A tiny state machine over the session's main-frame reports, with
 * every time injected by the caller so the windows are testable without a
 * clock. `State` is one object per engine session; nothing here touches a
 * WebView, a cookie jar, or a settings store — the engine session reads the
 * returned [Action] and performs it, and this object only decides WHICH.
 */
object GoogleSignInRecovery {

    /**
     * What the session should do about the refusal it just reported.
     * [NONE] means "nothing automatic is available" — the caller stays quiet.
     */
    enum class Action { DESKTOP_RETRY, COOKIE_RETRY, ADVICE, NONE }

    /**
     * How long a refusal stays part of the live attempt. A refusal that
     * arrives later than this after the last sign-in page or the previous
     * refusal is a NEW story — the user came back hours later — and starts a
     * fresh cycle rather than climbing this attempt's ladder.
     */
    const val REFUSAL_WINDOW_MS = 90_000L

    /**
     * How long the cycle counter survives. After this much silence since the
     * last refusal the counters reset entirely: a session kept open all day
     * must not refuse tomorrow's automatic recovery because of this
     * morning's three failures.
     */
    const val STALE_MS = 600_000L

    /**
     * How many full ladders a session climbs before automatic recovery stands
     * down. Three cycles of (desktop, cookies, advice) is the ceiling between
     * honest automation and a reload loop the user cannot stop; past it the
     * refusal is reported and the user drives.
     */
    const val MAX_CYCLES = 3

    /**
     * The host Google's refusal "Learn more" link and its redirect land on.
     * Narrower than "any help page": only the accounts help centre counts,
     * and only its answer articles.
     */
    const val REFUSAL_HELP_HOST = "support.google.com"

    /** Where the recovery reloads sign-in from. */
    const val SIGN_IN_HOME = "https://accounts.google.com/"

    /**
     * One engine session's recovery memory. Plain mutable counters, no clock
     * and no threads inside: the WebView session reports from the main thread
     * only, and the engine session owns the synchronisation story like it
     * does for `lastReissuedUrl`.
     */
    class State {
        /** The last main-frame start on a sign-in host, 0 when none yet. */
        var lastSignInPageAtMs: Long = 0L
            internal set

        /** The last refusal this session reported, 0 when none yet. */
        var lastRefusalAtMs: Long = 0L
            internal set

        /** Refusals inside the current attempt's ladder, 0..3. */
        var refusalsInCycle: Int = 0
            internal set

        /** Completed ladders this session has spent, capped by [MAX_CYCLES]. */
        var cycles: Int = 0
            internal set
    }

    /**
     * Whether [url] is the help article the refusal sends the user to.
     *
     * The host must be exactly [REFUSAL_HELP_HOST] — the same exact-match
     * rule `GoogleAuthNavigation.hostOf` applies everywhere else — and the
     * path must be an answer article of the ACCOUNTS help centre. Anything
     * else on that host is a page a signed-in user can browse to by choice,
     * and treating it as a refusal would erase a working session's cookies.
     */
    fun isRefusalHelpPage(url: String): Boolean {
        if (GoogleAuthNavigation.hostOf(url) != REFUSAL_HELP_HOST) return false
        val path = url
            .substringAfter("://", "")
            .substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
        return path.startsWith("accounts/answer/")
    }

    /**
     * A main-frame load started on a sign-in host. Records the attempt is
     * live, and clears the ladder counters when the last refusal is old
     * enough that this page is a fresh attempt rather than a step inside the
     * previous one — the reload each recovery action performs lands here
     * seconds after its own refusal, so the ladder must NOT reset for it.
     *
     * With no refusal ever recorded (the session's first attempt, or the
     * first after [onLeftSignInHosts] wiped the timestamps) there is nothing
     * to reset — and deliberately nothing else either: the cycle budget is
     * judged by staleness of REFUSALS, so leaving and re-entering the
     * sign-in hosts within the stale window does not mint a fresh budget.
     */
    fun onSignInHostPage(state: State, nowMs: Long) {
        state.lastSignInPageAtMs = nowMs
        if (state.lastRefusalAtMs == 0L) return
        if (nowMs - state.lastRefusalAtMs > REFUSAL_WINDOW_MS) {
            state.refusalsInCycle = 0
        }
        if (nowMs - state.lastRefusalAtMs > STALE_MS) {
            state.cycles = 0
        }
    }

    /**
     * The refusal help page started loading. Answers the next rung of the
     * ladder, or [Action.NONE] when no automatic move is available:
     *
     * - a refusal nobody is live for (no sign-in page this session, or the
     *   last activity is older than [REFUSAL_WINDOW_MS]) is not this
     *   feature's business — a user who opened the help article from a
     *   bookmark gets nothing;
     * - a session that has spent [MAX_CYCLES] ladders, or a cycle already
     *   past the advice rung, gets nothing — the stand-down above all;
     * - anything else climbs: first refusal retries as desktop, second as
     *   cookies, third closes the cycle with [Action.ADVICE].
     */
    fun onRefusal(state: State, nowMs: Long): Action {
        val lastActivity = maxOf(state.lastSignInPageAtMs, state.lastRefusalAtMs)
        if (lastActivity == 0L || nowMs - lastActivity > REFUSAL_WINDOW_MS) return Action.NONE
        if (state.cycles >= MAX_CYCLES) return Action.NONE
        if (state.refusalsInCycle >= 3) return Action.NONE
        state.lastRefusalAtMs = nowMs
        state.refusalsInCycle += 1
        return when (state.refusalsInCycle) {
            1 -> Action.DESKTOP_RETRY
            2 -> Action.COOKIE_RETRY
            else -> {
                state.cycles += 1
                Action.ADVICE
            }
        }
    }

    /**
     * The main frame left the sign-in hosts — signed in successfully, or
     * navigated away. The attempt is over either way: the ladder and its
     * freshness timestamps reset so a refusal hours later is judged on its
     * own. The cycle counter is deliberately left alone here — a user who
     * signs in, browses, and signs out again INSIDE the stale window should
     * not get three fresh automatic ladders per minute.
     */
    fun onLeftSignInHosts(state: State) {
        state.refusalsInCycle = 0
        state.lastSignInPageAtMs = 0L
        state.lastRefusalAtMs = 0L
    }
}
