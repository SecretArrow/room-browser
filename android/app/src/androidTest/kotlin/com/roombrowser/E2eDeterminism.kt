package com.roombrowser

import com.roombrowser.domain.model.WarningBehavior
import kotlinx.coroutines.runBlocking

/**
 * E2E determinism: suppress the ORGANIC profile-network warning.
 *
 * WHY: every e2e test that creates a profile and boots the engine does so
 * from the SAME runner IP. The IpConflictDetector correctly treats that as
 * a profile/IP association conflict (its whole purpose) — so on the CI
 * runner, nearly EVERY fresh-profile engine boot arms the full-screen
 * NetworkWarningActivity over the engine. That is correct product behavior
 * but test NOISE: it makes engine boots non-deterministic for suites that
 * do not exercise the warning.
 *
 * The fix: flip the GLOBAL warning behavior to DONT_WARN before booting.
 * The detector then never arms organically. NetworkWarningActivityE2eTest
 * is NOT affected: its cycles arm the gate through the PERSISTED pending
 * decision (app_state "net_decision_pending"), which initialize() reads
 * unconditionally — independent of warningBehavior.
 *
 * Runs in the instrumentation process; the write lands in the shared Room
 * database before any ':browser' process boots (multi-instance visibility
 * is also how setPendingNetDecision already crosses processes).
 *
 * PROFILE SCOPING (why no profile id is needed here): the lever used below
 * is the GLOBAL warning behavior, which is app-global by design — so this
 * helper stays profile-independent and is unaffected by the per-profile
 * "suppressed_ips:<profileId>" scoping. That matters because this runs
 * BEFORE any profile exists. Should a suite ever need IP-level
 * pre-suppression instead, writing the LEGACY app_state key
 * "suppressed_ips" still works for exactly the same reason: the first read
 * for the active profile adopts it and retires the row (see
 * AppStateRepository.suppressedIps), so an install/test can still
 * pre-suppress without knowing a profile id.
 */
object E2eDeterminism {

    /** Suppress organic network warnings for the whole app state. */
    fun suppressOrganicNetworkWarnings() {
        val graph = (appContext()).graph
        runBlocking {
            val current = graph.appState.globalSettingsSnapshot()
            if (current.warningBehavior != WarningBehavior.DONT_WARN) {
                graph.appState.saveGlobalSettings(
                    current.copy(warningBehavior = WarningBehavior.DONT_WARN)
                )
            }
        }
    }

    private fun appContext(): RoomBrowserApp =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as RoomBrowserApp
}
