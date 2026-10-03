package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.RetryPolicy
import com.roombrowser.domain.agent.StreamEvent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * JVM tests for the retry decorator. No server and no sleeping: the delegate
 * is a scripted fake and the pause is injected away, so each case asserts the
 * decision (retry or not, how many calls, how long between them) rather than
 * a wall clock.
 */
class RetryingAgentGatewayTest {

    private val request = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage(role = "user", content = "hi")),
        stream = true
    )

    /**
     * Fails with the next scripted outcome, then succeeds. [outcomes] is
     * consumed one entry per call; running out means success.
     */
    private class FakeGateway(
        private val outcomes: List<() -> Unit>,
        /** Emitted before the scripted outcome runs, to simulate a live stream. */
        private val emitBefore: Boolean = false
    ) : AgentGateway {
        var calls = 0
            private set

        override suspend fun chat(
            request: ChatRequest,
            events: suspend (StreamEvent) -> Unit
        ): ChatMessage {
            val index = calls++
            if (emitBefore) events(StreamEvent.Text("partial "))
            outcomes.getOrNull(index)?.invoke()
            return ChatMessage(role = "assistant", content = "ok")
        }

        override suspend fun listModels(): List<String> {
            outcomes.getOrNull(calls++)?.invoke()
            return listOf("m1")
        }
    }

    private fun policy(
        enabled: Boolean = true,
        attempts: Int = 3,
        codes: Set<Int> = RetryPolicy.DEFAULT_STATUS_CODES,
        connection: Boolean = true
    ) = RetryPolicy(enabled, attempts, codes, connection)

    private fun gateway(
        delegate: AgentGateway,
        policy: RetryPolicy,
        retries: MutableList<Int> = mutableListOf()
    ) = RetryingAgentGateway(
        delegate = delegate,
        policy = policy,
        wait = { },
        onRetry = { attempt, _ -> retries.add(attempt) }
    )

    @Test
    fun `a disabled policy makes exactly one call and propagates the error`() = runTest {
        val delegate = FakeGateway(listOf({ throw AgentHttpException(503, "down") }))
        val failure = runCatching {
            gateway(delegate, policy(enabled = false)).chat(request) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AgentHttpException::class.java)
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a ticked transient status is retried and can succeed`() = runTest {
        val delegate = FakeGateway(
            listOf(
                { throw AgentHttpException(503, "down") },
                { throw AgentHttpException(503, "still down") }
            )
        )
        val message = gateway(delegate, policy()).chat(request) { }

        assertThat(message.content).isEqualTo("ok")
        assertThat(delegate.calls).isEqualTo(3)
    }

    @Test
    fun `an unticked status is not retried`() = runTest {
        // 400 is in the catalog but NOT ticked by default — a malformed
        // request retried is the same malformed request.
        val delegate = FakeGateway(listOf({ throw AgentHttpException(400, "bad") }))
        val failure = runCatching {
            gateway(delegate, policy()).chat(request) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AgentHttpException::class.java)
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a permanent status becomes retryable once the user ticks it`() = runTest {
        val delegate = FakeGateway(listOf({ throw AgentHttpException(400, "bad") }))
        val message = gateway(
            delegate,
            policy(codes = RetryPolicy.DEFAULT_STATUS_CODES + 400)
        ).chat(request) { }

        assertThat(message.content).isEqualTo("ok")
        assertThat(delegate.calls).isEqualTo(2)
    }

    @Test
    fun `the attempt limit is a hard ceiling`() = runTest {
        val delegate = FakeGateway(
            List(10) { { throw AgentHttpException(500, "down") } }
        )
        val retries = mutableListOf<Int>()
        val failure = runCatching {
            gateway(delegate, policy(attempts = 3), retries).chat(request) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AgentHttpException::class.java)
        // Three attempts total, so two of them were followed by a retry.
        assertThat(delegate.calls).isEqualTo(3)
        assertThat(retries).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a turn that already reached the screen is never retried`() = runTest {
        // The delegate emits a token and THEN fails. Retrying would append a
        // second copy of the reply under the first.
        val delegate = FakeGateway(
            outcomes = listOf({ throw AgentHttpException(503, "died mid-stream") }),
            emitBefore = true
        )
        val failure = runCatching {
            gateway(delegate, policy()).chat(request) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AgentHttpException::class.java)
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a failure with no HTTP status follows the connection switch`() = runTest {
        val delegate = FakeGateway(listOf({ throw AgentHttpException(-1, "connection failed") }))
        val message = gateway(delegate, policy(connection = true)).chat(request) { }

        assertThat(message.content).isEqualTo("ok")
        assertThat(delegate.calls).isEqualTo(2)
    }

    @Test
    fun `a failure with no HTTP status is dropped when the switch is off`() = runTest {
        val delegate = FakeGateway(listOf({ throw AgentHttpException(-1, "connection failed") }))
        val failure = runCatching {
            gateway(delegate, policy(connection = false)).chat(request) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AgentHttpException::class.java)
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a plain IOException is treated as a connection failure`() = runTest {
        val delegate = FakeGateway(listOf({ throw IOException("no route to host") }))
        val message = gateway(delegate, policy(connection = true)).chat(request) { }

        assertThat(message.content).isEqualTo("ok")
        assertThat(delegate.calls).isEqualTo(2)
    }

    @Test
    fun `model listing is retried too`() = runTest {
        val delegate = FakeGateway(listOf({ throw AgentHttpException(502, "bad gateway") }))
        val models = gateway(delegate, policy()).listModels()

        assertThat(models).containsExactly("m1")
        assertThat(delegate.calls).isEqualTo(2)
    }

    @Test
    fun `every retry waits the pause the user set`() = runTest {
        val delegate = FakeGateway(
            listOf(
                { throw AgentHttpException(503, "down") },
                { throw AgentHttpException(503, "still down") }
            )
        )
        val waits = mutableListOf<Long>()
        RetryingAgentGateway(
            delegate = delegate,
            policy = policy().copy(delayMs = 6_000L),
            wait = { waits.add(it) },
            onRetry = { _, _ -> }
        ).chat(request) { }

        // Three attempts, two pauses, both the number that was configured —
        // no curve, because the setting is one value.
        assertThat(delegate.calls).isEqualTo(3)
        assertThat(waits).containsExactly(6_000L, 6_000L).inOrder()
    }

    @Test
    fun `the default pause is six seconds`() {
        assertThat(RetryPolicy().delayMs).isEqualTo(6_000L)
        assertThat(RetryPolicy().delay).isEqualTo(6_000L)
    }

    @Test
    fun `the millisecond bounds agree with the seconds the screen offers`() {
        // The screen asks in seconds and the transport waits in milliseconds.
        // The two pairs are literals in the same object, so this is what keeps
        // a change to one from silently leaving the other behind.
        assertThat(RetryPolicy.MIN_DELAY_MS).isEqualTo(RetryPolicy.MIN_DELAY_SECONDS * 1000L)
        assertThat(RetryPolicy.MAX_DELAY_MS).isEqualTo(RetryPolicy.MAX_DELAY_SECONDS * 1000L)
        assertThat(RetryPolicy.DEFAULT_DELAY_MS).isEqualTo(RetryPolicy.DEFAULT_DELAY_SECONDS * 1000L)
    }

    @Test
    fun `a pause from storage is clamped to the offered range`() {
        // The blob is written by whatever build last saved it, so the value
        // reaching the transport is bounded here and not trusted.
        assertThat(RetryPolicy(delayMs = -1L).delay).isEqualTo(RetryPolicy.MIN_DELAY_MS)
        assertThat(RetryPolicy(delayMs = 10 * 60_000L).delay).isEqualTo(RetryPolicy.MAX_DELAY_MS)
        // Zero is legal and means "retry immediately".
        assertThat(RetryPolicy(delayMs = 0L).delay).isEqualTo(0L)
    }
}
