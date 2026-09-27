package com.roombrowser.domain.agent

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenCode (opencode serve) wire-format helpers.
 *
 * opencode is a terminal/server AI coding agent whose `opencode serve`
 * command exposes a session-based REST API (default port 4096) that is NOT
 * OpenAI-compatible. This file adapts both directions:
 *
 *  1. [OpenCodeModelsParser] — parses `GET /provider` responses into
 *     "providerID/modelID" ids (with lenient fallbacks for shape drift
 *     across opencode versions).
 *  2. [OpenCodeToolCallParser] — turns the TEXT tool-call protocol used with
 *     opencode (the server has no browser tools, so the model is instructed
 *     to emit JSON action blocks inside its reply) into real [ToolCall]s,
 *     which the unchanged [AgentLoop] then executes against the browser.
 *  3. [OpenCodeWire] — request bodies for `POST /session` and
 *     `POST /session/{id}/message`.
 */

/** A parsed assistant reply: clean text + tool calls extracted from it. */
data class OpenCodeParsedReply(val text: String, val toolCalls: List<ToolCall>)

/**
 * Parses model lists as returned by opencode's `/provider` endpoint.
 * Accepted shapes (observed across versions, all parsed leniently):
 *
 *   [ {"id":"anthropic","models":[{"id":"claude-...", ...}]}, ... ]
 *   [ {"providerID":"anthropic","models":{"claude-...":{...}}} ]
 *   { "anthropic": {"models": [{"id":"claude-..."}]} }
 *   {"data":[...]} / ["m1","m2"]  (delegates to [ModelListParser])
 */
object OpenCodeModelsParser {

