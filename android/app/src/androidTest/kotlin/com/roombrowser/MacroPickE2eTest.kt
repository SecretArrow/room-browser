package com.roombrowser

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.roombrowser.domain.macro.MacroPick
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Tap-to-pick ("Pick on page") — the feature that lets a hand-authored macro
 * step capture its position by TAPPING the page instead of typing numbers.
 *
 * The full round trip, driven through the real UI:
 *
 *   BrowserActivity cold-started on a locally served page with a big
 *   positioned #target div
 *     -> a pick intent (EXTRA_MACRO_PICK + one-shot token) lands via
 *        onNewIntent (the singleTask engine is reused — the exact path the
 *        macro editor in the default process takes)
 *     -> the pick scrim banner ("Pick on page") becomes visible
 *     -> a real touch lands at a screen position inside the div
 *     -> the view model scales it to CSS-viewport pixels, the page probe
 *        names what sits there, and the answer is written as a one-shot
 *        file keyed by the token
 *     -> the test reads that file back, decodes it and asserts the element
 *        truth (kind ID, #target, div) and sane coordinates
 *
 * Three tests: a POINT pick, the two-phase DRAG (START then END, each a
 * separate pick request), and Cancel — which must resolve nothing.
 *
 * The pick answer file is the cross-process contract (see MacroPick): the
 * engine (:browser) writes it, the editor (default process) consumes it on
 * resume. The instrumentation reads the same files dir the engine wrote.
 */
@RunWith(AndroidJUnit4::class)
class MacroPickE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return if (path.startsWith("/pick")) pickPage() else MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The pick target: a huge positioned div so mid-screen taps always land in it. */
    private fun pickPage(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head><body style="font-size:24px; margin:0;">
            <h1>ROOM-E2E-PICK</h1>
            <div id="target"
                 style="position:absolute; left:5%; top:15%; width:90%; height:75%;
                        background:#88f; font-size:28px;">PICK-TARGET-ZONE</div>
            </body></html>
            """.trimIndent()
        )

    // ---------- harness helpers (proven patterns) ---------------------------

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            android.os.SystemClock.sleep(250)
        }
        return condition()
    }

    /** Cold-starts the engine on the pick page (the persisted profile is picked up). */
    private fun openEngineOnPickPage(): Boolean {
        val url = server.url("/pick").toString()
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
        val loaded = waitUntil(30_000) { device.hasObject(By.text("PICK-TARGET-ZONE")) }
        if (loaded) device.waitForIdle(800)
        return loaded
    }

    /** The editor's handoff: a second launch of the RUNNING singleTask engine. */
    private fun requestPick(kind: String, token: String) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("com.roombrowser.extra.MACRO_PICK", kind)
                .putExtra("com.roombrowser.extra.PICK_TOKEN", token)
        )
    }

    private fun bannerUp(timeoutMs: Long = 8_000): Boolean =
        device.wait(Until.hasObject(By.text("Pick on page")), timeoutMs)

    private fun bannerGone(timeoutMs: Long = 8_000): Boolean =
        device.wait(Until.gone(By.text("Pick on page")), timeoutMs)

    private fun answerFile(token: String): File =
        File(targetContext.filesDir, "macro_pick_$token.json")

    private fun awaitAnswer(token: String, timeoutMs: Long = 12_000): MacroPick? {
        waitUntil(timeoutMs) { answerFile(token).exists() }
        val raw = runCatching { answerFile(token).readText() }.getOrNull() ?: return null
        answerFile(token).delete()
        return MacroPick.decode(raw)
    }

    // ---------- the contract -------------------------------------------------

    @Test
    fun point_pick_captures_coordinates_and_element_truth() {
        assertTrue("Pick page must be loaded", openEngineOnPickPage())

        val token = UUID.randomUUID().toString().replace("-", "").take(20)
        requestPick(MacroPick.KIND_POINT, token)
        assertTrue("The pick banner must appear\n", bannerUp())

        // A real touch mid-screen — inside #target, below the banner.
        device.click(device.displayWidth / 2, device.displayHeight / 2)

        val pick = awaitAnswer(token)
        assertTrue("The pick answer file must be written and decodable", pick != null)
        pick!!
        assertTrue("Answer must echo the request token", pick.token == token)
        assertTrue("Answer kind must be POINT", pick.kind == MacroPick.KIND_POINT)
        assertTrue(
            "The probe must name the element under the finger: pick=$pick",
            pick.selector == "#target" && pick.selectorKind == "ID" && pick.tagName == "div"
        )
        assertTrue(
            "Coordinates must be sane CSS-viewport points (${pick.x}, ${pick.y})",
            pick.x >= 0f && pick.y >= 0f &&
                pick.x < device.displayWidth * 2f && pick.y < device.displayHeight * 2f
        )
        assertTrue("The banner must be gone after a resolved pick", bannerGone())
    }

    @Test
    fun drag_pick_captures_two_endpoints_in_order() {
        assertTrue("Pick page must be loaded", openEngineOnPickPage())

        val startToken = UUID.randomUUID().toString().replace("-", "").take(20)
        requestPick(MacroPick.KIND_DRAG_START, startToken)
        assertTrue("The pick banner must appear for the START phase", bannerUp())
        device.click(device.displayWidth / 4, device.displayHeight * 9 / 20)
        val start = awaitAnswer(startToken)
        assertTrue("START answer must be written and decodable", start != null)
        start!!
        assertTrue("START kind", start.kind == MacroPick.KIND_DRAG_START)
        assertTrue(
            "START must land on the target zone (pick=$start)",
            start.selector == "#target"
        )
        assertTrue("The banner must be gone between the two phases", bannerGone())

        val endToken = UUID.randomUUID().toString().replace("-", "").take(20)
        requestPick(MacroPick.KIND_DRAG_END, endToken)
        assertTrue("The pick banner must appear for the END phase", bannerUp())
        device.click(device.displayWidth * 3 / 4, device.displayHeight * 14 / 20)
        val end = awaitAnswer(endToken)
        assertTrue("END answer must be written and decodable", end != null)
        end!!
        assertTrue("END kind", end.kind == MacroPick.KIND_DRAG_END)
        assertTrue(
            "END must land on the target zone (pick=$end)",
            end.selector == "#target"
        )
        assertTrue(
            "The END point must sit below the START point (${start.y} -> ${end.y})",
            end.y > start.y
        )
    }

    @Test
    fun cancel_pick_resolves_nothing() {
        assertTrue("Pick page must be loaded", openEngineOnPickPage())

        val token = UUID.randomUUID().toString().replace("-", "").take(20)
        requestPick(MacroPick.KIND_POINT, token)
        assertTrue("The pick banner must appear", bannerUp())

        val cancel = device.wait(Until.findObject(By.text("Cancel")), 4_000)
        assertTrue("The pick banner must offer Cancel", cancel != null)
        cancel!!.click()
        assertTrue("The banner must disappear on cancel", bannerGone())

        val wrote = waitUntil(4_000) { answerFile(token).exists() }
        assertTrue("A canceled pick must write no answer file", !wrote)
    }
}
