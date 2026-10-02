package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.RetryPolicy
import com.roombrowser.domain.agent.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries a failed provider request according to a [RetryPolicy].
 *
 * A decorator rather than a change inside each gateway, for the same reason
 * [PromptToolGateway] is one: there are five transports (OpenAI-compatible,
 * Anthropic, Ollama, opencode, the embedded llama.cpp engine) and a retry
 * rule written into any one of them would be wrong in the other four the
 * day it changed. This sits OUTSIDE the tool-mode decorator, so a retry
 * re-runs the whole turn — including the text-protocol round trips — rather
 * than resuming a half-finished conversation.
 *
 * ## What is never retried
 *
 * **A turn that already reached the screen.** [chat] streams tokens to
 * [events] as they arrive, so an attempt that fails after emitting has
 * already put a partial reply in front of the user; sending the request
 * again would append a second copy under the first, and the transcript
 * would read as though the model answered twice. Once an attempt has
 * emitted, its failure is final and propagates. This is why the policy's
 * connection-failure switch — the one that matters most in practice — is
 * honest about only covering failures before the first token.
 *
 * **Cancellation.** A cancelled turn is the user closing the panel, not a
 * provider fault; swallowing it into a retry would relaunch a request the
 * user just dismissed.
 *
 * A failure carrying no HTTP status is reported by the gateways as code
 * `-1` (connection refused, DNS, TLS, interrupted stream). That is not a
 * status to match against [RetryPolicy.statusCodes], so it is routed to
 * [RetryPolicy.retriesConnectionFailure] instead.
 */
class RetryingAgentGateway(
    private val delegate: AgentGateway,
    private val policy: RetryPolicy,
    /** Delay before the next attempt; injected so tests do not wait. */
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** Called before each retry, for a log line. No-op by default. */
    private val onRetry: (attempt: Int, reason: String) -> Unit = { _, _ -> }
) : AgentGateway {

    override suspend fun chat(
        request: ChatRequest,
        events: suspend (StreamEvent) -> Unit
    ): ChatMessage {
        if (!policy.enabled) return delegate.chat(request, events)
        // Per attempt, not per gateway: an attempt that emitted is the one
        // whose failure must not be retried.
        var emitted = false
        val observed: suspend (StreamEvent) -> Unit = { event ->
            emitted = true
            events(event)
        }
        return attempt(producedOutput = { emitted }) {
            delegate.chat(request, observed)
        }
    }

    override suspend fun listModels(): List<String> =
        if (!policy.enabled) delegate.listModels() else attempt(producedOutput = { false }) {
            delegate.listModels()
        }

    /**
     * Runs [block] up to [RetryPolicy.attempts] times. [producedOutput] is
     * consulted on failure: when it says the attempt already showed the user
     * something, the failure is rethrown untouched.
     */
    private suspend fun <T> attempt(
        producedOutput: () -> Boolean,
        block: suspend () -> T
    ): T {
        val max = policy.attempts
        var attemptNo = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = when {
                    producedOutput() -> false
                    e is AgentHttpException && e.code >= HTTP_STATUS_FLOOR ->
                        policy.retriesStatus(e.code)
                    else -> policy.retriesConnectionFailure()
                }
                if (!retryable || attemptNo == max) throw e
                onRetry(attemptNo, e.message ?: e.javaClass.simpleName)
                wait(backoffMillis(attemptNo))
                attemptNo++
            }
        }
    }

    companion object {
        /**
         * Below this, a code is not an HTTP status: the gateways use `-1` for
         * "no response at all". Real statuses start at 100.
         */
        private const val HTTP_STATUS_FLOOR = 100

        private const val BASE_DELAY_MS = 300L
        private const val MAX_DELAY_MS = 4_000L

        /**
         * Doubling delay, capped. Three attempts therefore wait 300 ms and
         * then 600 ms — long enough to clear a momentary gateway hiccup,
         * short enough that a provider which is truly down is abandoned
         * inside a second instead of looking like a hung app.
         */
        fun backoffMillis(failedAttempt: Int): Long {
            val shift = (failedAttempt - 1).coerceIn(0, 16)
            return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        }
    }
}
