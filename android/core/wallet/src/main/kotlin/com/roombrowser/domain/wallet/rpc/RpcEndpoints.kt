package com.roombrowser.domain.wallet.rpc

import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A network's RPC endpoints, tried in order.
 *
 * ## Why this exists
 *
 * Every adapter used to take `network.rpcUrls.firstOrNull()` and nothing else.
 * The second url in a network's list was therefore decoration: the bundled
 * snapshot lists two endpoints per network precisely because public RPC hosts
 * die, rate-limit and mis-configure their TLS — and none of that mattered,
 * because only the first was ever called. A network whose primary endpoint had
 * gone bad was simply unusable, with a working endpoint sitting next to it in
 * the same list.
 *
 * That is the root cause behind a whole family of "the wallet cannot connect"
 * reports, and it is not fixable by curating the list: any list of free
 * endpoints rots. Failover is what makes the list mean something.
 *
 * ## What "failed" means here
 *
 * Only TRANSPORT-level failures move to the next url — see [isEndpointFailure].
 * A node that answered is not retried: `execution reverted` and `method not
 * found` are answers, and asking a second node produces the same answer more
 * slowly. What does move on: no connection, DNS, TLS, timeouts, and any HTTP
 * status an endpoint returns instead of a JSON-RPC result (401/403/404/429/5xx,
 * including the Cloudflare 525/526 that several bundled Ethereum and Cosmos
 * endpoints answer with when their own upstream certificate is broken).
 *
 * ## The last-known-good memo
 *
 * A working url is remembered per network, so the next call starts there
 * instead of paying the dead endpoint's connect timeout again. It is a cache,
 * never a commitment: if the remembered url fails it falls through normally,
 * and a url the user removed from the network is ignored because the memo is
 * only consulted when it is still in the list.
 */
class RpcEndpointChain(
    private val rpc: JsonRpcClient,
    private val key: String,
    val urls: List<String>
) {

    /** The urls in the order they will be tried for the next call. */
    fun ordered(): List<String> {
        val good = lastGood[key]
        return if (good != null && urls.contains(good)) {
            listOf(good) + urls.filter { it != good }
        } else {
            urls
        }
    }

    /**
     * Runs [block] against each endpoint until one succeeds and returns its
     * result, remembering the endpoint that answered.
     *
     * The failure thrown when every endpoint has failed is the LAST one: it is
     * the only failure that happened on every url, so it describes the network
     * rather than one host's bad day. Its message names how many endpoints were
     * tried, because "RPC unreachable" from a list of three is a different
     * situation from the same message from a list of one.
     */
    suspend fun <T> firstWorking(block: suspend (String) -> T): T {
        if (urls.isEmpty()) throw WalletException.InvalidParams("Network has no RPC endpoint")
        var last: WalletException? = null
        var tried = 0
        for (url in ordered()) {
            tried++
            try {
                val result = block(url)
                markGood(url)
                return result
            } catch (e: WalletException) {
                if (!isEndpointFailure(e)) throw e
                last = e
            }
        }
        val failure = last ?: WalletException.NetworkUnavailable("No RPC endpoint answered")
        throw if (tried > 1) {
            WalletException.NetworkUnavailable(
                "${failure.message ?: "RPC unreachable"} — all $tried endpoints failed"
            )
        } else {
            failure
        }
    }

    /** [firstWorking] for a plain JSON-RPC method. */
    suspend fun call(method: String, params: List<JsonElement> = emptyList()): JsonElement =
        firstWorking { url -> rpc.call(url, method, params) }

    /** [call] for the single-object-params shape most chains use. */
    suspend fun callObject(method: String, params: List<JsonElement> = emptyList()): JsonObject =
        call(method, params).jsonObject

    private fun markGood(url: String) {
        lastGood[key] = url
    }

    companion object {
        /** network id -> the url that answered most recently. Process-wide cache. */
        private val lastGood = ConcurrentHashMap<String, String>()

        /** An [RpcEndpointChain] for [network], with blank urls already dropped. */
        fun of(rpc: JsonRpcClient, network: NetworkConfig): RpcEndpointChain =
            RpcEndpointChain(
                rpc = rpc,
                key = network.id,
                urls = network.rpcUrls.map { it.trim() }.filter { it.isNotEmpty() }
            )

        /**
         * Whether [e] means "this endpoint could not answer" rather than "this
         * endpoint answered, and the answer was no".
         *
         * The distinction is the difference between a retry that helps and one
         * that wastes the user's time. [WalletException.RpcError] carries the
         * code from whichever layer produced it: the JSON-RPC spec reserves
         * negative codes for protocol and application errors, so a code in the
         * HTTP range can only have come from the transport, and an endpoint
         * answering `HTTP 525` is an endpoint that never got to speak JSON-RPC.
         * Codes below 400 are protocol answers and are surfaced as they are.
         */
        fun isEndpointFailure(e: WalletException): Boolean = when (e) {
            is WalletException.NetworkUnavailable -> true
            is WalletException.RpcError -> e.code >= 400
            else -> false
        }

        /**
         * Forgets which endpoint answered last. Only tests need this: the memo
         * is process-wide, so a test that asserts try-order would otherwise
         * inherit the order a previous test left behind.
         */
        fun clearMemo() {
            lastGood.clear()
        }
    }
}
