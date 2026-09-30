package com.roombrowser.agent

import android.os.SystemClock
import android.webkit.WebView
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.PageEvent
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.PageSnapshotDto
import com.roombrowser.domain.agent.PageSnapshotFormatter
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.engine.UrlIntelligence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.resume

/**
 * Executes the agent's browser tools against the live engine state held by
 * [BrowserViewModel]: navigation (with page-finished waiting), page
 * snapshots via [PageInjector], element interaction, tab management and
 * web search.
 *
 * All WebView access happens on the main dispatcher; navigation results are
 * awaited by polling the ViewModel's page events (race-free by design).
 */
class AgentToolExecutor(
    private val vm: BrowserViewModel,
    private val confirmGate: suspend (name: String, label: String) -> ActionVerdict
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult =
        withContext(Dispatchers.Main) {
            val args: Map<String, Any?> = parseArgs(argsJson)
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
                    AgentTools.OPEN_NEW_TAB -> openNewTab(strOrNull(args, "url"))
                    AgentTools.LIST_TABS -> listTabs()
                    AgentTools.SWITCH_TAB -> switchTab(int(args, "index"))
                    AgentTools.CLOSE_TAB -> closeTab()
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

    /** Snapshot text used for the "include current page" context feature. */
    suspend fun snapshotContext(): String? = withContext(Dispatchers.Main) {
        formatSnapshot()?.let { "Current page:\n$it" }
    }

    // ------------------------------------------------------------- tools

    private suspend fun navigate(rawUrl: String?): ToolResult {
        val url = normalizeUrl(rawUrl)
            ?: return ToolResult(false, "missing or invalid 'url' argument")
        val triggerAt = SystemClock.elapsedRealtime()
        vm.loadUrl(url)
        val settled = awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return if (settled) {
            ToolResult(
                true,
                "Navigated to ${vm.pageState.url.ifBlank { url }} — \"${vm.pageState.title}\". Call read_page to inspect the content."
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
        val engineId = vm.profileSettings().searchEngineId
        val url = UrlIntelligence.classify(query, engineId).second
        if (url.isBlank()) return ToolResult(false, "could not build a search URL for the query")
        return navigate(url)
    }

    private suspend fun readPage(): ToolResult {
        val formatted = formatSnapshot()
            ?: return ToolResult(
                false,
                "No readable page. The browser is on the start page, still loading, or JavaScript is disabled. Use navigate first and wait for it to finish."
            )
        return ToolResult(true, formatted)
    }

    private suspend fun click(ref: Int?): ToolResult {
        if (ref == null) return ToolResult(false, "missing 'ref' argument")
        refuse(AgentTools.CLICK, AgentTools.describeTool(AgentTools.CLICK, "{\"ref\":$ref}"))?.let { return it }
        val webView = currentWebView() ?: return ToolResult(false, "no page is loaded")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = evaluateJs(webView, PageInjector.clickJs(ref))
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
        refuse(AgentTools.FILL_INPUT, "type into [$ref]")?.let { return it }
        val webView = currentWebView() ?: return ToolResult(false, "no page is loaded")
        val jsonText = AgentJson.encodeToString(String.serializer(), text)
        val jsResult = evaluateJs(webView, PageInjector.fillJs(ref, jsonText))
            ?: return ToolResult(false, "fill failed (JavaScript error or page still loading)")
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun pressEnter(ref: Int?): ToolResult {
        refuse(AgentTools.PRESS_ENTER, "press Enter / submit")?.let { return it }
        val webView = currentWebView() ?: return ToolResult(false, "no page is loaded")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = evaluateJs(webView, PageInjector.enterJs(ref))
            ?: return ToolResult(false, "enter failed (JavaScript error)")
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun scroll(direction: String?, amount: Int?): ToolResult {
        val webView = currentWebView() ?: return ToolResult(false, "no page is loaded")
        val percent = (amount ?: 80).coerceIn(10, 300)
        val dy = (webView.height * percent / 100) * (if (direction == "up") -1 else 1)
        val jsResult = evaluateJs(webView, PageInjector.scrollJs(dy))
            ?: return ToolResult(false, "scroll failed")
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun goBack(): ToolResult {
        val webView = currentWebView() ?: return ToolResult(false, "no page is loaded")
        if (!webView.canGoBack()) return ToolResult(true, "already at the first page in this tab")
        val triggerAt = SystemClock.elapsedRealtime()
        webView.goBack()
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, "went back to ${vm.pageState.url}")
    }

    private suspend fun openNewTab(url: String?): ToolResult {
        val target = url?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) } ?: "about:home"
        vm.openNewTab(target)
        delay(SETTLE_MS)
        return ToolResult(true, "opened a new tab at ${vm.pageState.url.ifBlank { target }}")
    }

    private fun listTabs(): ToolResult {
        if (vm.tabs.isEmpty()) return ToolResult(true, "no open tabs")
        val lines = vm.tabs.mapIndexed { index, tab ->
            val current = if (tab.id == vm.activeTabId) " (current)" else ""
            "[$index] ${tab.title.ifBlank { tab.url }} — ${tab.url}$current"
        }
        return ToolResult(true, "Open tabs:\n" + lines.joinToString("\n"))
    }

    private suspend fun switchTab(index: Int?): ToolResult {
        if (index == null) return ToolResult(false, "missing 'index' argument")
        val tab = vm.tabs.getOrNull(index)
            ?: return ToolResult(false, "no tab with index $index — call list_tabs for current indices")
        vm.selectTab(tab.id)
        awaitPageSettle(SystemClock.elapsedRealtime() - 1500)
        return ToolResult(true, "switched to tab [$index]: ${tab.title.ifBlank { tab.url }}")
    }

    private suspend fun closeTab(): ToolResult {
        val id = vm.activeTabId ?: return ToolResult(false, "no open tab")
        vm.closeTab(id)
        return ToolResult(true, "closed the current tab")
    }

    // ------------------------------------------------------------- social automation

    private suspend fun autoLike(): ToolResult = socialAction(
        AgentTools.AUTO_LIKE, "like the visible posts"
    ) { webView -> evaluateJs(webView, PageInjector.autoLikeJs()) }

    private suspend fun autoRepost(): ToolResult = socialAction(
        AgentTools.AUTO_REPOST, "repost the visible posts"
    ) { webView -> evaluateJs(webView, PageInjector.autoRepostJs()) }

    private suspend fun autoReply(text: String?): ToolResult {
        if (text == null) return ToolResult(false, "missing 'text' argument")
        return socialAction(
            AgentTools.AUTO_REPLY, "reply with \"" + text.replace('\n', ' ').take(40) + "\"", 900L
        ) { webView ->
            val jsonText = AgentJson.encodeToString(String.serializer(), text)
            evaluateJs(webView, PageInjector.autoReplyJs(jsonText))
        }
    }

    private suspend fun autoPost(text: String?): ToolResult {
        if (text == null) return ToolResult(false, "missing 'text' argument")
        return socialAction(
            AgentTools.AUTO_POST, "post \"" + text.replace('\n', ' ').take(40) + "\"", 2400L
        ) { webView ->
            val jsonText = AgentJson.encodeToString(String.serializer(), text)
            evaluateJs(webView, PageInjector.autoPostJs(jsonText))
        }
    }

    /** Runs one heuristic social action: gate → JS → settle → result. */
    private suspend fun socialAction(
        name: String,
        label: String,
        settleMs: Long = 0L,
        js: suspend (WebView) -> String?
    ): ToolResult {
        refuse(name, label)?.let { return it }
        val webView = currentWebView()
            ?: return ToolResult(false, "no page is loaded — navigate to the site first")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = js(webView)
            ?: return ToolResult(false, "$name failed (JavaScript error or page still loading)")
        awaitPageSettle(triggerAt)
        if (settleMs > 0) delay(settleMs)
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun waitTool(ms: Int?): ToolResult {
        val bounded: Long = (ms ?: 1500).coerceIn(200, 20_000).toLong()
        delay(bounded)
        return ToolResult(true, "waited ${bounded}ms")
    }

    // ------------------------------------------------------------- helpers

    /**
     * Asks the gate about one action, returning the refusal to hand back to
     * the model — or null when the action may run.
     *
     * The reason travels: a refusal the model cannot read is a refusal it
     * repeats. "the user denied this action" tells a model to stop;
     * "judged outside what you asked for" tells it to try something else,
     * which is what a policy denial is usually for.
     */
    private suspend fun refuse(name: String, label: String): ToolResult? =
        when (val verdict = confirmGate(name, label)) {
            is ActionVerdict.Allow -> null
            is ActionVerdict.Deny -> ToolResult(false, verdict.reason)
            // Ask is resolved by the caller (it owns the approval UI), so a
            // verdict that reaches here is a bug — deny rather than run.
            is ActionVerdict.Ask -> ToolResult(false, "the user denied this action")
        }

    private fun currentWebView(): WebView? {
        val webView = vm.activeWebView
        if (webView == null || vm.pageState.isHomepage) return null
        return webView
    }

    private suspend fun formatSnapshot(): String? {
        val webView = currentWebView() ?: return null
        if (vm.pageState.loading) {
            // Give a still-loading page a short grace period before snapshotting.
            withTimeoutOrNull(4000) {
                while (vm.pageState.loading) delay(150)
            }
        }
        val raw = evaluateJs(webView, PageInjector.snapshotJs()) ?: return null
        if (raw.isBlank() || raw == "null" || raw == "undefined") return null
        val snapshot = runCatching {
            AgentJson.decodeFromString(PageSnapshotDto.serializer(), raw)
        }.getOrNull() ?: return null
        return PageSnapshotFormatter.format(snapshot)
    }

    /** Evaluates JS on the WebView, suspending until the callback fires. */
    private suspend fun evaluateJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                webView.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** evaluateJavascript returns string results JSON-encoded — undo that. */
    private fun unquote(jsResult: String): String = runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            AgentJson.decodeFromString(String.serializer(), jsResult)
        } else jsResult
    }.getOrDefault(jsResult)

    /**
     * Waits for a navigation that started at/after [triggerAt] to finish.
     * Pure-JS actions (no navigation) resolve immediately after a short
     * window with no page start event.
     */
    private suspend fun awaitPageSettle(triggerAt: Long): Boolean {
        val navigationStarted = pollUntil(NAV_START_WINDOW_MS) { event ->
            event is PageEvent.Started && event.at >= triggerAt
        }
        if (!navigationStarted) return true // JS-only action, no navigation
        return pollUntil(NAV_FINISH_TIMEOUT_MS) { event ->
            event is PageEvent.Finished && event.at >= triggerAt
        }
    }

    private suspend fun pollUntil(timeoutMs: Long, predicate: (PageEvent?) -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(vm.lastPageEvent)) return true
            if (!kotlin.coroutines.coroutineContext.isActive) return false
            delay(120)
        }
        return predicate(vm.lastPageEvent)
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

    private fun strOrNull(args: Map<String, Any?>, key: String): String? = args[key] as? String

    private fun int(args: Map<String, Any?>, key: String): Int? = intOrNull(args, key)

    private fun intOrNull(args: Map<String, Any?>, key: String): Int? =
        (args[key] as? Number)?.toInt() ?: (args[key] as? String)?.toIntOrNull()

    companion object {
        private const val SETTLE_MS = 350L
        private const val NAV_START_WINDOW_MS = 1800L
        private const val NAV_FINISH_TIMEOUT_MS = 25_000L
    }
}
