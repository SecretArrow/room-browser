package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool calling for a model that has no tool calling.
 *
 * The HTTP gateways hand the model a `tools` array and read `tool_calls` back
 * out of the reply — the wire protocol does the work. The embedded llama.cpp
 * engine has no such channel: [com.roombrowser.agent.LocalLlamaGateway] gives
 * it a prompt and gets prose back, so before this file the on-device model
 * could describe an action but never take one — `assistant.toolCalls` was
 * always null and the agent loop stopped at its "no calls → final answer"
 * branch on every single step.
 *
 * So the catalogue and the replies travel as TEXT, under a contract strict
 * enough to parse and plain enough for a small model to follow:
 *
 *  - the tool list is appended to the system message, as name, description and
 *    argument names — not as JSON Schema, which costs context a 2-4k window
 *    cannot spare and which a 1-3B model reads no better than a sentence;
 *  - a reply that ACTS is one JSON object, `{"tool":…,"args":{…}}`;
 *  - a reply that ANSWERS is plain text, and the two are never mixed.
 *
 * [parseReply] is deliberately forgiving about everything except the shape: a
 * model that wraps the object in a ```json fence, precedes it with "Sure!", or
 * trails a sentence after it still gets its call executed, because the
 * alternative is losing the turn. It is strict about what counts as a call at
 * all — an object is only a call when it carries `tool`, or both `name` and an
 * arguments key — so a tool RESULT that happens to be JSON (a page snapshot,
 * a tab list) is never mistaken for a new request to act.
 *
 * Pure (no Android, no engine, no HTTP), so the whole contract is covered by
 * the core unit tests rather than only by an emulator run.
 */
object LocalToolProtocol {

    /** The key a call names its tool with. */
    const val TOOL_KEY = "tool"

    /** The key a call carries its arguments under. */
    const val ARGS_KEY = "args"

    /** Argument keys accepted as spellings of [ARGS_KEY], for models that
     *  reproduce the OpenAI shape they were trained on instead of ours. */
    private val ARG_KEYS = listOf(ARGS_KEY, "arguments", "parameters", "input")

    /** Tool-name keys, likewise. */
    private val NAME_KEYS = listOf(TOOL_KEY, "name", "tool_name")

    /** The role the contract is appended to. */
    private const val SYSTEM = "system"

    /** What a reply means once it has been read. */
    sealed interface Reply {
        /** The model asked for [name] with [argumentsJson] (always a JSON object). */
        data class Call(val name: String, val argumentsJson: String) : Reply

        /** The model answered in words; [content] is the trimmed reply. */
        data class Text(val content: String) : Reply
    }

    // ------------------------------------------------------------- the prompt

    /** The rules appended to the system message, above the catalogue. */
    private const val CONTRACT: String = """HOW TO ACT
To use a tool, reply with ONE JSON object and nothing else:
{"tool":"<tool name>","args":{<arguments>}}
To answer without acting, reply with plain text only.
Never mix the two. One tool per reply."""

    /**
     * The conversation re-expressed for a provider that is given NO `tools`
     * array: the contract and catalogue are appended to the leading system
     * message (or a new system message when there is none), and the two
     * turns that only make sense alongside a wire protocol are rewritten.
     *
     * When [tools] is null/empty only the rewriting happens — the loop drops
     * the catalogue on its last step to force a plain answer, and that answer
     * must not be read as a call.
     */
    fun messagesFor(messages: List<ChatMessage>, tools: List<ToolDef>?): List<ChatMessage> {
        val rendered = messages.mapNotNull { message ->
            when (message.role) {
                "assistant" -> {
                    val calls = message.toolCalls.orEmpty()
                    val body = if (calls.isNotEmpty()) {
                        calls.joinToString("\n") { call ->
                            """{"tool":"${call.function.name}","args":${call.function.arguments}}"""
                        }
                    } else {
                        message.content
                    }
                    body?.takeIf { it.isNotBlank() }?.let { ChatMessage(role = "assistant", content = it) }
                }

                "tool" -> message.content?.takeIf { it.isNotBlank() }
                    ?.let { ChatMessage(role = "user", content = "RESULT: $it") }

                else -> message.content?.takeIf { it.isNotBlank() }
                    ?.let { ChatMessage(role = message.role, content = it) }
            }
        }.toMutableList()

        if (tools.isNullOrEmpty()) return rendered
        val contract = contractFor(tools)
        val system = rendered.indexOfFirst { it.role == SYSTEM }
        if (system >= 0) {
            rendered[system] = ChatMessage(
                role = SYSTEM,
                content = (rendered[system].content ?: "") + "\n\n" + contract
            )
        } else {
            rendered.add(0, ChatMessage(role = SYSTEM, content = contract))
        }
        return rendered
    }

    /** [messagesFor] flattened for the on-device engine's `role to content` API. */
    fun conversationFor(
        messages: List<ChatMessage>,
        tools: List<ToolDef>?
    ): List<Pair<String, String>> = pairs(messagesFor(messages, tools))

    private fun pairs(messages: List<ChatMessage>): List<Pair<String, String>> =
        messages.mapNotNull { message -> message.content?.let { message.role to it } }

    /** The contract plus one line per tool. */
    fun contractFor(tools: List<ToolDef>): String = buildString {
        appendLine(CONTRACT)
        appendLine()
        appendLine("TOOLS")
        tools.forEach { tool ->
            append("- ").append(tool.function.name)
            append(": ").append(tool.function.description)
            val args = describeArgs(tool.function.parameters)
            if (args.isNotEmpty()) append("  args: ").append(args)
            appendLine()
        }
    }

    /** `url (string, required), ref (integer)` — names and types, nothing more. */
    private fun describeArgs(parameters: JsonObject): String {
        val properties = runCatching { parameters["properties"]?.jsonObject }.getOrNull()
            ?: return ""
        val required = runCatching {
            parameters["required"]?.let { element ->
                (element as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.toSet()
            }
        }.getOrNull().orEmpty()
        return properties.entries.joinToString(", ") { (name, schema) ->
            val type = runCatching { schema.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
                .getOrNull() ?: "any"
            if (name in required) "$name ($type, required)" else "$name ($type)"
        }
    }

    // -------------------------------------------------------- the conversation

    /**
     * Maps the loop's history onto `role to content` pairs for the engine.
     *
     * Two conversions are not cosmetic:
     *
     *  - an assistant turn that CALLED a tool is re-rendered as the call it
     *    made. Its `content` is null in exactly that case, so passing it
     *    through would hand the model a blank turn and lose the thread of what
     *    it already did;
     *  - a tool RESULT is relabelled `user`, because llama.cpp applies the
     *    model's own chat template and that template is written for
     *    system/user/assistant — a `tool` role it does not know is dropped by
     *    some templates and rejected by others. The `RESULT:` prefix keeps the
     *    model able to tell an outcome from a fresh instruction.
     */
    fun renderConversation(messages: List<ChatMessage>): List<Pair<String, String>> =
        pairs(messagesFor(messages, null))

    // ------------------------------------------------------------- the reply

    /**
     * Reads the model's reply. The FIRST object in the text that has the shape
     * of a call wins; when there is none the whole reply is the answer.
     */
    fun parseReply(text: String): Reply {
        findCall(text)?.let { return it }
        return Reply.Text(text.trim())
    }

    private fun findCall(text: String): Reply.Call? {
        var from = 0
        while (true) {
            val start = text.indexOf('{', from)
            if (start < 0) return null
            val end = matchingBrace(text, start)
            if (end < 0) return null
            parseCall(text.substring(start, end + 1))?.let { return it }
            from = start + 1
        }
    }

    /**
     * The index of the `}` closing the `{` at [open], or -1 when unbalanced.
     * Braces inside a JSON string are text, not structure, so strings and
     * their escapes are tracked — a `click` whose label is `"{"` must not end
     * the object early.
     */
    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }

    /**
     * [slice] as a call, or null when it is some other JSON object.
     *
     * The guard is the point: an object counts only when it names a tool AND
     * carries arguments — `tool`, or `name` plus one of the argument keys. A
     * snapshot, a tab list or any other JSON a tool returned reddens none of
     * those, so it can never be read back as a request to act.
     */
    private fun parseCall(slice: String): Reply.Call? {
        val obj = runCatching { AgentJson.parseToJsonElement(slice).jsonObject }.getOrNull()
            ?: return null
        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: return null
        val isCall = obj.containsKey(TOOL_KEY) || ARG_KEYS.any { obj.containsKey(it) }
        if (!isCall) return null
        val args: JsonElement = ARG_KEYS.firstNotNullOfOrNull { obj[it] } ?: JsonObject(emptyMap())
        return Reply.Call(name, AgentJson.encodeToString(JsonElement.serializer(), args))
    }
}
