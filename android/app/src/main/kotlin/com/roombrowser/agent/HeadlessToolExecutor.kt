package com.roombrowser.agent

import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.KeyChord
import com.roombrowser.domain.agent.PageSnapshotDto
import com.roombrowser.domain.agent.PageSnapshotFormatter
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.formatDurationMs
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.unattendedRefusal
import com.roombrowser.domain.task.walletUnattendedRefusal
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
 * Executes the agent's browser tools on ONE headless [WebView].
 *
 * Same tool semantics and same result wording as [AgentToolExecutor], because
 * the model reading them is the same model. What differs is only what a
 * single hidden page cannot do, and each of those answers says so rather than
 * pretending:
 *
 *  - there are no TABS (one page, one WebView), so the four tab tools are
 *    refused;
 *  - an action that would have to ask the user is refused, and the refusal says
 *    which switch allows it — unless the caller supplied [askInteractive],
 *    which is how a HEADLESS CHAT reaches the person watching it. A scheduled
 *    run passes nothing, and the refusal stands.
 *
 * The WebView belongs to its caller and is never attached to the view tree, so
 * nothing here reaches the user's browsing. It is measured and laid out once
 * by its creator, because an unmeasured WebView has no viewport and every
 * scroll and snapshot would be a no-op on a 0x0 page.
 */
