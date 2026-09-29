package com.roombrowser.localai

import com.google.common.truth.Truth.assertThat
import com.roombrowser.localai.store.OnDeviceDownloadController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * JVM tests for the on-device model download controller against a real local
 * HTTP server (MockWebServer). The controller is built through its internal
 * constructor — temp dir + plain client — so no Android Context is involved.
 *
 * Timing philosophy (copied from OllamaLocalTest's cancellation test): throttle
 * windows are deliberately generous (30 s chunk periods, 10–15 s polling
 * deadlines) because CI runners are starved 2-core boxes; the discriminated
 * behavior (prompt cancellation, Range resume) still needs only milliseconds.
 */
class OnDeviceDownloadControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = tmp.newFolder("models")
    }

    @After
    fun tearDown() {
        // Resilient on purpose (same as OllamaLocalTest): the cancel test leaves
        // MockWebServer's throttled writer sleeping (30 s chunk period) on a
        // socket the client already abandoned via call.cancel() —
        // server.shutdown() can then surface the forced interrupt as an
        // IOException. That is harness noise from the intentional stall, not
        // a contract failure.
        runCatching { server.shutdown() }
    }

    /** Polls [condition] on the test thread until it holds or the deadline hits. */
    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        runBlocking {
            withTimeout(timeoutMs) {
                while (!condition()) delay(25)
            }
        }
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun `download completes and renames the part file`() {
        val body = "A".repeat(24 * 1024) // 3 chunks through the 8 KiB copy buffer
        server.enqueue(MockResponse().setBody(body))

        val controller = OnDeviceDownloadController(dir, OkHttpClient())
        controller.start(server.url("/qwen.gguf").toString(), "qwen.gguf")

        val finalFile = File(dir, "qwen.gguf")
        val partFile = File(dir, "qwen.gguf.part")
        // Finished entries are REMOVED (the model then shows up via the store's
        // disk list) — that is the documented completion contract.
        awaitUntil { finalFile.exists() && controller.entries.value.none { it.fileName == "qwen.gguf" } }

        assertThat(finalFile.readText()).isEqualTo(body)
        assertThat(partFile.exists()).isFalse()
        assertThat(controller.entries.value).isEmpty()
    }

    @Test
    fun `a quick download still reports a settle after its row is gone`() {
        // The exact shape the UI missed in CI: the whole download — enqueue,
        // body, rename, row removal — finishes before anything collects, so
        // `entries` goes 0 → 1 → 0 with no window in which a collector can see
        // the 1. A UI that inferred "a download settled" by comparing list
        // SIZES therefore saw 0 → 0 and never re-listed, leaving the Installed
        // chip unset for a model already on disk.
        //
        // `settled` is the signal that survives that: it is monotonic, so its
        // value still differs from the 0 the UI last read no matter how many
        // intermediate states were conflated away.
        val body = "F".repeat(4 * 1024)
        server.enqueue(MockResponse().setBody(body))

        val controller = OnDeviceDownloadController(dir, OkHttpClient())
        // What a freshly composed UI reads before the user taps Install.
        assertThat(controller.settled.value).isEqualTo(0L)

        controller.start(server.url("/fast.gguf").toString(), "fast.gguf")
        awaitUntil { controller.settled.value == 1L }

        // The event arrived — and the row that carried it is already gone,
        // which is precisely what made a list comparison blind to it.
        assertThat(controller.entries.value).isEmpty()
        assertThat(File(dir, "fast.gguf").readText()).isEqualTo(body)

        // Monotonic on every settle, not just the first: cancelling the row is
        // a settle too (a paused/orphaned row leaves the list), so a UI that
        // has already seen 1 still sees the value CHANGE for the next event.
        controller.cancel("fast.gguf")
        assertThat(controller.settled.value).isEqualTo(2L)
    }

    @Test
    fun `resume sends a range header and completes the file`() {
        val full = "B".repeat(10_000) + "C".repeat(15_000)
        // Leg 1: the connection dies partway through the body → the controller
        // records an honest error row and KEEPS the .part (the resumable state;
        // DISCONNECT_DURING_RESPONSE_BODY is MockWebServer's mid-body death).
        server.enqueue(
            MockResponse()
                .setBody(full)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val controller = OnDeviceDownloadController(dir, OkHttpClient())
        val url = server.url("/resume.gguf").toString()
        controller.start(url, "resume.gguf")

        val partFile = File(dir, "resume.gguf.part")
        awaitUntil(15_000) {
            val entry = controller.entries.value.firstOrNull { it.fileName == "resume.gguf" }
            entry != null && !entry.running && entry.error != null &&
                partFile.exists() && partFile.length() in 1 until full.length.toLong()
        }
        val partBytes = partFile.length()

        // Leg 2: 206 + exactly the missing tail.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $partBytes-${full.length - 1}/${full.length}")
                .setBody(full.substring(partBytes.toInt()))
        )
        controller.start(url, "resume.gguf")

        val finalFile = File(dir, "resume.gguf")
        awaitUntil(15_000) {
            finalFile.exists() && controller.entries.value.none { it.fileName == "resume.gguf" }
        }

        assertThat(finalFile.readText()).isEqualTo(full)
        assertThat(partFile.exists()).isFalse()

        server.takeRequest() // leg 1
        val resumed = server.takeRequest()
        assertThat(resumed.getHeader("Range")).isEqualTo("bytes=$partBytes-")
    }

    @Test
    fun `cancel removes the part file and the entry`() {
        val body = "D".repeat(200 * 1024)
        // Throttled body (8 KiB now, next chunk after 30 s): the first chunk
        // proves the .part is live while the download stalls — the exact
        // window in which the user presses Cancel. The 30 s period is CI
        // starvation headroom, the assertion deadline is not.
        server.enqueue(
            MockResponse()
                .setBody(body)
                .throttleBody(8 * 1024L, 30L, TimeUnit.SECONDS)
        )

        val controller = OnDeviceDownloadController(dir, OkHttpClient())
        controller.start(server.url("/cancel.gguf").toString(), "cancel.gguf")

        val partFile = File(dir, "cancel.gguf.part")
        awaitUntil { partFile.exists() && partFile.length() > 0 }

        controller.cancel("cancel.gguf")

        assertThat(partFile.exists()).isFalse()
        assertThat(File(dir, "cancel.gguf").exists()).isFalse()
        assertThat(controller.entries.value.map { it.fileName }).doesNotContain("cancel.gguf")
    }

    @Test
    fun `refresh list from disk revives an orphaned part file`() {
        File(dir, "orphan.gguf.part").writeBytes(ByteArray(4096))

        val controller = OnDeviceDownloadController(dir, OkHttpClient())
        controller.refreshListFromDisk()

        val entries = controller.entries.value
        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.fileName).isEqualTo("orphan.gguf")
        assertThat(entry.paused).isTrue()
        assertThat(entry.running).isFalse()
        assertThat(entry.received).isEqualTo(4096L)
        // The original URL is unknown after a process death — the row says so.
        assertThat(entry.url).isEmpty()
    }

    @Test
    fun `start refuses path traversal names with an honest error row`() {
        val controller = OnDeviceDownloadController(dir, OkHttpClient())

        controller.start("http://example.invalid/x.gguf", "../evil.gguf")

        val entry = controller.entries.value.single()
        assertThat(entry.error).isEqualTo("Invalid model file name")
        assertThat(entry.running).isFalse()
        // No job was launched, nothing was written anywhere near the store.
        assertThat(dir.listFiles()).isEmpty()
    }

    @Test
    fun `server ignoring the range restarts the file from scratch`() {
        // A half-finished .part from an interrupted attempt…
        val partFile = File(dir, "restart.gguf.part")
        partFile.writeBytes(ByteArray(1024) { 'X'.code.toByte() })
        // …and a server that answers the Range request with plain 200 + the
        // FULL body: the controller must rewrite from byte zero, not append.
        val body = "E".repeat(4096)
        server.enqueue(MockResponse().setBody(body))

        val controller = OnDeviceDownloadController(dir, OkHttpClient())
        controller.start(server.url("/restart.gguf").toString(), "restart.gguf")

        val finalFile = File(dir, "restart.gguf")
        awaitUntil { finalFile.exists() && controller.entries.value.none { it.fileName == "restart.gguf" } }

        assertThat(finalFile.readText()).isEqualTo(body)
        assertThat(finalFile.length()).isEqualTo(4096L)

        val request = server.takeRequest()
        assertThat(request.getHeader("Range")).isEqualTo("bytes=1024-")
    }
}
