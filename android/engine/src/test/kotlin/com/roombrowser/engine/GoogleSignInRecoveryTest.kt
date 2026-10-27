package com.roombrowser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in refusal recovery ladder, pinned before it reaches a WebView.
 *
 * Every branch is either a UX boundary (how many automatic reloads the user
 * can be subjected to) or a data-loss boundary (the cookie rung signs the
 * profile out of Google properties), so the constants and the order are part
 * of the contract the same way `GoogleAuthNavigation`'s are:
 *
 * - the ladder climbs DESKTOP -> COOKIES -> ADVICE and never re-climbs inside
 *   a cycle that has already reached ADVICE;
 * - a refusal nobody is live for (no sign-in page this session, or the last
 *   activity outside the refusal window) is ignored -- a user who opens the
 *   help article from a bookmark gets no automatic reload;
 * - the recovery's own reloads count as sign-in page starts, so the ladder
 *   does not reset for them, while a genuinely fresh attempt beyond the
 *   window does reset it;
 * - and the cycle counters expire, so a session kept open for days recovers
 *   automatically again instead of standing down forever.
 */
class GoogleSignInRecoveryTest {

    // ---- isRefusalHelpPage ------------------------------------------------

    @Test
    fun `the refusal help article is recognised - with query and fragment`() {
        assertTrue(
            GoogleSignInRecovery.isRefusalHelpPage(
                "https://support.google.com/accounts/answer/7675132?hl=en&ref_topic=7189038"
            )
        )
        assertTrue(
            GoogleSignInRecovery.isRefusalHelpPage(
                "https://support.google.com/accounts/answer/7675132#zippy=%2Candroid"
            )
        )
    }

    @Test
    fun `a different help-centre path on the same host is not a refusal`() {
        assertFalse(
            GoogleSignInRecovery.isRefusalHelpPage("https://support.google.com/accounts/")
        )
        assertFalse(
            GoogleSignInRecovery.isRefusalHelpPage("https://support.google.com/chrome/answer/1234")
        )
    }

    @Test
    fun `another host is not a refusal - including lookalikes`() {
        assertFalse(
            GoogleSignInRecovery.isRefusalHelpPage(
                "https://evil.example/accounts/answer/7675132"
            )
        )
        assertFalse(
            GoogleSignInRecovery.isRefusalHelpPage(
                "https://support.google.com.evil.example/accounts/answer/7675132"
            )
        )
    }

    @Test
    fun `a plain-http help page is not a refusal`() {
        assertFalse(
            GoogleSignInRecovery.isRefusalHelpPage(
                "http://support.google.com/accounts/answer/7675132"
            )
        )
    }

    @Test
    fun `a non-web url is never a refusal`() {
        assertFalse(GoogleSignInRecovery.isRefusalHelpPage("about:blank"))
        assertFalse(GoogleSignInRecovery.isRefusalHelpPage(""))
    }

    // ---- the ladder -------------------------------------------------------

    private val state = GoogleSignInRecovery.State()

