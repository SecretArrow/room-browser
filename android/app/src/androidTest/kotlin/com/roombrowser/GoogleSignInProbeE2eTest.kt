package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What GOOGLE answers, measured rather than assumed.
 *
 * Every other test in this suite asserts what this app puts on the wire and
 * what a page's own JavaScript reads back. None of them can say whether Google
 * accepts it, because only Google decides that — and the owner's report ("it
 * still refuses, with the same message") is exactly the question this suite
 * could not answer before.
 *
 * SO THIS ONE ASKS GOOGLE. It loads the real sign-in endpoint in the app's own
 * engine and reports which page came back:
 *
 *  - REFUSED — Google's "Couldn't sign you in / This browser or app may not be
 *    secure" page. Google's OAuth policy forbids directing an authorization
 *    request to an embedded user-agent under the app's control, and this
 *    browser IS one, so this outcome may be a policy answer rather than a
 *    fingerprint the app can clean up.
 *  - FORM — the account form rendered, which means the identity passed.
 *  - UNREACHABLE / UNKNOWN — the emulator had no route, or Google answered
 *    with something neither of the above.
 *
 * DELIBERATELY NOT AN ASSERTION ON GOOGLE'S VERDICT. Google's answer depends on
 * the network, on rate limiting and on policies this repository does not
 * control; failing the release gate on it would stop shipping for reasons no
 * commit here could fix, and passing it would be a promise the next Google
 * change could break. What IS asserted is that the probe really ran — a page
 * rendered — so a silent no-op cannot masquerade as a result. The verdict is
 * printed with a stable marker so it can be read out of the e2e artifact.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSignInProbeE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext
    private val appContext: RoomBrowserApp =
        instrumentation.targetContext.applicationContext as RoomBrowserApp

    private val tag = (System.currentTimeMillis() % 100_000).toString()

    private val signInUrl =
        "https://accounts.google.com/ServiceLogin?hl=en&continue=https%3A%2F%2Fwww.google.com%2F"

    /** Phrases that only appear on Google's refusal page. */
    private val refusalMarkers = listOf(
        "may not be secure",
        "Couldn't sign you in",
        "couldn't sign you in",
        "This browser or app"
    )

    /** Phrases that only appear when the account form actually rendered. */
    private val formMarkers = listOf(
        "Email or phone",
        "Forgot email",
        "Create account",
        "Sign in"
    )

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
    }

    private fun createProfile(): ProfileId = runBlocking {
        val profile = appContext.graph.profileManager.create("E2EGoogleProbe$tag", "G", 0xFF6750A4)
        appContext.graph.appState.setActiveProfile(profile.id.value)
        profile.id
    }

    private fun launchEngineAt(url: String, profileId: ProfileId) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.PROFILE_ID", profileId.value)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
    }

    private fun textsOnScreen(): List<String> = runCatching {
        device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
    }.getOrDefault(emptyList())

    private fun waitForMarker(markers: List<String>, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val screen = textsOnScreen()
            markers.firstOrNull { marker -> screen.any { it.contains(marker, ignoreCase = true) } }
                ?.let { return it }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
        }
        return null
    }

    @Test
    fun google_sign_in_reports_what_google_answers() {
        val profileId = createProfile()
        launchEngineAt(signInUrl, profileId)

        // Generous on purpose: a cold emulator DNS lookup plus Google's own
        // redirect chain is slow, and a short wait would report UNREACHABLE for
        // a network that was merely still working. Both branches are bounded so
        // a Google that answers with neither page cannot stall the suite.
        val refusal = waitForMarker(refusalMarkers, 60_000)
        val form = if (refusal == null) waitForMarker(formMarkers, 20_000) else null
        val screen = textsOnScreen()

        val verdict = when {
            refusal != null -> "REFUSED"
            form != null -> "FORM"
            screen.isEmpty() -> "UNREACHABLE"
            else -> "UNKNOWN"
        }

        println(
            "GoogleSignInProbe: verdict=$verdict marker='${refusal ?: form}' " +
                "engine='${runCatching { ProfileEngine.engineName(targetContext) }.getOrNull()}'"
        )
        println("GoogleSignInProbe: screen=${screen.joinToString(" | ")}")

        // The probe must have produced a result, never silence: a blank screen
        // would mean this test measured nothing and its verdict means nothing.
        assertTrue(
            "The probe rendered nothing at all — it cannot report on Google from an " +
                "empty screen",
            screen.isNotEmpty()
        )
    }
}
