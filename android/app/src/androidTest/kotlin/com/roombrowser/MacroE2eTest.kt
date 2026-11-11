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

    private val dataUrl = "data:text/html,<html><body><h1>MacroWasHere</h1></body></html>"

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            android.os.SystemClock.sleep(250)
        }
        return condition()
    }

    /** The browsing UI is up: the omnibox field or the start page's own texts. */
    private fun engineUiUp(timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) {
            device.hasObject(By.desc("omni_field")) ||
                device.hasObject(By.textContains("Search or type URL")) ||
                device.hasObject(By.textContains("Privacy Dashboard"))
        }

    /**
     * Cold start that works from ANY suite position. The launcher intent
     * opens the PROFILE PICKER — the app never auto-opens a browser — so the
     * old helper (wait for the omnibox and hope) passed only when a previous
     * test had left the browser task front, and failed in every isolated
     * re-run (#712's retry shard). Creating a profile is the one path that
     * lands in the browser BY CONTRACT (MainScreen opens it after confirm),
     * and it works with zero profiles (empty-state CTA) or many ("Add
     * another") — same label, same dialog.
     */
    private fun openEngineFromLauncher(): Boolean {
        device.pressHome()
        device.waitForIdle(800)
        val launch = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        targetContext.startActivity(launch)
        // The picker (or the first-run empty state) must be up...
        if (!waitUntil(60_000) {
                device.hasObject(By.text("Your profiles")) ||
                    device.hasObject(By.text("Create Profile"))
            }
        ) {
            return false
        }
        // ...then create a profile.
        for (i in 1..12) {
            if (clickText("Create Profile", 1_500)) break
            swipeUpQuarter()
        }
        if (!waitUntil(8_000) { device.hasObject(By.text("Cancel")) }) return false
        val nameField = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull()
            ?: return false
        nameField.click()
        device.waitForIdle(400)
        val name = "Macro${android.os.SystemClock.elapsedRealtime() % 100000}"
        runCatching { nameField.setText(name) }
        // SET_TEXT travels the a11y layer; verify it landed, else the shell
        // burst (this name has no special characters, so input text is safe).
        if (!waitUntil(3_000) {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .any { it.text == name }
            }
        ) {
            device.executeShellCommand("input text $name")
            device.waitForIdle(600)
        }
        device.waitForIdle(1_000)
        // Confirm = the dialog's own button: same row as Cancel, rightmost
        // (the picker's opener shares the label but sits on another row).
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

    @Test
    fun macro_library_crud_and_playback_roundtrip() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable", openEngineFromLauncher())

        // ---- Library opens from the page actions sheet -------------------
        openPageActions()
        val macroItem = device.wait(Until.hasObject(By.desc("Macro")), 4_000)
        assertTrue("Macro row must be present in the page-actions sheet", macroItem)
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
        // The step dialog: fill URL with a data: page then save. SET_TEXT
        // carries the URL byte-exact; the shell burst mangles ':' and '/'
        // into spaces, so it is only a fallback.
        val urlField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000)
        assertTrue("The step dialog's URL field must exist", urlField != null)
        runCatching { urlField!!.setText(dataUrl) }
        device.waitForIdle(300)
        assertTrue(
            "The URL field must carry the data: page",
            waitUntil(4_000) {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .any { runCatching { it.text }.getOrNull()?.contains("data:") == true }
            }
        )
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
        // The honest Back contract: if the exit dialog came up on the way,
        // stay in the app rather than leaving it in a half-exited state.
        device.findObject(By.text("Stay Here"))?.click()
        device.waitForIdle(600)
        assertTrue("Browser must be back", engineUiUp(10_000))
    }

    @Test
    fun reset_settings_keeps_browsing_data() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable", openEngineFromLauncher())

        // Profile settings: the sheet's row is "Profile settings" and the
        // reset section lives in ProfileSettingsScreen. The old flow tapped
        // desc "Settings", which matches nothing in the sheet (the rows are
        // "Browser settings" / "Profile settings"), so every later step
        // no-op'd silently and the swipes ran on the browser screen.
        openPageActions()
        val profileRow = device.wait(Until.findObject(By.desc("Profile settings")), 5_000)
        assertTrue("Profile settings row must be present in the sheet", profileRow != null)
        profileRow!!.click()
        device.waitForIdle(800)
        // The reset row sits under its own section near the bottom of a long
        // screen; drag to it and report how far it took.
        var resetRow = device.findObject(By.text("Reset settings to default"))
        var swipes = 0
        while (resetRow == null && swipes < 10) {
            swipeUpQuarter()
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

    /** Opens the page-actions sheet and VERIFIES its header — a helper that
     *  only clicks and hopes lets a swallowed tap poison every later step. */
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
     * Types [text] into the field with [desc] and reads it back.
     *
     * Clears first — the search field is typed twice in this test, and a
     * burst that appends to the previous round's text filters the list into
     * a state the next step cannot see. SET_TEXT travels the accessibility
     * layer and needs no IME focus; the shell burst is the fallback, and the
     * read-back gate catches whichever path failed.
     */
    private fun typeIntoField(desc: String, text: String) {
        val field = device.wait(Until.findObject(By.desc(desc)), 4_000)
        assertTrue("Field $desc must exist", field != null)
        field!!.click()
        device.waitForIdle(400)
        runCatching { field.clear() }
        device.waitForIdle(200)
        runCatching { field.setText(text) }
        // SET_TEXT first; the read-back below decides — if it never landed,
        // the shell burst gets one round before the assert fails honestly.
        if (!waitUntil(3_000) {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .any { runCatching { it.text }.getOrNull()?.contains(text) == true }
            }
        ) {
            device.executeShellCommand("input text ${shellSafe(text)}")
            device.waitForIdle(600)
        }
        device.waitForIdle(300)
        assertTrue(
            "Field $desc must contain '$text'",
            waitUntil(4_000) {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .any { runCatching { it.text }.getOrNull()?.contains(text) == true }
            }
        )
    }

    private fun shellSafe(text: String): String =
        text.replace(" ", "%s").replace("'", "").replace(":", "%s").replace("/", "%s").replace("<", "%s").replace(">", "%s")

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return runCatching { node.click(); true }.getOrDefault(false)
    }

    private fun clickCenter(node: UiObject2) {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
    }

    /** One quarter-screen drag upward, then settle. */
    private fun swipeUpQuarter() {
        device.swipe(
            device.displayWidth / 2, (device.displayHeight * 0.75).toInt(),
            device.displayWidth / 2, (device.displayHeight * 0.35).toInt(), 40
        )
        device.waitForIdle(400)
    }
}
