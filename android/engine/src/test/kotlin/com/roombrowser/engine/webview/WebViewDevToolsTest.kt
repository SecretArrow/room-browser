package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineNetworkSignal
import org.junit.Test

/**
 * What the WebView edition declares to Developer Tools, and the page-world
 * patch that backs one of those declarations.
 *
 * The capability set is a promise the panels read directly, and the console
 * patch is a Kotlin string the page bridge parses -- neither has a compiler
 * between the two halves that must agree, so both are pinned here as text.
 */
class WebViewDevToolsTest {

    private val expectedResponseHeadersNote =
        "WebView reports a request to shouldInterceptRequest before it is sent and never hands back the response, so status and response headers are not observable without intercepting the body — which this app deliberately does not do because it would break streaming."

    @Test
    fun the_capability_set_is_exactly_what_this_engine_serves() {
        val capabilities = WebViewDevTools.CAPABILITIES
        assertThat(capabilities.capabilities).containsExactly(
            DevToolsCapability.PAGE_SCRIPTING,
            DevToolsCapability.CONSOLE_CAPTURE,
            DevToolsCapability.ENGINE_CONSOLE,
            DevToolsCapability.NETWORK_REQUEST_LINE
        )
        assertThat(capabilities.has(DevToolsCapability.NETWORK_RESPONSE_HEADERS)).isFalse()
    }

    @Test
    fun only_the_in_scope_absence_carries_a_reason() {
        val capabilities = WebViewDevTools.CAPABILITIES
        // Present capabilities answer null by construction; every other absent
        // one must stay silent, because "not yet" is not "cannot".
        assertThat(capabilities.noteFor(DevToolsCapability.NETWORK_RESPONSE_HEADERS))
            .isEqualTo(expectedResponseHeadersNote)
        DevToolsCapability.entries
            .filter { it != DevToolsCapability.NETWORK_RESPONSE_HEADERS }
            .forEach { capability ->
                assertThat(capabilities.noteFor(capability)).isNull()
            }
    }

    @Test
    fun the_page_console_capture_is_withdrawn_when_the_device_cannot_install_the_patch() {
        // The patch is installed through addDocumentStartJavaScript, so a WebView
        // without it has no page console to show. Declaring the capability anyway
        // would put a panel on screen whose feed can never fill.
        val degraded = WebViewDevTools.capabilities(documentStartScripts = false)

        assertThat(degraded.capabilities).containsExactly(
            DevToolsCapability.PAGE_SCRIPTING,
            DevToolsCapability.ENGINE_CONSOLE,
            DevToolsCapability.NETWORK_REQUEST_LINE
        )
        // And the absence is explained rather than silent, because on THIS
        // device it is a real limit the user can do nothing about.
        assertThat(degraded.noteFor(DevToolsCapability.CONSOLE_CAPTURE)).isNotNull()

        // The supported branch is the build's own set, unchanged.
        val supported = WebViewDevTools.capabilities(documentStartScripts = true)
        assertThat(supported.capabilities).isEqualTo(WebViewDevTools.CAPABILITIES.capabilities)
        assertThat(supported.noteFor(DevToolsCapability.CONSOLE_CAPTURE)).isNull()
    }

    @Test
    fun the_console_patch_carries_the_markers_both_sides_speak() {
        val patch = WebViewDevTools.CONSOLE_PATCH
        assertThat(patch).contains("__rbConsole")
        assertThat(patch).contains("arm")
        assertThat(patch).contains("disarm")
        // The bounded ring buffer.
        assertThat(patch).contains("BUFFER_MAX")
        assertThat(patch).contains("200")
        // The bridge needs a string, because a complex JS value does not cross
        // addJavascriptInterface.
        assertThat(patch).contains("JSON.stringify")
        // The global the patch calls is the one the session registers.
        assertThat(patch).contains(WebViewPageChannels.CONSOLE_INTERFACE)
        assertThat(patch).contains("bridge.entry")
        listOf("log", "info", "warn", "error", "debug", "assert").forEach { level ->
            assertThat(patch).contains(level)
        }
    }

    @Test
    fun the_installed_patch_carries_its_arm_state_in_the_script() {
        // A page-world flag dies with its document, so a patch installed armed
        // the old way started disarmed on the next navigation and the open panel
        // went quiet. The state therefore has to be in the installed text.
        assertThat(WebViewDevTools.consolePatch(armed = true)).contains("var armed = true;")
        assertThat(WebViewDevTools.consolePatch(armed = false)).contains("var armed = false;")
        // Nothing is left for the page or the session to substitute at runtime.
        assertThat(WebViewDevTools.consolePatch(armed = true)).doesNotContain("__RB_CONSOLE_ARMED__")
    }

    @Test
    fun the_console_patch_keeps_calling_the_original_console_method() {
        // The forward is best-effort; the page's own console call is not. A
        // wrapper that swallowed it would silently break the page's logging.
        assertThat(WebViewDevTools.CONSOLE_PATCH).contains("original.apply(console")
    }

    @Test
    fun the_engine_backlog_keeps_the_newest_and_drains_exactly_once() {
        // A page logs at document start and the panel opens much later, so the
        // copies that arrive in between are the only record of what the page
        // said. Keeping them is what stops the feed opening empty; dropping the
        // oldest is what stops an uninspected page growing a buffer forever.
        val backlog = EngineSignalBacklog<EngineConsoleMessage>(capacity = 2)
        backlog.add(engineLine("one"))
        backlog.add(engineLine("two"))
        backlog.add(engineLine("three"))

        assertThat(backlog.drain().map { it.text }).containsExactly("two", "three").inOrder()

        // A drain empties, so a replayed line is never delivered twice.
        assertThat(backlog.drain()).isEmpty()

        backlog.add(engineLine("four"))
        backlog.clear()
        assertThat(backlog.drain()).isEmpty()
    }

    @Test
    fun the_request_backlog_holds_the_document_a_page_was_opened_on() {
        // The document itself is reported before anything can be listening, so
        // without a backlog of its own the Network panel opened on a page whose
        // own address could never appear in it.
        val backlog = EngineSignalBacklog<EngineNetworkSignal>(capacity = 1)
        backlog.add(signal("https://example.com/"))
        backlog.add(signal("https://example.com/asset.js"))

        assertThat(backlog.drain().map { it.url }).containsExactly("https://example.com/asset.js")
    }

    private fun signal(url: String) = EngineNetworkSignal(
        kind = EngineNetworkSignal.Kind.REQUEST,
        url = url,
        method = "GET",
        status = null,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        isForMainFrame = false,
        resourceType = null,
        timestampMs = 0L
    )

    private fun engineLine(text: String) = EngineConsoleMessage(
        level = "log",
        text = text,
        source = null,
        line = null,
        timestampMs = 0L,
        fromEngine = true
    )
}
