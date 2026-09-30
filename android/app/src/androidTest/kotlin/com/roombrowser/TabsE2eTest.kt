package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the multi-tab contract (the "tabs" regression):
 *
 *   BrowserActivity (':browser' process), one FRESH profile per run
 *     -> Tab A: the initial homepage tab loads page A via the omnibox
 *     -> "New tab" (page actions sheet) -> Tab B (homepage) -> omnibox loads
 *        page B into B. THE regression this pins: a New Tab must NEVER
 *        overwrite the current tab's page (the old loadUrl("about:home",
 *        newTab = true) early-return bug).
 *     -> tab grid: exactly "Tabs (2)", A's card shows A's title + URL
 *     -> switch to A -> A's page content is intact (own WebView, own history)
 *     -> switch to B -> B's content intact
 *     -> grid "+" creates Tab C -> "Tabs (3)", A and B cards unchanged
 *     -> close B from its card -> "Tabs (2)"
 *     -> A still shows page A; C is the (fresh) start page
 *     -> DB ground truth: the profile's open tabs are exactly A and C
 *
 * Every page carries a per-run marker (title + h1), so the assertions are
 * immune to leftovers from earlier runs on a dirty device. Navigation uses
 * the same UiAutomator patterns proven by BrowserNavigationE2eTest
 * (coordinate/shell taps, verification-driven retries, scroll-aware finds).
 */
