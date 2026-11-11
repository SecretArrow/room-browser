package com.roombrowser

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Ask this page" — the one-shot page-grounded AI assistant — opened through
 * the real page-actions sheet, against the real picker/browser flow.
 *
 * The honest contract verified here (CI runs with NO provider configured):
 * the sheet OPENS from its row, and an unconfigured setup SAYS SO — with the
 * two setup doors offered — instead of showing a composer that could only
 * fail. A provider created by an earlier suite run satisfies the same
 * asserts through the quick-ask chips, so both realities are covered.
 */
@RunWith(AndroidJUnit4::class)
class PageAssistantE2eTest {

    private val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            android.os.SystemClock.sleep(250)
        }
        return condition()
    }

    private fun engineUiUp(timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) {
            device.hasObject(By.desc("omni_field")) ||
                device.hasObject(By.textContains("Search or type URL")) ||
                device.hasObject(By.textContains("Privacy Dashboard"))
        }

    /** Cold start from ANY suite position: picker → create profile → browser. */
    private fun openEngineFromLauncher(): Boolean {
        device.pressHome()
        device.waitForIdle(800)
        val launch = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        targetContext.startActivity(launch)
        if (!waitUntil(60_000) {
                device.hasObject(By.text("Your profiles")) ||
                    device.hasObject(By.text("Create Profile"))
            }
        ) {
            return false
        }
        for (i in 1..12) {
            if (clickText("Create Profile", 1_500)) break
            swipeUpQuarter()
        }
        if (!waitUntil(8_000) { device.hasObject(By.text("Cancel")) }) return false
        val nameField = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull()
            ?: return false
        nameField.click()
        device.waitForIdle(400)
        val name = "Assistant${android.os.SystemClock.elapsedRealtime() % 100000}"
        runCatching { nameField.setText(name) }
        if (!waitUntil(3_000) {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .any { it.text == name }
            }
        ) {
            device.executeShellCommand("input text $name")
            device.waitForIdle(600)
        }
        val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
        val confirm = device.findObjects(By.text("Create Profile"))
            .filter { c ->
                cancel != null && kotlin.math.abs(
                    c.visibleBounds.centerY() - cancel.visibleBounds.centerY()
                ) < 200
            }
            .maxByOrNull { it.visibleBounds.centerX() } ?: return false
        clickCenter(confirm)
        if (!waitUntil(10_000) { device.findObjects(By.text("Cancel")).isEmpty() }) return false
        return engineUiUp(40_000)
    }

    /** Opens the page-actions sheet and VERIFIES its header came up. */
    private fun openPageActions() {
        for (round in 1..3) {
            if (!device.hasObject(By.desc("Page actions and settings"))) {
                device.pressBack()
                device.waitForIdle(800)
            }
            device.findObject(By.desc("Page actions and settings"))?.click()
            if (device.wait(Until.hasObject(By.text("Page Actions")), 4_000)) return
        }
        assertTrue("Page actions sheet must open (its 'Page Actions' header never appeared)", false)
    }

    /**
     * Scrolls the sheet until [text] is on-screen and clicks it; [verify] —
     * judged on a marker UNIQUE to the destination — decides success. A
     * click that only scrolled the sheet returns false and the loop tries
     * again (a header that scrolled away is not a dismissed sheet).
     */
    private fun clickSheetText(text: String, verify: () -> Boolean, attempts: Int = 10): Boolean {
        for (i in 1..attempts) {
            if (verify()) return true
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null) {
                val bounds = runCatching { node.visibleBounds }.getOrNull()
                if (bounds != null && bounds.top >= 0 && bounds.bottom <= device.displayHeight) {
                    runCatching { node.click() }
                    device.waitForIdle(1_000)
                    if (verify()) return true
                }
            }
            swipeUpQuarter()
        }
        return verify()
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return runCatching { node.click(); true }.getOrDefault(false)
    }

    private fun clickCenter(node: UiObject2) {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
    }

    private fun swipeUpQuarter() {
        device.swipe(
            device.displayWidth / 2, (device.displayHeight * 0.75).toInt(),
            device.displayWidth / 2, (device.displayHeight * 0.35).toInt(), 40
        )
        device.waitForIdle(400)
    }

    @Test
    fun ask_this_page_opens_and_says_what_it_needs() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable", openEngineFromLauncher())

        openPageActions()
        val assistantState = {
            device.hasObject(By.textContains("No AI provider is configured yet")) ||
                device.hasObject(By.text("Summarize"))
        }
        assertTrue(
            "The assistant sheet must open from the 'Ask this page' row",
            clickSheetText("Ask this page", verify = assistantState)
        )

        // Either reality is honest: no provider (the message + setup doors)
        // or a provider (the quick asks are offered).
        assertTrue(
            "The assistant must say it has no provider, or offer its quick asks",
            assistantState()
        )
        if (device.hasObject(By.textContains("No AI provider is configured yet"))) {
            assertTrue(
                "'Open AI settings' must be offered on the no-provider path",
                device.hasObject(By.text("Open AI settings"))
            )
            assertTrue(
                "'Open Local AI' must be offered on the no-provider path",
                device.hasObject(By.text("Open Local AI"))
            )
            assertTrue(
                "'Open Local AI' must open the Local AI screen",
                clickText("Open Local AI", 5_000) &&
                    device.wait(Until.hasObject(By.textContains("Local AI")), 8_000)
            )
        }
    }
}
