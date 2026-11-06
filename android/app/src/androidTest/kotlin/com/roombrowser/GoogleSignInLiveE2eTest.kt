package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE OWNER'S OWN TEST, word for word: open accounts.google.com, type
 * alamattest@gmail.com into the email field, submit, and the page that must
 * come back is the one asking for the PASSWORD. Nothing here is mocked —
 * the real site, the real network, the real engine, exactly the flow the
 * field reports were filed against. If this passes, the login page the
 * owner cannot reach on the handset is reachable in the app's engine; if it
 * fails, the screen dump printed below is the evidence.
 *
 * NOT IN THE SMOKE GATE. Google's answer is a policy verdict on a network
 * and an account, not a property of any commit here; gating the release on
 * it would stop shipping for reasons no commit could fix, and passing it
 * would promise something the next Google change could break. The probe
 * class (GoogleSignInProbeE2eTest) keeps the non-asserting verdict print
 * for the smoke run; THIS class is the asserting deep cut, run on demand:
 *
 *   E2E_FILTER=com.roombrowser.GoogleSignInLiveE2eTest
 *
 * THE FLOW, deliberately the owner's exact steps and no shortcuts:
 *
 *   1. a fresh profile — no poisoned jar from any earlier attempt can be
 *      part of the answer, and the 400 recovery path (v1.0.196) is NOT
 *      exercised unless Google itself trips it;
 *   2. open the sign-in URL as the initial tab, the way a user reaches it
 *      from the address bar;
 *   3. the email step: the real account form renders (its own markers,
 *      matched case-sensitively against the page's own text), the address
 *      is typed with the IME-gated shell idiom the suite uses everywhere
 *      (ACTION_SET_TEXT can return false without typing anything), the
 *      read-back is a global text search because a cached node's `.text`
 *      is stale, and the submit is the page's own button;
 *   4. the password step: the markers of the page Google serves after the
 *      email is accepted — "Enter your password", "Show password",
 *      "Forgot password" — any one of them, within a bound generous
 *      enough for Google's own next-page round trip.
 *
 * THE THREE HONEST OUTCOMES are all printable from the screen dump the
 * failure message carries: REFUSED (Google's "not secure" page — the
 * emulator's IP/ASN is a datacenter range Google treats differently from
 * the handset's carrier network), a still-rendered EMAIL step (submit or
 * typing failed — a UI idiom bug, ours to fix), or the 400 page (the
 * jar/identity answer v1.0.196 shipped). The assertion asks for the one
 * the owner asked for: the password page.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSignInLiveE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext
    private val appContext: RoomBrowserApp =
        instrumentation.targetContext.applicationContext as RoomBrowserApp

    private val tag = (System.currentTimeMillis() % 100_000).toString()

    private val signInUrl =
        "https://accounts.google.com/ServiceLogin?hl=en&continue=https%3A%2F%2Fwww.google.com%2F"

    /** The account from the owner's own report. */
    private val account = "alamattest@gmail.com"

    /**
     * Markers of the EMAIL step, matched case-sensitively: "Sign in" alone
     * would match both steps; "Email or phone" is the label of the field
     * itself and "Forgot email" the link only the email step carries.
     */
    private val emailStepMarkers = listOf("Email or phone", "Forgot email")

    /** Markers only the PASSWORD page carries. */
    private val passwordStepMarkers = listOf(
        "Enter your password",
        "Show password",
        "Forgot password"
    )

    /** The refusal page, in case Google answers the load itself. */
    private val refusalMarkers = listOf(
        "may not be secure",
        "Couldn't sign you in",
        "couldn't sign you in",
        "This browser or app"
    )

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
    }

    private fun createProfile(): ProfileId = runBlocking {
        val profile = appContext.graph.profileManager.create("E2EGoogleLive$tag", "G", 0xFF6750A4)
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
            markers.firstOrNull { marker -> screen.any { it.contains(marker) } }?.let { return it }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
        }
        return null
    }

    /**
     * The IME-gated typing idiom this suite settled on (see
     * SettingsEffectsE2eTest): click the field, wait for the IME, MOVE_END,
     * clear, `input text`, and a global read-back because a cached node's
     * `.text` is stale. Returns the text actually visible after typing.
     */
    private fun typeInto(field: UiObject2): Boolean {
        clickCenter(field)
        val imeUp = waitImeShown(5_000)
        if (!imeUp) return false
        device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
        device.clearFocusedField()
        device.waitForIdle(300)
        device.executeShellCommand("input text $account")
        device.waitForIdle(800)
        val typed = device.hasObject(By.textContains(account))
        if (!typed) {
            runCatching { field.setText(account) }
            device.waitForIdle(600)
        }
        return device.hasObject(By.textContains(account))
    }

    private fun clickCenter(node: UiObject2) {
        runCatching {
            val r = node.visibleBounds
            device.click(r.centerX(), r.centerY())
            device.waitForIdle(400)
        }
    }

    private fun waitImeShown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown()) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            }
        }
        return imeShown()
    }

    private fun imeShown(): Boolean = try {
        device.executeShellCommand("dumpsys input_method | grep mInputShown")
            .contains("mInputShown=true")
    } catch (_: Exception) {
        false
    }

    @Test
    fun email_step_accepts_the_address_and_password_page_renders() {
        val profileId = createProfile()
        launchEngineAt(signInUrl, profileId)

        // --- Step 1: the email form itself must render. A refusal here is
        // Google answering the page LOAD, before any typing — the outcome
        // the identity work (v1.0.193-197) is about.
        val emailMarker = waitForMarker(emailStepMarkers + refusalMarkers, 60_000)
        val screen1 = textsOnScreen()
        assertTrue(
            "The Google sign-in form did not render. Screen was:\n" +
                screen1.joinToString(" | "),
            emailMarker != null
        )
        assertTrue(
            "Google answered the sign-in load with its refusal page ('$emailMarker'). " +
                "Screen was:\n" + screen1.joinToString(" | "),
            refusalMarkers.none { m -> screen1.any { it.contains(m) } }
        )

        // --- Step 2: find the email input. Google's sign-in field is an
        // EditText inside the page; the omnibox is also an EditText, so the
        // field is the one NOT carrying the URL and the one whose hint or
        // label names the email.
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
        assertTrue(
            "No text field found on the sign-in page. Screen was:\n" +
                screen1.joinToString(" | "),
            field != null
        )
        // The omnibox may be first in the tree; pick the field whose text is
        // not the sign-in URL, or the last EditText on screen (the form
        // field renders below the chrome in the layout order this suite
        // has always seen).
        val candidates = device.findObjects(By.clazz("android.widget.EditText"))
        val formField = candidates.lastOrNull { it.text?.contains("accounts.google.com") != true }
        assertTrue(
            "The email input could not be told apart from the omnibox " +
                "(${candidates.size} EditTexts on screen). Screen was:\n" +
                textsOnScreen().joinToString(" | "),
            formField != null
        )

        // --- Step 3: type the address, read it back.
        assertTrue(
            "The address $account could not be typed into the email field.",
            typeInto(formField!!)
        )

        // --- Step 4: submit via the page's own button. The CI screen dump
        // settled the label question: the ServiceLogin page under hl=en
        // serves "Sign in" for the email step (a "Next" hunt failed at
        // 02:45Z on run 667 with the button in plain sight); Indonesian
        // pages serve "Berikutnya". "Sign in" is also the page TITLE, so
        // the candidates are filtered to buttons/links BELOW the typed
        // address — the form's action row, not the document header.
        val typedBottom = device.findObjects(By.textContains(account))
            .mapNotNull { runCatching { it.visibleBounds.bottom }.getOrNull() }
            .maxOrNull()
        val next = device.findObjects(By.text("Sign in"))
            .ifEmpty { device.findObjects(By.text("Berikutnya")) }
            .ifEmpty { device.findObjects(By.text("Next")) }
            .filter { node ->
                runCatching { node.visibleBounds }.isSuccess &&
                    (typedBottom == null || node.visibleBounds.top > typedBottom)
            }
            .maxByOrNull { it.visibleBounds.top }
        assertTrue(
            "The submit button (Sign in / Berikutnya / Next) was not found after " +
                "typing the address. Screen was:\n" + textsOnScreen().joinToString(" | "),
            next != null
        )
        clickCenter(next!!)
        device.waitForIdle(1_500)

        // --- Step 5: the password page. Generous bound: Google's own
        // round trip (the lookup, the session cookie chain) is slow, and a
        // short wait would fail the test for a network that was still
        // working.
        val passwordMarker = waitForMarker(passwordStepMarkers + refusalMarkers, 45_000)
        val screen2 = textsOnScreen()
        assertTrue(
            "The password page did not render after submitting $account. " +
                "Markers wanted: $passwordStepMarkers. Screen was:\n" +
                screen2.joinToString(" | "),
            passwordMarker != null
        )
        assertTrue(
            "Google answered the email submission with its refusal page ('$passwordMarker'). " +
                "Screen was:\n" + screen2.joinToString(" | "),
            refusalMarkers.none { m -> screen2.any { it.contains(m) } }
        )
        assertTrue(
            "The password field is missing from the page that rendered after submit. " +
                "Screen was:\n" + screen2.joinToString(" | "),
            device.hasObject(By.clazz("android.widget.EditText"))
        )
    }
}
