package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.OllamaModelInfo
import com.roombrowser.domain.agent.OllamaPullEvent
import com.roombrowser.domain.agent.OllamaPullParser
import com.roombrowser.domain.agent.OllamaTagsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Management client for the LOCAL AI screen: talks to a real Ollama server
 * (`http://localhost:11434` — Termux on the phone, or a PC on the LAN) via
 * its native admin endpoints, which the OpenAI-compat layer does not expose:
 *
 *   GET    /api/version → {"version":"0.5.7"}       (connection check)
 *   GET    /api/tags    → installed models          (Local AI list)
 *   POST   /api/pull    → NDJSON progress stream    (model download)
 *   DELETE /api/delete  → {"model":"<name>"}        (uninstall)
 *
 * No API key is needed for a local daemon, but a Bearer header is sent when
 * one is configured (Ollama's OLLAMA_API_KEY / reverse-proxy setups), keeping
 * this client consistent with the other gateways.
 *
 * Timeouts: connect 20s, read 60s (progress lines arrive continuously — a
 * longer read timeout buys nothing), whole-call timeout 0 because PULLS ARE
 * LONG: a multi-GB download legally runs for hours.
 */
class OllamaClient(
    client: OkHttpClient,
    baseUrl: String,
    private val apiKey: String = ""
) {

    private val callFactory: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val base: String = normalizeHost(baseUrl)

    // -------------------------------------------------------------- version

    /** Server version string, e.g. "0.5.7"; parsed leniently. */
    suspend fun version(): String {
        val response = execute(get("/api/version"))
        try {
            val text = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            val root = runCatching {
                AgentJson.parseToJsonElement(text.trim()) as? JsonObject
            }.getOrNull()
            return (root?.get("version") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: throw AgentHttpException(response.code, "ollama returned no version field")
        } finally {
            runCatching { response.close() }
        }
    }

    // -------------------------------------------------------------- models

    /** Installed models from /api/tags; EMPTY list is a valid, non-error state. */
    suspend fun listModels(): List<OllamaModelInfo> {
        val response = execute(get("/api/tags"))
        try {
            val text = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            if (!response.isSuccessful) throw AgentHttpException(response.code, text.take(500))
            return OllamaTagsParser.parse(text)
        } finally {
            runCatching { response.close() }
        }
    }

    // -------------------------------------------------------------- pull

    /**
     * Streams a model download (`POST /api/pull`, NDJSON progress lines).
     * Returns normally once a TERMINAL event ("success") arrives; every
     * parsed line is handed to [onEvent] in order.
     *
     * Cancellation contract (the pause/resume backbone): cancelling the
     * calling coroutine cancels the HTTP call (see [execute]) and surfaces
     * as a [CancellationException] — even when the blocked read only wakes
     * up with an IOException, a cancelled coroutine must stay cancelled.
     * Ollama keeps completed blobs server-side, so a re-issued pull simply
     * skips them: that re-issue IS the resume.
     */
    suspend fun pull(model: String, onEvent: suspend (OllamaPullEvent) -> Unit) {
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "stream" to JsonPrimitive(true)
            )
        )
        val request = Request.Builder()
            .url("$base/api/pull")
            .post(AgentJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .header("Accept", "application/x-ndjson")
            .header("User-Agent", "RoomBrowser-Agent/1.0")
        if (apiKey.isNotBlank()) request.header("Authorization", "Bearer $apiKey")

        val response = execute(request.build())
        try {
            if (!response.isSuccessful) throw AgentHttpException(response.code, errorBody(response))
            val responseBody = response.body ?: throw AgentHttpException(response.code, "empty body")
            readPullStream(responseBody, onEvent)
        } finally {
            runCatching { response.close() }
        }
    }

    private suspend fun readPullStream(
        body: ResponseBody,
        onEvent: suspend (OllamaPullEvent) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var reader: BufferedReader? = null
            try {
                reader = body.byteStream().bufferedReader(Charsets.UTF_8)
                while (true) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("ollama pull cancelled")
                    val line = reader.readLine() ?: break
                    val event = OllamaPullParser.parseLine(line) ?: continue
                    onEvent(event)
                    if (event.isTerminal) return@withContext
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // call.cancel() surfaces as an IOException, NOT a Cancellation-
                // Exception — map it back so "pause" stays a cancellation.
                if (!currentCoroutineContext().isActive) throw CancellationException("ollama pull cancelled")
                throw AgentHttpException(-1, "pull stream interrupted: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { reader?.close() }
            }
        }
    }

    // -------------------------------------------------------------- delete

    /** Uninstalls a model (`DELETE /api/delete` with a JSON body). */
    suspend fun delete(model: String) {
        val body = JsonObject(mapOf("model" to JsonPrimitive(model)))
        val request = Request.Builder()
            .url("$base/api/delete")
            .delete(AgentJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("User-Agent", "RoomBrowser-Agent/1.0")
        if (apiKey.isNotBlank()) request.header("Authorization", "Bearer $apiKey")

        val response = execute(request.build())
        try {
            if (!response.isSuccessful) throw AgentHttpException(response.code, errorBody(response))
        } finally {
            runCatching { response.close() }
        }
    }

    // -------------------------------------------------------------- utils

    private fun get(path: String): Request {
        val builder = Request.Builder()
            .url("$base$path")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "RoomBrowser-Agent/1.0")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    private fun errorBody(response: Response): String = runCatching {
        response.body?.string()?.take(500) ?: ""
    }.getOrDefault("")

    /** Enqueues the call and suspends; cancelling the coroutine cancels HTTP. */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        if (call.isCanceled()) {
                            continuation.resumeWithException(CancellationException("ollama request cancelled"))
                        } else {
                            continuation.resumeWithException(
                                AgentHttpException(-1, "connection failed: ${e.message ?: e.javaClass.simpleName}")
                            )
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Normalizes user-entered hosts (trims spaces + trailing slash). */
        fun normalizeHost(input: String): String = input.trim().trimEnd('/')
    }
}
