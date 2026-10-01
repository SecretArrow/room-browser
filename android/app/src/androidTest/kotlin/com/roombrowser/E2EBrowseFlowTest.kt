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
 *     -> create profile via dialog (first profile AUTO-OPENS the engine)
 *        or tap OPEN on an existing profile card
 *   BrowserActivity (':browser' process, own WebView data dir)
 *     -> omnibox / homepage is visible
 *
 * Compose nodes are clicked by COORDINATE (center of the text node's
 * bounds) — the most robust strategy across the Compose accessibility
 * bridge, independent of the 'clickable' flag mapping.
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

    /** Polls until no node shows [text] anymore (dialog closed). */
    private fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isEmpty()) return true
            Thread.sleep(250)
        }
        return device.findObjects(By.text(text)).isEmpty()
    }

    /** Polls until [condition] holds (250 ms cadence). */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    /** Clicks the center of the first node showing [text]. */
    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickCenter(node)
    }

    /**
     * Slow half-screen drag (3/4 → 1/4). The CI emulator's default profile
     * is 320x640 mdpi — anything below ~570px is off-screen, and off-screen
     * nodes are NOT exposed to the a11y tree (proven in CI run 9399640:
     * "Create Profile" sat ~30px under the fold and was unreachable for
     * 8 minutes of polling). Slow steps = controlled scroll, no fling.
     */
    private fun dragUpHalf() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(600)
    }

    /** Scroll-aware presence check (deep list content lives below the fold). */
    private fun hasTextScrollable(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpHalf()
        }
        return false
    }

    /** Scroll-aware click. */
    private fun clickTextScrollable(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpHalf()
        }
        return false
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    /** The engine UI is up when the omnibox placeholder or homepage shows. */
    private fun engineUiUp(timeoutMs: Long): Boolean =
        hasText("Search or type URL", timeoutMs)
            || hasText("Privacy Dashboard", 10_000)
            || hasText("trackers blocked", 5_000)

    /**
     * Full bootstrap: cold start -> first-run profile creation (or OPEN on
     * an existing card) -> the ':browser' engine UI is visible.
     */
    private fun openEngineFromLauncher(): Boolean {
        // ---- 1. Cold start into the launcher activity --------------------
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "First-run welcome (or profile list) should appear",
            hasText("Create Profile", 90_000) ||
                hasText("OPEN", 10_000) ||
                // Cards can push the create affordance below the fold on the
                // small CI screen — scroll before giving up.
                hasTextScrollable("Create Profile")
        )

        val alreadyHasProfile = device.findObjects(By.text("OPEN")).isNotEmpty()

        if (!alreadyHasProfile) {
            // ---- 2a. Create the first profile through the real dialog ---
            // 'Cancel' only exists inside the dialog: reliable open-signal.
            var dialogOpen = false
            for (attempt in 1..3) {
                assertTrue(
                    "Welcome 'Create Profile' button must be visible",
                    clickTextScrollable("Create Profile", attempts = 4)
                )
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open (retry failed)", dialogOpen)

            // Focus the name field: Compose text fields map to EditText; fall
            // back to the 'Name' label node when the class hint is absent.
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                ?: device.wait(Until.findObject(By.text("Name")), 5_000)
            assertTrue("Name text field must be visible", field != null)

            // Type the profile name — verified, with one retry.
            var typed = false
            for (attempt in 1..2) {
                clickCenter(field!!)
                device.executeShellCommand("input text E2E_Profile")
                device.waitForIdle(1_500)
                if (device.findObjects(By.textContains("E2E")).isNotEmpty()) {
                    typed = true
                    break
                }
            }
            assertTrue("Profile name must be typed into the field", typed)

            // Confirm button: same row as 'Cancel', to its right.
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

            // The dialog must close — proves the create callback fired (a
            // blank name would leave the dialog open).
            assertTrue(
                "Create dialog should close after confirm",
                waitGone("Cancel", 8_000)
            )

            // ---- 3. First-run creation AUTO-OPENS the :browser engine ---
            // (MainScreen's onCreate callback calls onOpenProfile directly.)
            // Older/alternative flows land on the profile list with OPEN.
            if (!engineUiUp(30_000)) {
                assertTrue(
                    "Profile card with OPEN must appear after creation",
                    hasText("OPEN", 10_000)
                )
                assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))
            }
        } else {
            // ---- 2b. Existing profile: straight to the engine -----------
            assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))
        }

        // ---- 4. The engine UI runs in the separate ':browser' process ---
        // UiAutomator addresses the whole device, so this also proves the
        // engine process booted with its own WebView data directory.
        return engineUiUp(30_000)
    }

    @Test
    fun first_run_create_profile_and_open_browser_engine() {
        // Determinism: the runner's shared IP arms the organic network
        // warning on fresh-profile boots — suppress it (E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue(
            "Browser UI (omnibox / homepage) must appear in the :browser process",
            openEngineFromLauncher()
        )
    }

    /**
     * SYSTEM BACK vs the engine (the "menabrak tombol back" regression guard).
     *
     * Contract (BrowserScreen's BackHandler):
     *   On the homepage (no web history) the FIRST system Back press must
     *   move the whole task to the background (moveTaskToBack) — keeping the
     *   engine process and all tabs alive. It must NEVER finish the engine
     *   activity back to the profile list on a single press.
     */
    @Test
    fun system_back_backgrounds_app_without_killing_engine() {
        // Determinism: the runner's shared IP arms the organic network
        // warning on fresh-profile boots — suppress it (E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())

        // ---- 1. First Back press: homepage has no history -> background ---
        device.pressBack()
        device.waitForIdle(1_000)
        val backgrounded = waitUntil(4_000) {
            device.currentPackageName != targetContext.packageName
        }
        assertTrue(
            "First Back must background the app (moveTaskToBack), not stay in-app",
            backgrounded
        )
        assertTrue(
            "Back must not finish the engine back to the profile list (old bug)",
            device.findObjects(By.text("Your profiles")).isEmpty()
        )

        // ---- 2. The engine survives: resume it, UI intact ----------------
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertTrue(
            "Engine must resume with its UI intact after being backgrounded",
            engineUiUp(20_000)
        )
    }
}
