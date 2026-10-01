package com.roombrowser

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E WARM-UP — runs FIRST (class name sorts before every other suite).
 *
 * MEASURED PROBLEM (runs d9587fe / 0e60f38): the first app launches on a
 * freshly-installed APK block for MINUTES on the 2-core CI runner — the
 * window draws (ActivityTaskManager reports Displayed +150ms) but Compose
 * composition stalls while ART verifies the dex of the whole (now wallet-
 * bearing) APK and the background dexopt churns. In the 0e60f38 run every
 * engine-booting test that started inside the first ~7 minutes timed out
 * finding ANY UI node, and everything afterwards passed. Individual test
 * timeouts cannot absorb that window without wasting it per test.
 *
 * So this test absorbs it ONCE, patiently: launch the app, wait for the
 * welcome with a huge timeout, boot the engine once (which also warms the
 * ':browser' process AND — via the deferred bind — the wallet crypto
 * stack), then delete the warm-up profile through the SAME repository the
 * UI uses so the app returns to its first-run state for the real suites.
 *
 * The real suites keep their existing generous timeouts; after this test
 * they run on a warm, fully-dexopted app.
 */
@RunWith(AndroidJUnit4::class)
class A00WarmupTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val targetContext =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun warm_up_default_and_browser_processes() {
        E2eDeterminism.suppressOrganicNetworkWarnings()

        // ---- 1. Default process: launch and wait out the cold window ----
        device.pressHome()
        launchMainActivity()
        assertTrue(
            "Warm-up: welcome/profile list must appear (patient cold-start wait)",
            device.wait(Until.hasObject(By.text("Create Profile")), 480_000) ||
                device.wait(Until.hasObject(By.text("Your profiles")), 10_000)
        )

        // ---- 2. Create a throwaway profile and boot the engine -----------
        // (Also warms the deferred wallet bind's crypto classes ~2.5 s in.)
        assertTrue(
            "Warm-up: Create Profile affordance must be reachable",
            clickScrollAwareCreate()
        )
        assertTrue(
            "Warm-up: create dialog must open",
            device.wait(Until.hasObject(By.text("Cancel")), 15_000)
        )
        val field = device.wait(
            Until.findObject(By.clazz("android.widget.EditText")), 15_000
        )
        assertTrue("Warm-up: name field must exist", field != null)
        field!!.click()
        device.executeShellCommand("input text Warmup")
        device.waitForIdle(1_000)
        // Confirm = the 'Create Profile' button in the dialog row (Cancel is
        // the open signal; proximity picks the right one — proven pattern).
        val cancel = device.findObjects(By.text("Cancel"))
            .minByOrNull { it.visibleBounds.top }
        val confirm = device.findObjects(By.text("Create Profile"))
            .filter { c ->
                cancel != null && kotlin.math.abs(
                    c.visibleBounds.centerY() - cancel.visibleBounds.centerY()
                ) < 200
            }
            .maxByOrNull { it.visibleBounds.centerX() }
        assertTrue("Warm-up: dialog confirm must be found", confirm != null)
        confirm!!.click()
        assertTrue(
            "Warm-up: dialog must close after confirm",
            device.wait(Until.gone(By.text("Cancel")), 20_000)
        )

        // ---- 3. ':browser' process: wait for the engine UI (patient) ------
        val engineUp = run {
            val deadline = System.currentTimeMillis() + 480_000
            var up = false
            while (System.currentTimeMillis() < deadline && !up) {
                up = device.wait(Until.hasObject(By.descContains("Address bar")), 400) ||
                    device.wait(Until.hasObject(By.text("Privacy Dashboard")), 400)
                if (!up) Thread.sleep(250)
            }
            up
        }
        assertTrue("Warm-up: engine UI must come up (patient ':browser' wait)", engineUp)

        // ---- 4. Clean up through the real repository path -----------------
        // Leaves the app in first-run state for the suites that follow.
        runBlocking {
            val graph = (targetContext.applicationContext as RoomBrowserApp).graph
            val active = graph.appState.activeProfileIdSnapshot()
            if (active != null) {
                graph.profileRepo.remove(
                    com.roombrowser.domain.model.ProfileId(active),
                    cascadeData = true
                )
                graph.appState.setActiveProfile(null)
            }
        }
        device.pressHome()
    }

    private fun launchMainActivity() {
        val intent = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    /**
     * The create affordance must be REACHED before it can be tapped: on the
     * CI emulator's 320x640 mdpi screen an affordance below the fold is NOT
     * in the a11y tree (CI run 9399640: 8 minutes of blind polling found
     * "Your profiles" but never the button). Poll, slow-drag a half screen,
     * tap, and VERIFY the dialog actually opened before declaring success.
     */
    private fun clickScrollAwareCreate(): Boolean {
        for (i in 1..16) {
            val node = device.wait(Until.findObject(By.text("Create Profile")), 1_500)
            if (node != null) {
                val b = node.visibleBounds
                device.click(b.centerX(), b.centerY())
                device.waitForIdle(1_000)
                if (device.wait(Until.hasObject(By.text("Cancel")), 4_000)) return true
                // The tap missed (the node moved mid-frame) — fall through
                // to another scroll round and retry.
            }
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 100
            )
            device.waitForIdle(600)
        }
        return false
    }
}
