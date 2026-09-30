package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device tool contract, covered here because the engine it drives needs
 * a real GGUF model and a device to run at all — so an emulator-only test would
 * mean the parsing rules that decide whether a browsing agent ACTS or merely
 * talks were never exercised in CI.
 */
class LocalToolProtocolTest {

    private fun tool(
        name: String,
        description: String = "does a thing",
        schema: String = """{"type":"object","properties":{"ref":{"type":"integer"}},"required":["ref"]}"""
    ) = ToolDef(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = AgentJson.parseToJsonElement(schema).jsonObject
        )
    )

    private fun call(reply: String) = LocalToolProtocol.parseReply(reply) as LocalToolProtocol.Reply.Call
    private fun text(reply: String) = LocalToolProtocol.parseReply(reply) as LocalToolProtocol.Reply.Text

    // ------------------------------------------------------------ parsing

    @Test
    fun `a bare call is read as a call`() {
        val parsed = call("""{"tool":"click","args":{"ref":3}}""")
        assertEquals("click", parsed.name)
        assertEquals("""{"ref":3}""", parsed.argumentsJson)
    }

    @Test
    fun `a fenced call is still a call`() {
        val parsed = call(
            """
            Sure, I'll click that.

            ```json
            {"tool":"click","args":{"ref":3}}
            ```
            """.trimIndent()
        )
        assertEquals("click", parsed.name)
        assertEquals("""{"ref":3}""", parsed.argumentsJson)
    }

    @Test
    fun `a call with no arguments carries an empty object`() {
        val parsed = call("""{"tool":"read_page"}""")
        assertEquals("read_page", parsed.name)
        assertEquals("{}", parsed.argumentsJson)
    }

    /** Models reproduce the OpenAI shape they were trained on; accept it. */
    @Test
    fun `the openai spelling names and arguments is accepted`() {
        val parsed = call("""{"name":"navigate","arguments":{"url":"https://example.com"}}""")
        assertEquals("navigate", parsed.name)
        assertEquals("""{"url":"https://example.com"}""", parsed.argumentsJson)
    }

    @Test
    fun `an answer is an answer`() {
        assertEquals("The page is a login form.", text("The page is a login form.").content)
    }

    /**
     * The guard that keeps a tool RESULT from being executed as a fresh
     * command: neither a snapshot nor a tab list names a tool alongside
     * arguments, so neither is a call however JSON-shaped it is.
     */
    @Test
    fun `a json object that is not a call is not a call`() {
        val snapshot = """{"ref":1,"tag":"button","label":"Sign in","viewport":true}"""
        assertEquals(snapshot, text(snapshot).content)

        val tabs = """{"index":0,"title":"Home"}"""
        assertEquals(tabs, text(tabs).content)

        assertEquals("""{"name":"Sign in"}""", text("""{"name":"Sign in"}""").content)
    }

    @Test
    fun `braces inside a string do not end the object early`() {
        val parsed = call("""{"tool":"fill_input","args":{"ref":2,"text":"{not structure}"}}""")
        assertEquals("fill_input", parsed.name)
        assertEquals("""{"ref":2,"text":"{not structure}"}""", parsed.argumentsJson)
    }

    @Test
    fun `an escaped quote inside a string does not end it`() {
        val parsed = call("""{"tool":"auto_reply","args":{"text":"say \"hi\" now"}}""")
        assertEquals("auto_reply", parsed.name)
        assertEquals("""{"text":"say \"hi\" now"}""", parsed.argumentsJson)
    }

    /** A model that starts writing a call and stops must not throw. */
    @Test
    fun `unbalanced or malformed json falls back to an answer`() {
        assertEquals("""{"tool":"click","args":""", text("""{"tool":"click","args":""").content)
        assertEquals("""{"tool": }""", text("""{"tool": }""").content)
    }

    @Test
    fun `an empty reply is an empty answer`() {
        assertEquals("", text("").content)
        assertEquals("", text("   ").content)
    }

    /** The first well-formed call wins, wherever it sits. */
    @Test
    fun `text before a call does not hide it`() {
        val parsed = call("""I will scroll. {"tool":"scroll","args":{"direction":"down"}} Done.""")
        assertEquals("scroll", parsed.name)
        assertEquals("""{"direction":"down"}""", parsed.argumentsJson)
    }

    // -------------------------------------------------------- the catalogue

    @Test
    fun `the contract lists every tool with its arguments`() {
        val contract = LocalToolProtocol.contractFor(
            listOf(
                tool("click", "Click an element by its [ref]."),
                tool(
                    "navigate",
                    "Go to a URL.",
                    """{"type":"object","properties":{"url":{"type":"string"},"wait":{"type":"boolean"}},"required":["url"]}"""
                )
            )
        )
        assertTrue(contract.contains("click: Click an element by its [ref]."))
        assertTrue(contract.contains("ref (integer, required)"))
        assertTrue(contract.contains("navigate: Go to a URL."))
        assertTrue(contract.contains("url (string, required)"))
        assertTrue(contract.contains("wait (boolean)"))
        assertTrue(contract.contains(""""tool":"<tool name>""""))
    }

    @Test
    fun `a tool with no properties renders without an args clause`() {
        val contract = LocalToolProtocol.contractFor(
            listOf(tool("read_page", "Read the page.", """{"type":"object"}"""))
        )
        assertTrue(contract.contains("- read_page: Read the page."))
        assertTrue(!contract.contains("read_page: Read the page.  args:"))
    }

    // ----------------------------------------------------- the conversation

    @Test
    fun `the contract joins the existing system message`() {
        val messages = LocalToolProtocol.conversationFor(
            listOf(ChatMessage(role = "system", content = "You are a browsing agent.")),
            listOf(tool("click"))
        )
        assertEquals(1, messages.size)
        assertEquals("system", messages[0].first)
        assertTrue(messages[0].second.startsWith("You are a browsing agent."))
        assertTrue(messages[0].second.contains("click: does a thing"))
    }

    @Test
    fun `a system message is added when the history has none`() {
        val messages = LocalToolProtocol.conversationFor(
            listOf(ChatMessage(role = "user", content = "like my feed")),
            listOf(tool("click"))
        )
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].first)
        assertEquals("user" to "like my feed", messages[1])
    }

    /** The loop drops the catalogue to force a plain answer; nothing is added. */
    @Test
    fun `no tools means the messages pass through untouched`() {
        val history = listOf(
            ChatMessage(role = "system", content = "s"),
            ChatMessage(role = "user", content = "u")
        )
        assertEquals(listOf("system" to "s", "user" to "u"), LocalToolProtocol.conversationFor(history, null))
        assertEquals(listOf("system" to "s", "user" to "u"), LocalToolProtocol.conversationFor(history, emptyList()))
    }

    /**
     * An assistant turn that called a tool carries no text, so relaying it
     * verbatim would hand the model a blank turn and lose what it just did.
     */
    @Test
    fun `an assistant tool call is re-rendered as the call it made`() {
        val rendered = LocalToolProtocol.renderConversation(
            listOf(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", function = FunctionCall("click", """{"ref":3}"""))
                    )
                )
            )
        )
        assertEquals(listOf("assistant" to """{"tool":"click","args":{"ref":3}}"""), rendered)
    }

    /** llama.cpp templates know system/user/assistant, not `tool`. */
    @Test
    fun `a tool result is relabelled as a user turn`() {
        val rendered = LocalToolProtocol.renderConversation(
            listOf(ChatMessage(role = "tool", content = "clicked [3]", toolCallId = "c1"))
        )
        assertEquals(listOf("user" to "RESULT: clicked [3]"), rendered)
    }

    @Test
    fun `blank turns are dropped rather than sent as empty messages`() {
        val rendered = LocalToolProtocol.renderConversation(
            listOf(
                ChatMessage(role = "assistant", content = null),
                ChatMessage(role = "tool", content = "   "),
                ChatMessage(role = "user", content = "go")
            )
        )
        assertEquals(listOf("user" to "go"), rendered)
    }

    /** A full step: act, get the result back, and still read the next call. */
    @Test
    fun `a history with a completed call round-trips`() {
        val messages = LocalToolProtocol.conversationFor(
            listOf(
                ChatMessage(role = "system", content = "You are a browsing agent."),
                ChatMessage(role = "user", content = "like the visible posts"),
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("auto_like", "{}")))
                ),
                ChatMessage(role = "tool", content = "liked 7 posts", toolCallId = "c1")
            ),
            listOf(tool("auto_like", "Like the visible posts.", """{"type":"object"}"""))
        )
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.first })
        assertEquals("""{"tool":"auto_like","args":{}}""", messages[2].second)
        assertEquals("RESULT: liked 7 posts", messages[3].second)
        assertTrue(messages[0].second.contains("auto_like: Like the visible posts."))
    }

    /** A JsonObject argument survives the round trip into the tool executor. */
    @Test
    fun `nested arguments are preserved verbatim`() {
        val parsed = call(
            """{"tool":"fill_input","args":{"ref":1,"text":"a","meta":{"deep":[1,2,{"x":true}]}}}"""
        )
        val args: JsonObject = AgentJson.parseToJsonElement(parsed.argumentsJson).jsonObject
        assertEquals(3, args.size)
        assertTrue(parsed.argumentsJson.contains(""""deep":[1,2,{"x":true}]"""))
    }
}