@RunWith(AndroidJUnit4::class)
class TabsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    /** Per-run marker suffix — unique tab titles/URLs across runs. */
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2ETabs$tag"
    private val titleA = "TAB-A-$tag"
    private val titleB = "TAB-B-$tag"
    private val contentA = "PAGE-A-$tag"
    private val contentB = "PAGE-B-$tag"
    private lateinit var urlA: String
    private lateinit var urlB: String

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path.startsWith("/a-$tag") -> html(titleA, contentA)
                    path.startsWith("/b-$tag") -> html(titleB, contentB)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        urlA = "$base/a-$tag"
        urlB = "$base/b-$tag"
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun html(title: String, body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            </head><body style="font-size:24px; margin:24px;">
            <h1>$body</h1>
            </body></html>
            """.trimIndent()
        )

    // ---------- UiAutomator helpers (proven patterns) --------------------

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

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    private fun waitGone(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { device.findObjects(By.text(text)).isEmpty() }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP (input tap) — deterministic on the busy CI a11y pipeline
        // (Task 12 lesson; injected gestures can be silently swallowed).
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

    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Scroll-aware click (off-screen grid rows are not in the a11y tree). */
    private fun clickTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware presence check. */
    private fun hasTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun imeShown(): Boolean = try {
        device.executeShellCommand("dumpsys input_method | grep mInputShown")
            .contains("mInputShown=true")
    } catch (_: Exception) {
        false
    }

    private fun hideImeIfNeeded() {
        if (imeShown()) {
            device.pressBack()
            device.waitForIdle(600)
        }
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.descContains("Address bar")), 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.wait(Until.hasObject(By.descContains("Address bar")), 500)
    }

    /** The start page is up exactly when the address pill says so. */
    private fun homepageUp(timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc("Address bar: search or type URL")), timeoutMs) ||
            hasText("Privacy Dashboard", 2_000)

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
        }.getOrDefault(emptyList())
        val descs = runCatching {
            device.findObjects(By.descContains("")).mapNotNull { it.contentDescription }
                .distinct().take(30)
        }.getOrDefault(emptyList())
        "TEXTS: $texts\nDESCS: $descs"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // ---------- Bootstrap: ALWAYS a fresh, single-tab profile ----------------

    /**
     * Creates THIS test's own profile through the real dialog (the create
     * callback opens the engine bound to it), so the tab state starts from
     * exactly one start-page tab — deterministic counts on every run, dirty
     * device or not.
     */
    private fun bootstrapFreshEngine(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        // First run: the empty-state CTA; later runs: the outlined
        // "add another" button (below the cards — scroll-aware).
        assertTrue(
            "Profile list or first-run state must appear",
            hasText("Your profiles", 90_000) || hasText("Create Profile", 90_000)
        )
        assertTrue(
            "Create Profile affordance must be reachable",
            clickTextWithScroll("Create Profile")
        )
        assertTrue("Create-profile dialog should open", hasText("Cancel", 8_000))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
        assertTrue("Name text field must be visible", field != null)
        clickCenter(field!!)
        device.executeShellCommand("input text $profileName")
        device.waitForIdle(1_000)
        // Confirm = the 'Create Profile' button in the dialog row (Cancel is
        // the same row's open-signal — proven proximity pattern).
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
        assertTrue("Create dialog should close after confirm", waitGone("Cancel", 10_000))
        // The create callback opens the engine (a first profile directly; a
        // later one through the process-restart switch path).
        return engineUiUp(40_000)
    }

    // ---------- The tab helpers -------------------------------------------

    /**
     * Loads [url] in the CURRENT tab through the real omnibox (omni_field is
     * the stable a11y hook) and waits for [contentMarker] in the page.
     * Verification-driven with full re-clear-and-retype rounds — the pattern
     * that survives the CI runner's flaky input pipeline.
     */
    private fun loadInOmnibox(url: String, contentMarker: String): Boolean {
        for (round in 1..3) {
            if (hasText(contentMarker, 500)) return true
            hideImeIfNeeded()
            val field = device.wait(Until.findObject(By.desc("omni_field")), 4_000) ?: continue
            clickSmart(field)
            device.waitForIdle(500)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.executeShellCommand("input keyevent KEYCODE_DEL; ".repeat(40).trimEnd())
            device.waitForIdle(300)
            device.executeShellCommand("input text $url")
            device.waitForIdle(600)
            // IME Go action -> onOmniBoxInput -> loadUrl (same tab).
            device.executeShellCommand("input keyevent 66")
            if (hasText(contentMarker, 12_000)) return true
            device.pressEnter()
            if (hasText(contentMarker, 12_000)) return true
        }
        return false
    }

    /** Opens a page-actions sheet entry by desc and VERIFIES the effect. */
    private fun openSheetEntry(label: String, verify: () -> Boolean): Boolean {
        for (round in 1..3) {
            if (!hasDesc("Page actions and settings", 1_500)) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (!clickDesc("Page actions and settings", 6_000)) continue
            for (attempt in 1..12) {
                val node = device.wait(Until.findObject(By.desc(label)), 2_000)
                if (node != null) {
                    clickSmart(node)
                    if (verify()) return true
                    runCatching { clickCenter(node) }
                    if (verify()) return true
                }
                dragUpQuarter()
            }
        }
        return false
    }

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun openTabGrid(): Boolean {
        if (!clickDesc("Open tab grid", 6_000)) return false
        return waitUntil(6_000) { device.findObjects(By.textStartsWith("Tabs (")).isNotEmpty() }
    }

    /** True while the tab grid route is on screen. */
    private fun gridUp(): Boolean =
        device.findObjects(By.textStartsWith("Tabs (")).isNotEmpty()

    private fun tabCountTitle(): String? =
        device.findObjects(By.textStartsWith("Tabs ("))
            .firstOrNull()?.text

    /** A tab card is rendered with BOTH its title and its URL text nodes. */
    private fun cardShows(title: String, url: String): Boolean =
        device.findObjects(By.text(title)).isNotEmpty() &&
            device.findObjects(By.text(url)).isNotEmpty()

    /**
     * Taps the tab card whose title is [title] (walks up to the clickable
     * card) and verifies the grid closed — selecting a card returns to the
     * browsing surface with that tab active.
     */
    private fun selectCardByTitle(title: String): Boolean {
        for (i in 1..12) {
            val node = device.wait(Until.findObject(By.text(title)), 1_500)
            if (node != null) {
                clickSmart(node)
                if (waitUntil(5_000) { !gridUp() }) return true
                runCatching { clickCenter(node) }
                if (waitUntil(5_000) { !gridUp() }) return true
            }
            dragUpQuarter()
        }
        return false
    }

    /**
     * Closes the tab card whose title is [title] via that card's own
     * "Close tab" button. The close button is located geometrically: the
     * card's bounds come from the first CLICKABLE ancestor of the title node
     * (the whole card is the touch target), and the close button is the
     * "Close tab" node whose center lies inside those bounds.
     */
    private fun closeCardByTitle(title: String): Boolean {
        for (i in 1..12) {
            val titleNode = device.wait(Until.findObject(By.text(title)), 1_500)
            if (titleNode == null) {
                dragUpQuarter()
                continue
            }
            val card = clickableAncestorOf(titleNode)
            if (card == null) {
                dragUpQuarter()
                continue
            }
            val cardBounds = card.visibleBounds
            val close = device.findObjects(By.desc("Close tab"))
                .firstOrNull { b ->
                    val cb = b.visibleBounds
                    cardBounds.contains(cb.centerX(), cb.centerY())
                }
            if (close == null) {
                dragUpQuarter()
                continue
            }
            clickSmart(close)
            return true
        }
        return false
    }

    private fun clickableAncestorOf(node: UiObject2): UiObject2? {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try { current.isClickable } catch (_: Exception) { false }
            if (clickable) return current
            current = try { current.parent } catch (_: Exception) { null }
            hops++
        }
        return null
    }

    /** DB ground truth: the active profile's open tabs (test-process graph). */
    private fun openTabsGroundTruth(): List<Pair<String, String>> = runBlocking {
        val appGraph = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph
        val profileId = appGraph.appState.activeProfileIdSnapshot()
            ?: return@runBlocking emptyList()
        appGraph.database.tabDao().openTabs(profileId)
            .map { it.title to it.url }
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun new_tabs_never_overwrite_and_switching_preserves_each_tab() {
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())

        // ---- 1. Tab A = the initial start-page tab, loaded with page A ----
        assertTrue(
            "Page A must load through the omnibox\n${uiTree()}",
            loadInOmnibox(urlA, contentA)
        )

        // ---- 2. New tab B: the start page must NOT clobber tab A ---------
        assertTrue(
            "Page actions 'New tab' must open a fresh start page",
            openSheetEntry("New tab") { homepageUp(8_000) }
        )
        assertTrue(
            "Page B must load into the NEW tab\n${uiTree()}",
            loadInOmnibox(urlB, contentB)
        )

        // ---- 3. Grid: exactly two tabs; A's card kept its page ------------
        assertTrue("Tab grid must open", openTabGrid())
        assertTrue(
            "Grid must show exactly two tabs (found ${tabCountTitle()})",
            waitUntil(6_000) { tabCountTitle() == "Tabs (2)" }
        )
        assertTrue(
            "Tab A's card must still show its page after the new tab",
            hasTextWithScroll(titleA) && cardShows(titleA, urlA)
        )
        assertTrue("Tab B's card must show its page", cardShows(titleB, urlB))

        // ---- 4. Switch to A: content intact (per-tab WebView) -------------
        assertTrue("Tab A's card must be selectable", selectCardByTitle(titleA))
        assertTrue(
            "Switching back to A must show A's page, not B's\n${uiTree()}",
            hasText(contentA, 15_000)
        )

        // ---- 5. Switch to B: intact ---------------------------------------
        assertTrue("Tab grid must re-open", openTabGrid())
        assertTrue("Tab B's card must be selectable", selectCardByTitle(titleB))
        assertTrue(
            "Switching back to B must show B's page\n${uiTree()}",
            hasText(contentB, 15_000)
        )

        // ---- 6. Create C from the grid "+": A and B unchanged -------------
        assertTrue("Grid '+' (New tab) must be clickable", clickDesc("New tab", 6_000))
        assertTrue("Tab C must land on the start page", homepageUp(10_000))
        assertTrue("Tab grid must re-open (for C)", openTabGrid())
        assertTrue(
            "Grid must show exactly three tabs (found ${tabCountTitle()})",
            waitUntil(6_000) { tabCountTitle() == "Tabs (3)" }
        )
        assertTrue(
            "A and B must be unchanged after creating C",
            hasTextWithScroll(titleA) && cardShows(titleA, urlA) && cardShows(titleB, urlB)
        )

        // ---- 7. Close B from its card --------------------------------------
        assertTrue("Tab B's close button must be clickable", closeCardByTitle(titleB))
        assertTrue(
            "Grid must drop to two tabs after closing B (found ${tabCountTitle()})",
            waitUntil(6_000) { tabCountTitle() == "Tabs (2)" }
        )

        // ---- 8. A and C are still correct ----------------------------------
        assertTrue("Tab A's card must still be selectable", selectCardByTitle(titleA))
        assertTrue(
            "Tab A must still show its page after B was closed\n${uiTree()}",
            hasText(contentA, 15_000)
        )
        assertTrue("Tab grid must re-open (for C)", openTabGrid())
        // The fresh start-page tab's card falls back to its URL as the title.
        assertTrue("Tab C's card must be selectable", selectCardByTitle("about:home"))
        assertTrue("Tab C must be the start page", homepageUp(10_000))

        // ---- 9. DB ground truth: exactly A and C remain open ---------------
        val openTabs = openTabsGroundTruth()
        assertTrue(
            "The profile must have exactly A and C open in the DB (found $openTabs)",
            openTabs.size == 2 &&
                openTabs.any { it.first == titleA && it.second == urlA } &&
                openTabs.any { it.second == "about:home" }
        )
    }
}
