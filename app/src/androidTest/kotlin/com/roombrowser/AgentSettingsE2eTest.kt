package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the AI Agent settings flow (cross-process, real app UI):
 *
 *   MainActivity (default process)
 *     -> first-run welcome / profile list -> engine opens
 *   BrowserActivity (':browser' process)
 *     -> page menu -> "AI Agent (autonomous browsing)" opens the panel
 *     -> Configure providers -> AgentSettingsActivity (own window)
 *     -> Add provider -> AgentProviderEditorActivity (own window)
 *     -> type name + base URL (local MockWebServer) + API key
 *     -> Fetch models -> chips from the provider's /models response
 *     -> Save -> provider listed in the settings activity
 *     -> back to the browser: the panel shows the selected model
 *     -> "Show AI Agent button" toggle in Browser settings:
 *        default OFF (no floating pill), ON shows the pill, OFF hides it
 *     -> AgentSessionsActivity opens from the page menu
 *
 * Compose fields are driven exactly like E2EBrowseFlowTest: EditText class
 * nodes + `input text` shell command + coordinate clicks.
 */
@RunWith(AndroidJUnit4::class)
class AgentSettingsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Several identical responses so retried fetches also succeed.
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[{"id":"mock-model-a"},{"id":"mock-model-b"}]}""")
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
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

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /** Clicks the first node whose content-description CONTAINS [part]. */
    private fun clickDescContains(part: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.descContains(part)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Clicks via the accessibility ACTION_CLICK (immune to overlays like the
     * IME or sheets covering the node), walking up to the nearest clickable
     * ancestor for Compose text-inside-button nodes; falls back to a
     * coordinate tap. NB: UiObject2.click() returns Unit.
     */
    private fun clickSmart(node: UiObject2): Boolean {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try {
                current.isClickable
            } catch (_: Exception) {
                false
            }
            if (clickable) {
                try {
                    current.click()
                    device.waitForIdle(1_000)
                    return true
                } catch (_: Exception) {
                }
            }
            current = try {
                current.parent
            } catch (_: Exception) {
                null
            }
            hops++
        }
        return clickCenter(node)
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        // The address pill ALWAYS carries the desc "Address bar: …" (its
        // Row semantics), so the engine is detectable regardless of whether
        // the omnibox placeholder text is currently exposed (the expanded
        // agent panel or a non-home tab can hide it). Short poll cadence —
        // the old hard-coded 10s+5s sub-waits made one failing check take
        // ~19s and starved the retry loops.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDescContains("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            if (hasText("trackers blocked", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDescContains("Address bar", 500)
    }

    /** Probes the live accessibility tree for the nodes we care about and
     *  lists every visible text — goes into the failure message (readable
     *  from the e2e-reports artifact). */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes: List<Pair<String, BySelector>> = listOf(
            "pill(AI Agent desc)" to By.desc("AI Agent"),
            "agent_configure desc" to By.desc("agent_configure"),
            "'Configure providers' text" to By.text("Configure providers"),
            "'No AI provider configured'" to By.text("No AI provider configured"),
            "'Room Agent' text" to By.text("Room Agent"),
            "Agent settings gear" to By.desc("Agent settings"),
            "agent_model line" to By.desc("agent_model"),
            "Page actions button" to By.desc("Page actions and settings"),
            "'Page Actions' sheet title" to By.text("Page Actions"),
            "'AI Agent (autonomous browsing)' entry" to By.text("AI Agent (autonomous browsing)"),
            "'Browser settings' entry" to By.text("Browser settings"),
            "'AI Agent chats' entry" to By.text("AI Agent chats"),
            "'Add provider' text" to By.text("Add provider"),
            "'AI Agent Settings' title" to By.text("AI Agent Settings"),
            "'Show AI Agent button' switch" to By.descContains("Show AI Agent button"),
            "agent hint text" to By.textContains("Ask the agent")
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
        // Compose editable fields expose their content as accessibility text
        for (fd in listOf("provider_name_field", "provider_url_field", "provider_key_field")) {
            val v = runCatching {
                device.findObjects(By.desc(fd)).firstOrNull()?.text
            }.getOrNull()
            sb.append(fd).append(" value='").append(v).append("'\n")
        }
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
        }.getOrDefault(emptyList())
        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
        sb.toString().take(9000)
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    /** Types text into the editor field with the given content description. */
    private fun typeIntoField(desc: String, text: String): Boolean {
        hideImeIfNeeded()
        var field = device.wait(Until.findObject(By.desc(desc)), 4_000)
        if (field == null) {
            // scroll the editor content up so lower fields come into view
            dragUpQuarter()
            field = device.wait(Until.findObject(By.desc(desc)), 4_000) ?: return false
        }
        // if the field sits below the fold, scroll it into view before tapping
        runCatching {
            val b = field.visibleBounds
            if (b.bottom > device.displayHeight - 80) dragUpQuarter()
        }
        clickCenter(field)
        // NB: executeShellCommand does not interpret shell quoting — a quoted
        // argument would type the quotes into the field. Values here contain
        // no spaces or shell metacharacters, so pass them bare.
        device.executeShellCommand("input text $text")
        device.waitForIdle(1_000)
        return true
    }

    /** SLOW drag (100 steps ≈ no fling momentum) that scrolls ~1/4 of the
     *  screen — deterministic: a fast fling overshoots past the target row
     * in scrollable sheets (observed in CI: "AI Agent (autonomous
     * browsing)" never became visible after 4 fling attempts). A slow drag
     * also fully expands a half-expanded ModalBottomSheet. */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Off-screen rows of a scrollable container are not exposed to the
     *  accessibility tree — advance the viewport with SMALL deterministic
     *  drags between find attempts (no fling overshoot). */
    private fun clickTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** The IME is a separate accessibility window that can shadow node
     *  lookups — close it before searching for the next field/button. */
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

    /** Scrolls to the switch row, flips it and VERIFIES the state label
     *  actually flipped. CI evidence (run 36292517214): an a11y ACTION_CLICK
     *  can silently no-op, so every click is followed by a state check and a
     *  coordinate-tap fallback on the same row. */
    private fun flipSwitch(title: String, wantOn: Boolean): Boolean {
        val want = "$title switch, ${if (wantOn) "on" else "off"}"
        val from = "$title switch, ${if (wantOn) "off" else "on"}"
        for (i in 1..12) {
            // Already in the wanted state (e.g. dirty-device rerun)?
            if (hasDesc(want, 500)) return true
            val node = device.wait(Until.findObject(By.desc(from)), 1_500)
            if (node != null) {
                clickSmart(node)
                if (hasDesc(want, 4_000)) return true
                runCatching { clickCenter(node) }
                if (hasDesc(want, 4_000)) return true
            }
            // The row sits at the bottom of the settings column — scroll.
            dragUpQuarter()
        }
        return hasDesc(want, 2_000)
    }

    /** Opens a page-actions sheet entry by its content description and
     *  VERIFIES the effect. Defence in depth (CI evidence: an accessibility
     *  ACTION_CLICK on a text-matched sheet row once silently no-opped):
     *  - desc-click on the clickable row (the proven gear-button pattern)
     *  - coordinate-tap fallback on the same node
     *  - small drags between attempts (scrollable sheet, no fling overshoot)
     *  - sheet-dismissal recovery between rounds (Back closes a stuck sheet)
     */
    private fun openSheetEntry(label: String, verify: () -> Boolean): Boolean {
        for (round in 1..3) {
            // If the toolbar gear is covered, a leftover sheet is open —
            // close it first (Back dismisses ModalBottomSheet).
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
                    // The a11y click can silently no-op — tap the row itself.
                    runCatching { clickCenter(node) }
                    if (verify()) return true
                }
                dragUpQuarter()
            }
        }
        return false
    }

    /** Opens the floating agent panel — direct pill tap when visible,
     *  otherwise the always-available page-menu entry. */
    private fun openAgentPanelFromMenu(): Boolean {
        if (hasDesc("AI Agent", 1_000)) {
            for (attempt in 1..2) {
                clickDesc("AI Agent", 3_000)
                if (panelUp(4_000)) return true
            }
        }
        return openSheetEntry("AI Agent (autonomous browsing)") { panelUp(6_000) }
    }

    private fun panelUp(timeout: Long): Boolean =
        hasText("Configure providers", 2_000)
            || hasText("No provider configured", 1_000)
            || hasText("Room Agent", 1_000)
            || hasDesc("Agent settings", 1_000)
            || hasDesc("agent_model", 1_000)

    @Test
    fun add_provider_fetch_models_and_save() {
        // ---- 1. Cold start → engine (profile auto-open or OPEN tap) --------
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)

        if (device.findObjects(By.text("OPEN")).isNotEmpty()) {
            assertTrue("OPEN must be clickable", clickText("OPEN", 8_000))
        } else {
            assertTrue(
                "First-run welcome should appear",
                hasText("Create Profile", 20_000)
            )
            var dialogOpen = false
            for (attempt in 1..2) {
                assertTrue("Create Profile button must be visible", clickText("Create Profile", 5_000))
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open", dialogOpen)
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            assertTrue("Name field must be visible", field != null)
            clickCenter(field!!)
            device.executeShellCommand("input text E2E_Agent")
            val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
            val confirm = device.findObjects(By.text("Create Profile"))
                .filter { c -> cancel != null && kotlin.math.abs(c.visibleBounds.centerY() - cancel!!.visibleBounds.centerY()) < 200 }
                .maxByOrNull { it.visibleBounds.centerX() }
            assertTrue("Confirm button must be found", confirm != null)
            clickCenter(confirm!!)
            if (!engineUiUp(30_000)) {
                assertTrue("OPEN must appear after creation", hasText("OPEN", 10_000))
                assertTrue("OPEN must be clickable", clickText("OPEN", 5_000))
            }
        }
        assertTrue("Browser engine must be up", engineUiUp(30_000))

        // ---- 2. The floating pill is HIDDEN by default ---------------------
        // (Show-AI-Agent-button is off out of the box; the panel is reached
        // from the page menu instead.)
        assertTrue(
            "Floating agent pill must be hidden by default",
            !hasDesc("AI Agent", 3_000)
        )
        if (!openAgentPanelFromMenu()) {
            throw AssertionError("Agent panel must open from the page menu; UI:\n" + uiTree())
        }

        // Reach provider settings: empty-state button, or the header gear
        // (when a default provider already exists the empty state is skipped).
        if (!clickDesc("agent_configure", 6_000)) {
            assertTrue(
                "Agent settings (gear) must be clickable",
                clickDesc("Agent settings", 6_000)
            )
        }

        // ---- 3. AgentSettingsActivity (own window) -------------------------
        assertTrue(
            "AgentSettingsActivity must open with its title",
            hasText("AI Agent Settings", 15_000)
        )

        // ---- 4. AgentProviderEditorActivity (own window) -------------------
        assertTrue("Add provider button must appear", hasText("Add provider", 10_000))
        assertTrue("Add provider must be clickable", clickText("Add provider", 8_000))
        assertTrue("Editor must open", hasText("Presets", 10_000))

        // Fill the manual fields via their semantics descriptions
        // (name → URL → API key), then fetch the model list.
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        assertTrue("name field must be typeable", typeIntoField("provider_name_field", "MockLLM"))
        assertTrue("base URL field must be typeable", typeIntoField("provider_url_field", baseUrl))
        assertTrue("API key field must be typeable", typeIntoField("provider_key_field", "test-key-123"))

        // ---- 5. Fetch models from the MockWebServer ------------------------
        hideImeIfNeeded()
        var chipsShown = false
        for (attempt in 1..2) {
            assertTrue("Fetch models button must be clickable", clickText("Fetch models", 8_000))
            if (hasText("mock-model-a", 15_000)) {
                chipsShown = true
                break
            }
            device.waitForIdle(2_000)
        }
        if (!chipsShown) {
            throw AssertionError("Model chips from /models must appear; UI:\n" + uiTree())
        }
        if (!clickText("mock-model-a", 8_000)) {
            throw AssertionError("mock-model-a chip must be selectable; UI:\n" + uiTree())
        }

        // ---- 6. Save -> back in the settings activity ----------------------
        hideImeIfNeeded()
        if (!clickTextWithScroll("Save provider")) {
            throw AssertionError("Save provider must be clickable; UI:\n" + uiTree())
        }
        assertTrue(
            "Settings screen must list the saved provider",
            hasText("MockLLM", 15_000)
        )

        // ---- 7. Back to the browser: the panel shows the model -------------
        // RACE GUARD: right after saving, the EDITER window can still be
        // finishing — the first node matching desc "Close" may belong to the
        // dying editor (its click is a silent no-op). Click, VERIFY the
        // engine is back, and fall back to system Back (deterministic
        // finish()) until the browser surface is truly visible again.
        assertTrue(
            "Settings must close (Close button or system Back)",
            closeUntilEngineBack()
        )
        assertTrue("Engine UI must be back", engineUiUp(15_000))
        // The panel may STILL be expanded from step 2 (rememberSaveable) —
        // in that case there is no pill to click and none is needed.
        var reopened = hasDesc("agent_model", 2_000) || hasText("Room Agent", 2_000)
        if (!reopened) {
            for (attempt in 1..3) {
                if (openAgentPanelFromMenu()) {
                    reopened = true
                    break
                }
            }
        }
        if (!reopened) {
            throw AssertionError("Agent panel must be reachable; UI:\n" + uiTree())
        }
        if (!device.wait(Until.hasObject(By.textContains("mock-model-a")), 15_000)) {
            throw AssertionError(
                "Agent panel model line must show the fetched model; UI:\n" + uiTree()
            )
        }
        // ---- 8. Show/hide the floating agent button ------------------------
        // Collapse the panel first (system Back collapses it — see the
        // BackHandler priority chain) so the pill area is observable.
        device.pressBack()
        device.waitForIdle(1_000)

        // Turn the toggle ON via Browser settings. The sheet scrolls (the
        // "Browser settings" row sits low) and the switch row itself sits
        // at the BOTTOM of the settings screen (AI Agent section) — both
        // need scroll-aware clicking.
        assertTrue(
            "Browser settings must open",
            openSheetEntry("Browser settings") { hasText("Browser Settings", 6_000) }
        )
        assertTrue(
            "Show AI Agent button switch must flip ON",
            flipSwitch("Show AI Agent button", wantOn = true)
        )
        // Leave settings via system Back — after scrolling, the top-bar Close
        // button has scrolled out of the viewport.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Pill must appear once the toggle is ON",
            hasDesc("AI Agent", 15_000)
        )

        // Turn the toggle OFF again — the pill disappears.
        assertTrue(
            "Browser settings must open (2nd)",
            openSheetEntry("Browser settings") { hasText("Browser Settings", 6_000) }
        )
        assertTrue(
            "Show AI Agent button switch must flip OFF",
            flipSwitch("Show AI Agent button", wantOn = false)
        )
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Pill must be gone after turning the toggle OFF",
            !hasDesc("AI Agent", 4_000)
        )

        // ---- 9. AgentSessionsActivity opens from the page menu -------------
        assertTrue(
            "AI Agent chats must open",
            openSheetEntry("AI Agent chats") {
                hasText("Agent chats", 15_000) || hasText("No agent chats yet", 5_000)
            }
        )
        assertTrue("Sessions must close (Close button or system Back)", closeUntilEngineBack())
        assertTrue("Engine UI must be back (2nd)", engineUiUp(15_000))
    }

    /** Clicks the current activity's Close button, then falls back to the
     *  system Back button until the BROWSER surface is visible again —
     *  immune to the dying-editor-window Close race (CI evidence run
     *  36306609106: the first desc-Close belonged to the finishing editor). */
    private fun closeUntilEngineBack(): Boolean {
        for (attempt in 1..4) {
            runCatching { clickDesc("Close", 2_000) }
            if (engineUiUp(4_000)) return true
            device.pressBack()
            device.waitForIdle(1_000)
            if (engineUiUp(4_000)) return true
        }
        return engineUiUp(4_000)
    }
}
