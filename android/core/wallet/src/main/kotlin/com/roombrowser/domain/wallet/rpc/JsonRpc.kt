package com.roombrowser.domain.wallet.rpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.roombrowser.domain.wallet.model.WalletException
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 2.0 client shared by EVM, Solana, Aptos, Sui and TRON adapters.
 * POSTs one request per call with a short timeout and clear error mapping —
 * RPC problems surface as [WalletException] instead of being swallowed.
 */
class JsonRpcClient(
    private val http: OkHttpClient = defaultClient()
) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Calls `method(params)` on [endpoint] and returns the `result` element.
     * Throws [WalletException.RpcError] on a JSON-RPC error object,
     * [WalletException.NetworkUnavailable] on transport failures.
     */
    suspend fun call(endpoint: String, method: String, params: List<JsonElement> = emptyList()): JsonElement =
        withContext(Dispatchers.IO) {
            val url = endpoint.toHttpUrlOrNull()
                ?: throw WalletException.InvalidParams("Invalid RPC endpoint: $endpoint")
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", method)
                put("params", kotlinx.serialization.json.JsonArray(params))
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            val responseText = try {
                http.newCall(request).execute().use { resp ->
                    when {
                        !resp.isSuccessful -> throw WalletException.RpcError(
                            resp.code,
                            "RPC HTTP ${resp.code} from $endpoint"
                        )
                        else -> resp.body?.string() ?: throw WalletException.RpcError(resp.code, "Empty RPC response")
                    }
                }
            } catch (e: WalletException) {
                throw e
            } catch (e: java.io.IOException) {
                throw WalletException.NetworkUnavailable("RPC unreachable (${e.message})")
            }
            val parsed = json.parseToJsonElement(responseText).jsonObject
            parsed["error"]?.let { err ->
                val obj = (err as? JsonObject)
                val code = obj?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                val message = obj?.get("message")?.jsonPrimitive?.content ?: "RPC error"
                throw WalletException.RpcError(code, message)
            }
            parsed["result"] ?: throw WalletException.RpcError(-1, "RPC response missing result")
        }

    /** Convenience for single-object params (most chains use this shape). */
    suspend fun callObject(endpoint: String, method: String, params: List<JsonElement> = emptyList()): JsonObject =
        call(endpoint, method, params).jsonObject

    /** Plain REST helpers for Aptos (indexer), Cosmos LCD and Bitcoin APIs. */
    suspend fun getJson(endpoint: String): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).get().build()
        executeForJson(request)
    }

    suspend fun postJson(endpoint: String, payload: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        executeForJson(request)
    }

    suspend fun postJsonText(endpoint: String, bodyText: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(bodyText.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request)
    }

    suspend fun getBytes(endpoint: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).get().build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw WalletException.RpcError(resp.code, "HTTP ${resp.code} from $endpoint")
                resp.body?.bytes() ?: throw WalletException.RpcError(resp.code, "Empty body")
            }
        } catch (e: WalletException) {
            throw e
        } catch (e: java.io.IOException) {
            throw WalletException.NetworkUnavailable("Request unreachable (${e.message})")
        }
    }

    private suspend fun executeForJson(request: Request): JsonElement {
        val text = execute(request)
        return json.parseToJsonElement(text)
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw WalletException.RpcError(resp.code, "HTTP ${resp.code} from $request")
                resp.body?.string() ?: throw WalletException.RpcError(resp.code, "Empty body")
            }
        } catch (e: WalletException) {
            throw e
        } catch (e: java.io.IOException) {
            throw WalletException.NetworkUnavailable("Request unreachable (${e.message})")
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
