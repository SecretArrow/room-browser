package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * E2E for the bottom-bar NAVIGATION contract (the "supaya works" fix):
 *
 *   MainActivity (default process)
 *     -> first-run welcome / profile list -> engine opens
 *   BrowserActivity (':browser' process), cold-started with EXTRA_INITIAL_URL
 *     -> http://127.0.0.1:PORT/page1 (local MockWebServer, cleartext allowed)
 *     -> click the in-page link -> /page2 (real WebView navigation)
 *     -> bottom-bar "Go back" (desc) -> page ONE is visible again
 *     -> bottom-bar "Go forward" (desc) -> page TWO is visible again
 *     -> bottom-bar "Reload page" (desc) -> server hit counter for /page2 rises
 *     -> SYSTEM Back on a page with no back history left:
 *        "Exit Room Browser?" confirmation appears (app does NOT leave)
 *        -> "Cancel" -> still on page TWO, dialog gone
 *        -> Back again -> "Back to start page" -> homepage omnibox visible
 *        -> Back on the HOMEPAGE -> app backgrounds (engine stays alive)
 *
 * History state is live-tracked via doUpdateVisitedHistory; before that fix
 * canGoBack/canGoForward were never reported and the nav buttons stayed
 * grey forever, and system Back backgrounded the app from ANY page.
 */
