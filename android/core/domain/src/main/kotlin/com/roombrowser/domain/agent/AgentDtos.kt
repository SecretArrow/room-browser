package com.roombrowser.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * OpenAI-compatible chat-completions DTOs used by the browsing agent.
 *
 * The wire format follows the de-facto standard shared by OpenAI, Z.ai,
 * OpenRouter, Groq, DeepSeek, Mistral, Together, Ollama (/v1) and LM Studio:
 *
 *   POST {baseUrl}/chat/completions  {model, messages, tools, stream, temperature}
 *   GET  {baseUrl}/models            {"data":[{"id":"..."}]} (or a bare array)
 *
 * One shared [Json] instance is used for both directions:
 *  - encodeDefaults=true  → `stream:true` / `type:"function"` always sent
 *  - explicitNulls=false  → null content / absent tools are omitted
 */
val AgentJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val tools: List<ToolDef>? = null,
    val temperature: Double? = null
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(val name: String, val arguments: String)

// ---------- Non-streaming response ----------

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<RespChoice> = emptyList()
) {
    val firstMessage: ChatMessage?
        get() = choices.firstOrNull()?.message
}

@Serializable
data class RespChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

// ---------- Streaming chunks (SSE `data:` payloads) ----------

@Serializable
data class StreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FnDelta? = null
)

@Serializable
data class FnDelta(
    val name: String? = null,
    val arguments: String? = null
)

/** Provider HTTP failure with the (truncated) body for honest error UI. */
class AgentHttpException(val code: Int, val body: String) :
    Exception("HTTP $code${if (body.isBlank()) "" else ": $body"}")

/**
 * Parses `/models` responses leniently. Accepted shapes:
 *  {"object":"list","data":[{"id":"m1"},...]}   (OpenAI-compatible)
 *  {"data":["m1","m2"]}                          (string ids)
 *  ["m1","m2"]                                    (bare array)
 *  {"models":[{"id":"m1"}]}                       (alternate key)
 */
object ModelListParser {

    fun parse(body: String): List<String> = runCatching {
        val element = AgentJson.parseToJsonElement(body.trim())
        extractModelIds(element)
    }.getOrDefault(emptyList())

    private fun extractModelIds(element: JsonElement): List<String> {
        val candidates: List<JsonElement> = when (element) {
            is JsonArray -> element.jsonArray.toList()
            is JsonObject -> {
                val arr = element.jsonObject["data"] as? JsonArray
                    ?: element.jsonObject["models"] as? JsonArray
                arr?.toList() ?: emptyList()
            }
            else -> emptyList()
        }
        return candidates.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> {
                    val id = (item["id"] ?: item["name"]) as? JsonPrimitive
                    id?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }.distinct().sorted()
    }
}
