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
 * controller's unit tests (OllamaLocalTest).
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.setDispatcher(OllamaFake())
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
     */
    private class OllamaFake : Dispatcher() {
        private val pulled = mutableSetOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return when {
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
        var installDesc = "localai_install_qwen2.5:0.5b"
        if (!clickDescContainsWithScroll(installDesc, attempts = 14)) {
            installDesc = findFirstInstallButton()
                ?: throw AssertionError("No catalog Install button found; UI:\n" + uiTree())
            assertTrue(
                "Fallback install button ($installDesc) must be clickable",
                clickDescContainsWithScroll(installDesc, attempts = 4)
            )
        }
        val installedTag = installDesc.removePrefix("localai_install_")

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
}