    fun parse(body: String): List<String> = runCatching {
        val root = AgentJson.parseToJsonElement(body.trim())
        when (root) {
            is JsonArray -> root.flatMap { providerModels(it) }
            is JsonObject -> when {
                // OpenAI-style wrappers → bare model ids.
                root.containsKey("data") || root.containsKey("models") -> ModelListParser.parse(body)
                root.containsKey("providers") -> {
                    (root["providers"] as? JsonArray)?.flatMap { providerModels(it) } ?: emptyList()
                }
                // {"providerId": {"models": ...}} map form.
                else -> root.entries.flatMap { (pid, v) ->
                    val obj = v as? JsonObject ?: return@flatMap emptyList()
                    modelsOf(obj).map { "$pid/$it" }
                }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).distinct().sorted()

    private fun providerModels(element: JsonElement): List<String> {
        val obj = element as? JsonObject ?: return emptyList()
        val pid = str(obj, "id") ?: str(obj, "providerID") ?: str(obj, "providerId") ?: str(obj, "name")
        val models = modelsOf(obj)
        return when {
            pid.isNullOrBlank() -> models // already bare ids (delegate shape)
            else -> models.map { "$pid/$it" }
        }
    }

    private fun modelsOf(obj: JsonObject): List<String> = when (val models = obj["models"]) {
        is JsonArray -> models.mapNotNull { m ->
            when (m) {
                is JsonPrimitive -> m.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> (str(m, "id") ?: str(m, "name") ?: str(m, "modelID") ?: str(m, "modelId"))
                    ?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        is JsonObject -> models.keys.filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Extracts tool calls from the model's TEXT reply (text tool-call protocol).
 *
 * Accepted blocks — fenced (```json ... ```) or bare balanced JSON objects
 * inside the prose:
 *
 *   {"tool_calls":[{"function":{"name":"navigate","arguments":{"url":"..."}}}]}
 *   {"actions":[{"name":"click","arguments":{"ref":3}}]}
 *   {"tool_call":{"function":{"name":"scroll","arguments":{"direction":"down"}}}}
 *   {"name":"read_page","arguments":{}}
 *
 * Non-tool JSON objects in the text are left untouched. The matched blocks
 * are removed from the returned text so the chat UI stays readable.
 */
object OpenCodeToolCallParser {

    fun parse(content: String, enabled: Boolean): OpenCodeParsedReply {
        if (!enabled || content.isBlank()) return OpenCodeParsedReply(content, emptyList())

        val calls = mutableListOf<ToolCall>()
        val removals = mutableListOf<IntRange>()

        // 1) fenced blocks: ```json ... ``` (also ```actions / ```tool_calls)
        val fence = Regex("```(?:json|actions|tool_calls|tool)?[ \\t]*\\r?\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val fenceMatches = fence.findAll(content).toList()
        for (m in fenceMatches) {
            val body = m.groupValues[1].trim()
            if (body.isEmpty()) continue
            parseToolBlock(body)?.let { parsed ->
                calls += parsed
                removals += m.range
            }
        }
        val textWithoutFences = removeRanges(content, removals)

        // 2) bare balanced JSON objects remaining in the prose
        val bareCalls = mutableListOf<ToolCall>()
        val bareRemovals = mutableListOf<IntRange>()
        for ((range, obj) in scanJsonObjects(textWithoutFences)) {
            val parsed = toolCallsOf(obj)
            if (parsed != null) {
                bareCalls += parsed
                bareRemovals += range
            }
        }

        val cleanText = removeRanges(textWithoutFences, bareRemovals)
            .replace(Regex("[ \\t]*\\n[ \\t]*\\n[ \\t]*\\n+"), "\n\n")
            .trim()
        val combined = calls + bareCalls
        return OpenCodeParsedReply(
            text = cleanText,
            toolCalls = combined.mapIndexed { i, tc ->
                tc.copy(id = if (tc.id.isBlank()) "text_$i" else tc.id)
            }
        )
    }

    private fun parseToolBlock(body: String): List<ToolCall>? {
        val obj = runCatching { AgentJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return toolCallsOf(obj)
    }

    /** Returns null when [obj] is NOT a tool block (so plain JSON stays visible). */
    private fun toolCallsOf(obj: JsonObject): List<ToolCall>? {
        val out = mutableListOf<ToolCall>()
        (obj["tool_calls"] as? JsonArray)?.forEach { el ->
            (el as? JsonObject)?.let { out += callFromFunctionForm(it) }
        }
        (obj["actions"] as? JsonArray)?.forEach { el ->
            (el as? JsonObject)?.let { out += callFromActionForm(it) }
        }
        (obj["tool_call"] as? JsonObject)?.let { out += callFromFunctionForm(it) }
        if (out.isEmpty() && (obj["name"] != null || obj["tool"] != null) &&
            (obj["arguments"] != null || obj["args"] != null)
        ) {
            out += callFromActionForm(obj)
        }
        // An explicit empty "tool_calls": [] is still a tool block ("done acting")
        val isToolBlock = out.isNotEmpty() || obj.containsKey("tool_calls") ||
            obj.containsKey("actions") || obj.containsKey("tool_call")
        return if (isToolBlock) out else null
    }

    private fun callFromFunctionForm(obj: JsonObject): ToolCall {
        val fn = obj["function"] as? JsonObject
        val name = fn?.let { str(it, "name") } ?: str(obj, "name") ?: str(obj, "tool") ?: ""
        val argsEl = fn?.get("arguments") ?: obj["arguments"]
        return ToolCall(id = "", function = FunctionCall(name, argsToString(argsEl)))
    }

    private fun callFromActionForm(obj: JsonObject): ToolCall {
        val name = str(obj, "name") ?: str(obj, "tool") ?: str(obj, "action") ?: ""
        val argsEl = obj["arguments"] ?: obj["args"]
        return ToolCall(id = "", function = FunctionCall(name, argsToString(argsEl)))
    }

    private fun argsToString(el: JsonElement?): String = when (el) {
        null -> "{}"
        is JsonPrimitive -> el.contentOrNull ?: "{}"
        is JsonObject, is JsonArray -> el.toString()
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Finds balanced, string-aware JSON objects; returns (range, parsed or null). */
    private fun scanJsonObjects(text: String): List<Pair<IntRange, JsonObject>> {
        val out = mutableListOf<Pair<IntRange, JsonObject>>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            val end = balancedEnd(text, i)
            if (end < 0) { i++; continue }
            val candidate = text.substring(i, end + 1)
            val obj = runCatching { AgentJson.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            if (obj != null) {
                out += i..end to obj
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    /** Returns the index of the `}` closing the object opened at [start], or -1. */
    private fun balancedEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var j = start
        while (j < text.length) {
            val c = text[j]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        return -1
    }

    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sorted = ranges.sortedBy { it.first }
        val sb = StringBuilder()
        var cursor = 0
        for (r in sorted) {
            if (r.first < cursor) continue // overlapping — already removed
            sb.append(text, cursor, r.first)
            cursor = r.last + 1
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }
}

/** Request bodies for the opencode server REST API. */
object OpenCodeWire {

    private val wire = Json { encodeDefaults = false }

    fun createSessionBody(title: String): String =
        """{"title":${jsonStr(title)}}"""

    /**
     * `POST /session/{id}/message`. [modelSpec] is "providerID/modelID"
     * (as produced by [OpenCodeModelsParser]) or a bare model id.
     */
    fun messageBody(modelSpec: String, text: String): String {
        val providerId: String? = modelSpec.takeIf { it.contains('/') }?.substringBefore('/')
        val modelId = modelSpec.takeIf { it.contains('/') }?.substringAfter('/') ?: modelSpec
        val sb = StringBuilder("{")
        providerId?.let { sb.append("\"providerID\":").append(jsonStr(it)).append(",") }
        sb.append("\"modelID\":").append(jsonStr(modelId)).append(",")
        sb.append("\"parts\":[{\"type\":\"text\",\"text\":").append(jsonStr(text)).append("}]")
        sb.append("}")
        return sb.toString()
    }

    /** JSON-encodes [s] as a quoted string (escaping control characters). */
    private fun jsonStr(s: String): String =
        wire.encodeToString(String.serializer(), s)
}
