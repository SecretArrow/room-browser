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
 * E2E for the Local AI (Ollama) manager (real app UI, fake Ollama server):
 *
 *   AgentSettingsActivity (launched DIRECTLY, default process — no browser
 *   round-trip needed; the package name is resolved from the instrumentation
 *   target context, never hard-coded, because debug builds carry an
 *   applicationId suffix)
 *     -> "Local AI (Ollama)" entry row -> LocalAiActivity (own window)
 *     -> clear + type the MockWebServer host + Connect
 *     -> status "Connected · Ollama 0.5.7"      (GET /api/version)
 *     -> installed models listed from the server (GET /api/tags → llama3.2:1b)
 *     -> Install a catalog preset               (POST /api/pull, NDJSON stream)
 *     -> after the pull finishes and the model list refreshes, the preset's
 *        Install button flips to the "Installed" chip
 *        (desc "localai_installed_<tag>")
 *
 *   Catalog refresh ("Find new models", LocalAiActivity launched directly
 *   with the test-only library_url extra pointing at the same fake server):
 *     -> GET /library?sort=newest → structurally faithful HTML slice
 *     -> preset-covered families (llama3.2) and embedding-only families
 *        (bge-m3) stay hidden; the new phone-suitable family (qwen3.5,
 *        badges 0.8b/2b/27b) is discovered
 *     -> tapping its 0.8b Install button pulls qwen3.5:0.8b on the fake
 *        daemon (same verification-driven pull-evidence pattern)
 *
 * The fake server is a STATEFUL [Dispatcher], not a strict response queue:
 * the LocalAiController may call /api/version and /api/tags in any order and
 * any count (refresh on connect, refresh after a finished pull, manual
 * refresh) — every request gets a correct response, and /api/tags lists
 * every model that has been pulled so far, so the flow stays deterministic.
 *
 * HONEST NOTE — pause/resume labels are NOT asserted here: the MockWebServer
 * pull body completes instantly, so the DOWNLOADING phase can flash by
 * before UiAutomator polls the accessibility tree. Pause/resume semantics
 * (job cancel + re-attach, server-side layer cache) are covered by the
 * controller's unit tests (OllamaLocalTest). The library PARSER itself is
 * unit-tested against a faithful fixture in OllamaDtosTest — the e2e only
 * proves the wire-up (button → fetch → parsed cards → install).
 *
 * Compose fields are driven exactly like AgentSettingsE2eTest: semantics
 * content-description nodes + `input text` shell command (WITHOUT quotes —
 * executeShellCommand does not parse shell quoting) + coordinate clicks.
 */
