package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The request/reply bookkeeping of a CDP session, over a fake transport so it
 * is pinned on the JVM rather than against a device that may expose no socket.
 *
 * The transport is deliberately dumb: it hands back whatever the test tells it
 * to, in order, so what is under test is the session's routing -- which message
 * completes which request, and what is left behind when one never completes.
 */
class CdpProtocolSessionTest {

    /**
     * A transport that answers every write with [replyFor], tagged with the id
     * the request carried. A reply list is enqueued up front, so the reader
     * thread pulls responses in the order a real server would send them.
     */
    private class FakeTransport(
        private val replyFor: (request: String, id: Int) -> List<String> = { _, id ->
            listOf("""{"id":$id,"result":{"ok":true}}""")
        }
    ) : CdpTransport {

        private val inbound = LinkedBlockingQueue<String>()
        val sent = CopyOnWriteArrayList<String>()

        @Volatile
        var failWrites = false

        @Volatile
        private var closed = false

        override fun sendText(text: String) {
            if (failWrites) throw IOException("broken pipe")
            sent.add(text)
            val id = cdpMessageId(text) ?: return
            replyFor(text, id).forEach { inbound.add(it) }
        }

        override fun readTextMessage(deadlineMs: Long): String? {
            while (!closed) {
                val next = inbound.poll(50, TimeUnit.MILLISECONDS)
                if (next != null) return next
            }
            return null
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun a_reply_completes_the_request_whose_id_it_carries() {
        val transport = FakeTransport()
        val session = CdpProtocolSession(transport)
        try {
            val reply = runBlocking { session.send("Runtime.evaluate", "{}") }
            assertThat(reply).contains("\"ok\":true")
            // The request went out with the session's own id, and the reply was
            // matched to it rather than assumed to be the next thing.
            assertThat(transport.sent.single()).contains("\"id\":2")
        } finally {
            session.close()
        }
    }

    @Test
    fun an_unrelated_id_is_not_taken_for_the_reply() {
        val transport = FakeTransport { _, id ->
            // A notification-shaped message with a foreign id arrives first; only
            // the matching id may complete the request.
            listOf("""{"id":999,"result":{"ok":"wrong"}}""", """{"id":$id,"result":{"ok":"right"}}""")
        }
        val session = CdpProtocolSession(transport)
        try {
            val reply = runBlocking { session.send("Runtime.evaluate", "{}") }
            assertThat(reply).contains("\"ok\":\"right\"")
            assertThat(reply).doesNotContain("wrong")
        } finally {
            session.close()
        }
    }

    @Test
    fun a_failed_write_leaves_no_pending_entry() {
        val transport = FakeTransport().apply { failWrites = true }
        val session = CdpProtocolSession(transport)
        try {
            val failure = runBlocking { runCatching { session.send("Runtime.evaluate", "{}") } }
            assertThat(failure.isFailure).isTrue()
            // The write threw AFTER the id was registered; the entry must be gone.
            assertThat(session.pendingCount).isEqualTo(0)
        } finally {
            session.close()
        }
    }

    @Test
    fun close_settles_a_request_whose_reply_never_comes() {
        // Accepts every write, answers none, so the request stays pending until
        // it is cancelled.
        val transport = FakeTransport { _, _ -> emptyList() }
        val session = CdpProtocolSession(transport)
        val outcome = CompletableFuture<Throwable?>()
        Thread {
            val thrown = runCatching {
                runBlocking<Unit> { session.send("Runtime.evaluate", "{}") }
            }.exceptionOrNull()
            outcome.complete(thrown)
        }.apply {
            isDaemon = true
            start()
        }

        assertThat(waitUntil(2_000) { session.pendingCount == 1 }).isTrue()
        session.close()

        // It must settle promptly -- not after the 20s request timeout -- and it
        // must settle with a failure rather than silently returning a value.
        val thrown = outcome.get(5, TimeUnit.SECONDS)
        assertThat(thrown != null).isTrue()
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }
}
