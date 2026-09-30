package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.ActionGate
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.SystemOneQuestion
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the decision endpoint client, against a real local HTTP
 * server: the request the gate actually sends, what it does with each answer,
 * and — the part that matters most — that every failure arrives as a throw
 * rather than as a silent "no objections".
 */
class SystemOneClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A client pointed at the mock server (base URL has no `/v1` suffix). */
    private fun client(apiKey: String = ""): SystemOneClient =
        SystemOneClient(OkHttpClient(), server.url("/").toString(), apiKey)

    private fun choiceBody(choice: String, probability: Double): String =
        """{"model":"nimble","answers":{"action":{"type":"choice","choice":"$choice",""" +
            """"probabilities":{"$choice":$probability},"confidence":0.9}},""" +
            """"usage":{"input_tokens":42,"output_tokens":1}}"""

    private fun ask(): okhttp3.mockwebserver.RecordedRequest = server.takeRequest()

    // ---------- the request ----------

    @Test
    fun `posts to v1 systemone with the question and the action state`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))

        client().decideAction(
            model = "nimble",
            action = "Click [12] Sign in",
            pageUrl = "https://example.com/login",
            pageTitle = "Example",
            policy = null
        )

        val request = ask()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/systemone")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"model\":\"nimble\"")
        assertThat(body).contains("\"agent_action\":\"Click [12] Sign in\"")
        assertThat(body).contains("\"page_url\":\"https://example.com/login\"")
        assertThat(body).contains("\"page_title\":\"Example\"")
        assertThat(body).contains("\"questions\":{\"action\"")
    }

    @Test
    fun `keeps the model loaded between decisions`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))
        client().decideAction("nimble", "Scroll down", null, null, null)
        assertThat(ask().body.readUtf8()).contains("\"keep_alive\":\"${SystemOneClient.KEEP_ALIVE}\"")
    }

    @Test
    fun `a base url with a trailing slash still resolves the endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))
        SystemOneClient(OkHttpClient(), server.url("/").toString() + "/", "")
            .decideAction("nimble", "Scroll down", null, null, null)
        assertThat(ask().path).isEqualTo("/v1/systemone")
    }

    @Test
    fun `sends the bearer header only when a key is set`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))
        client(apiKey = "secret").decideAction("nimble", "Scroll", null, null, null)
        assertThat(ask().getHeader("Authorization")).isEqualTo("Bearer secret")

        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))
        client().decideAction("nimble", "Scroll", null, null, null)
        assertThat(ask().getHeader("Authorization")).isNull()
    }

    @Test
    fun `a base url that is not http is rejected up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemOneClient(OkHttpClient(), "localhost:11434", "")
        }
    }

    // ---------- the answer ----------

    @Test
    fun `a confident allow becomes an allow verdict`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.93)))
        val response = client().decideAction("nimble", "Scroll down", null, null, null)
        assertThat(response.answers).containsKey(ActionGate.QUESTION)
        assertThat(ActionGate.verdict(response)).isEqualTo(ActionVerdict.Allow)
    }

    @Test
    fun `a deny becomes a refusal carrying the model's reason`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("deny", 0.81)))
        val response = client().decideAction("nimble", "Click [3] Delete account", null, null, null)
        val verdict = ActionGate.verdict(response)
        assertThat(verdict).isInstanceOf(ActionVerdict.Deny::class.java)
        assertThat((verdict as ActionVerdict.Deny).reason).contains("81%")
    }

    @Test
    fun `a confirm falls through to the user`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("confirm", 0.9)))
        val response = client().decideAction("nimble", "Type into [4]", null, null, null)
        assertThat(ActionGate.verdict(response)).isInstanceOf(ActionVerdict.Ask::class.java)
    }

    @Test
    fun `a custom policy replaces the built-in instructions`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))
        client().decideAction("nimble", "Scroll", null, null, "Never sign in anywhere.")
        val body = ask().body.readUtf8()
        assertThat(body).contains("Never sign in anywhere.")
        assertThat(body).doesNotContain(ActionGate.DEFAULT_POLICY)
    }

    @Test
    fun `the criteria offer the three options the gate understands`() = runTest {
        server.enqueue(MockResponse().setBody(choiceBody("allow", 0.95)))
        client().decide(
            model = "nimble",
            state = JsonObject(mapOf("agent_action" to JsonPrimitive("Scroll"))),
            questions = mapOf(
                "action" to SystemOneQuestion.Choice("judge it", mapOf("allow" to "fine"))
            )
        )
        val body = AgentJson.parseToJsonElement(ask().body.readUtf8()).toString()
        assertThat(body).contains("\"criteria\":{\"allow\":\"fine\"}")
    }

    // ---------- failures (must never read as "allow") ----------

    @Test
    fun `an older server without the endpoint throws with its status`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":"unknown endpoint"}""")
        )
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { client().decideAction("nimble", "Scroll", null, null, null) }
        }
        assertThat(thrown.code).isEqualTo(404)
    }

    @Test
    fun `a 200 with nothing readable throws rather than answering nothing`() = runTest {
        server.enqueue(MockResponse().setBody("""{"model":"nimble"}"""))
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { client().decideAction("nimble", "Scroll", null, null, null) }
        }
        assertThat(thrown.message).contains("no answers")
    }

    @Test
    fun `a body over the endpoint's limit surfaces the server's 413`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(413)
                .setBody("""{"error":"request body must not exceed 64 KiB"}""")
        )
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { client().decideAction("nimble", "Scroll", null, null, null) }
        }
        assertThat(thrown.code).isEqualTo(413)
    }

    @Test
    fun `an unreachable server throws a network error`() = runTest {
        val dead = SystemOneClient(OkHttpClient(), "http://127.0.0.1:1", "")
        val thrown = assertThrows(AgentHttpException::class.java) {
            kotlinx.coroutines.runBlocking { dead.decideAction("nimble", "Scroll", null, null, null) }
        }
        assertThat(thrown.code).isEqualTo(-1)
    }
}