@RunWith(AndroidJUnit4::class)
class LocalAiE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private lateinit var fake: OllamaFake

    /**
     * Structurally faithful slice of ollama.com/library (see OllamaDtosTest
     * for the full-fidelity parser tests). Three families on purpose:
     *  - llama3.2 — covered by the curated presets → hidden from discovery
     *  - qwen3.5 — NEW phone-suitable family (0.8b/2b fit; 27b too big)
     *  - bge-m3  — embedding-only → filtered out of discovery
     */
    private val libraryHtml = """
        <html><body><ul>
        <li  class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/llama3.2" class="group w-full space-y-5">
            <div  title="llama3.2" class="flex flex-col">
              <p class="max-w-lg break-words text-neutral-800 text-md">Meta&#39;s compact multilingual models.</p>
            </div>
            <div class="flex flex-col space-y-2">
              <div class="flex flex-wrap space-x-2">
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">tools</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">1b</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">3b</span>
              </div>
              <span><span class="hidden sm:flex">Updated&nbsp;</span><span >3 months ago</span></span>
            </div>
          </a>
        </li>
        <li  class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/qwen3.5" class="group w-full space-y-5">
            <div  title="qwen3.5" class="flex flex-col">
              <p class="max-w-lg break-words text-neutral-800 text-md">Qwen 3.5 is a family of open-source multimodal models.</p>
            </div>
            <div class="flex flex-col space-y-2">
              <div class="flex flex-wrap space-x-2">
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">tools</span>
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">thinking</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">0.8b</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">2b</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">27b</span>
              </div>
              <span><span class="hidden sm:flex">Updated&nbsp;</span><span >3 weeks ago</span></span>
            </div>
          </a>
        </li>
        <li  class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/bge-m3" class="group w-full space-y-5">
            <div  title="bge-m3" class="flex flex-col">
              <p class="max-w-lg break-words text-neutral-800 text-md">BGE-M3 is a versatile embedding model.</p>
            </div>
            <div class="flex flex-col space-y-2">
              <div class="flex flex-wrap space-x-2">
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">embedding</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">0.5b</span>
              </div>
              <span><span class="hidden sm:flex">Updated&nbsp;</span><span >2 months ago</span></span>
            </div>
          </a>
        </li>
        </ul></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        fake = OllamaFake(libraryHtml)
        // Kotlin property syntax — OkHttp 4.x MockWebServer.dispatcher is a
        // var, so the Java-style setDispatcher() does not resolve in Kotlin.
        server.dispatcher = fake
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /**
     * Stateful fake Ollama server:
     *  - GET  /api/version → {"version":"0.5.7"}
     *  - GET  /api/tags    → llama3.2:1b + every model pulled so far
     *  - POST /api/pull    → instant NDJSON success stream
     *  - GET  /library     → the faithful HTML slice above (catalog refresh)
     */
    private class OllamaFake(val libraryHtml: String) : Dispatcher() {
        private val pulled = mutableSetOf<String>()

        /** Ground truth for the refresh test: GET /library hit count. */
        val libraryHits = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return when {
                path.startsWith("/library") -> {
                    libraryHits.incrementAndGet()
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(libraryHtml)
                }
                path.startsWith("/api/version") ->
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"version":"0.5.7"}""")

                path.startsWith("/api/tags") -> {
                    val models = StringBuilder()
                        .append(llamaModelJson())
                    synchronized(pulled) {
                        pulled.forEach { tag ->
                            models.append(',')
                                .append(pulledModelJson(tag))
                        }
                    }
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"models":[$models]}""")
                }

                path.startsWith("/api/pull") -> {
                    val body = request.body.readUtf8()
                    Regex(""""model"\s*:\s*"([^"]+)"""").find(body)
                        ?.groupValues?.get(1)
                        ?.let { tag -> synchronized(pulled) { pulled.add(tag) } }
                    MockResponse()
                        .setHeader("Content-Type", "application/x-ndjson")
                        .setBody(
                            "{\"status\":\"pulling manifest\"}\n" +
                                "{\"status\":\"downloading\",\"digest\":\"sha256:abc\",\"completed\":500,\"total\":1000}\n" +
                                "{\"status\":\"verifying sha256 digest\"}\n" +
                                "{\"status\":\"success\"}"
                        )
                }

                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun llamaModelJson() =
            """{"name":"llama3.2:1b","model":"llama3.2:1b","size":1328238021,""" +
                """"digest":"sha256:llama","modified_at":"2025-01-01T00:00:00Z",""" +
                """"details":{"family":"llama","parameter_size":"1.2B","quantization_level":"Q4_K_M"}}"""

        private fun pulledModelJson(tag: String) =
            """{"name":"$tag","model":"$tag","size":494337152,""" +
                """"digest":"sha256:pulled","modified_at":"2025-01-01T00:00:00Z",""" +
                """"details":{"family":"qwen2","parameter_size":"0.5B","quantization_level":"Q4_K_M"}}"""
    }

    // =====================================================================
    // Launch helpers
    // =====================================================================

    /**
     * Launches AgentSettingsActivity directly (default process) — its own
     * window, no browser round-trip. The activity is not exported, so the
     * primary path is the app's own context (same-app start is allowed);
     * the `am start` shell fallback uses the REAL application id resolved
     * from the instrumentation target context (never hard-coded: debug
     * builds append ".debug" to the application id while the class keeps
     * the fixed source namespace).
     */
    private fun launchAgentSettingsDirectly() {
        runCatching {
            val intent = Intent()
                .setClassName(targetContext, "com.roombrowser.agent.ui.AgentSettingsActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            targetContext.startActivity(intent)
        }
        if (!hasText("AI Agent Settings", 4_000)) {
            device.executeShellCommand(
                "am start -n ${targetContext.packageName}/com.roombrowser.agent.ui.AgentSettingsActivity"
            )
            device.waitForIdle(2_000)
        }
    }

    /**
     * Launches LocalAiActivity directly with the TEST-ONLY library_url extra
     * (mirrors LocalAiActivity.EXTRA_LIBRARY_URL; kept as a literal so the
     * test reads like the manifest contract) — the catalog refresh then
     * talks to the fake server instead of ollama.com.
     *
     * The PRIMARY path uses FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK so
     * an instance left by the earlier test method can never absorb the launch
     * (a stale, extra-less activity would silently point the refresh at the
     * real ollama.com). The shell fallback carries the same flags + extra.
     */
    private fun launchLocalAiDirectly(libraryUrl: String) {
        runCatching {
            val intent = Intent()
                .setClassName(targetContext, "com.roombrowser.agent.ui.LocalAiActivity")
            intent.putExtra("library_url", libraryUrl)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            targetContext.startActivity(intent)
        }
        if (!hasDesc("localai_host_field", 4_000)) {
            // 0x10008000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK.
            device.executeShellCommand(
                "am start -f 0x10008000 -n ${targetContext.packageName}/com.roombrowser.agent.ui.LocalAiActivity" +
                    " --es library_url $libraryUrl"
            )
            device.waitForIdle(2_000)
        }
    }

    // =====================================================================
    // UiAutomator helpers (proven patterns from AgentSettingsE2eTest)
    // =====================================================================

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    /** Scroll-aware text wait: off-screen rows are NOT exposed to the a11y
     *  tree — small deterministic drags between polls (no fling overshoot). */
    private fun hasTextWithScroll(text: String, attempts: Int = 10): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware desc wait — same reason as [hasTextWithScroll]. */
    private fun hasDescContainsWithScroll(part: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (hasDescContains(part, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
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
     * IME covering the node), walking up to the nearest clickable ancestor
     * for Compose text-inside-button nodes; falls back to a coordinate tap.
     * NB: UiObject2.click() returns Unit.
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

    /** Off-screen rows of a scrollable container are not exposed to the
     *  accessibility tree — advance the viewport with SMALL deterministic
     *  drags between click attempts (no fling overshoot). */
    private fun clickTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware desc-contains click (catalog Install buttons). */
    private fun clickDescContainsWithScroll(part: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            val node = device.wait(Until.findObject(By.descContains(part)), 1_500)
            if (node != null && clickSmart(node)) return true
            dragUpQuarter()
        }
        return false
    }

    /** SLOW drag (100 steps ≈ no fling momentum) that scrolls ~1/4 of the
     *  screen — deterministic. */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Reverse of [dragUpQuarter] — scrolls the viewport toward the START of
     *  the screen, so retry rounds can get back to widgets above the fold. */
    private fun dragDownQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 8,
            device.displayWidth / 2, device.displayHeight * 5 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Reset to the top of the scrollable screen. Generous on purpose: the
     *  drift between rounds can reach ~5 screens (failed find loops drag the
     *  viewport to the bottom), so 18 quarter-drags (~4.5 screens) climb back
     *  far enough for the next round's down-search to cross the catalog row. */
    private fun scrollToTop() {
        repeat(18) { dragDownQuarter() }
    }

    /** Catalog-state caption read IN PLACE (no scrolling) — call while the
     *  viewport is still at the refresh button. Viewport-limited on purpose:
     *  the caption lives in the SAME row as the button. */
    private fun catalogCaptionInPlace(): String {
        val probes = listOf(
            "Idle" to "Fetch the live ollama.com library",
            "Loading" to "Fetching the newest models",
            "Ready" to "Library updated",
            "Failed" to "Couldn't read the library"
        )
        for ((label, needle) in probes) {
            val found = runCatching {
                device.findObjects(By.textContains(needle)).isNotEmpty()
            }.getOrDefault(false)
            if (found) return label
        }
        return "?"
    }

    /** Single-LINE screen state for assertion messages — multi-line dumps get
     *  truncated by the runner's console, so everything is joined with ' | '. */
    private fun screenSummary(): String {
        val refreshBtn = runCatching {
            device.findObjects(By.descContains("localai_refresh_catalog")).size
        }.getOrDefault(-1)
        val texts = runCatching {
            device.findObjects(By.textContains(""))
                .mapNotNull { it.text }
                .distinct()
                .take(30)
                .map { it.take(44) }
        }.getOrDefault(emptyList())
        return "libraryHits=${fake.libraryHits.get()} refreshBtn=$refreshBtn " +
            "texts=[${texts.joinToString(" | ")}]"
    }

    /** Full a11y dump AT the catalog top — descs AND texts — for the
     *  button-not-found assertion. Runs after positioning the viewport at the
     *  "Model catalog" header + refresh row (~1.25 screens down from the top). */
    private fun dumpCatalogTop(): String {
        repeat(18) { dragDownQuarter() }
        repeat(5) { dragUpQuarter() }
        device.waitForIdle(800)
        val descs = runCatching {
            device.findObjects(By.descContains("localai"))
                .mapNotNull { it.contentDescription }
                .take(16)
        }.getOrDefault(emptyList())
        val texts = runCatching {
            device.findObjects(By.textContains(""))
                .mapNotNull { it.text }
                .distinct()
                .take(24)
                .map { it.take(40) }
        }.getOrDefault(emptyList())
        return "descs=[${descs.joinToString(", ")}] texts=[${texts.joinToString(" | ")}]"
    }

    /** Types text into the field with the given content description. */
    private fun typeIntoField(desc: String, text: String): Boolean {
        hideImeIfNeeded()
        var field = device.wait(Until.findObject(By.desc(desc)), 4_000)
        if (field == null) {
            dragUpQuarter()
            field = device.wait(Until.findObject(By.desc(desc)), 4_000) ?: return false
        }
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

    /**
     * Clears a field that already holds text (the host field defaults to
     * http://localhost:11434): tap → cursor to end → repeated DEL. Verified
     * by reading the node's accessibility text — retries once more if any
     * characters survived.
     */
    private fun clearField(desc: String): Boolean {
        hideImeIfNeeded()
        val field = device.wait(Until.findObject(By.desc(desc)), 6_000) ?: return false
        for (round in 1..3) {
            clickCenter(field)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            repeat(32) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
            device.waitForIdle(400)
            val current = runCatching { field.text }.getOrNull()
            if (current.isNullOrBlank()) return true
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

    /**
     * The connection-status node carries desc "localai_status"; its visible
     * label ("Connected · Ollama 0.5.7") is exposed as accessibility text.
     * Three probes (desc-node text, screen text, desc-contains) so Compose
     * semantics merging can never hide the version.
     */
    private fun statusContains(part: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = runCatching { device.findObject(By.desc("localai_status")) }.getOrNull()
            if (node != null && runCatching { node.text }.getOrNull()?.contains(part) == true) {
                return true
            }
            if (runCatching { device.findObjects(By.textContains(part)) }.getOrDefault(emptyList()).isNotEmpty()) {
                return true
            }
            if (hasDescContains(part, 300)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return false
    }

    /** First visible catalog Install button desc, scrolling if needed.
     *  "localai_install_" cannot collide with "localai_installed_" (the
     *  char after "localai_install" differs: "_" vs "e"). */
    private fun findFirstInstallButton(): String? {
        for (i in 1..8) {
            val nodes = runCatching {
                device.findObjects(By.descContains("localai_install_"))
            }.getOrDefault(emptyList())
            nodes.firstOrNull()?.contentDescription?.let { return it }
            dragUpQuarter()
        }
        return null
    }

    /** PROOF that a pull for [tag] started/ran: the download row exposes a
     *  Pause button while STARTING/DOWNLOADING/VERIFYING, a Clear button on
     *  the kept SUCCESS row, a Resume button when PAUSED/FAILED — and the
     *  catalog flips to the Installed chip once /api/tags lists the model.
     *  Any of the four settles within [timeoutMs] on the instant fake server. */
    private fun pullEvidence(tag: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val probes = listOf(
            "localai_installed_$tag",
            "localai_clear_$tag",
            "localai_pause_$tag",
            "localai_resume_$tag"
        )
        while (System.currentTimeMillis() < deadline) {
            for (probe in probes) {
                val found = runCatching {
                    device.findObjects(By.descContains(probe))
                }.getOrDefault(emptyList())
                if (found.isNotEmpty()) return true
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
        }
        return false
    }

    /** Probes the local-AI nodes for readable failure messages. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        for (probe in listOf(
            "AI Agent Settings title" to By.text("AI Agent Settings"),
            "Local AI row" to By.text("Local AI (Ollama)"),
            "localai_host_field" to By.desc("localai_host_field"),
            "localai_connect" to By.desc("localai_connect"),
            "localai_status" to By.desc("localai_status"),
            "localai_installed_list" to By.desc("localai_installed_list"),
            "localai_install_qwen2.5:0.5b" to By.descContains("localai_install_")
        )) {
            val nodes = runCatching { device.findObjects(probe.second) }.getOrDefault(emptyList())
            sb.append(probe.first).append(": count=").append(nodes.size)
            nodes.take(2).forEach { n ->
                sb.append(" text='").append(runCatching { n.text }.getOrNull())
                    .append("' bounds=").append(runCatching { n.visibleBounds }.getOrNull())
            }
            sb.append('\n')
        }
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
        }.getOrDefault(emptyList())
        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
        sb.toString().take(9000)
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // =====================================================================
    // The flow
    // =====================================================================

    @Test
    fun local_ai_connect_install_and_pause_resume_labels() {
        // ---- 1. Agent settings activity (own window) -----------------------
        device.pressHome()
        launchAgentSettingsDirectly()
        assertTrue(
            "AgentSettingsActivity must open with its title; UI:\n" + uiTree(),
            hasText("AI Agent Settings", 15_000)
        )

        // ---- 2. Local AI entry row opens LocalAiActivity -------------------
        assertTrue(
            "The Local AI (Ollama) entry row must be clickable; UI:\n" + uiTree(),
            clickTextWithScroll("Local AI (Ollama)")
        )
        // The row label and the screen title share the same text — the
        // screen-open proof is the UNIQUE host field / Connect desc.
        assertTrue(
            "LocalAiActivity must open (host field visible); UI:\n" + uiTree(),
            hasDesc("localai_host_field", 15_000)
        )

        // ---- 3. Point the host field at the fake Ollama server ------------
        assertTrue(
            "Host field must be clearable (defaults to http://localhost:11434)",
            clearField("localai_host_field")
        )
        val host = server.url("/").toString().trimEnd('/')
        assertTrue("Host field must be typeable", typeIntoField("localai_host_field", host))
        hideImeIfNeeded()
        assertTrue("Connect button must be clickable", clickDesc("localai_connect", 8_000))

        // ---- 4. Connected: the status shows the server version -------------
        assertTrue(
            "Connection status must show Ollama 0.5.7; UI:\n" + uiTree(),
            statusContains("0.5.7", 20_000)
        )

        // ---- 5. Installed models come from GET /api/tags -------------------
        assertTrue(
            "The installed model llama3.2:1b must be listed; UI:\n" + uiTree(),
            hasTextWithScroll("llama3.2:1b")
        )

        // ---- 6. Install a catalog preset -----------------------------------
        // Prefer the documented ultra-light preset; fall back to ANY
        // installable preset so the test does not hard-depend on the exact
        // preset catalogue ("localai_install_" never matches the installed
        // chip desc "localai_installed_…").
        val preferredDesc = "localai_install_qwen2.5:0.5b"
        val installDesc = if (hasDescContainsWithScroll(preferredDesc, attempts = 14)) {
            preferredDesc
        } else {
            findFirstInstallButton()
                ?: throw AssertionError("No catalog Install button found; UI:\n" + uiTree())
        }
        val installedTag = installDesc.removePrefix("localai_install_")

        // The tap is VERIFICATION-DRIVEN: CI emulators can drop an injected
        // tap on this busy screen (13 preset cards + chips recomposing while
        // the a11y tree is polled — observed in CI: the tap landed dead-on
        // the button and the download coroutine never started). So each round
        // clicks, then waits for PROOF that the pull started: the row's Pause
        // button while running, its Clear button once finished, the catalog
        // Installed chip — or a Resume button if it failed. No proof → the
        // tap was lost → find the button again and tap again.
        var pullStarted = false
        for (round in 1..3) {
            clickDescContainsWithScroll(installDesc, attempts = 6)
            if (pullEvidence(installedTag, 8_000)) {
                pullStarted = true
                break
            }
        }
        assertTrue(
            "Install tap must start the pull for $installedTag " +
                "(no Pause/Clear/Resume/chip evidence after 3 taps); UI:\n" + uiTree(),
            pullStarted
        )

        // ---- 7. Pull finishes → the preset flips to "Installed" -----------
        // (The fake server lists every pulled model in /api/tags from the
        // moment its pull request arrives, so ANY refresh timing works.)
        assertTrue(
            "Preset $installedTag must flip to the Installed chip after the pull; UI:\n" + uiTree(),
            hasDescContainsWithScroll("localai_installed_$installedTag", attempts = 16)
        )

        // Pause/resume buttons are intentionally NOT asserted here — see the
        // class KDoc: instant MockWebServer bodies make the DOWNLOADING phase
        // too short to observe deterministically (unit-tested instead).
    }

    // =====================================================================
    // Catalog refresh — "Find new models" (live library discovery)
    // =====================================================================

    @Test
    fun local_ai_catalog_refresh_discovers_and_installs_new_models() {
        // ---- 1. LocalAiActivity directly, library pointed at the fake server
        device.pressHome()
        launchLocalAiDirectly(server.url("/").toString().trimEnd('/'))
        assertTrue(
            "LocalAiActivity must open (host field visible); UI:\n" + uiTree(),
            hasDesc("localai_host_field", 15_000)
        )

        // ---- 2. Point the OLLAMA host at the same fake server ------------
        // (persists the tuning host; pulls will ride on this dispatcher)
        assertTrue(
            "Host field must be clearable (persisted from the earlier test or default)",
            clearField("localai_host_field")
        )
        val host = server.url("/").toString().trimEnd('/')
        assertTrue("Host field must be typeable", typeIntoField("localai_host_field", host))
        hideImeIfNeeded()
        assertTrue("Connect button must be clickable", clickDesc("localai_connect", 8_000))
        assertTrue(
            "Connection status must show Ollama 0.5.7; UI:\n" + uiTree(),
            statusContains("0.5.7", 20_000)
        )

        // ---- 3. Find new models → GET /library?sort=newest ----------------
        // DUAL-PATH verification-driven tap. The desc path mirrors every other
        // working button in this suite; the TEXT path ("Find new models") is an
        // independent route to the same button that survives any semantics
        // anomaly. After the click the viewport is still AT the row — the
        // catalog caption there separates the failure theories in the message:
        //   Idle → tap dead; Loading → hang; Failed/Ready with 0 mock hits →
        //   library_url extra lost (fetch went to the real ollama.com).
        var qwenFound = false
        var sawLibraryRequest = false
        var buttonNeverFound = false
        var catalogDump = ""
        var lastCaption = "?"
        for (round in 1..4) {
            hideImeIfNeeded()
            scrollToTop()
            val clicked = clickDescContainsWithScroll("localai_refresh_catalog", attempts = 10) ||
                clickTextWithScroll("Find new models", attempts = 8)
            if (!clicked) {
                buttonNeverFound = true
                catalogDump = dumpCatalogTop()
                break
            }
            // In-place capture: the caption is beside the button right now.
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                lastCaption = catalogCaptionInPlace()
                if (fake.libraryHits.get() > 0) break
                if (lastCaption == "Failed" || lastCaption == "Ready") break
                try { Thread.sleep(300) } catch (_: InterruptedException) { }
            }
            if (fake.libraryHits.get() > 0) sawLibraryRequest = true
            // Proof tier 2: the discovered card (scrolls away from the button).
            if (hasTextWithScroll("qwen3.5")) {
                qwenFound = true
                break
            }
        }
        assertTrue(
            "The refresh button must be findable/clickable by desc OR by its " +
                "'Find new models' text; at the catalog top: $catalogDump; " + screenSummary(),
            !buttonNeverFound
        )
        assertTrue(
            "The refresh tap must reach the fake /library within 4 rounds " +
                "(caption after tap: $lastCaption — Idle=tap dead, Loading=hang, " +
                "Failed/Ready with 0 hits=extra lost, fetch went to real ollama.com); " +
                screenSummary(),
            sawLibraryRequest
        )
        assertTrue(
            "Discovered family qwen3.5 must be listed (caption after tap: $lastCaption); " +
                screenSummary(),
            qwenFound
        )

        // ---- 4. Embedding-only families stay hidden ------------------------
        // (bge-m3 exists in the fake library but cannot chat — never listed.)
        assertTrue(
            "Embedding-only family bge-m3 must NOT be listed; UI:\n" + uiTree(),
            !hasText("bge-m3", 2_000)
        )

        // ---- 5. Install the discovered family's phone-friendly tag ---------
        // VERIFICATION-DRIVEN, same as the preset install: tap → proof that
        // the pull started (Pause/Clear/Resume/chip), retry if the tap was
        // lost on the busy screen.
        val installDesc = "localai_install_qwen3.5:0.8b"
        var pullStarted = false
        for (round in 1..3) {
            clickDescContainsWithScroll(installDesc, attempts = 6)
            if (pullEvidence("qwen3.5:0.8b", 8_000)) {
                pullStarted = true
                break
            }
        }
        assertTrue(
            "Install tap must start the pull for qwen3.5:0.8b " +
                "(no Pause/Clear/Resume/chip evidence after 3 taps); UI:\n" + uiTree(),
            pullStarted
        )

        // ---- 6. Pull finishes → the discovered card flips to Installed -----
        assertTrue(
            "Discovered tag qwen3.5:0.8b must flip to the Installed chip after the pull; UI:\n" + uiTree(),
            hasDescContainsWithScroll("localai_installed_qwen3.5:0.8b", attempts = 16)
        )
    }
}
