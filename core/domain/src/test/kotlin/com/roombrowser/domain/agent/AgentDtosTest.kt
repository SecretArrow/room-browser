package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentDtosTest {

    // ---------- ModelListParser ----------

    @Test
    fun `openai style models list`() {
        val body = """{"object":"list","data":[{"id":"glm-4.6"},{"id":"glm-4-flash"}]}"""
        assertThat(ModelListParser.parse(body)).containsExactly("glm-4.6", "glm-4-flash").inOrder()
    }

    @Test
    fun `bare array of strings`() {
        assertThat(ModelListParser.parse("""["b-model","a-model"]"""))
            .containsExactly("a-model", "b-model").inOrder() // sorted + distinct
    }

    @Test
    fun `string ids inside data`() {
        assertThat(ModelListParser.parse("""{"data":["m1","m2","m1"]}"""))
            .containsExactly("m1", "m2")
    }

    @Test
    fun `alternate models key`() {
        assertThat(ModelListParser.parse("""{"models":[{"id":"local"}]}"""))
            .containsExactly("local")
    }

    @Test
    fun `garbage body yields empty list`() {
        assertThat(ModelListParser.parse("<html>blocked</html>")).isEmpty()
        assertThat(ModelListParser.parse("")).isEmpty()
    }

    // ---------- Request wire format ----------

    @Test
    fun `chat request serializes to the openai wire format`() {
        val request = ChatRequest(
            model = "glm-4.6",
            messages = listOf(
                ChatMessage(role = "system", content = "sys"),
                ChatMessage(role = "user", content = "hi")
            ),
            stream = true,
            tools = AgentTools.toolDefs(),
            temperature = 0.2
        )
        val json = AgentJson.encodeToString(ChatRequest.serializer(), request)
        assertThat(json).contains("\"model\":\"glm-4.6\"")
        assertThat(json).contains("\"stream\":true")
        assertThat(json).contains("\"tools\":[")
        assertThat(json).contains("\"type\":\"function\"")
        assertThat(json).contains("\"temperature\":0.2")
        assertThat(json).contains("\"messages\":[{\"role\":\"system\",\"content\":\"sys\"}")
    }

    @Test
    fun `null content and absent tools are omitted`() {
        val assistant = ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("navigate", "{}")))
        )
        val json = AgentJson.encodeToString(ChatMessage.serializer(), assistant)
        assertThat(json).doesNotContain("\"content\"")
        assertThat(json).contains("\"tool_calls\"")
        assertThat(json).contains("\"id\":\"c1\"")
        assertThat(json).contains("\"arguments\":\"{}\"")
    }

    @Test
    fun `tool message carries tool_call_id`() {
        val msg = ChatMessage(role = "tool", content = "ok", toolCallId = "c9")
        val json = AgentJson.encodeToString(ChatMessage.serializer(), msg)
        assertThat(json).contains("\"tool_call_id\":\"c9\"")
    }

    // ---------- Response / chunk decoding ----------

    @Test
    fun `non streaming response decodes with unknown fields ignored`() {
        val body = """
            {"id":"x","object":"chat.completion","created":1,"model":"m",
             "usage":{"prompt_tokens":1},
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"answer","refusal":null}}]}
        """.trimIndent()
        val parsed = AgentJson.decodeFromString(ChatResponse.serializer(), body)
        assertThat(parsed.firstMessage!!.content).isEqualTo("answer")
    }

    @Test
    fun `stream chunk with tool call delta decodes`() {
        val body = """
            {"choices":[{"index":0,"delta":{"tool_calls":[
                {"index":0,"id":"call_1","type":"function",
                 "function":{"name":"navigate","arguments":"{\"url\":"}}]}}]}
        """.trimIndent()
        val chunk = AgentJson.decodeFromString(StreamChunk.serializer(), body)
        val dtc = chunk.choices[0].delta!!.toolCalls!![0]
        assertThat(dtc.id).isEqualTo("call_1")
        assertThat(dtc.function!!.name).isEqualTo("navigate")
        assertThat(dtc.function!!.arguments).isEqualTo("{\"url\":")
    }

    @Test
    fun `reasoning content delta decodes`() {
        val body = """{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}"""
        val chunk = AgentJson.decodeFromString(StreamChunk.serializer(), body)
        assertThat(chunk.choices[0].delta!!.reasoningContent).isEqualTo("thinking...")
    }

    // ---------- Tool catalogue / snapshot formatting ----------

    @Test
    fun `tool defs cover the interactive catalogue`() {
        val names = AgentTools.toolDefs().map { it.function.name }
        assertThat(names).containsAtLeast(
            AgentTools.NAVIGATE, AgentTools.READ_PAGE, AgentTools.CLICK,
            AgentTools.FILL_INPUT, AgentTools.PRESS_ENTER, AgentTools.SCROLL
        )
        AgentTools.toolDefs().forEach { def ->
            assertThat(def.function.parameters.containsKey("type")).isTrue()
        }
    }

    @Test
    fun `snapshot formatting truncates text and lists elements`() {
        val snapshot = PageSnapshotDto(
            url = "https://example.com",
            title = "Example",
            text = "a".repeat(20_000),
            scrollY = 10,
            maxScrollY = 5000,
            elements = listOf(
                SnapElement(ref = 1, tag = "a", label = "More information", viewport = true, href = "/more"),
                SnapElement(ref = 2, tag = "input", label = "Search", viewport = true, type = "search")
            )
        )
        val formatted = PageSnapshotFormatter.format(snapshot)
        assertThat(formatted).contains("URL: https://example.com")
        assertThat(formatted).contains("SCROLL: 10/5000")
        assertThat(formatted).contains("…[middle omitted]…")
        assertThat(formatted).contains("[1] <a> \"More information\" -> /more  (in viewport)")
        assertThat(formatted).contains("[2] <input type=search> \"Search\"")
    }

    @Test
    fun `describeTool builds labels leniently`() {
        assertThat(AgentTools.describeTool(AgentTools.NAVIGATE, """{"url":"https://x.dev"}"""))
            .isEqualTo("Open https://x.dev")
        assertThat(AgentTools.describeTool(AgentTools.CLICK, """{"ref":12}"""))
            .isEqualTo("Click [12]")
        assertThat(AgentTools.describeTool(AgentTools.FILL_INPUT, "not json"))
            .isEqualTo("Type into [?]")
        assertThat(AgentTools.describeTool(AgentTools.READ_PAGE, null))
            .isEqualTo("Read current page")
    }

    @Test
    fun `prompt renders placeholders`() {
        val rendered = AgentPrompts.render(java.time.LocalDate.of(2026, 9, 27), "Google")
        assertThat(rendered).contains("2026-09-27")
        assertThat(rendered).contains("Google")
        assertThat(rendered).doesNotContain("{DATE}")
        assertThat(rendered).doesNotContain("{ENGINE}")
    }
}
