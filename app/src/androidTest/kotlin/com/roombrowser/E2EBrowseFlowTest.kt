package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
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
 *     -> omnibox / homepage is visible
 *
 * Compose nodes are clicked by COORDINATE (center of the text node's
 * bounds) — this is the most robust strategy across the Compose
 * accessibility bridge, independent of the 'clickable' flag mapping.
 */
@RunWith(AndroidJUnit4::class)
class E2EBrowseFlowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    /** Clicks the center of the first node showing [text]. */
    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickCenter(node)
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun first_run_create_profile_and_open_browser_engine() {
        // ---- 1. Cold start into the launcher activity ------------------------
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "First-run welcome (or profile list) should appear",
            hasText("Create Profile", 20_000) || hasText("OPEN", 10_000)
        )

        // ---- 2. Create a profile through the real dialog --------------------
        if (device.findObjects(By.text("OPEN")).isEmpty()) {
            // Welcome screen -> open the Create Profile dialog. 'Cancel' only
            // exists inside the dialog, so it is a reliable open-signal.
            var dialogOpen = false
            for (attempt in 1..2) {
                assertTrue("Welcome 'Create Profile' button must be visible", clickText("Create Profile", 5_000))
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open (retry failed)", dialogOpen)

            // Focus the name field: Compose text fields map to EditText; fall
            // back to the 'Name' label node when the class hint is absent.
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                ?: device.wait(Until.findObject(By.text("Name")), 5_000)
            assertTrue("Name text field must be visible", field != null)
            clickCenter(field!!)
            device.executeShellCommand("input text E2E_Profile")
            device.waitForIdle(1_000)

            // Confirm button: sits in the SAME row as 'Cancel', to its right.
            val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
            val confirm = device.findObjects(By.text("Create Profile"))
                .filter { c ->
                    cancel != null && kotlin.math.abs(
                        c.visibleBounds.centerY() - cancel.visibleBounds.centerY()
                    ) < 200
                }
                .maxByOrNull { it.visibleBounds.centerX() }
            assertTrue("Dialog confirm button must be found", confirm != null)
            clickCenter(confirm!!)

            // Wait until the profile card with the OPEN button appears.
            assertTrue(
                "Profile card with OPEN must appear after creation",
                hasText("OPEN", 15_000)
            )
        }

        // ---- 3. Open the engine process (:browser) --------------------------
        assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))

        // The omnibox lives in the separate ':browser' process; UiAutomator
        // addresses the whole device, so this also proves the engine started.
        val engineUp = hasText("Search or type URL", 30_000)
            || hasText("Privacy Dashboard", 10_000)
            || hasText("trackers blocked", 5_000)
        assertTrue(
            "Browser UI (omnibox / homepage) must appear in the :browser process",
            engineUp
        )
    }
}
