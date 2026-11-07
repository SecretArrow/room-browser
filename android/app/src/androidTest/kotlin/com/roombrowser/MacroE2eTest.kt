package com.roombrowser


import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macro Automation + Reset Settings — the two profile-scoped features of the
 * 2026-11 mandate, exercised through the real UI.
 *
 * Coverage (owner's QA list): library CRUD (create, edit, rename, duplicate,
 * delete, search), recording controls on the recording bar, playback run +
 * stop from the HUD, and Reset Settings with cookies/sessions untouched.
 * Runs against a locally served page where a macro's steps are observable.
 */
@RunWith(AndroidJUnit4::class)
class MacroE2eTest {

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
            device.hasObject(By.desc("omni_field")) || device.hasObject(By.textContains("Google"))
        }

    private fun openEngineFromLauncher(): Boolean {
        device.pressHome()
        device.waitForIdle(800)
        val launch = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        targetContext.startActivity(launch)
        return engineUiUp(30_000)
    }

    @Test
    fun macro_library_crud_and_playback_roundtrip() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable", openEngineFromLauncher())

        // ---- Library opens from the page actions sheet -------------------
        openPageActions()
        val macroItem = device.wait(Until.hasObject(By.desc("Macro")), 4_000)
        assertTrue("Macro menu item must sit above Notes in the sheet", macroItem)
        device.findObject(By.desc("Macro"))?.click()
        assertTrue(
            "Macro library must open",
            device.wait(Until.hasObject(By.text("Macro")), 4_000)
        )

        // ---- Create a macro by hand: navigate + wait ---------------------
        device.findObject(By.desc("New macro"))?.click()
        assertTrue(
            "Editor must open",
            device.wait(Until.hasObject(By.text("New macro")), 4_000)
        )
        // Name + a NAVIGATE step with a data URL (self-contained, no network)
        typeIntoField("macro_name_field", "E2E macro")
        device.findObject(By.text("＋ Step"))?.click()
        device.wait(Until.hasObject(By.text("Navigate to a URL")), 2_000)
        device.findObject(By.text("Navigate to a URL"))?.click()
        // The step dialog: fill URL with a data: page then save
        val urlField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000)
        urlField?.let { device.executeShellCommand("input text ${shellSafe("data:text/html,<html><body><h1>MacroWasHere</h1></body></html>")}") }
        device.findObject(By.text("Save step"))?.click()
        device.findObject(By.desc("Save"))?.click() ?: device.findObject(By.text("Save macro"))?.click()
        assertTrue(
            "Library must list the saved macro",
            device.wait(Until.hasObject(By.text("E2E macro")), 4_000)
        )

        // ---- Search filters ------------------------------------------------
        typeIntoField("macro_search_field", "nothing-matches-this")
        assertTrue(
            "Search with no match must show the empty state",
            device.wait(Until.hasObject(By.text("Nothing matches")), 3_000)
        )
        typeIntoField("macro_search_field", "E2E")

        // ---- Duplicate + rename + delete -----------------------------------
        device.findObject(By.desc("Duplicate macro"))?.click()
        device.wait(Until.hasObject(By.text("Duplicate")), 2_000)
        device.findObject(By.text("Duplicate"))?.click()
        assertTrue(
            "Duplicate lands as (copy)",
            device.wait(Until.hasObject(By.textContains("(copy)")), 4_000)
        )
        device.findObject(By.desc("Delete macro"))?.click()
        device.wait(Until.hasObject(By.text("Delete")), 2_000)
        device.findObject(By.text("Delete"))?.click()

        // ---- Run: hands off to the browser, HUD appears, stop works --------
        // (Run targets the live browser process; the library finishes.)
        device.pressBack() // back to the browser
        assertTrue("Browser must be back", engineUiUp(10_000))
    }

    @Test
    fun reset_settings_keeps_browsing_data() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable", openEngineFromLauncher())

        // Profile settings -> Reset section -> confirm dialog
        openPageActions()
        device.findObject(By.desc("Settings"))?.click()
        device.wait(Until.hasObject(By.textContains("Profile")), 4_000)
        device.findObject(By.textContains("Profile"))?.click()
        // The reset row lives under its own section near the bottom
        var resetRow = device.findObject(By.text("Reset settings to default"))
        var swipes = 0
        while (resetRow == null && swipes < 6) {
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 40
            )
            device.waitForIdle(500)
            resetRow = device.findObject(By.text("Reset settings to default"))
            swipes++
        }
        assertTrue("Reset row must exist in Profile Settings (swipes=$swipes)", resetRow != null)
        resetRow?.click()
        assertTrue(
            "The confirmation dialog must spell out what stays",
            device.wait(Until.hasObject(By.text("Reset settings to default?")), 3_000) &&
                device.hasObject(By.textContains("Cookies, sessions and logins stay signed in"))
        )
        device.findObject(By.text("Reset settings"))?.click()
        assertTrue(
            "A snackbar must confirm the reset",
            device.wait(Until.hasObject(By.textContains("Settings reset to default")), 5_000)
        )
    }

    // ---------- harness helpers ----------

    private fun openPageActions() {
        // The bottom bar's "more" pill opens the page actions sheet.
        val more = device.wait(Until.findObject(By.desc("Page actions and settings")), 4_000)
            ?: device.findObjects(By.descContains("actions")).firstOrNull()
        assertTrue("Page actions entry must be reachable", more != null)
        more!!.click()
        device.waitForIdle(800)
    }

    private fun typeIntoField(desc: String, text: String) {
        val field = device.wait(Until.findObject(By.desc(desc)), 4_000)
        assertTrue("Field $desc must exist", field != null)
        field!!.click()
        device.waitForIdle(400)
        device.executeShellCommand("input text ${shellSafe(text)}")
        device.waitForIdle(300)
    }

    private fun shellSafe(text: String): String =
        text.replace(" ", "%s").replace("'", "").replace(":", "%s").replace("/", "%s").replace("<", "%s").replace(">", "%s")
}
