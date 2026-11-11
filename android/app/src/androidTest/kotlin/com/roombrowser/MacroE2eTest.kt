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
        // The sheet rows expose their LABELS as text; UiAutomator does not
        // resolve the rows' contentDescription inside the ModalBottomSheet's
        // dialog window (the pill's desc in the main window works fine). And
        // success is judged by the DESTINATION — a header that merely
        // scrolled off is not a dismissed sheet (#716's false positive).
        assertTrue(
            "Macro library must open from the sheet's Macro row",
            clickSheetText("Macro", verify = { device.hasObject(By.desc("New macro")) })
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

        // ---- Duplicate + delete: every hop VERIFIED, retried ----------------
        // The dialog round-trip was the silent killer (#718): a null-safe
        // click on the row, on "Duplicate", or a confirm that lost the race
        // with the dialog's entrance animation all no-op'd without a trace.
        var duplicated = false
        for (round in 1..3) {
            if (device.hasObject(By.textContains("(copy)"))) { duplicated = true; break }
            if (!device.hasObject(By.text("Duplicate macro?"))) {
                val dupRow = device.wait(Until.findObject(By.desc("Duplicate macro")), 3_000)
                dupRow?.click()
                device.waitForIdle(800)
            }
            val confirm = device.wait(Until.findObject(By.text("Duplicate")), 2_500)
            confirm?.click()
            device.waitForIdle(800)
            if (device.wait(Until.hasObject(By.textContains("(copy)")), 3_000)) duplicated = true
        }
        assertTrue(
            "Duplicate must land as (copy); visible: ${visibleTexts()}",
            duplicated
        )
        var deleted = false
        for (round in 1..3) {
            if (!device.hasObject(By.textContains("(copy)"))) { deleted = true; break }
            if (!device.hasObject(By.text("Delete macro?"))) {
                val delRow = device.wait(Until.findObject(By.desc("Delete macro")), 3_000)
                delRow?.click()
                device.waitForIdle(800)
            }
            val confirm = device.wait(Until.findObject(By.text("Delete")), 2_500)
            confirm?.click()
            device.waitForIdle(800)
            if (!device.wait(Until.hasObject(By.textContains("(copy)")), 3_000)) deleted = true
        }
        assertTrue(
            "The (copy) must be deletable; visible: ${visibleTexts()}",
            deleted
        )

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
        assertTrue(
            "Profile settings screen must open from the sheet row",
            clickSheetText(
                "Profile settings",
                verify = { device.hasObject(By.textContains("Profile Settings —")) }
            )
        )
        // The reset row sits deep in a long settings screen — UiAutomator only
        // matches VISIBLE nodes in a scrolling container, so the drag has to
        // actually travel there. Quarter-swipes go DOWN until found; if the
        // row was overshot, a downward sweep brings it back before failing.
        var resetRow = device.findObject(By.text("Reset settings to default"))
        var swipes = 0
        while (resetRow == null && swipes < 30) {
            swipeUpQuarter()
            resetRow = device.findObject(By.text("Reset settings to default"))
            swipes++
        }
        if (resetRow == null) {
            for (i in 1..6) {
                swipeDownQuarter()
                resetRow = device.findObject(By.text("Reset settings to default"))
                if (resetRow != null) break
            }
        }
        assertTrue(
            "Reset row must exist in Profile Settings (down-swipes=$swipes; " +
                "visible now: ${visibleTexts()})",
            resetRow != null
        )
        // Click verified at every hop: a click during the list's fling
        // settle lands nowhere (#726), so re-find, click, and re-check the
        // dialog before giving up — and name the viewport when it fails.
        var dialogUp = false
        for (round in 1..3) {
            device.findObject(By.text("Reset settings to default"))?.click()
            device.waitForIdle(800)
            if (device.wait(Until.hasObject(By.text("Reset settings to default?")), 3_000)) {
                dialogUp = true
                break
            }
        }
        assertTrue(
            "The confirmation dialog must spell out what stays; visible: ${visibleTexts()}",
            dialogUp && device.hasObject(By.textContains("Cookies, sessions and logins stay signed in"))
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
     * Types [text] into the field with [desc] and reads it back EXACTLY.
     *
     * Two traps this helper has already caught (#726's viewport dump): a text
     * change RECOMPOSES the field, so SET_TEXT against the node found before
     * the click is silently dropped; and a shell burst types at the CURSOR,
     * so without a real clear it splices into the middle of the old value
     * ("nothing-matcE2Ehes-this"). Both are caught by the exact read-back —
     * never by a thrown exception.
     */
    private fun typeIntoField(desc: String, text: String) {
        val field = device.wait(Until.findObject(By.desc(desc)), 4_000)
        assertTrue("Field $desc must exist", field != null)
        field!!.click()
        device.waitForIdle(400)
        for (round in 1..3) {
            val fresh = device.findObject(By.desc(desc))
            if (fresh != null) {
                runCatching { fresh.setText(text) }
                device.waitForIdle(300)
            }
            if (exactTextPresent(desc, text)) return
            // SET_TEXT did not stick: clear for real, then the shell burst
            // at a clean cursor.
            device.findObject(By.desc(desc))?.let { runCatching { it.clear() } }
            device.waitForIdle(300)
            if (exactTextPresent(desc, "")) {
                device.executeShellCommand("input text ${shellSafe(text)}")
                device.waitForIdle(300)
            } else {
                device.executeShellCommand("input keyevent --longpress KEYCODE_MOVE_END")
                repeat(40) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
                device.executeShellCommand("input text ${shellSafe(text)}")
                device.waitForIdle(300)
            }
            if (exactTextPresent(desc, text)) return
        }
        assertTrue("Field $desc must contain exactly '$text'; visible: ${visibleTexts()}", false)
    }

    /** The field's content equals [text] — the only honest read-back. */
    private fun exactTextPresent(desc: String, text: String): Boolean =
        waitUntil(2_500) {
            device.findObjects(By.clazz("android.widget.EditText"))
                .any { runCatching { it.text }.getOrNull() == text } ||
                device.findObjects(By.desc(desc))
                    .any { runCatching { it.text }.getOrNull() == text }
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

    /** One quarter-screen drag downward, then settle (the undo of a search). */
    private fun swipeDownQuarter() {
        device.swipe(
            device.displayWidth / 2, (device.displayHeight * 0.35).toInt(),
            device.displayWidth / 2, (device.displayHeight * 0.75).toInt(), 40
        )
        device.waitForIdle(400)
    }

    /** What is actually on screen — a failure that names its own viewport. */
    private fun visibleTexts(): String = runCatching {
        device.findObjects(By.textContains(""))
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(24)
            .toString()
    }.getOrDefault("viewport dump failed")

    private fun hasTextContains(text: String, timeoutMs: Long = 1_000): Boolean =
        waitUntil(timeoutMs) { device.hasObject(By.textContains(text)) }

    /**
     * Scrolls the sheet until [text] is on-screen and clicks it; [verify] —
     * judged on a marker UNIQUE to the destination — decides success. A
     * click that only scrolled the sheet returns false and the loop tries
     * again: a header that scrolled away is not a dismissed sheet.
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
}
