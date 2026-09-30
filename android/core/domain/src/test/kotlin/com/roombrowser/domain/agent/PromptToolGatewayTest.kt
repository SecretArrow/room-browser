package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The decorator that gives tool calling to a provider that refuses it.
 *
 * Covered here rather than only through a provider, because what it decides —
 * whether a turn is rewritten, and whether a refused one is retried — is the
 * difference between an agent that acts and one that only talks, and it must
 * hold for every protocol without a network or an emulator in the loop.
 */

/** Transport that answers from a script and records what it was asked. */
private class ScriptedGateway(
    private val respond: (ChatRequest) -> ChatMessage
) : AgentGateway {
    val requests = mutableListOf<ChatRequest>()
    var models = 0

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        requests.add(request)
        val message = respond(request)
        // Streams what it returns, the way the real SSE gateways do.
        message.content?.takeIf { it.isNotEmpty() }?.let { events(StreamEvent.Text(it)) }
        return message
    }

    override suspend fun listModels(): List<String> {
        models++
        return listOf("m")
    }
}

class PromptToolGatewayTest {

    private fun tool(name: String) = ToolDef(
        function = ToolFunction(
            name = name,
            description = "$name does a thing",
            parameters = AgentJson.parseToJsonElement(
                """{"type":"object","properties":{"ref":{"type":"integer"}},"required":["ref"]}"""
            ).jsonObject
        )
    )

    private val tools = listOf(tool("click"))

    private fun request(tools: List<ToolDef>? = null) = ChatRequest(
        model = "m",
        messages = listOf(
            ChatMessage(role = "system", content = "You are a browsing agent."),
            ChatMessage(role = "user", content = "like the visible posts")
        ),
        tools = tools
    )

    private fun textReply(text: String) = ChatMessage(role = "assistant", content = text)

    /** Runs one turn and collects the events the CALLER would see. */
    private suspend fun PromptToolGateway.turn(
        request: ChatRequest,
        seen: MutableList<StreamEvent> = mutableListOf()
    ): Pair<ChatMessage, List<StreamEvent>> {
        val message = chat(request) { seen.add(it) }
        return message to seen
    }

    // ------------------------------------------------------------- NATIVE

