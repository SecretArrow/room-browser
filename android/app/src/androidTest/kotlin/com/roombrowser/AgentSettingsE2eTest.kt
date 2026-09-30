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
 * E2E for the AI Agent settings flow (cross-process, real app UI):
 *
 *   MainActivity (default process)
 *     -> first-run welcome / profile list -> engine opens
 *   BrowserActivity (':browser' process)
 *     -> page menu -> "AI Agents" opens the panel
 *     -> Configure providers -> AgentSettingsActivity (own window)
 *     -> Add provider -> AgentProviderEditorActivity (own window)
 *     -> type name + base URL (local MockWebServer) + API key
 *     -> Fetch models -> chips from the provider's /models response
 *     -> Save -> provider listed in the settings activity
 *     -> back to the browser: the panel shows the selected model
 *     -> chat round-trip: send a prompt -> user bubble + copy icon,
 *        mock SSE reply -> answer bubble + copy icon, tap copy -> "Copied"
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
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        // Path-routed dispatcher (NOT a strict response queue): the
        // verification-driven flow may click Fetch several times and the
        // chat step below POSTs /chat/completions — every request gets a
        // correct answer regardless of order or count.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/models") ->
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"object":"list","data":[{"id":"mock-model-a"},{"id":"mock-model-b"}]}""")
                    // OpenAI-style SSE stream: one content delta then DONE —
                    // a plain final answer with no tool calls, so the agent
                    // turn ends after this single response.
                    path.contains("/chat/completions") ->
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"mock-reply-ok\"}}]}\n\n" +
                                    "data: [DONE]\n\n"
                            )
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

    /** Polls until no node shows [text] anymore (dialog/editor closed). */
    private fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isEmpty()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text(text)).isEmpty()
    }

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
        // SHELL TAP, not device.click() gesture injection: the CI runner's
        // busy a11y pipeline silently swallows injected gestures (Task 12
        // lesson, commit 2354d81 — "Gestures took longer than expected");
        // `input tap` is deterministic and focuses Compose fields reliably.
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
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
            "'AI Agents' entry" to By.text("AI Agents"),
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

    /** Types text into the editor field with the given content description —
     *  VERIFICATION-DRIVEN, and EVERY round clears the field first: retyping
     *  into a non-empty field inserts at the tap-positioned cursor and
     *  corrupts the content (CI-observed). The clear (cursor to end + one
     *  compound shell line of 40 semicolon-chained DEL keyevents — 40
     *  separate commands cost ~20s) also makes retries safe. Verification is
     *  a GLOBAL text search: Compose renders field content on an inner text
     *  node (the desc node itself reports null), findable via By.textContains
     *  exactly like the key field's bullets. */
    private fun typeIntoField(desc: String, text: String, masked: Boolean = false): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            // Scroll-aware lookup: the editor's manual fields sit BELOW the
            // preset list, and that list GROWS when presets are added (CI
            // regression: one extra preset row pushed the name field past the
            // old single-drag fallback). Poll + drag until the field is on
            // screen — a fixed drag count silently breaks on layout growth.
            // (Task 13 regression: the 4th protocol chip + LOCAL caption add
            // another wrapped FlowRow row + a hint paragraph above the fields.)
            var field: UiObject2? = null
            for (i in 1..10) {
                field = device.wait(Until.findObject(By.desc(desc)), 1_500)
                if (field != null) break
                dragUpQuarter()
            }
            if (field == null) continue
            // FRESH resolve immediately before the tap: a handle captured
            // earlier can carry stale bounds — tapping it hits the void.
            val fresh = device.wait(Until.findObject(By.desc(desc)), 2_000) ?: field
            clickCenter(fresh)
            // ALWAYS clear: the field may hold text from a failed earlier
            // round (or this may be a retype after a fetch error).
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.executeShellCommand("input keyevent KEYCODE_DEL; ".repeat(40).trimEnd())
            device.waitForIdle(400)
            // NB: executeShellCommand does not interpret shell quoting — a quoted
            // argument would type the quotes into the field. Values here contain
            // no spaces or shell metacharacters, so pass them bare.
            device.executeShellCommand("input text $text")
            device.waitForIdle(1_000)
            // Global text search: the field renders its content as a text
            // node (masked fields render a bullet run).
            val token = if (masked) "\u2022\u2022\u2022\u2022\u2022" else text
            if (device.wait(Until.hasObject(By.textContains(token)), 1_500)) return true
        }
        return false
    }

    /** Outcome of one Fetch-models click. The ERROR text renders right below
     *  the button (in view); the chips render below the fold — one drag after
     *  a quiet moment makes them a11y-visible. */
    private enum class FetchOutcome { CHIPS, ERROR, NOTHING }

    private fun fetchOutcome(): FetchOutcome {
        val deadline = System.currentTimeMillis() + 9_000
        var dragged = false
        while (System.currentTimeMillis() < deadline) {
            if (textExists("Could not fetch models")) return FetchOutcome.ERROR
            if (textExists("mock-model-a")) return FetchOutcome.CHIPS
            if (!dragged) {
                try { Thread.sleep(1_200) } catch (_: InterruptedException) { }
                dragUpQuarter()
                dragged = true
            } else {
                try { Thread.sleep(300) } catch (_: InterruptedException) { }
            }
        }
        return FetchOutcome.NOTHING
    }

    private fun textExists(part: String): Boolean =
        runCatching { device.findObjects(By.textContains(part)) }.getOrDefault(emptyList()).isNotEmpty()

    /** SLOW drag (100 steps ≈ no fling momentum) that scrolls ~1/4 of the
     *  screen — deterministic: a fast fling overshoots past the target row
     * in scrollable sheets (observed in CI: the "AI Agents" sheet entry
     * never became visible after 4 fling attempts). A slow drag
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
        return openSheetEntry("AI Agents") { panelUp(6_000) }
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
                hasText("Create Profile", 90_000)
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
        assertTrue(
            "name field must be typeable; UI:\n" + uiTree(),
            typeIntoField("provider_name_field", "MockLLM")
        )
        assertTrue("base URL field must be typeable", typeIntoField("provider_url_field", baseUrl))
        assertTrue("API key field must be typeable", typeIntoField("provider_key_field", "test-key-123", masked = true))

        // ---- 5. Fetch models from the MockWebServer ------------------------
        // VERIFICATION-DRIVEN: after each click, watch for one of three
        // outcomes — the chips (fetch worked; they render below the fold on
        // the small CI screen, so poll + drag), the fetch ERROR text (the
        // URL text got corrupted — clear the field completely and retype),
        // or nothing at all (the tap was lost — click again). Retyping into
        // a non-empty field without clearing corrupts it at the cursor
        // (CI-observed: interleaved URL fragments, "Invalid URL port").
        hideImeIfNeeded()
        var chipsShown = false
        for (attempt in 1..3) {
            assertTrue(
                "Fetch models button must be clickable",
                clickTextWithScroll("Fetch models")
            )
            when (fetchOutcome()) {
                FetchOutcome.CHIPS -> { chipsShown = true; break }
                FetchOutcome.ERROR ->
                    assertTrue(
                        "base URL field must be retypable after a failed fetch",
                        typeIntoField("provider_url_field", baseUrl)
                    )
                FetchOutcome.NOTHING -> device.waitForIdle(2_000)
            }
        }
        if (!chipsShown) {
            throw AssertionError("Model chips from /models must appear; UI:\n" + uiTree())
        }
        if (!clickTextWithScroll("mock-model-a")) {
            throw AssertionError("mock-model-a chip must be selectable; UI:\n" + uiTree())
        }

        // ---- 6. Save -> back in the settings activity ----------------------
        hideImeIfNeeded()
        if (!clickTextWithScroll("Save provider")) {
            throw AssertionError("Save provider must be clickable; UI:\n" + uiTree())
        }
        // The save primitives are non-cancellable (AgentProviderStore.save /
        // saveAgentSettings survive the editor being finished mid-write), but
        // the EDITOR must still close ITSELF via its onDone — proving the
        // write committed. "Add provider" is only visible on the SETTINGS
        // screen (the editor covers it while open), so it is the unambiguous
        // editor-closed signal — "Presets" disappearing is NOT (a scroll can
        // push that header out of the a11y viewport while the editor is
        // still open; CI evidence run 36312695165).
        assertTrue(
            "Editor must close itself after the save completes",
            hasText("Add provider", 15_000)
        )
        assertTrue(
            "Settings screen must list the saved provider",
            hasText("MockLLM", 15_000)
        )

        // ---- 6b. The local decision gate -----------------------------------
        // The gate is switchable here but NOT usable: this test configures an
        // OpenAI-compatible provider, and /v1/systemone only exists on a local
        // Ollama 0.35+. That is exactly the state the row has to describe
        // honestly, so both halves are checked — the switch persists, and the
        // policy field the model is asked with is on the screen at all.
        //
        // Order matters. flipSwitch only advances the viewport downwards, and
        // off-screen rows of a scrollable column are not in the a11y tree, so
        // every switch is flipped FIRST, in top-to-bottom order, and the
        // policy field — which sits below all of them, in the gate's own
        // section — is found on the way down at the end. A check that needed
        // to scroll back up would fail outright: flipSwitch cannot go up.
        assertTrue(
            "Local decision gate switch must flip ON",
            flipSwitch("Local decision gate", wantOn = true)
        )
        assertTrue(
            "Local decision gate switch must flip OFF",
            flipSwitch("Local decision gate", wantOn = false)
        )

        // ---- 6c. YOLO ------------------------------------------------------
        // YOLO's "on" state is indistinguishable from the app working
        // normally, which is the whole reason it is dangerous. The screen
        // therefore owes the user a sentence saying otherwise, and flipping
        // the switch must actually render it — an off-by-one in the condition
        // that draws it would leave the app silently unguarded with nothing
        // on screen to say so, and that is worth an assertion.
        assertTrue(
            "YOLO switch must flip ON",
            flipSwitch("YOLO: always allow", wantOn = true)
        )
        // The warning paragraph renders directly under the switch row, and
        // flipSwitch may have found that row at the very bottom edge of the
        // viewport — in which case the paragraph is still below the fold and
        // therefore not in the a11y tree. One small drag brings it in and
        // still leaves the row on screen, which is what the OFF flip below
        // needs: flipSwitch only ever scrolls DOWNWARDS, so a row carried off
        // the top could never be flipped back.
        var warningUp = device.wait(Until.hasObject(By.textContains("YOLO is ON")), 1_500)
        for (i in 1..2) {
            if (warningUp) break
            dragUpQuarter()
            warningUp = device.wait(Until.hasObject(By.textContains("YOLO is ON")), 2_000)
        }
        assertTrue("YOLO must show its warning while it is on", warningUp)
        assertTrue(
            "YOLO switch must flip OFF",
            flipSwitch("YOLO: always allow", wantOn = false)
        )

        var policySeen = false
        for (i in 1..18) {
            if (hasDesc("decision_gate_policy", 700)) { policySeen = true; break }
            dragUpQuarter()
        }
        assertTrue("The gate's policy field must be reachable", policySeen)

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
                "Agent panel model line must show the fetched model; " +
                    "DB ground truth: " + dbGroundTruth() +
                    "; agent logcat: " + agentLogTail() +
                    "; UI:\n" + uiTree()
            )
        }

        // ---- 7b. Chat round-trip + the copy affordance --------------------
        // Type a prompt and send it: the user bubble (with its copy icon)
        // appears immediately, then the mock SSE reply streams back as an
        // assistant bubble. Tapping the copy icon puts the EXACT previously
        // sent text back on the clipboard — proven by the "Copied" feedback
        // label — so it can be pasted into the composer and re-processed.
        assertTrue(
            "composer field must be typeable",
            typeIntoField("agent_composer_field", "e2e_copy_prompt")
        )
        hideImeIfNeeded()
        if (!clickDesc("agent_send", 5_000)) {
            throw AssertionError("send button must be clickable; UI:\n" + uiTree())
        }
        assertTrue(
            "user bubble with the sent text must appear",
            hasText("e2e_copy_prompt", 10_000)
        )
        assertTrue(
            "copy icon under the user bubble must appear",
            hasDesc("agent_copy_user", 5_000)
        )
        assertTrue(
            "mock SSE reply must stream back as an assistant bubble",
            hasText("mock-reply-ok", 20_000)
        )
        assertTrue(
            "copy icon under the assistant reply must appear",
            hasDesc("agent_copy_assistant", 5_000)
        )
        assertTrue(
            "copying the previously sent text must show the Copied feedback",
            clickDesc("agent_copy_user", 5_000) && hasText("Copied", 3_000)
        )

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

    /** Ground truth: reads the providers table from THIS instrumentation
     *  process (a third Room instance on the same file) — proves whether the
     *  saved provider is committed and visible cross-process. */
    private fun dbGroundTruth(): String = runCatching {
        kotlinx.coroutines.runBlocking {
            val db = androidx.room.Room.databaseBuilder(
                targetContext, com.roombrowser.data.db.AppDatabase::class.java,
                com.roombrowser.data.db.AppDatabase.NAME
            ).allowMainThreadQueries().build()
            try {
                val providers = db.agentDao().providers()
                val agentSettings = db.appStateDao().get("agent_settings")
                "providers=${providers.map { "${it.name}/${it.defaultModel}" }} " +
                    "agent_settings=$agentSettings"
            } finally {
                db.close()
            }
        }
    }.getOrElse { "db-query-failed: ${it.message}" }

    /** Last RoomAgent diagnostic lines from the app's logcat. */
    private fun agentLogTail(): String = runCatching {
        val logs = device.executeShellCommand(
            "logcat -d -s RoomAgent:V -t 40"
        ).trim()
        if (logs.isBlank()) "(no RoomAgent logs)" else logs.take(1500)
    }.getOrDefault("(logcat failed)")
}
