package com.roombrowser.agent

import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.PageSnapshotDto
import com.roombrowser.domain.agent.PageSnapshotFormatter
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.formatDurationMs
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.unattendedRefusal
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Executes the agent's browser tools for a SCHEDULED run: one headless
 * [WebView] and nobody watching.
 *
 * Same tool semantics and same result wording as [AgentToolExecutor], because
 * the model reading them is the same model. What differs is only what a
 * headless run cannot do, and each of those answers says so rather than
 * pretending:
 *
 *  - there are no TABS (one page, one WebView), so the four tab tools are
 *    refused;
 *  - there is nobody to answer a confirmation, so an action that would have to
 *    ask the user is refused, and the refusal says which switch allows it.
 *
 * The WebView is this task's own and is never attached to the view tree, so
 * nothing here reaches the user's browsing. It is measured and laid out once
 * by its creator, because an unmeasured WebView has no viewport and every
 * scroll and snapshot would be a no-op on a 0x0 page.
 */
class HeadlessToolExecutor(
    private val webView: WebView,
    private val searchEngineId: String,
    private val permissions: AiTaskPermissions,
    private val confirmActions: Boolean
) : ToolExecutor {

    private val lastStartedAt = AtomicLong(0L)
    private val lastFinishedAt = AtomicLong(0L)

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                lastStartedAt.set(SystemClock.elapsedRealtime())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lastFinishedAt.set(SystemClock.elapsedRealtime())
            }
        }
    }

    override suspend fun execute(name: String, argsJson: String): ToolResult =
        withContext(Dispatchers.Main) {
            val args: Map<String, Any?> = parseArgs(argsJson)
            refusal(name)?.let { return@withContext it }
            try {
                when (name) {
                    AgentTools.NAVIGATE -> navigate(str(args, "url"))
                    AgentTools.SEARCH_WEB -> searchWeb(str(args, "query"))
                    AgentTools.READ_PAGE -> readPage()
                    AgentTools.CLICK -> click(int(args, "ref"))
                    AgentTools.FILL_INPUT -> fillInput(int(args, "ref"), str(args, "text"))
                    AgentTools.PRESS_ENTER -> pressEnter(intOrNull(args, "ref"))
                    AgentTools.SCROLL -> scroll(str(args, "direction") ?: "down", intOrNull(args, "amount"))
                    AgentTools.GO_BACK -> goBack()
                    AgentTools.AUTO_LIKE -> autoLike()
                    AgentTools.AUTO_REPOST -> autoRepost()
                    AgentTools.AUTO_REPLY -> autoReply(str(args, "text"))
                    AgentTools.AUTO_POST -> autoPost(str(args, "text"))
                    AgentTools.WAIT -> waitTool(intOrNull(args, "ms"))
                    else -> ToolResult(false, "unknown tool: $name")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ToolResult(false, "${t.message ?: t.javaClass.simpleName}")
            }
        }

    /**
     * The refusals that come from the RUN rather than from the page: a tool the
     * task does not allow (whose message names the switch), a tool with no
     * meaning without tabs, and an action that would have to ask a user who is
     * not there. Checked before dispatch so no tool can be reached by a route
     * that forgot to ask.
     */
    private fun refusal(name: String): ToolResult? {
        permissions.refusal(name)?.let { return ToolResult(false, it) }
        if (name in TAB_TOOLS) {
            return ToolResult(
                false,
                "a scheduled run works in a single page and has no tabs, so '$name' is not " +
                    "available here — navigate to what you need and read_page it."
            )
        }
        if (confirmActions && name in AgentTools.INTERACTIVE_TOOLS) {
            return ToolResult(false, unattendedRefusal(name))
        }
        return null
    }

    // ------------------------------------------------------------- tools

    private suspend fun navigate(rawUrl: String?): ToolResult {
        val url = normalizeUrl(rawUrl)
            ?: return ToolResult(false, "missing or invalid 'url' argument")
        val triggerAt = SystemClock.elapsedRealtime()
        webView.loadUrl(url)
        val settled = awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        val landedUrl = webView.url?.takeIf { it.isNotBlank() } ?: url
        return if (settled) {
            ToolResult(
                true,
                "Navigated to $landedUrl — \"${webView.title.orEmpty()}\". Call read_page to inspect the content."
            )
        } else {
            ToolResult(
                false,
                "Navigation to $url is still loading (timeout). Call read_page to check what loaded."
            )
        }
    }

    private suspend fun searchWeb(rawQuery: String?): ToolResult {
        val query = nonBlank(rawQuery) ?: return ToolResult(false, "missing 'query' argument")
        val url = UrlIntelligence.classify(query, searchEngineId).second
        if (url.isBlank()) return ToolResult(false, "could not build a search URL for the query")
        return navigate(url)
    }

    private suspend fun readPage(): ToolResult {
        val formatted = formatSnapshot()
            ?: return ToolResult(
                false,
                "No readable page yet. It is on the start page, still loading, or JavaScript is " +
                    "disabled. Use navigate first and wait for it to finish."
            )
        return ToolResult(true, formatted)
    }

    private suspend fun click(ref: Int?): ToolResult {
        if (ref == null) return ToolResult(false, "missing 'ref' argument")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = evaluateJs(PageInjector.clickJs(ref))
            ?: return ToolResult(false, "click failed (JavaScript error or page still loading)")
        val outcome = unquote(jsResult)
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, outcome)
    }

    private suspend fun fillInput(ref: Int?, text: String?): ToolResult {
        if (ref == null || text == null) {
            return ToolResult(false, "missing 'ref' or 'text' argument")
        }
        val jsonText = AgentJson.encodeToString(String.serializer(), text)
        val jsResult = evaluateJs(PageInjector.fillJs(ref, jsonText))
            ?: return ToolResult(false, "fill failed (JavaScript error or page still loading)")
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun pressEnter(ref: Int?): ToolResult {
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = evaluateJs(PageInjector.enterJs(ref))
            ?: return ToolResult(false, "enter failed (JavaScript error)")
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, unquote(jsResult))
    }

    /**
     * The viewport comes from the PAGE, not from the view's current size: this
     * WebView is laid out once by its creator and never resized, and a scroll
     * computed from a zero height would report success while moving nothing.
     */
    private suspend fun scroll(direction: String?, amount: Int?): ToolResult {
        val percent = (amount ?: 80).coerceIn(10, 300)
        val viewport = evaluateJs("window.innerHeight")?.trim()?.toIntOrNull() ?: 0
        if (viewport <= 0) return ToolResult(false, "scroll failed (no page viewport yet)")
        val dy = (viewport * percent / 100) * (if (direction == "up") -1 else 1)
        val jsResult = evaluateJs(PageInjector.scrollJs(dy))
            ?: return ToolResult(false, "scroll failed")
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun goBack(): ToolResult {
        if (!webView.canGoBack()) return ToolResult(true, "already at the first page")
        val triggerAt = SystemClock.elapsedRealtime()
        webView.goBack()
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, "went back to ${webView.url.orEmpty()}")
    }

    private suspend fun autoLike(): ToolResult = socialAction { evaluateJs(PageInjector.autoLikeJs()) }

    private suspend fun autoRepost(): ToolResult = socialAction { evaluateJs(PageInjector.autoRepostJs()) }

    private suspend fun autoReply(text: String?): ToolResult {
        if (text == null) return ToolResult(false, "missing 'text' argument")
        return socialAction {
            val jsonText = AgentJson.encodeToString(String.serializer(), text)
            evaluateJs(PageInjector.autoReplyJs(jsonText))
        }
    }

    private suspend fun autoPost(text: String?): ToolResult {
        if (text == null) return ToolResult(false, "missing 'text' argument")
        return socialAction {
            val jsonText = AgentJson.encodeToString(String.serializer(), text)
            evaluateJs(PageInjector.autoPostJs(jsonText))
        }
    }

    private suspend fun socialAction(js: suspend () -> String?): ToolResult {
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = js() ?: return ToolResult(false, "the action failed (JavaScript error or page still loading)")
        awaitPageSettle(triggerAt)
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun waitTool(ms: Int?): ToolResult {
        val bounded: Long = (ms ?: 1500).coerceIn(200, 20_000).toLong()
        delay(bounded)
        return ToolResult(true, "waited ${formatDurationMs(bounded.toInt())}")
    }

    // ------------------------------------------------------------- helpers

    private suspend fun formatSnapshot(): String? {
        if (webView.progress < 100) {
            withTimeoutOrNull(4000) {
                while (webView.progress < 100) delay(POLL_MS)
            }
        }
        val raw = evaluateJs(PageInjector.snapshotJs()) ?: return null
        if (raw.isBlank() || raw == "null" || raw == "undefined") return null
        val snapshot = runCatching {
            AgentJson.decodeFromString(PageSnapshotDto.serializer(), raw)
        }.getOrNull() ?: return null
        return PageSnapshotFormatter.format(snapshot)
    }

    /** Evaluates JS on the headless WebView, suspending until the callback fires. */
    private suspend fun evaluateJs(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                webView.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** The engine returns string results JSON-encoded — undo that. */
    private fun unquote(jsResult: String): String = runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            AgentJson.decodeFromString(String.serializer(), jsResult)
        } else jsResult
    }.getOrDefault(jsResult)

    /**
     * Waits for a navigation that started at/after [triggerAt] to finish, from
     * THIS WebView's own page events. A JS-only action starts no navigation and
     * resolves after the start window passes with none.
     */
    private suspend fun awaitPageSettle(triggerAt: Long): Boolean {
        val navigationStarted = withTimeoutOrNull(NAV_START_WINDOW_MS) {
            while (lastStartedAt.get() < triggerAt) delay(POLL_MS)
            true
        } ?: false
        if (!navigationStarted) return true
        return withTimeoutOrNull(NAV_FINISH_TIMEOUT_MS) {
            while (lastFinishedAt.get() < triggerAt) delay(POLL_MS)
            true
        } ?: false
    }

    private fun normalizeUrl(raw: String?): String? {
        val input = nonBlank(raw)?.trim() ?: return null
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.startsWith("about:") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> null
        }
    }

    private fun nonBlank(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    private fun parseArgs(argsJson: String): Map<String, Any?> = runCatching {
        if (argsJson.isBlank()) return emptyMap()
        val element = AgentJson.parseToJsonElement(argsJson)
        if (element !is JsonObject) return emptyMap()
        element.entries.associate { (key, value) ->
            val v: Any? = when (value) {
                is JsonPrimitive -> value.intOrNull ?: value.booleanOrNull ?: value.contentOrNull
                else -> value.toString()
            }
            key to v
        }
    }.getOrDefault(emptyMap())

    private fun str(args: Map<String, Any?>, key: String): String? =
        (args[key] as? String)?.takeIf { it.isNotBlank() }

    private fun int(args: Map<String, Any?>, key: String): Int? = intOrNull(args, key)

    private fun intOrNull(args: Map<String, Any?>, key: String): Int? =
        (args[key] as? Number)?.toInt() ?: (args[key] as? String)?.toIntOrNull()

    companion object {
        private const val SETTLE_MS = 350L
        private const val NAV_START_WINDOW_MS = 1800L
        private const val NAV_FINISH_TIMEOUT_MS = 25_000L
        private const val POLL_MS = 150L

        /**
         * The tools a headless run performs, declared rather than inferred so
         * [HeadlessToolCatalogTest] can hold the WHOLE catalogue to
         * [SUPPORTED_TOOLS] ∪ [TAB_TOOLS]: a tool added to the catalogue later
         * must be classified deliberately, because the wrong answer is either a
         * tool the model is offered and refused, or one refused silently.
         */
        internal val SUPPORTED_TOOLS: Set<String> = setOf(
            AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.READ_PAGE,
            AgentTools.CLICK, AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER,
            AgentTools.SCROLL, AgentTools.GO_BACK, AgentTools.AUTO_LIKE,
            AgentTools.AUTO_REPOST, AgentTools.AUTO_REPLY, AgentTools.AUTO_POST,
            AgentTools.WAIT
        )

        /**
         * Tools about a set of tabs. A scheduled run has one page, so these
         * cannot mean anything here.
         */
        internal val TAB_TOOLS: Set<String> = setOf(
            AgentTools.OPEN_NEW_TAB,
            AgentTools.LIST_TABS,
            AgentTools.SWITCH_TAB,
            AgentTools.CLOSE_TAB
        )

        /**
         * Give a headless WebView the device's viewport. Without a measure and
         * a layout pass the page is 0x0, `window.innerHeight` is 0 and every
         * scroll is a no-op that still reports success.
         */
        fun measureForHeadlessUse(webView: WebView, widthPx: Int, heightPx: Int) {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, widthPx, heightPx)
        }
    }
}