    @Test
    fun `native mode hands the tools straight through`() = runTest {
        val scripted = ScriptedGateway {
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("click", """{"ref":1}""")))
            )
        }
        val (message, _) = PromptToolGateway(scripted, ToolMode.NATIVE).turn(request(tools))

        assertThat(scripted.requests).hasSize(1)
        assertThat(scripted.requests[0].tools).isEqualTo(tools)
        assertThat(message.toolCalls).hasSize(1)
    }

    @Test
    fun `native mode does not retry a refused turn`() = runTest {
        val scripted = ScriptedGateway { throw AgentHttpException(400, "tools are not supported") }
        val gateway = PromptToolGateway(scripted, ToolMode.NATIVE)

        val error = runCatching { gateway.turn(request(tools)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(AgentHttpException::class.java)
        assertThat(scripted.requests).hasSize(1)
    }

    // --------------------------------------------------------------- TEXT

    @Test
    fun `text mode drops the tools array and appends the catalogue`() = runTest {
        val scripted = ScriptedGateway { textReply("Nothing to do.") }
        PromptToolGateway(scripted, ToolMode.TEXT).turn(request(tools))

        val sent = scripted.requests.single()
        assertThat(sent.tools).isNull()
        val system = sent.messages.first { it.role == "system" }.content!!
        assertThat(system).contains("You are a browsing agent.")
        assertThat(system).contains("click: click does a thing")
        assertThat(sent.messages.map { it.role }).containsExactly("system", "user").inOrder()
    }

    @Test
    fun `a text-mode call becomes a tool call and is never shown as an answer`() = runTest {
        val scripted = ScriptedGateway { textReply("""{"tool":"click","args":{"ref":3}}""") }
        val (message, seen) = PromptToolGateway(scripted, ToolMode.TEXT).turn(request(tools))

        assertThat(message.toolCalls).hasSize(1)
        val call = message.toolCalls!!.single()
        assertThat(call.function.name).isEqualTo("click")
        assertThat(call.function.arguments).isEqualTo("""{"ref":3}""")
        assertThat(call.id).isNotEmpty()
        assertThat(message.content).isNull()
        // The raw JSON is not an answer, so it must not reach the bubble.
        assertThat(seen).isEmpty()
    }

    @Test
    fun `a text-mode answer is streamed once`() = runTest {
        val scripted = ScriptedGateway { textReply("The page is a login form.") }
        val (message, seen) = PromptToolGateway(scripted, ToolMode.TEXT).turn(request(tools))

        assertThat(seen).containsExactly(StreamEvent.Text("The page is a login form."))
        assertThat(message.content).isEqualTo("The page is a login form.")
        assertThat(message.toolCalls).isNull()
    }

    /** The catalogue is only offered while the loop still allows acting. */
    @Test
    fun `a turn with no tools is not rewritten even in text mode`() = runTest {
        val scripted = ScriptedGateway { textReply("Done.") }
        PromptToolGateway(scripted, ToolMode.TEXT).turn(request(tools = null))

        val sent = scripted.requests.single()
        assertThat(sent.messages.map { it.role }).containsExactly("system", "user").inOrder()
        assertThat(sent.messages.first().content).isEqualTo("You are a browsing agent.")
    }

    @Test
    fun `a tool result is shown to the model as an outcome, not as a tool turn`() = runTest {
        val scripted = ScriptedGateway { textReply("Done.") }
        PromptToolGateway(scripted, ToolMode.TEXT).turn(
            request(tools).copy(
                messages = listOf(
                    ChatMessage(role = "system", content = "s"),
                    ChatMessage(role = "user", content = "like the posts"),
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("click", """{"ref":3}""")))
                    ),
                    ChatMessage(role = "tool", content = "liked 7 posts", toolCallId = "c1")
                )
            )
        )

        val sent = scripted.requests.single().messages
        assertThat(sent.map { it.role }).containsExactly("system", "user", "assistant", "user").inOrder()
        // The blank-content assistant turn is re-rendered as the call it made.
        assertThat(sent[2].content).isEqualTo("""{"tool":"click","args":{"ref":3}}""")
        assertThat(sent[3].content).isEqualTo("RESULT: liked 7 posts")
    }

    @Test
    fun `successive text-mode calls get distinct ids`() = runTest {
        val scripted = ScriptedGateway { textReply("""{"tool":"click","args":{}}""") }
        val gateway = PromptToolGateway(scripted, ToolMode.TEXT)

        val first = gateway.turn(request(tools)).first.toolCalls!!.single().id
        val second = gateway.turn(request(tools)).first.toolCalls!!.single().id

        assertThat(first).isNotEqualTo(second)
    }

    // --------------------------------------------------------------- AUTO

    @Test
    fun `auto mode leaves a provider that accepts tools alone`() = runTest {
        val scripted = ScriptedGateway { textReply("All done.") }
        PromptToolGateway(scripted, ToolMode.AUTO).turn(request(tools))

        assertThat(scripted.requests.single().tools).isEqualTo(tools)
    }

    @Test
    fun `auto mode retries in text when the provider refuses tools`() = runTest {
        val scripted = ScriptedGateway { sent ->
            if (sent.tools != null) {
                throw AgentHttpException(400, """{"error":{"message":"tools are not supported"}}""")
            }
            textReply("""{"tool":"click","args":{"ref":3}}""")
        }
        val (message, _) = PromptToolGateway(scripted, ToolMode.AUTO).turn(request(tools))

        assertThat(scripted.requests).hasSize(2)
        assertThat(scripted.requests[0].tools).isEqualTo(tools)
        assertThat(scripted.requests[1].tools).isNull()
        assertThat(message.toolCalls!!.single().function.name).isEqualTo("click")
    }

    /** The downgrade is learned, so only the first turn pays for it. */
    @Test
    fun `auto mode remembers the refusal`() = runTest {
        val scripted = ScriptedGateway { sent ->
            if (sent.tools != null) throw AgentHttpException(400, "unknown field: tools")
            textReply("Done.")
        }
        val gateway = PromptToolGateway(scripted, ToolMode.AUTO)

        gateway.turn(request(tools))
        gateway.turn(request(tools))

        // Two requests for the first turn (refused, then retried), one for the second.
        assertThat(scripted.requests).hasSize(3)
        assertThat(scripted.requests.map { it.tools }).containsExactly(tools, null, null).inOrder()
    }

    /**
     * A server fault is the provider's problem, not a verdict on tools —
     * retrying it in text would hide an outage behind a silent downgrade.
     */
    @Test
    fun `auto mode does not fall back on a server error`() = runTest {
        val scripted = ScriptedGateway { throw AgentHttpException(503, "upstream unavailable") }
        val gateway = PromptToolGateway(scripted, ToolMode.AUTO)

        val error = runCatching { gateway.turn(request(tools)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(AgentHttpException::class.java)
        assertThat((error as AgentHttpException).code).isEqualTo(503)
        assertThat(scripted.requests).hasSize(1)
    }

    /** A client error that says nothing about tools is about something else. */
    @Test
    fun `auto mode does not fall back on an unrelated client error`() = runTest {
        val scripted = ScriptedGateway { throw AgentHttpException(401, "invalid API key") }
        val gateway = PromptToolGateway(scripted, ToolMode.AUTO)

        val error = runCatching { gateway.turn(request(tools)) }.exceptionOrNull()

        assertThat((error as AgentHttpException).code).isEqualTo(401)
        assertThat(scripted.requests).hasSize(1)
    }

    @Test
    fun `a network failure below the status line is not a refusal`() = runTest {
        val scripted = ScriptedGateway { throw AgentHttpException(-1, "stream interrupted") }
        val gateway = PromptToolGateway(scripted, ToolMode.AUTO)

        assertThat(runCatching { gateway.turn(request(tools)) }.exceptionOrNull())
            .isInstanceOf(AgentHttpException::class.java)
        assertThat(scripted.requests).hasSize(1)
    }

    // -------------------------------------------------------------- other

    @Test
    fun `listing models is left to the wrapped gateway`() = runTest {
        val scripted = ScriptedGateway { textReply("x") }
        val gateway = PromptToolGateway(scripted, ToolMode.TEXT)

        assertThat(gateway.listModels()).containsExactly("m")
        assertThat(scripted.models).isEqualTo(1)
    }

    @Test
    fun `a stored tool mode is read back, and anything unknown is auto`() {
        assertThat(ToolMode.fromStored("TEXT")).isEqualTo(ToolMode.TEXT)
        assertThat(ToolMode.fromStored("native")).isEqualTo(ToolMode.NATIVE)
        assertThat(ToolMode.fromStored(null)).isEqualTo(ToolMode.AUTO)
        assertThat(ToolMode.fromStored("")).isEqualTo(ToolMode.AUTO)
        assertThat(ToolMode.fromStored("nonsense")).isEqualTo(ToolMode.AUTO)
    }
}
