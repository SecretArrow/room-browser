package com.roombrowser.agent

import com.roombrowser.domain.agent.ActionGate
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.SystemOneParser
import com.roombrowser.domain.agent.SystemOneQuestion
import com.roombrowser.domain.agent.SystemOneResponse
import com.roombrowser.domain.agent.SystemOneWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for Ollama's decision endpoint, `POST /v1/systemone` — the one
 * transport the agent's local gate uses.
 *
 * Deliberately NOT an [com.roombrowser.domain.agent.AgentGateway]: a gateway
 * streams a conversation and can call tools, while this answers one request
 * with one JSON object and nothing else. Borrowing the gateway interface
 * would mean implementing chat, streaming and tool calls for an endpoint
 * that has none of them.
 *
 * The base URL is an `OLLAMA`-protocol provider's (`http://host:11434`, no
 * `/v1` suffix) — the same server the Local AI screen manages, which is what
 * makes a gate decision cost no network round trip when the server is on the
 * same machine. `keep_alive` is sent on every call so the model stays
 * resident between decisions; a cold 9B load is the difference between 91 ms
 * and a minute.
 *
 * Timeouts are short by design. This sits between the agent and its next
 * action, and a slow judge is worse than no judge: read 60s is enough for a
 * cold model load on a phone-hosted server, and the caller applies its own
 * shorter deadline (see `BrowserAgentController.gate`) so a stuck decision
 * cannot stall a turn.
 */
class SystemOneClient(
    client: OkHttpClient,
    baseUrl: String,
    private val apiKey: String = ""
) {

    private val callFactory: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // a cold model load is not a hang
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val base: String = OllamaClient.normalizeHost(baseUrl)

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Ollama base URL must start with http:// or https://"
        }
    }

    /**
     * Asks [model] the [questions] about [state] and returns the parsed
     * answers.
     *
     * Throws [AgentHttpException] on any failure — a 404 in particular means
     * the server is older than Ollama 0.35 or the model is not pulled, and
     * the caller reports that rather than treating it as "no objections".
     */
    suspend fun decide(
        model: String,
        state: JsonObject,
        questions: Map<String, SystemOneQuestion>,
        keepAlive: String? = KEEP_ALIVE
    ): SystemOneResponse {
        val body = SystemOneWire.request(model, state, questions, keepAlive)
        val builder = Request.Builder()
            .url("$base/v1/systemone")
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("User-Agent", "RoomBrowser-Agent/1.0")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val response = execute(builder.build())
        try {
            val text = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            if (!response.isSuccessful) {
                throw AgentHttpException(response.code, text.take(500))
            }
            val parsed = SystemOneParser.parse(text)
            if (parsed.answers.isEmpty()) {
                // A 200 with nothing readable is the one failure the parser
                // cannot distinguish from an empty decision, so the caller is
                // told here instead of silently getting an "ask the user".
                throw AgentHttpException(response.code, "no answers in the response")
            }
            return parsed
        } finally {
            runCatching { response.close() }
        }
    }

    /**
     * The action question, answered by [model] — the shape the gate uses.
     * Kept here so the caller does not have to know [ActionGate]'s question
     * name or its criteria.
     */
    suspend fun decideAction(
        model: String,
        action: String,
        pageUrl: String?,
        pageTitle: String?,
        policy: String?
    ): SystemOneResponse = decide(
        model = model,
        state = ActionGate.state(action, pageUrl, pageTitle),
        questions = ActionGate.questions(policy)
    )

    /** Enqueues the call and suspends; cancelling the coroutine cancels HTTP. */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        if (call.isCanceled()) CancellationException("systemone request cancelled")
                        else AgentHttpException(-1, "connection failed: ${e.message ?: e.javaClass.simpleName}")
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * How long the decision model stays loaded after a call. Long enough
         * that a turn's later actions reuse the loaded weights, short enough
         * that the server reclaims the RAM when the user stops browsing.
         */
        const val KEEP_ALIVE = "10m"
    }
}
