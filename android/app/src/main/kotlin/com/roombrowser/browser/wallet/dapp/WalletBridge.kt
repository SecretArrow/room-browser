package com.roombrowser.browser.wallet.dapp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.roombrowser.browser.wallet.DappOutcome
import com.roombrowser.browser.wallet.WalletBridgeError
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * NATIVE half of the dApp wallet bridge, exposed to page JS as
 * `window.RoomWallet` (see [RoomWalletScript] for the injected half and
 * [WalletBridgeProtocol] for the codec). One bridge per WebView, built by
 * the integrator alongside the document-start script install.
 *
 * PROTOCOL (what page JS can call — nothing else is exported):
 *  - `RoomWallet.request(json)` — one bridge call. Returns "" immediately;
 *    the answer is pushed asynchronously with
 *    `evaluateJavascript("window.__roomWalletResponse(id, resultJson,
 *    errorCode, errorMessage)")` once the engine settles it (or the RPC
 *    relay completes).
 *
 * SECURITY MODEL — the bridge never trusts the page (mirrors
 * RoomVaultBridge):
 *  1. Only [request] is reachable from JS, and every payload is parsed by
 *     the codec into typed calls or typed errors — never an exception.
 *  2. Every call hops to the MAIN thread first, then the WebView's OWN
 *     current URL supplies the host and origin for the contract
 *     [DappRequest] — a page's claimed origin is metadata only and params
 *     never carry a trusted domain. Solana/Aptos/Sui/Tron Connect calls
 *     carry no origin claim at all: the verified host IS the host.
 *  3. Anti-flood: a 300ms minimum gap per (host, method) and at most 8
 *     concurrent pending calls per host — the OLDEST is auto-rejected with
 *     4001 when a 9th arrives, so a hostile page cannot pile prompts onto
 *     the confirmation queue.
 *  4. Nothing is ever logged.
 *
 * ROUTING:
 *  - kind "rpc" (read-only EVM calls) is relayed to the chain's ACTIVE
 *    network RPC via [JsonRpcClient] (8s timeouts, Dispatchers.IO inside
 *    the client) — no prompt is needed for reads; offline maps to 4901
 *    CHAIN_DISCONNECTED. The engine is not involved.
 *  - kind "request" is answered WITHOUT a prompt whenever it can be:
 *    already-permitted Connects auto-approve silently
 *    ([WalletEngineApi.isDappPermitted] with `eth_requestAccounts` for EVM,
 *    `connect` for the other families) and `eth_accounts` returns the
 *    permitted address list (empty while not permitted — never a prompt).
 *    Everything else becomes a contract [DappRequest] (UUID id) submitted
 *    to the engine queue; the outcome settles the page promise.
 *  - A null [engineProvider] (engine not bound) answers 4900 DISCONNECTED
 *    for wallet-level calls; the RPC relay works regardless.
 *
 * THREADING: `@JavascriptInterface` arrives on WebView's JavaBridge thread;
 * WebView state is main-thread only, so every call hops inside [main].
 * The engine settles outcomes on the main thread (contract), and [finish]
 * re-checks the looper defensively. All bookkeeping maps are confined to
 * the main thread.
 *
 * LIFETIME: the WebView holds the interface object strongly, so the
 * interface holds the WebView only through the [webViewRef]
 * [WeakReference] — a destroyed engine releases its bridge.
 */
