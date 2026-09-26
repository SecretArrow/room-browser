package com.roombrowser

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * END-TO-END FLOW TEST (spec section 59: integration / e2e).
 *
 * Drives the real app across BOTH processes with UiAutomator:
 *
 *   MainActivity (default process)
 *     -> first-run welcome
 *     -> create profile via dialog
 *     -> tap OPEN
 *   BrowserActivity (':browser' process, own WebView data dir)
 *     -> omnibox with "Search or type URL" is visible
 *
 * This is the closest thing to a real user session that can run
 * unattended on CI (GitHub Actions emulator).
 */
@RunWith(AndroidJUnit4::class)
class E2EBrowseFlowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(
                    targetContext.packageName,
                    "com.roombrowser.main.MainActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun hasObject(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    /** Clicks the topmost (smallest vertical center) clickable node matching [text]. */
    private fun clickTopmost(text: String): Boolean {
        val nodes = device.findObjects(By.text(text).clickable(true))
        if (nodes.isEmpty()) return false
        val top = nodes.minByOrNull { it.visibleBounds.centerY() } ?: return false
        return top.click()
    }

    @Test
    fun first_run_create_profile_and_open_browser_engine() {
        // ---- 1. Cold start into the launcher activity ------------------------
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "First-run welcome (or profile list) should appear",
            hasObject("Create Profile", 20_000) || hasObject("OPEN", 10_000)
        )

        // ---- 2. Create a profile through the real dialog --------------------
        if (device.findObjects(By.text("OPEN")).isEmpty()) {
            // Welcome screen -> open the Create Profile dialog.
            clickTopmost("Create Profile")
            assertTrue("Create-profile dialog should show the Name field", hasObject("Name", 10_000))

            // Focus the name field (label doubles as node text) and type a name.
            val field = device.findObjects(By.text("Name"))
                .minByOrNull { it.visibleBounds.centerY() }
            assertTrue("Name text field must be visible", field != null)
            field!!.click()
            SystemClock.sleep(500)
            device.executeShellCommand("input text E2E_Profile")
            device.waitForIdle(1_000)

            // Confirm inside the dialog (topmost 'Create Profile' button).
            assertTrue("Dialog confirm button must be clickable", clickTopmost("Create Profile"))

            // Wait until the profile card with the OPEN button appears.
            assertTrue(
                "Profile card with OPEN must appear after creation",
                hasObject("OPEN", 15_000)
            )
        }

        // ---- 3. Open the engine process (:browser) --------------------------
        assertTrue("OPEN button must be clickable", clickTopmost("OPEN"))

        // The omnibox lives in the separate ':browser' process; UiAutomator
        // addresses the whole device, so this also proves the engine started.
        assertTrue(
            "Browser omnibox ('Search or type URL') must appear in the :browser process",
            hasObject("Search or type URL", 30_000)
        )
    }
}