    @Test
    fun `the first live refusal climbs to the desktop retry`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        assertEquals(
            GoogleSignInRecovery.Action.DESKTOP_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        )
    }

    @Test
    fun `the second refusal climbs to the cookie retry`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        assertEquals(
            GoogleSignInRecovery.Action.COOKIE_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L)
        )
    }

    @Test
    fun `the third refusal closes the cycle with the advice notice`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L)
        assertEquals(
            GoogleSignInRecovery.Action.ADVICE,
            GoogleSignInRecovery.onRefusal(state, nowMs = 13_000L)
        )
        assertEquals(1, state.cycles)
    }

    @Test
    fun `a cycle past the advice rung never climbs again`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 13_000L)
        assertEquals(
            GoogleSignInRecovery.Action.NONE,
            GoogleSignInRecovery.onRefusal(state, nowMs = 14_000L)
        )
    }

    @Test
    fun `a refusal with no sign-in page this session is ignored`() {
        assertEquals(
            GoogleSignInRecovery.Action.NONE,
            GoogleSignInRecovery.onRefusal(state, nowMs = 10_000L)
        )
    }

    @Test
    fun `a refusal long after the last sign-in page is ignored`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        assertEquals(
            GoogleSignInRecovery.Action.NONE,
            GoogleSignInRecovery.onRefusal(
                state,
                nowMs = 10_000L + GoogleSignInRecovery.REFUSAL_WINDOW_MS + 1
            )
        )
    }

    @Test
    fun `the recovery's own reload keeps the ladder alive inside the window`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        // The DESKTOP_RETRY reload lands on the sign-in home a second later;
        // this page start must NOT reset the ladder, or the cookie rung would
        // never be reached and every retry would loop on the desktop step.
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 12_000L)
        assertEquals(1, state.refusalsInCycle)
        assertEquals(
            GoogleSignInRecovery.Action.COOKIE_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 13_000L)
        )
    }

    @Test
    fun `the refusal window is measured from the last refusal, not only the page`() {
        // A flow with no reload between refusals: the sign-in page is at
        // t=1s, the first refusal at t=81s (inside the page's window), the
        // second at t=161s -- 161s after the PAGE (stale by the page-only
        // reading) but only 80s after the FIRST REFUSAL (still a live flow).
        // The window therefore follows the refusal, and the ladder climbs.
        // (The page start must be non-zero: 0L is the "never happened"
        // sentinel every entry point guards on.)
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 1_000L)
        assertEquals(
            GoogleSignInRecovery.Action.DESKTOP_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 81_000L)
        )
        assertEquals(
            GoogleSignInRecovery.Action.COOKIE_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 161_000L)
        )
    }

    // ---- fresh attempts and caps -------------------------------------------

    @Test
    fun `a fresh attempt beyond the window starts the ladder from the top`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L)
        // The user gives up, comes back later, starts over: beyond the
        // refusal window a new sign-in page is a new attempt.
        GoogleSignInRecovery.onSignInHostPage(
            state,
            nowMs = 12_000L + GoogleSignInRecovery.REFUSAL_WINDOW_MS + 1
        )
        assertEquals(0, state.refusalsInCycle)
        assertEquals(
            GoogleSignInRecovery.Action.DESKTOP_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L + GoogleSignInRecovery.REFUSAL_WINDOW_MS + 2)
        )
    }

    @Test
    fun `the session stands down after the cycle cap`() {
        // Three full ladders, each in its own cycle.
        var now = 0L
        repeat(3) {
            now += 10_000L
            GoogleSignInRecovery.onSignInHostPage(state, nowMs = now)
            // Each cycle is deliberately a fresh one: a stale gap between
            // cycles so the page-start reset applies.
            now += GoogleSignInRecovery.REFUSAL_WINDOW_MS + 10_000L
            GoogleSignInRecovery.onSignInHostPage(state, nowMs = now)
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 1_000)
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 2_000)
            assertEquals(
                "cycle ${it + 1} should close with advice",
                GoogleSignInRecovery.Action.ADVICE,
                GoogleSignInRecovery.onRefusal(state, nowMs = now + 3_000)
            )
        }
        assertEquals(GoogleSignInRecovery.MAX_CYCLES, state.cycles)
        // A fourth attempt, still inside the stale window: no automatic moves.
        now = state.lastRefusalAtMs + 10_000L
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = now)
        assertEquals(
            GoogleSignInRecovery.Action.NONE,
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 1_000)
        )
    }

    @Test
    fun `the cycle cap expires after the stale window`() {
        var now = 0L
        repeat(3) {
            now += 10_000L
            GoogleSignInRecovery.onSignInHostPage(state, nowMs = now)
            now += GoogleSignInRecovery.REFUSAL_WINDOW_MS + 10_000L
            GoogleSignInRecovery.onSignInHostPage(state, nowMs = now)
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 1_000)
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 2_000)
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 3_000)
        }
        // Long silence -- a session left open overnight -- then a fresh try.
        now = state.lastRefusalAtMs + GoogleSignInRecovery.STALE_MS + 1
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = now)
        assertEquals(0, state.cycles)
        assertEquals(
            GoogleSignInRecovery.Action.DESKTOP_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = now + 1_000)
        )
    }

    @Test
    fun `leaving the sign-in hosts ends the attempt`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        GoogleSignInRecovery.onLeftSignInHosts(state)
        assertEquals(0, state.refusalsInCycle)
        assertEquals(0L, state.lastSignInPageAtMs)
        // A refusal right after -- a help page reached from elsewhere -- is
        // nobody's business: the attempt is over.
        assertEquals(
            GoogleSignInRecovery.Action.NONE,
            GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L)
        )
    }

    @Test
    fun `leaving the sign-in hosts starts a fresh ladder, not a fresh budget`() {
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 10_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 11_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 12_000L)
        GoogleSignInRecovery.onRefusal(state, nowMs = 13_000L)
        assertEquals(1, state.cycles)
        // Signed in on another tab's turn, or navigated away and back.
        GoogleSignInRecovery.onLeftSignInHosts(state)
        GoogleSignInRecovery.onSignInHostPage(state, nowMs = 60_000L)
        assertEquals(
            GoogleSignInRecovery.Action.DESKTOP_RETRY,
            GoogleSignInRecovery.onRefusal(state, nowMs = 61_000L)
        )
        assertEquals(1, state.cycles)
    }
}
