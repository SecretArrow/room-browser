package com.roombrowser.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeParsersTest {

    // ------------------------------------------------------ tool-call parser

    @Test
    fun `fenced tool_calls block is parsed and removed`() {
        val content = """
            I will open the page first.
            ```json
            {"tool_calls":[{"function":{"name":"navigate","arguments":{"url":"https://example.com"}}}]}
            ```
        """.trimIndent()
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("navigate", parsed.toolCalls[0].function.name)
        assertEquals("""{"url":"https://example.com"}""", parsed.toolCalls[0].function.arguments)
        assertTrue("tool_calls" !in parsed.text)
        assertTrue("I will open the page first." in parsed.text)
    }

    @Test
    fun `bare tool_calls object inside prose is parsed`() {
        val content = """Let me read the page. {"tool_calls":[{"function":{"name":"read_page","arguments":{}}}]} Done."""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("read_page", parsed.toolCalls[0].function.name)
        assertEquals("{}", parsed.toolCalls[0].function.arguments)
        assertTrue("tool_calls" !in parsed.text)
        assertTrue("Let me read the page." in parsed.text)
    }

    @Test
    fun `actions array form is parsed with multiple calls`() {
        val content = """{"actions":[{"name":"scroll","args":{"direction":"down"}},{"name":"read_page","arguments":{}}]}"""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertEquals(2, parsed.toolCalls.size)
        assertEquals("scroll", parsed.toolCalls[0].function.name)
        assertEquals("read_page", parsed.toolCalls[1].function.name)
        assertEquals("""{"direction":"down"}""", parsed.toolCalls[0].function.arguments)
    }

    @Test
    fun `single name plus arguments object is parsed`() {
        val content = """Sure! {"name":"click","arguments":{"ref":3}}"""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("click", parsed.toolCalls[0].function.name)
        assertEquals("""{"ref":3}""", parsed.toolCalls[0].function.arguments)
    }

    @Test
    fun `string arguments are passed through verbatim`() {
        val content = """{"tool_calls":[{"function":{"name":"fill_input","arguments":"{\"ref\":2,\"text\":\"hi\"}"}}]}"""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("""{"ref":2,"text":"hi"}""", parsed.toolCalls[0].function.arguments)
    }

    @Test
    fun `plain answer without tool blocks stays intact`() {
        val content = "The page lists three products. Task complete."
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertTrue(parsed.toolCalls.isEmpty())
        assertEquals(content, parsed.text)
    }

    @Test
    fun `non-tool JSON objects are left visible`() {
        val content = """Here is the data: {"products":[{"id":1}]} — nothing to click."""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertTrue(parsed.toolCalls.isEmpty())
        assertTrue("""{"products":[{"id":1}]}""" in parsed.text)
    }

    @Test
    fun `disabled parser returns the raw text`() {
        val content = """{"tool_calls":[{"function":{"name":"read_page","arguments":{}}}]}"""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = false)
        assertTrue(parsed.toolCalls.isEmpty())
        assertEquals(content, parsed.text)
    }

    @Test
    fun `synthetic ids are assigned`() {
        val content = """{"actions":[{"name":"scroll","arguments":{}},{"name":"read_page","arguments":{}}]}"""
        val parsed = OpenCodeToolCallParser.parse(content, enabled = true)
        assertEquals(listOf("text_0", "text_1"), parsed.toolCalls.map { it.id })
    }

    // ------------------------------------------------------ models parser

    @Test
    fun `provider array with model objects is flattened to provider slash model`() {
        val body = """[{"id":"anthropic","models":[{"id":"claude-sonnet-4","name":"Sonnet"}]},{"id":"opencode","models":[{"id":"dev"}]}]"""
        assertEquals(
            listOf("anthropic/claude-sonnet-4", "opencode/dev"),
            OpenCodeModelsParser.parse(body)
        )
    }

    @Test
    fun `provider map with models map is flattened`() {
        val body = """{"groq":{"models":{"llama-3.3-70b":{"name":"Llama"}}}}"""
        assertEquals(listOf("groq/llama-3.3-70b"), OpenCodeModelsParser.parse(body))
    }

    @Test
    fun `data wrapper delegates to bare ids`() {
        val body = """{"data":[{"id":"m1"},{"id":"m2"}]}"""
        assertEquals(listOf("m1", "m2"), OpenCodeModelsParser.parse(body))
    }

    @Test
    fun `garbage input yields an empty list`() {
        assertTrue(OpenCodeModelsParser.parse("not json at all").isEmpty())
    }

    // ------------------------------------------------------ wire

    @Test
    fun `message body splits provider and model`() {
        val body = OpenCodeWire.messageBody("anthropic/claude-sonnet-4", "hello \"world\"")
        assertTrue(body.contains("\"providerID\":\"anthropic\""))
        assertTrue(body.contains("\"modelID\":\"claude-sonnet-4\""))
        assertTrue(body.contains("\"type\":\"text\",\"text\":\"hello \\\"world\\\"\""))
    }

    @Test
    fun `message body without provider omits providerID`() {
        val body = OpenCodeWire.messageBody("some-model", "hi")
        assertTrue(!body.contains("providerID"))
        assertTrue(body.contains("\"modelID\":\"some-model\""))
    }
}
