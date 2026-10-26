package com.roombrowser

import android.content.Context
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.engine.EngineDownloadStream
import com.roombrowser.engine.EngineSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A download whose bytes only exist inside the page.
 *
 * WHY THIS TEST EXISTS. A page that exports a file builds a `Blob` and points an
 * anchor at `URL.createObjectURL(blob)`. WebView reports that as a download with
 * a `blob:https://origin/uuid` URL, and the app used to hand that URL to its HTTP
 * download engine — which cannot fetch it, so the row appeared and died on the
 * scheme while the file the page had already built was never written. The fix
 * reads the bytes out of the page instead; this drives the REAL engine session
 * through the app's own [ProfileEngine] facade to prove the whole path works on
 * an actual WebView: the injected reader script, the `RoomBlob` interface, the
 * slice-by-slice transport, the temp file, and the name the anchor carried.
 *
 * The payload is deliberately larger than one slice, so the chunking is
 * exercised rather than assumed.
 *
 * The engine runs in `:browser` for the app, but instrumentation runs in the
 * default process, so the session created here is a real WebView in THIS
 * process — the same posture [DevToolsProtocolSpikeE2eTest] takes.
 */
@RunWith(AndroidJUnit4::class)
class BlobDownloadE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private var session: EngineSession? = null

    private val payloadBytes = 700_000
    private val fileName = "e2e-export.bin"

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(page())
        )
        server.start()

        session = newSession()
        session?.let { load(it) }
    }

    @After
    fun tearDown() {
        session?.let { s ->
            instrumentation.runOnMainSync { runCatching { s.close() } }
        }
        session = null
        runCatching { server.shutdown() }
    }

    @Test
    fun a_blob_download_is_read_out_of_the_page_and_handed_back_whole() {
        val s = session
        if (s == null) {
            fail("the engine created no session in this process")
            return
        }

        val blobUrl = evalString("window.__blobUrl")
        if (blobUrl == null || !blobUrl.startsWith("blob:")) {
            fail(
                "the page never produced a blob: URL (got '$blobUrl'), " +
                    "so the read could not be attempted"
            )
            return
        }

        val latch = CountDownLatch(1)
        val body = AtomicReference<EngineDownloadStream?>(null)
        s.openDownloadStream(blobUrl) {
            body.set(it)
            latch.countDown()
        }
        assertTrue("openDownloadStream never answered", latch.await(60, TimeUnit.SECONDS))

        val download = body.get()
        if (download == null) {
            fail(
                "the engine could not read the page's blob: " +
                    "the bytes the page built were not handed back"
            )
            return
        }
        val bytes = download.stream.use { it.readBytes() }

        assertEquals("the bytes handed back are not the bytes the page built", payloadBytes, bytes.size)
        assertEquals(
            "the bytes handed back are not the bytes the page built",
            expectedChecksum(),
            checksum(bytes)
        )
        assertEquals(
            "the filename the page's anchor carried did not travel with the bytes",
            fileName,
            download.suggestedName
        )
        Unit
    }

    @Test
    fun a_blob_url_the_page_never_created_is_reported_as_unreadable() {
        val s = session
        if (s == null) {
            fail("the engine created no session in this process")
            return
        }

        val latch = CountDownLatch(1)
        val body = AtomicReference<EngineDownloadStream?>(null)
        s.openDownloadStream("blob:${server.url("/")}missing-object") {
            body.set(it)
            latch.countDown()
        }
        assertTrue("openDownloadStream never answered", latch.await(60, TimeUnit.SECONDS))

        assertNull(
            "a blob the page never created must come back as nothing, not as an empty file",
            body.get()
        )
        Unit
    }

    // ---- harness ----------------------------------------------------------

    /**
     * The page under test: builds a blob larger than one slice, publishes its
     * URL for the test to read, and hangs an anchor with the name on it — the
     * anchor being the only place a page ever states a filename for a blob.
     */
    private fun page(): String = """
        <!DOCTYPE html>
        <html><body>
        <h1>ROOM-BLOB-DOWNLOAD-E2E</h1>
        <script>
          var N = $payloadBytes;
          var bytes = new Uint8Array(N);
          for (var i = 0; i < N; i++) bytes[i] = i % 251;
          window.__blobUrl = URL.createObjectURL(
            new Blob([bytes], { type: 'application/octet-stream' })
          );
          var a = document.createElement('a');
          a.href = window.__blobUrl;
          a.download = '$fileName';
          document.body.appendChild(a);
        </script>
        </body></html>
    """.trimIndent()

    private fun newSession(): EngineSession? {
        val profileId = ensureBoundProfile() ?: return null
        val profile = Profile(
            id = profileId,
            name = "Blob download",
            createdAt = System.currentTimeMillis()
        )
        var created: EngineSession? = null
        instrumentation.runOnMainSync {
            created = runCatching {
                ProfileEngine.createSession(targetContext, profile, "blob-download-e2e", false)
            }.getOrNull()
            created?.let { s ->
                // A real tab is attached; so is this one. The session keeps the
                // host alive, so nothing here needs to.
                s.attachTo(FrameLayout(targetContext))
            }
        }
        return created
    }

    private fun load(session: EngineSession) {
        val url = server.url("/export").toString()
        instrumentation.runOnMainSync { session.loadUri(url) }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (session.url?.startsWith(url.substringBefore('?')) == true) return
            Thread.sleep(100)
        }
    }

    /**
     * Bind this process, or adopt the binding an earlier suite already made.
     *
     * The binding is once-per-process and irreversible, so whichever suite runs
     * first takes it and the rest reuse it rather than tripping the exclusivity
     * guard.
     */
    private fun ensureBoundProfile(): ProfileId? = runCatching {
        ProfileEngine.boundProfile()?.let { return@runCatching it }
        val id = ProfileId.new()
        if (ProfileEngine.bindProcessToProfile(id)) id else ProfileEngine.boundProfile()
    }.getOrNull()

    /** Evaluate [expression] until it yields a non-null string, or give up. */
    private fun evalString(expression: String, timeoutMs: Long = 30_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            session?.evaluateJs(expression) { value ->
                result.set(value)
                latch.countDown()
            }
            if (latch.await(5, TimeUnit.SECONDS)) {
                val decoded = result.get()?.let { decode(it) }
                if (!decoded.isNullOrBlank() && decoded != "null") return decoded
            }
            Thread.sleep(200)
        }
        return null
    }

    private fun decode(json: String): String? = runCatching {
        when (val value = JSONTokener(json).nextValue()) {
            is String -> value
            else -> value?.toString()
        }
    }.getOrNull()

    private fun expectedChecksum(): Int {
        var sum = 0
        for (i in 0 until payloadBytes) sum = (sum + i % 251) % 1_000_003
        return sum
    }

    private fun checksum(bytes: ByteArray): Int {
        var sum = 0
        for (b in bytes) sum = (sum + (b.toInt() and 0xFF)) % 1_000_003
        return sum
    }
}