class HeadlessToolExecutor(
    private val webView: WebView,
    private val searchEngineId: String,
    private val permissions: AiTaskPermissions,
    private val confirmActions: Boolean,
    /**
     * Asks the person watching whether one interactive action may run. Null —
     * the default, and every scheduled run — keeps the refusal, because an
     * approval that cannot be given must not be invented.
     */
    private val askInteractive: (suspend (name: String, label: String) -> ActionVerdict)? = null
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
            refusal(name, argsJson)?.let { return@withContext it }
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
                    AgentTools.RUN_JS -> runJs(str(args, "script"))
                    AgentTools.SELECT_OPTION -> selectOption(int(args, "ref"), str(args, "value"))
                    AgentTools.PRESS_KEYS -> pressKeys(str(args, "keys"), intOrNull(args, "ref"))
                    AgentTools.WAIT_FOR -> waitFor(str(args, "text"), intOrNull(args, "timeout_ms"))
                    else -> ToolResult(false, "unknown tool: $name")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ToolResult(false, "${t.message ?: t.javaClass.simpleName}")
            }
        }

    /**
     * The refusals that come from the RUN rather than from the page: a wallet
     * tool (never available without a person), a tool the run does not allow
     * (whose message names the switch), a tool with no meaning without tabs, and
     * an action that would have to ask the user — which becomes a real question
     * when the caller supplied [askInteractive], and the refusal above when it
     * did not. Checked before dispatch so no tool can be reached by a route that
     * forgot to ask.
     */
    private suspend fun refusal(name: String, argsJson: String): ToolResult? {
        if (name in AgentTools.WALLET_TOOLS) return ToolResult(false, walletUnattendedRefusal(name))
        permissions.refusal(name)?.let { return ToolResult(false, it) }
        if (name in TAB_TOOLS) {
            return ToolResult(
                false,
                "a scheduled run works in a single page and has no tabs, so '$name' is not " +
                    "available here — navigate to what you need and read_page it."
            )
        }
        if (confirmActions && name in AgentTools.INTERACTIVE_TOOLS) {
            val ask = askInteractive ?: return ToolResult(false, unattendedRefusal(name))
            return when (val verdict = ask(name, AgentTools.describeTool(name, argsJson))) {
                is ActionVerdict.Allow -> null
                is ActionVerdict.Deny -> ToolResult(false, verdict.reason)
                // Asking is the caller's job, so a verdict that reaches here is
                // a bug — deny rather than run.
                is ActionVerdict.Ask -> ToolResult(false, "the user denied this action")
            }
        }
        return null
    }

    /** The page's text, for the chat's "include the current page" context. */
    suspend fun snapshotContext(): String? = withContext(Dispatchers.Main) {
        formatSnapshot()?.let { "Current page:\n$it" }
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

    // ------------------------------------------------- direct page control

    private suspend fun runJs(script: String?): ToolResult {
        val code = nonBlank(script) ?: return ToolResult(false, "missing 'script' argument")
        val raw = evaluateJs(PageInjector.runJs(jsonString(code)))
            ?: return ToolResult(false, "run_js failed (JavaScript error or page still loading)")
        return ToolResult(true, clip(unquote(raw)))
    }

    private suspend fun selectOption(ref: Int?, value: String?): ToolResult {
        if (ref == null || value == null) {
            return ToolResult(false, "missing 'ref' or 'value' argument")
        }
        val raw = evaluateJs(PageInjector.selectOptionJs(ref, jsonString(value)))
            ?: return ToolResult(false, "select_option failed (JavaScript error or page still loading)")
        return ToolResult(true, unquote(raw))
    }

    private suspend fun pressKeys(keys: String?, ref: Int?): ToolResult {
        val chord = KeyChord.parse(keys) ?: return ToolResult(
            false,
            "unsupported key chord '${keys.orEmpty()}'. Supported keys: ${KeyChord.SUPPORTED_KEYS}"
        )
        val script = PageInjector.pressKeysJs(
            ref, jsonString(chord.key), jsonString(chord.code), chord.keyCode,
            chord.ctrl, chord.shift, chord.alt, chord.meta, jsonString(chord.label())
        )
        val raw = evaluateJs(script)
            ?: return ToolResult(false, "press_keys failed (JavaScript error or page still loading)")
        return ToolResult(true, unquote(raw))
    }

    private suspend fun waitFor(text: String?, timeoutMs: Int?): ToolResult {
        val needle = nonBlank(text) ?: return ToolResult(false, "missing 'text' argument")
        val timeout = (timeoutMs ?: DEFAULT_WAIT_FOR_MS).coerceIn(500, 30_000).toLong()
        val probe = PageInjector.waitProbeJs(jsonString(needle))
        val startedAt = SystemClock.elapsedRealtime()
        val found = withTimeoutOrNull(timeout) {
            var hit = false
            while (!hit) {
                hit = unquote(evaluateJs(probe).orEmpty()).trim() == "1"
                if (!hit) delay(POLL_MS)
            }
            true
        } ?: false
        val waited = SystemClock.elapsedRealtime() - startedAt
        return if (found) {
            ToolResult(true, "\"$needle\" appeared after ${formatDurationMs(waited.toInt())}")
        } else {
            ToolResult(
                false,
                "\"$needle\" did not appear within ${formatDurationMs(waited.toInt())} — the page may " +
                    "load it later, show it only after a click, or never show it at all."
            )
        }
    }

    // ------------------------------------------------------------- helpers

    private fun jsonString(value: String): String =
        AgentJson.encodeToString(String.serializer(), value)

    /** A script's own output is not a place to spend the context window. */
    private fun clip(text: String): String =
        if (text.length <= MAX_JS_RESULT_CHARS) {
            text
        } else {
            text.take(MAX_JS_RESULT_CHARS) + "\n…[truncated at $MAX_JS_RESULT_CHARS characters]"
        }

    private suspend fun formatSnapshot(): String? {        if (webView.progress < 100) {
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
        private const val DEFAULT_WAIT_FOR_MS = 10_000
        private const val MAX_JS_RESULT_CHARS = 4000

        /**
         * The tools a headless run performs, declared rather than inferred so
         * [HeadlessToolCatalogTest] can hold the WHOLE catalogue to
         * [SUPPORTED_TOOLS] ∪ [TAB_TOOLS] ∪ [APP_TOOLS] ∪
         * [AgentTools.WALLET_TOOLS]: a tool added to the catalogue later
         * must be classified deliberately, because the wrong answer is either a
         * tool the model is offered and refused, or one refused silently.
         */
        internal val SUPPORTED_TOOLS: Set<String> = setOf(
            AgentTools.NAVIGATE, AgentTools.SEARCH_WEB, AgentTools.READ_PAGE,
            AgentTools.CLICK, AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER,
            AgentTools.SCROLL, AgentTools.GO_BACK, AgentTools.AUTO_LIKE,
            AgentTools.AUTO_REPOST, AgentTools.AUTO_REPLY, AgentTools.AUTO_POST,
            AgentTools.WAIT,
            AgentTools.RUN_JS, AgentTools.SELECT_OPTION, AgentTools.PRESS_KEYS,
            AgentTools.WAIT_FOR
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
         * The tools that act on the BROWSER rather than on the page — its
         * screens, tabs, saved data, settings, shields and permissions. A
         * scheduled run has one headless page and nobody to ask, so none of
         * them can mean anything here: there is no screen to open, no second
         * tab, and an action that changes the user's browser is not something
         * a run may do while they are asleep.
         *
         * Declared rather than inferred so [HeadlessToolCatalogTest] can hold
         * the whole catalogue to these sets. The refusal itself comes from
         * [AiTaskPermissions], which denies the APP group outright — so there
         * is one message for it, not two.
         */
        internal val APP_TOOLS: Set<String> = AgentAppActions.TOOLS

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
