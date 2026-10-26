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
 * the read happens there: `fetch(url)` inside the page, sliced and base64'd,
 * posted back through a JavaScript interface one slice at a time. Slices are
 * appended to a cache file, and the finished file is handed to the caller as a
 * stream. Base64 is the transport because the interface carries strings and
 * nothing else, and it is chunked because a whole file encoded in one string
 * would be a single allocation of roughly 4/3 the file — the failure that
 * kills a 60 MB export on a phone.
 *
 * WHY A TEMP FILE AND NOT A PIPE. A pipe would need the reader to keep up with
 * the JavaScript thread, and a slow reader would block that thread — freezing
 * the page — while the writer waited. A file has no such coupling: the page
 * posts as fast as it can, and the copy to Downloads happens afterwards.
 *
 * LIMITS ARE EXPLICIT. [MAX_BYTES] caps what this will hold, and it mirrors the
 * app's inline-save cap rather than inventing a second number. A page that
 * offers more is refused with a message rather than filling the device.
 */
internal class WebViewBlobDownloads(private val context: Context) {

    companion object {
        /**
         * The interface name the page sees. Prefixed like the app's other
         * bridges so a page can tell this app's globals from a random one.
         */
        const val INTERFACE = "RoomBlob"

        /**
         * Cap on the bytes held for one download. The app's own inline save
         * cap is the same number; this one exists so a page cannot fill the
         * disk before the app ever sees a byte.
         */
        const val MAX_BYTES = 64L * 1024 * 1024

        /**
         * How long a page gets to deliver the whole blob. Generous, because
         * the work is local, but finite: a page whose `fetch` never settles
         * would otherwise leave a download row running for ever.
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
         * Written from the JavaBridge thread (the page delivering its last
         * slice) and from the main thread (the timeout, and [cancelAll] on the
         * session's close path), so it is the one field here that needs
         * publishing. The rest are only ever touched by the JavaBridge thread,
         * which is single.
         */
        @Volatile
        var finished: Boolean = false

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
        handler.postDelayed({ fail(id, "the page did not finish reading the file") }, TIMEOUT_MS)
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
            // prevent.
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