@RunWith(AndroidJUnit4::class)
class BrowserNavigationE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private val page1Hits = AtomicInteger(0)
    private val page2Hits = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path.startsWith("/page1") -> {
                        page1Hits.incrementAndGet()
                        html(
                            """
                            <h1>ROOM-E2E-PAGE-ONE</h1>
                            <p><a href="/page2">Goto page 2</a></p>
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/page2") -> {
                        page2Hits.incrementAndGet()
                        html(
                            """
                            <h1>ROOM-E2E-PAGE-TWO</h1>
                            <p><a href="/page1">Goto page 1</a></p>
                            """.trimIndent()
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun html(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head><body style="font-size:24px; margin:24px;">
            $body
            </body></html>
            """.trimIndent()
        )

    // ---------- UiAutomator helpers (proven patterns) --------------------

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    private fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isEmpty()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text(text)).isEmpty()
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP (input tap), not gesture injection — the CI runner's busy
        // a11y pipeline silently swallows injected gestures (Task 12 lesson).
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    private fun clickSmart(node: UiObject2): Boolean {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try { current.isClickable } catch (_: Exception) { false }
            if (clickable) {
                try {
                    current.click()
                    device.waitForIdle(1_000)
                    return true
                } catch (_: Exception) {
                }
            }
            current = try { current.parent } catch (_: Exception) { null }
            hops++
        }
        return clickCenter(node)
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /** Chromium/WebView net log captured ON-DEVICE at the moment of failure —
     *  the runner-level logcat dump only fires at the END of the whole gradle
     *  run, long after this test's window has scrolled away. */
    private fun chromiumLog(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.lineSequence()
            .filter {
                it.contains("chromium", true) || it.contains("ERR_", true) ||
                    it.contains("SSL", true) || it.contains("cr_")
            }
            .toList()
            .takeLast(80)
            .joinToString("\n")
    } catch (_: Exception) {
        "(logcat unavailable)"
    }

    /** Readable failure diagnostics (readable from the e2e-reports artifact). */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes = listOf(
            "'ROOM-E2E-PAGE-ONE'" to By.text("ROOM-E2E-PAGE-ONE"),
            "'ROOM-E2E-PAGE-TWO'" to By.text("ROOM-E2E-PAGE-TWO"),
            "'Goto page 2' link" to By.text("Goto page 2"),
            "'Go back' button" to By.desc("Go back"),
            "'Go forward' button" to By.desc("Go forward"),
            "'Reload page' button" to By.desc("Reload page"),
            "'Exit Room Browser?' dialog" to By.text("Exit Room Browser?"),
            "'Back to start page' action" to By.text("Back to start page"),
            "omni_field" to By.desc("omni_field")
        )
        for ((label, selector) in probes) {
            val nodes = runCatching { device.findObjects(selector) }.getOrDefault(emptyList())
            sb.append(label).append(": count=").append(nodes.size)
            nodes.take(2).forEach { n ->
                sb.append(" bounds=").append(runCatching { n.visibleBounds }.getOrNull())
                    .append(" clickable=").append(runCatching { n.isClickable }.getOrDefault(false))
            }
            sb.append('\n')
        }
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
        }.getOrDefault(emptyList())
        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
        sb.append("page1Hits=").append(page1Hits.get())
            .append(" page2Hits=").append(page2Hits.get()).append('\n')
        sb.toString().take(8000)
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.descContains("Address bar")), 400)) return true
            if (device.wait(Until.hasObject(By.text("Search or type URL")), 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.wait(Until.hasObject(By.descContains("Address bar")), 500)
    }

    /** Same bootstrap contract as E2EBrowseFlowTest (create-or-OPEN profile). */
    private fun openEngineFromLauncher(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "First-run welcome (or profile list) should appear",
            hasText("Create Profile", 20_000) || hasText("OPEN", 10_000)
        )
        val alreadyHasProfile = device.findObjects(By.text("OPEN")).isNotEmpty()
        if (!alreadyHasProfile) {
            var dialogOpen = false
            for (attempt in 1..2) {
                assertTrue("Welcome 'Create Profile' button must be visible", clickText("Create Profile", 5_000))
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open (retry failed)", dialogOpen)
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                ?: device.wait(Until.findObject(By.text("Name")), 5_000)
            assertTrue("Name text field must be visible", field != null)
            var typed = false
            for (attempt in 1..2) {
                clickCenter(field!!)
                device.executeShellCommand("input text E2E_Nav")
                device.waitForIdle(1_500)
                if (device.findObjects(By.textContains("E2E_Nav")).isNotEmpty()) {
                    typed = true
                    break
                }
            }
            assertTrue("Profile name must be typed into the field", typed)
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
            assertTrue("Create dialog should close after confirm", waitGone("Cancel", 8_000))
            if (!engineUiUp(30_000)) {
                assertTrue("Profile card with OPEN must appear after creation", hasText("OPEN", 10_000))
                assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))
            }
        } else {
            assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))
        }
        return engineUiUp(30_000)
    }

    /**
     * Cold-starts the engine activity with the mock URL as EXTRA_INITIAL_URL
     * (the persisted active profile is picked up automatically) — the page
     * opens in a NEW tab whose fresh engine has NO back history, exactly the
     * state where system Back used to kick the user out of the app.
     */
    private fun openEngineAt(url: String): Boolean {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
        return hasText("ROOM-E2E-PAGE-ONE", 30_000)
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun bottom_bar_back_forward_reload_and_system_back_confirmation() {
        val base = server.url("/").toString().trimEnd('/')
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())

        // ---- 1. Open page ONE in a fresh tab (no back history) ------------
        assertTrue(
            "Page ONE must load via EXTRA_INITIAL_URL\n${uiTree()}",
            openEngineAt("$base/page1")
        )

        // ---- 2. Real in-page navigation: ONE -> TWO -----------------------
        assertTrue(
            "The 'Goto page 2' link must be clickable\n${uiTree()}",
            clickText("Goto page 2", 15_000)
        )
        if (!hasText("ROOM-E2E-PAGE-TWO", 12_000)) {
            // Defense-in-depth against a swallowed first tap on a busy runner
            // (Task 12 lesson: the a11y pipeline can drop injected input).
            clickText("Goto page 2", 5_000)
        }
        assertTrue(
            "Page TWO must load after the link click\n${uiTree()}\nCHROMIUM LOG:\n${chromiumLog()}",
            hasText("ROOM-E2E-PAGE-TWO", 15_000)
        )

        // ---- 3. Bottom-bar Back: TWO -> ONE (the old always-grey bug) -----
        assertTrue(
            "Bottom-bar 'Go back' must be clickable\n${uiTree()}",
            clickDesc("Go back", 10_000)
        )
        assertTrue(
            "Back must return to page ONE\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-ONE", 20_000)
        )

        // ---- 4. Bottom-bar Forward: ONE -> TWO ----------------------------
        assertTrue(
            "Bottom-bar 'Go forward' must be clickable\n${uiTree()}",
            clickDesc("Go forward", 10_000)
        )
        assertTrue(
            "Forward must return to page TWO\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-TWO", 20_000)
        )

        // ---- 5. Bottom-bar Reload: the server must see a fresh hit --------
        val hitsBefore = page2Hits.get()
        assertTrue(
            "Bottom-bar 'Reload page' must be clickable\n${uiTree()}",
            clickDesc("Reload page", 10_000)
        )
        assertTrue(
            "Reload must re-request /page2 (hits before=$hitsBefore, now=${page2Hits.get()})\n${uiTree()}",
            waitUntil(15_000) { page2Hits.get() > hitsBefore }
        )

        // ---- 6. SYSTEM Back with web history: walks BACK like a browser ---
        // (the BackHandler's canGoBack branch — previously dead code, Back
        // backgrounded the app from ANY page). History is [page1, page2] and
        // we are on page2, so Back must land on page ONE.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "System Back must walk the web history back to page ONE\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-ONE", 20_000)
        )

        // ---- 7. System Back with NO history left: CONFIRM, never exit -----
        // We are on page ONE — the FIRST entry of this tab's history.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "System Back must show the exit confirmation dialog, not leave the app\n${uiTree()}",
            hasText("Exit Room Browser?", 8_000)
        )
        assertTrue("App must still be in the foreground after Back", device.currentPackageName == targetContext.packageName)

        // ---- 8. Cancel: the dialog closes, the page stays -----------------
        assertTrue("'Cancel' must be clickable", clickText("Cancel", 5_000))
        assertTrue("Dialog must close after Cancel", waitGone("Exit Room Browser?", 8_000))
        assertTrue(
            "Page ONE must still be visible after Cancel\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-ONE", 10_000)
        )

        // ---- 9. Back to start page via the confirmation -------------------
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue("Exit confirmation must appear again", hasText("Exit Room Browser?", 8_000))
        assertTrue(
            "'Back to start page' must be clickable\n${uiTree()}",
            clickText("Back to start page", 5_000)
        )
        // Homepage marker — the SAME hedge the older E2EBrowseFlowTest uses
        // (engineUiUp): on the Compose a11y bridge the omnibox placeholder
        // is frequently MERGED into the address-bar row's semantics and not
        // exposed as a standalone text node, so assert by homepage-only
        // texts first (CI dump-proven: Privacy Dashboard / Quick Access /
        // Good morning were all visible while "Search or type URL" wasn't).
        assertTrue(
            "The start page must be visible after 'Back to start page'\n${uiTree()}",
            waitUntil(15_000) {
                device.findObjects(By.text("Privacy Dashboard")).isNotEmpty() ||
                    device.findObjects(By.text("Quick Access")).isNotEmpty() ||
                    device.findObjects(By.textContains("Search or type URL")).isNotEmpty() ||
                    device.findObjects(By.descContains("Address bar: search or type URL")).isNotEmpty()
            }
        )

        // ---- 10. Homepage Back: instant background (existing contract) ----
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Back on the homepage must background the app (engine stays alive)\n${uiTree()}",
            waitUntil(6_000) { device.currentPackageName != targetContext.packageName }
        )
    }
}
