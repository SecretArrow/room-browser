package com.roombrowser.engine.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a download whose bytes live INSIDE the page, and hands them over as a
 * stream.
 *
 * WHY THIS EXISTS. A page that exports a file usually builds a `Blob` and
 * navigates an anchor to `URL.createObjectURL(blob)`. WebView reports that as
 * a download with a `blob:https://origin/uuid` URL — and that URL is not
 * addressable outside the renderer that created it. An HTTP client asked to
 * fetch it fails on the scheme alone, which is exactly what the download
 * engine did: the row appeared and died instantly with "Expected URL scheme
 * 'http' or 'https' but was 'blob'", and the file the page had already built
 * in memory was never written. `data:` URLs are the same story with the bytes
 * inline.
 *
 * WHAT IT DOES. The page's own world is the only place that URL resolves, so
 * the read happens there: `fetch(url)` inside the page, read as a BLOB and
 * sliced into windows, each window base64'd and posted back through a
 * JavaScript interface one at a time. The page announces the read first --
 * `begin(id, size, name)` -- and transfers nothing until the native side
 * accepts it, so a file over [MAX_BYTES] is refused before a single byte
 * crosses the bridge instead of after a cache file has already been written.
 * Windows are appended to a cache file, and the finished file is handed to the
 * caller as a stream. Base64 is the transport because the interface carries
 * strings and nothing else, and it is windowed because a whole file encoded in
 * one string would be a single allocation of roughly 4/3 the file — the
 * failure that kills a 60 MB export on a phone.
 *
 * WHY A TEMP FILE AND NOT A PIPE. A pipe would need the reader to keep up with
 * the JavaScript thread, and a slow reader would block that thread — freezing
 * the page — while the writer waited. A file has no such coupling: the page
 * posts as fast as it can, and the copy to Downloads happens afterwards.
 *
 * LIMITS ARE EXPLICIT. [MAX_BYTES] caps what this will hold, and it mirrors
 * the app's page-local save cap (`DownloadEngine.MAX_PAGE_LOCAL_BYTES`, 512
 * MiB — the two numbers must stay in step, or one side accepts what the other
 * refuses) rather than inventing a second one. The old 64 MiB cap made real
 * exports — video, datasets, large PDFs — fail silently. A page that offers
 * more is refused up front, before the transfer starts.
 *
 * THE WATCHDOG IS AN IDLE BUDGET, NOT A DEADLINE. [TIMEOUT_MS] bounds how long
 * a read may go WITHOUT a chunk; it is re-armed on every arrival, so a
 * legitimate multi-hundred-MB read is never killed mid-transfer, while a page
 * whose `fetch` never settles still fails here. It is still a budget: a read
 * cannot run for ever.
 */
internal class WebViewBlobDownloads(private val context: Context) {

    companion object {
        /**
         * The interface name the page sees. Prefixed like the app's other
         * bridges so a page can tell this app's globals from a random one.
         */
        const val INTERFACE = "RoomBlob"

        /**
         * Cap on the bytes held for one download. MIRRORS the app's page-local
         * save cap, `DownloadEngine.MAX_PAGE_LOCAL_BYTES` (512 MiB) — the two
         * must stay in step, because a file the engine accepts but the app
         * refuses wastes the whole transfer, and one the app accepts but the
         * engine refuses never reaches it. The old 64 MiB cap made real
         * exports (video, datasets, large PDFs) fail silently. This one exists
         * so a page cannot fill the disk before the app ever sees a byte.
         */
        const val MAX_BYTES = 512L * 1024 * 1024

        /**
         * The IDLE budget for a read: how long it may go without delivering a
         * chunk. Re-armed on every arrival (see [rearmWatchdog]), so it bounds
         * a stalled read rather than a long one — a page whose `fetch` never
         * settles still fails here, while a slow multi-hundred-MB transfer
         * keeps its budget fresh. Generous, because the work is local, but
         * finite: a read cannot run for ever.
         */
        private const val TIMEOUT_MS = 120_000L
    }

