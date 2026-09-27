package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.StreamEvent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the OpenCode (opencode serve) gateway against a real local
 * HTTP server: session creation, delta messaging (only unsent messages are
 * posted), polled assistant replies with text tool-call extraction, the
 * /provider model list and timeout behavior.
 */
class OpenCodeAgentGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: OpenCodeAgentGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = OpenCodeAgentGateway(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            pollIntervalMs = 25,
            pollTotalMs = 5000
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sessionMessagesJson(vararg messages: String): String =
        """{"items":[${messages.joinToString(",")}]}"""

    private fun msg(id: String, role: String, text: String): String {
        val quoted = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"id":"$id","role":"$role","parts":[{"type":"text","text":"$quoted"}]}"""
    }

    @Test
    fun `tool call round trip then final answer`() = runTest {
        val toolReply = """
            Opening the page now.
            ```json
            {"tool_calls":[{"function":{"name":"navigate","arguments":{"url":"https://example.com"}}}]}
            ```
        """.trimIndent()

        // 1st chat(): create session → post message → poll once (tool block
        // completes immediately, no stability wait needed).
        server.enqueue(MockResponse().setBody("""{"id":"s1"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(
            MockResponse().setBody(
                sessionMessagesJson(
                    msg("u1", "user", "open example.com"),
                    msg("a1", "assistant", toolReply)
                )
            )
        )

        val first = gateway.chat(
            ChatRequest(
                model = "anthropic/claude-sonnet-4",
                messages = mutableListOf(
                    ChatMessage(role = "system", content = "You are helpful."),
                    ChatMessage(role = "user", content = "open example.com")
                ),
                stream = true,
                tools = AgentTools.toolDefs()
            ),
            events = {}
        )

        assertThat(first.toolCalls).isNotNull()
        assertThat(first.toolCalls!!.size).isEqualTo(1)
        assertThat(first.toolCalls!![0].function.name).isEqualTo("navigate")
        assertThat(first.toolCalls!![0].function.arguments)
            .isEqualTo("""{"url":"https://example.com"}""")
        assertThat(first.content).isEqualTo("Opening the page now.")

        val createRequest = server.takeRequest()
        assertThat(createRequest.path).isEqualTo("/session")
        assertThat(createRequest.getHeader("Authorization")).isEqualTo("Bearer test-key")
        assertThat(createRequest.body.readUtf8()).contains("\"title\"")

        val post1 = server.takeRequest()
        assertThat(post1.path).isEqualTo("/session/s1/message")
        val post1Body = post1.body.readUtf8()
        assertThat(post1Body).contains("\"providerID\":\"anthropic\"")
        assertThat(post1Body).contains("\"modelID\":\"claude-sonnet-4\"")
        // First message of the session carries the text-tool protocol briefing.
        assertThat(post1Body).contains("TEXT-BASED tool-call protocol")
        assertThat(post1Body).contains("\"navigate\"") // tool catalogue is embedded
        server.takeRequest() // poll GET

        // 2nd chat(): only the delta (assistant + tool result) is posted.
        server.enqueue(MockResponse().setBody("{}"))
        repeat(4) {
            server.enqueue(
                MockResponse().setBody(
                    sessionMessagesJson(
                        msg("u1", "user", "open example.com"),
                        msg("a1", "assistant", toolReply),
                        msg("a2", "assistant", "The page is open. Task complete.")
                    )
                )
            )
        }

        val streamed = StringBuilder()
        val second = gateway.chat(
            ChatRequest(
                model = "anthropic/claude-sonnet-4",
                messages = mutableListOf(
                    ChatMessage(role = "system", content = "You are helpful."),
                    ChatMessage(role = "user", content = "open example.com"),
                    first,
                    ChatMessage(role = "tool", content = "OK: navigated", toolCallId = first.toolCalls!![0].id)
                ),
                stream = true,
                tools = AgentTools.toolDefs()
            ),
            events = { if (it is StreamEvent.Text) streamed.append(it.text) }
        )

        assertThat(second.toolCalls).isNull()
        assertThat(second.content).isEqualTo("The page is open. Task complete.")
        assertThat(streamed.toString()).isEqualTo("The page is open. Task complete.")

        val post2 = server.takeRequest()
        val post2Body = post2.body.readUtf8()
        assertThat(post2Body).contains("TOOL RESULT (text_0):")
        assertThat(post2Body).contains("OK: navigated")
        // Nothing before the delta is re-sent.
        assertThat(post2Body).doesNotContain("SYSTEM:\nYou are helpful.")
    }

    @Test
    fun `listModels flattens provider slash model ids`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"anthropic","models":[{"id":"claude-sonnet-4"}]},{"id":"opencode","models":[{"id":"dev"}]}]"""
            )
        )
        val models = gateway.listModels()
        assertThat(models).containsExactly("anthropic/claude-sonnet-4", "opencode/dev").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/provider")
    }

    @Test
    fun `listModels falls back to config providers endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setBody("""{"groq":{"models":{"llama-3.3-70b":{}}}}""")
        )
        val models = gateway.listModels()
        assertThat(models).containsExactly("groq/llama-3.3-70b")
        assertThat(server.takeRequest().path).isEqualTo("/provider")
        assertThat(server.takeRequest().path).isEqualTo("/config/providers")
    }

    @Test
    fun `times out when no assistant reply appears`() = runTest {
        val timeoutGateway = OpenCodeAgentGateway(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "",
            pollIntervalMs = 10,
            pollTotalMs = 300
        )
        server.enqueue(MockResponse().setBody("""{"id":"s1"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        repeat(60) { server.enqueue(MockResponse().setBody("""{"items":[]}""")) }

        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking {
                timeoutGateway.chat(
                    ChatRequest(
                        model = "opencode/dev",
                        messages = mutableListOf(ChatMessage(role = "user", content = "hi")),
                        stream = true
                    ),
                    events = {}
                )
            }
        }
        assertThat(thrown.message).contains("did not produce an assistant reply")
    }
}