class WalletBridge(
    private val engineProvider: () -> WalletEngineApi?,
    private val activeNetworkProvider: (ChainType) -> NetworkConfig?,
    private val webViewRef: WeakReference<WebView>
) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Relay coroutines. SupervisorJob so one failed relay cannot cancel
     * siblings; Main dispatcher because respond* must touch main-confined
     * state (the IO hop happens inside JsonRpcClient).
     */
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The bridge's own relay client (pure-JVM, okhttp-based). */
    private val rpc = JsonRpcClient(
        OkHttpClient.Builder()
            .connectTimeout(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    )

    /**
     * Main-thread-confined pending state. [pendingById] tracks every call
     * until its response is delivered; [pageIdByDappId] maps engine request
     * ids back to page ids; [pendingByHost] enforces the per-host cap;
     * [lastDispatchAt] enforces the per-(host, method) gap.
     */
    private class Pending(val host: String) {
        var dappRequestId: String? = null
    }

    private val pendingById = LinkedHashMap<String, Pending>()
    private val pageIdByDappId = HashMap<String, String>()
    private val pendingByHost = HashMap<String, ArrayDeque<String>>()
    private val lastDispatchAt = HashMap<String, Long>()

    /**
     * The single page-reachable entry point. Always returns "" — answers
     * are asynchronous (the async-response pattern; a synchronous return
     * value would be evaluated on the JavaBridge thread and is unreliable).
     */
    @JavascriptInterface
    fun request(payload: String): String {
        main.post { onMain(payload) }
        return ""
    }

    /**
     * Pushes `accountsChanged` / `chainChanged` to the page's providers
     * (`window.__roomWalletEmit(event, payloadJson)`). Public for the
     * integrator: call it after a switch or account change settles, with a
     * raw JSON value payload (`["0x…"]` / `"0x…"`).
     */
    fun emitEvent(event: String, payloadJson: String) {
        runOnMain {
            val script = WalletBridgeProtocol.encodeEmitScript(event, payloadJson)
            webViewRef.get()?.let { view -> runCatching { view.evaluateJavascript(script, null) } }
        }
    }

    // ------------------------------------------------------------------
    // Main-thread pipeline
    // ------------------------------------------------------------------

    private fun onMain(payload: String) {
        val view = webViewRef.get() ?: return
        when (val parsed = WalletBridgeProtocol.parseRequest(payload)) {
            is BridgeParseResult.Invalid -> respondError(parsed.pageId, parsed.error)
            is BridgeParseResult.Ok -> dispatch(view, parsed.call)
        }
    }

    private fun dispatch(view: WebView, call: BridgeCall) {
        // The WebView's own URL is the ONLY host/origin source — never a
        // page claim, never a param.
        val url = view.url ?: run {
            respondError(call.id, WalletBridgeError(WalletBridgeError.DISCONNECTED, "No page loaded"))
            return
        }
        val host = UrlIntelligence.hostOf(url) ?: run {
            respondError(call.id, WalletBridgeError(WalletBridgeError.DISCONNECTED, "No host for current page"))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastDispatchAt.size > THROTTLE_TABLE_LIMIT) lastDispatchAt.clear()
        val throttleKey = host + '\n' + call.method
        val last = lastDispatchAt[throttleKey] ?: 0L
        if (now - last < MIN_METHOD_GAP_MS) {
            respondError(
                call.id,
                WalletBridgeError(WalletBridgeProtocol.CODE_RATE_LIMITED, "Too many requests, retry shortly")
            )
            return
        }
        lastDispatchAt[throttleKey] = now

        // Cap concurrent work per host: reject the OLDEST pending call.
        val queue = pendingByHost.getOrPut(host) { ArrayDeque() }
        while (queue.size >= MAX_PENDING_PER_HOST) {
            val oldest = queue.pollFirst() ?: break
            pendingById.remove(oldest)
            respondError(
                oldest,
                WalletBridgeError(WalletBridgeError.USER_REJECTED, "Too many pending requests")
            )
        }
        queue.addLast(call.id)
        pendingById[call.id] = Pending(host)

        when (call) {
            is WalletRpcCall -> relay(call)
            is WalletDappCall -> handleDappCall(call, host, url)
        }
    }

    /**
     * Read-only relay to the chain's active network. Needs only a
     * NetworkConfig + method + params — the engine is not involved, so it
     * works even while no engine is bound.
     */
    private fun relay(call: WalletRpcCall) {
        if (call.chainType != ChainType.EVM || !WalletBridgeProtocol.isReadonlyRpcMethod(call.method)) {
            respondError(
                call.id,
                WalletBridgeError(WalletBridgeError.UNSUPPORTED_METHOD, "Read-only relay supports known EVM methods only")
            )
            return
        }
        val params = WalletBridgeProtocol.rpcParamsList(call) ?: run {
            respondError(call.id, WalletBridgeError(WalletBridgeError.INVALID_PARAMS, "Relay params must be an array"))
            return
        }
        val network = activeNetworkProvider(ChainType.EVM)
        val endpoint = network?.rpcUrls?.firstOrNull { it.isNotBlank() }
        if (endpoint == null) {
            respondError(call.id, WalletBridgeError(WalletBridgeError.CHAIN_DISCONNECTED, "No active EVM network"))
            return
        }
        relayScope.launch {
            try {
                val result = rpc.call(endpoint, call.method, params)
                respondSuccess(call.id, result.toString())
            } catch (e: WalletException) {
                respondError(call.id, WalletBridgeProtocol.relayError(e))
            } catch (e: Exception) {
                respondError(call.id, WalletBridgeError(WalletBridgeError.INTERNAL, "RPC relay failed"))
            }
        }
    }

    private fun handleDappCall(call: WalletDappCall, host: String, originUrl: String) {
        val engine = engineProvider()
        if (engine == null) {
            respondError(call.id, WalletBridgeError(WalletBridgeError.DISCONNECTED, "Wallet is not available"))
            return
        }

        // eth_accounts: the permitted address list, never a prompt.
        if (call.method == WalletBridgeProtocol.METHOD_ETH_ACCOUNTS) {
            val addresses = engine.accounts.value
                .filter { it.chainType == ChainType.EVM && engine.isDappPermitted(host, ChainType.EVM, it.address, call.method) }
                .map { it.address }
            respondSuccess(call.id, WalletBridgeProtocol.accountsResult(addresses))
            return
        }

        val primary = engine.accounts.value.firstOrNull { it.chainType == call.chainType }

        // Already-permitted connects auto-approve silently.
        if (call.method in WalletBridgeProtocol.CONNECT_METHODS && primary != null &&
            engine.isDappPermitted(
                host, call.chainType, primary.address,
                WalletBridgeProtocol.permissionMethodFor(call.chainType)
            )
        ) {
            respondSuccess(call.id, WalletBridgeProtocol.connectSuccessResult(call.chainType, primary.address))
            return
        }

        val dappId = UUID.randomUUID().toString()
        val networkId = activeNetworkProvider(call.chainType)?.id
        when (val built = WalletBridgeProtocol.buildDappRequest(call, dappId, host, originUrl, networkId, primary?.address)) {
            is DappBuildResult.Invalid -> respondError(call.id, built.error)
            is DappBuildResult.Ok -> {
                pendingById[call.id]?.dappRequestId = dappId
                pageIdByDappId[dappId] = call.id
                engine.submitDappRequest(built.request) { outcome -> onOutcome(outcome) }
            }
        }
    }

    private fun onOutcome(outcome: DappOutcome) {
        runOnMain {
            val pageId = pageIdByDappId.remove(outcome.requestId) ?: return@runOnMain
            val error = outcome.error
            if (error != null) {
                respondError(pageId, error)
            } else {
                respondSuccess(pageId, outcome.resultJson)
            }
        }
    }

    // ------------------------------------------------------------------
    // Response delivery
    // ------------------------------------------------------------------

    private fun respondSuccess(pageId: String, resultJson: String?) {
        finish(pageId, WalletBridgeProtocol.encodeResponseScript(pageId, resultJson, 0, null))
    }

    private fun respondError(pageId: String?, error: WalletBridgeError) {
        if (pageId == null) return
        finish(pageId, WalletBridgeProtocol.encodeResponseScript(pageId, null, error.code, error.message))
    }

    /** Delivers one script and retires the pending call (idempotent). */
    private fun finish(pageId: String, script: String) {
        runOnMain {
            removePending(pageId)
            webViewRef.get()?.let { view -> runCatching { view.evaluateJavascript(script, null) } }
        }
    }

    private fun removePending(pageId: String) {
        val entry = pendingById.remove(pageId) ?: return
        entry.dappRequestId?.let { pageIdByDappId.remove(it) }
        val queue = pendingByHost[entry.host]
        if (queue != null) {
            queue.remove(pageId)
            if (queue.isEmpty()) pendingByHost.remove(entry.host)
        }
    }

    /** Runs [action] on the main thread (immediate when already there). */
    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post { action() }
        }
    }

    companion object {
        /** JS object name the injected script (and, defensively, pages) see. */
        const val JS_INTERFACE_NAME = "RoomWallet"

        /** Minimum gap between calls of the same (host, method). */
        private const val MIN_METHOD_GAP_MS = 300L

        /** Concurrent pending calls allowed per host (oldest auto-rejected beyond it). */
        private const val MAX_PENDING_PER_HOST = 8

        /** Hard cap on throttle bookkeeping before it is reset (hostile-method growth). */
        private const val THROTTLE_TABLE_LIMIT = 256

        /** Relay timeout (connect/read/write) per call. */
        private const val RPC_TIMEOUT_SECONDS = 8L
    }
}
