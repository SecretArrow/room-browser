package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.DevToolsCapability
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
    fun the_console_patch_keeps_calling_the_original_console_method() {
        // The forward is best-effort; the page's own console call is not. A
        // wrapper that swallowed it would silently break the page's logging.
        assertThat(WebViewDevTools.CONSOLE_PATCH).contains("original.apply(console")
    }
}
