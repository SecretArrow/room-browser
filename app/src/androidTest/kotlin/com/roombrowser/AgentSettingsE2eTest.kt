package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
 *     -> tap the "AI Agent" pill
 *     -> Configure providers -> Add provider
 *     -> type name + base URL (local MockWebServer) + API key
 *     -> Fetch models -> chips from the provider's /models response
 *     -> Save -> provider listed
 *     -> model line of the agent panel shows the selected model
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
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"mock-model-a"},{"id":"mock-model-b"}]}""")
        )
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

    private fun engineUiUp(timeoutMs: Long): Boolean =
        hasText("Search or type URL", timeoutMs)
            || hasText("Privacy Dashboard", 10_000)
            || hasText("trackers blocked", 5_000)

    /** Types text into the editor field with the given content description. */
    private fun typeIntoField(desc: String, text: String): Boolean {
        val field = device.wait(Until.findObject(By.desc(desc)), 8_000) ?: return false
        clickCenter(field)
        device.executeShellCommand("input text '$text'")
        device.waitForIdle(1_000)
        return true
    }

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

        // ---- 2. Open the agent panel via the floating pill -----------------
        assertTrue(
            "AI Agent pill must be visible (content-desc 'AI Agent')",
            hasDesc("AI Agent", 20_000)
        )
        var panelOpen = false
        for (attempt in 1..3) {
            clickDesc("AI Agent", 5_000)
            if (hasText("Configure providers", 4_000)) {
                panelOpen = true
                break
            }
            // A transient overlay (IME, sheet) may have swallowed the tap —
            // dismiss the keyboard and retry.
            device.pressBack()
            device.waitForIdle(1_000)
        }
        assertTrue("Empty-state 'Configure providers' must appear", panelOpen)
        assertTrue("Configure button must be clickable", clickDesc("agent_configure", 8_000))

        // ---- 3. Add a provider ---------------------------------------------
        assertTrue("Add provider button must appear", hasText("Add provider", 10_000))
        assertTrue("Add provider must be clickable", clickText("Add provider", 8_000))
        assertTrue("Editor must open", hasText("Presets", 10_000))

        // Fill the manual fields via their semantics descriptions
        // (name → URL → API key), then fetch the model list.
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        assertTrue("name field must be typeable", typeIntoField("provider_name_field", "MockLLM"))
        assertTrue("base URL field must be typeable", typeIntoField("provider_url_field", baseUrl))
        assertTrue("API key field must be typeable", typeIntoField("provider_key_field", "test-key-123"))

        // ---- 4. Fetch models from the MockWebServer ------------------------
        assertTrue("Fetch models button must be clickable", clickText("Fetch models", 8_000))
        assertTrue(
            "Model chips from /models must appear",
            hasText("mock-model-a", 20_000)
        )
        assertTrue("mock-model-a chip must be selectable", clickText("mock-model-a", 8_000))

        // ---- 5. Save --------------------------------------------------------
        assertTrue("Save provider must be clickable", clickText("Save provider", 8_000))
        assertTrue(
            "Settings screen must list the saved provider",
            hasText("MockLLM", 15_000)
        )

        // ---- 6. Back to the browser: the panel shows the model -------------
        assertTrue("Settings close button must work", clickDesc("Close", 8_000))
        assertTrue("Engine UI must be back", engineUiUp(15_000))
        assertTrue("Agent pill must still be present", hasDesc("AI Agent", 10_000))
        assertTrue("Agent panel must reopen", clickDesc("AI Agent", 8_000))
        assertTrue(
            "Agent panel model line must show the fetched model",
            device.wait(Until.hasObject(By.textContains("mock-model-a")), 15_000)
        )
    }
}