    private class Pending(
        val file: File,
        val onResult: (InputStream?, String) -> Unit
    ) {
        var received: Long = 0
        var nextSeq: Int = 0

        /**
         * The total the page announced in its `begin` call, or -1 when it did
         * not — an old page script (and the e2e) may still drive a read
         * without the handshake. Recording it is what lets [receive] treat its
         * per-window cap arithmetic as a re-check of
         * `received + decoded <= MAX_BYTES` against a size the native side
         * already vetted, instead of trusting each slice to be polite.
         */
        var total: Long = -1

        /**
         * Written from the JavaBridge thread (the page delivering its last
         * slice) and from the main thread (the timeout, and [cancelAll] on the
         * session's close path), so it is the one field here that needs
         * publishing. `watchdog` below is the other cross-thread field; the
         * rest are only ever touched by the JavaBridge thread, which is
         * single.
         */
        @Volatile
        var finished: Boolean = false

        /**
         * The current idle-watchdog runnable, kept so the next arrival can
         * remove it before re-posting. Touched from the main thread ([read],
         * the handler) and the JavaBridge thread ([receive]) — hence volatile;
         * the worst interleaving leaves two runnables queued, and [fail] is
         * idempotent, so the second is a no-op.
         */
        @Volatile
        var watchdog: Runnable? = null

        var fileName: String = ""
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Keyed by the id this class generated, never by anything the page chose. */
    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * The page-facing half. Methods here are reachable from ANY page, so each
     * one only ever acts on an id this class handed out, and an unknown id is
     * ignored rather than answered — a page cannot use this to write anything,
     * only to finish a download it already asked for.
     */
    inner class Bridge {
        /**
         * The read's opening handshake: the page announces [fileName] and
         * [size] and gets a yes/no BEFORE transferring anything. False is
         * returned when the id is unknown, the read is already finished or
         * already slicing (a second begin must never re-arm a transfer in
         * flight), or the size is over [MAX_BYTES] — and in that last case the
         * read is FAILED here, so the caller learns the file is too big before
         * a single byte of it is base64'd across the bridge.
         *
         * The name itself is not recorded: the chunk calls carry it too and
         * remain the single writer of [Pending.fileName], so there is exactly
         * one source of truth for it.
         */
        @JavascriptInterface
        fun begin(id: String, size: Long, fileName: String): Boolean = beginRead(id, size)

        /**
         * One slice. [data] is base64 of the slice's bytes, empty on the final
         * call or on an error. Returns false when the reader has given up, so
         * the page can stop slicing instead of encoding a file nobody wants.
         */
        @JavascriptInterface
        fun chunk(
            id: String,
            seq: Int,
            data: String,
            last: Boolean,
            error: String,
            fileName: String
        ): Boolean = receive(id, seq, data, last, error, fileName)
    }

    /** The instance to hand to `addJavascriptInterface`, once per WebView. */
    val bridge: Bridge = Bridge()

    /**
     * Ask [webView]'s page for [url]'s bytes. [onResult] is invoked exactly
     * once, on the main thread, with null when the page refused or the read
     * failed. The second argument is what the page's anchor called the file,
     * empty when it did not say.
     */
    fun read(webView: WebView, url: String, onResult: (InputStream?, String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "blob-downloads").apply { mkdirs() }
        val file = File(dir, "$id.part")
        val entry = Pending(file, onResult)
        pending[id] = entry
        rearmWatchdog(id)
        runCatching {
            webView.evaluateJavascript(BlobReaderScript.forUrl(id, url), null)
        }.onFailure {
            fail(id, it.message ?: "the page could not be asked")
        }
    }

    /** Drop every in-flight read, for the session's close path. */
    fun cancelAll() {
        pending.keys.toList().forEach { fail(it, null) }
    }

    /**
     * The native half of [Bridge.begin]. JavaBridge thread only, like every
     * page-facing entry point — that thread is single, so the checks below are
     * race-free against each other and against [receive].
     */
    private fun beginRead(id: String, size: Long): Boolean {
        val entry = pending[id] ?: return false
        if (entry.finished) return false
        // A begin after slicing started is a duplicate or a second read trying
        // to reuse the id; both are refused rather than allowed to reset a
        // transfer already in flight.
        if (entry.nextSeq != 0) return false
        if (size > MAX_BYTES) {
            // Refused UP FRONT — this is the point of the handshake. The old
            // flow discovered the size only after the file had been base64'd
            // across the bridge, with a cache file already partially written.
            fail(id, "the file is larger than this browser will save in one go")
            return false
        }
        if (size > 0) entry.total = size
        return true
    }

    /**
     * (Re)arm the idle watchdog for [id]: remove the previous runnable and
     * post a fresh one, so the budget measures idleness rather than total
     * duration. Called when the read starts and on every chunk arrival — a
     * page that keeps delivering keeps its budget, a page that stalls fails
     * here with the same message it always got.
     *
     * Safe from both the main and the JavaBridge thread: Handler post/remove
     * are internally synchronised, and the one interleaving that can slip
     * through leaves two runnables queued, the second of which is a no-op
     * because [fail] is idempotent.
     */
    private fun rearmWatchdog(id: String) {
        val entry = pending[id] ?: return
        entry.watchdog?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { fail(id, "the page did not finish reading the file") }
        entry.watchdog = runnable
        handler.postDelayed(runnable, TIMEOUT_MS)
    }

    private fun receive(
        id: String,
        seq: Int,
        data: String,
        last: Boolean,
        error: String,
        fileName: String
    ): Boolean {
        val entry = pending[id] ?: return false
        if (entry.finished) return false
        // An arrival IS progress: the idle budget measures the gap between
        // chunks, not the total duration, so every arrival buys the next gap.
        rearmWatchdog(id)
        if (error.isNotBlank()) {
            fail(id, error)
            return false
        }
        if (seq != entry.nextSeq) {
            fail(id, "the page delivered its slices out of order")
            return false
        }
        if (fileName.isNotBlank()) entry.fileName = fileName
        entry.nextSeq = seq + 1
        if (data.isNotEmpty()) {
            // Checked on the ENCODED length before decoding. This method is
            // reachable from any page, so the cap must not depend on the
            // caller having sliced politely: decoding a huge string only to
            // discover it is huge would be the allocation the cap exists to
            // prevent. Where the begin handshake armed a total, this is also
            // the cheap re-check it promised — `received + decoded <=
            // MAX_BYTES` is the same inequality; without a begin (old page
            // scripts, the e2e) the identical bound still holds per window.
            if (data.length / 4 * 3 > MAX_BYTES - entry.received) {
                fail(id, "the file is larger than this browser will save in one go")
                return false
            }
            val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
            if (bytes == null) {
                fail(id, "a slice of the file could not be decoded")
                return false
            }
            entry.received += bytes.size
            if (entry.received > MAX_BYTES) {
                fail(id, "the file is larger than this browser will save in one go")
                return false
            }
            val written = runCatching {
                entry.file.appendBytes(bytes)
                true
            }.getOrDefault(false)
            if (!written) {
                fail(id, "the file could not be written to this device")
                return false
            }
        }
        if (last) complete(id, entry)
        return !entry.finished
    }

    /**
     * Hand the finished file over. The stream deletes the cache copy when it is
     * closed, so the caller's normal `use { }` is the whole cleanup.
     */
    private fun complete(id: String, entry: Pending) {
        // Whoever takes the entry out of the map owns the one callback. The
        // timeout and the session's close path race with this on purpose, and a
        // read that finishes just as the WebView goes away must answer once
        // rather than twice — `onResult` is documented as exactly-once, and the
        // app's caller has already returned from it the first time.
        if (pending.remove(id) !== entry) return
        entry.finished = true
        val stream = runCatching {
            DeleteOnCloseStream(FileInputStream(entry.file), entry.file)
        }.getOrNull()
        val name = entry.fileName
        handler.post {
            if (stream == null) {
                entry.file.delete()
                entry.onResult(null, "")
            } else {
                entry.onResult(stream, name)
            }
        }
    }

    private fun fail(id: String, reason: String?) {
        val entry = pending.remove(id) ?: return
        if (entry.finished) return
        entry.finished = true
        entry.file.delete()
        handler.post { entry.onResult(null, "") }
        if (reason != null) lastError = reason
    }

    /** Why the last read failed, for a message the user can act on. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * A file-backed stream that removes its cache copy on close.
     *
     * The copy exists only to get the bytes from the page to the app's own
     * save path; leaving it behind would grow the cache by the size of every
     * blob the user ever downloaded.
     */
    private class DeleteOnCloseStream(
        private val delegate: FileInputStream,
        private val file: File
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            runCatching { delegate.close() }
            file.delete()
        }
    }
}

/** File.appendBytes: one call, so a failed write cannot leave a half slice. */
private fun File.appendBytes(bytes: ByteArray) {
    if (!exists()) {
        parentFile?.mkdirs()
        createNewFile()
    }
    java.io.FileOutputStream(this, true).use { it.write(bytes) }
}
