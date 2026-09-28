package com.roombrowser.localai.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One row of the download list, keyed by [fileName].
 *
 * Finished downloads are REMOVED from [OnDeviceDownloadController.entries] the
 * moment their file lands on disk: the model then appears via
 * OnDeviceModelStore.list() instead, and the UI reacts to both flows. [paused]
 * entries keep their `.part` file so [OnDeviceDownloadController.start] can
 * resume them with an HTTP Range request. [url] is empty for entries revived
 * from an orphaned `.part` by [OnDeviceDownloadController.refreshListFromDisk]
 * — resuming those needs a fresh URL from the UI.
 */
data class OnDeviceDownloadEntry(
    /** Target file name, e.g. "qwen2.5-0.5b-q4_k_m.gguf". */
    val fileName: String,
    val url: String,
    val running: Boolean,
    val paused: Boolean,
    /** Bytes on disk (.part included) — the high-water mark across pauses. */
    val received: Long,
    /** Content-Length derived total when known, null when the server doesn't say. */
    val total: Long?,
    val error: String?,
    /** True only in the transition into removal (see class KDoc); never a persistent state. */
    val finished: Boolean
)

/**
 * Resumable GGUF download controller writing straight into
 * [OnDeviceModelStore.modelsDir].
 *
 * Resume design (honest about what it is):
 *  - bytes accumulate in `<fileName>.part`; a re-[start] with the same
 *    fileName+url sends `Range: bytes=<partSize>-` and APPENDS on HTTP 206;
 *    a server that answers 200 anyway makes the controller rewrite the .part
 *    from scratch (never a silently-stitched corrupt file);
 *  - a 206 whose Content-Range starts somewhere else than the .part size is an
 *    error, not a guess — the .part stays for a manual cancel/retry;
 *  - PAUSE cancels the download job AND the OkHttp call (a blocked socket read
 *    only aborts when the CALL is cancelled — the same prompt-pause contract
 *    OllamaClient established);
 *  - CANCEL additionally deletes the .part and removes the entry;
 *  - on completion the .part is renamed to the final .gguf and the entry is
 *    dropped (the model surfaces through OnDeviceModelStore.list()).
 *
 * The primary constructor is `internal` so JVM tests can drive the controller
 * against a temp dir + plain OkHttp client without any Android Context; the
 * public constructor takes the real [OnDeviceModelStore].
 */
class OnDeviceDownloadController internal constructor(
    private val dir: File,
    private val client: OkHttpClient
) {

    constructor(store: OnDeviceModelStore) : this(
        store.modelsDir,
        OkHttpClient.Builder()
            // Model hosts (HuggingFace & mirrors) redirect to CDN buckets.
            .followRedirects(true)
            // Same timeout philosophy as OllamaClient: multi-GB downloads
            // legally run for hours → no whole-call timeout; the read timeout
            // only bounds a STALLED stream, not a slow one.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val state = MutableStateFlow<List<OnDeviceDownloadEntry>>(emptyList())

    /** Live download rows; conflated like every StateFlow. */
    val entries: StateFlow<List<OnDeviceDownloadEntry>> = state

    /** Active download job per fileName (the cancellation handle for pause). */
    private val jobs = mutableMapOf<String, Job>()

    /** Active OkHttp call per fileName — pause must cancel the SOCKET read. */
    private val calls = mutableMapOf<String, Call>()

    /** FileNames whose stop was a USER pause (vs error / scope shutdown). */
    private val pauseRequested = mutableSetOf<String>()

    /** FileNames whose stop was a USER cancel (part file must die). */
    private val cancelRequested = mutableSetOf<String>()

    // All state mutations (maps, sets, MutableStateFlow) go through short
    // synchronized sections on this lock — a Mutex would force every touch
    // point to be suspend for no benefit, since no section ever blocks.
    private val lock = Any()

    // -------------------------------------------------------------- public API

    /**
     * Starts (or resumes) a download. Called twice with the same fileName while
     * running, the second call is a no-op; called again after a pause, it sends
     * a Range request for the bytes the `.part` already holds.
     */
    fun start(url: String, fileName: String) {
        val safe = sanitizeFileName(fileName)
        if (safe == null) {
            // Honest refusal instead of a silent drop: an unusable name shows
            // up as a dead row the UI can render and the user can cancel.
            upsertEntry(
                OnDeviceDownloadEntry(
                    fileName = fileName.take(60),
                    url = url,
                    running = false,
                    paused = false,
                    received = 0,
                    total = null,
                    error = "Invalid model file name",
                    finished = false
                )
            )
            return
        }
        synchronized(lock) {
            // isActive (not containsKey): a job that just finished may still be
            // in the map for microseconds while its finally-block unwinds — a
            // restart in that window must not be silently swallowed.
            if (jobs[safe]?.isActive == true) return
            val part = File(dir, "$safe.part")
            val received = if (part.exists()) part.length() else 0L
            upsertEntry(
                OnDeviceDownloadEntry(
                    fileName = safe,
                    url = url,
                    running = true,
                    paused = false,
                    received = received,
                    total = null,
                    error = null,
                    finished = false
                )
            )
            jobs[safe] = scope.launch { runDownload(url, safe) }
        }
    }

    /** Pauses a running download; keeps the `.part` for a later Range resume. */
    fun pause(fileName: String) {
        val job: Job
        val call: Call?
        synchronized(lock) {
            if (!jobs.containsKey(fileName)) {
                pauseRequested.remove(fileName)
                return
            }
            pauseRequested.add(fileName)
            job = jobs[fileName] ?: return
            call = calls[fileName]
        }
        // BOTH are needed: job.cancel() unwinds the coroutine at its next
        // suspension/isActive check, call.cancel() breaks a socket read that is
        // blocked right now (see OllamaClient's pause contract).
        runCatching { call?.cancel() }
        job.cancel()
    }

    /** Cancels a download: kills the `.part` and forgets the entry. */
    fun cancel(fileName: String) {
        val job: Job
        val call: Call?
        synchronized(lock) {
            if (!jobs.containsKey(fileName)) {
                // Not running: drop any paused/orphaned row and its .part outright.
                cancelRequested.remove(fileName)
                state.value = state.value.filterNot { it.fileName == fileName }
                File(dir, "$fileName.part").delete()
                return
            }
            cancelRequested.add(fileName)
            job = jobs[fileName] ?: return
            call = calls[fileName]
        }
        runCatching { call?.cancel() }
        job.cancel()
        // Synchronous removal for a snappy UI; the coroutine's stop handler
        // repeats both steps idempotently.
        synchronized(lock) {
            state.value = state.value.filterNot { it.fileName == fileName }
            File(dir, "$fileName.part").delete()
        }
    }

    /**
     * Re-syncs the entry list with the `.part` files found on disk: orphans
     * (process died mid-download) come back as paused rows so the UI can offer
     * resume (needs a fresh URL — URLs are not persisted) or cancel. Rows whose
     * files vanished entirely are dropped.
     */
    fun refreshListFromDisk() {
        synchronized(lock) {
            dir.mkdirs()
            val current = state.value
            val running = jobs.keys
            val orphans = dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }
                .orEmpty()
                .mapNotNull { part ->
                    val fileName = part.name.removeSuffix(".part")
                    if (fileName.isEmpty() || fileName in running || current.any { it.fileName == fileName }) {
                        null
                    } else {
                        OnDeviceDownloadEntry(
                            fileName = fileName,
                            url = "",
                            running = false,
                            paused = true,
                            received = part.length(),
                            total = null,
                            error = null,
                            finished = false
                        )
                    }
                }
            val kept = current.filter { entry ->
                entry.fileName in running ||
                    File(dir, "${entry.fileName}.part").exists() ||
                    File(dir, entry.fileName).exists()
            }
            state.value = kept + orphans
        }
    }

    /**
     * Cancels every active download and the controller scope. Terminal — the
     * controller must not be reused afterwards (a fresh instance picks the
     * `.part` files back up via [refreshListFromDisk]).
     */
    fun shutdown() {
        val toCancel: List<Call>
        synchronized(lock) {
            toCancel = calls.values.toList()
            scope.cancel()
            calls.clear()
        }
        toCancel.forEach { runCatching { it.cancel() } }
    }

    // ----------------------------------------------------------------- worker

    /**
     * One download attempt. Total flow: optional Range request → append or
     * rewrite the `.part` → rename to the final `.gguf` → drop the entry.
     * Every stop path funnels through [handleStop] so pause/cancel/error all
     * leave the disk in the state their UI row promises.
     */
    private suspend fun runDownload(url: String, fileName: String) {
        val part = File(dir, "$fileName.part")
        val target = File(dir, fileName)
        var callRef: Call? = null
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val existing = if (part.exists()) part.length() else 0L
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                    .build()
                val call = client.newCall(request)
                callRef = call
                synchronized(lock) { calls[fileName] = call }

                val response = awaitCall(call)
                try {
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} ${response.message}".trim())
                    }
                    val body = response.body ?: throw IOException("empty response body")

                    val contentRange = response.header("Content-Range")
                    if (response.code == 206 && contentRange != null &&
                        !contentRange.startsWith("bytes $existing-")
                    ) {
                        // A 206 that resumes somewhere else would corrupt the
                        // file if appended; refuse instead of guessing.
                        throw IOException("server resumed from an unexpected offset ($contentRange)")
                    }

                    // 206 + Range → append; a 200 despite the Range means the
                    // server ignored it → rewrite the .part from scratch.
                    val append = response.code == 206 && existing > 0
                    val remaining = body.contentLength() // -1 when unknown; for 206: the REMAINING bytes
                    val total = when {
                        response.code == 206 && remaining >= 0 -> existing + remaining
                        remaining >= 0 -> remaining
                        else -> null
                    }
                    updateEntry(fileName) { it.copy(received = existing, total = total) }

                    FileOutputStream(part, append).use { out ->
                        copyBody(body, out, fileName, startAt = existing)
                    }

                    // Complete: the .part becomes the model; the entry makes
                    // way for OnDeviceModelStore.list() to show it.
                    target.delete()
                    if (!part.renameTo(target)) {
                        throw IOException("could not move the finished file into place")
                    }
                    synchronized(lock) {
                        jobs.remove(fileName)
                        calls.remove(fileName)
                        pauseRequested.remove(fileName)
                        cancelRequested.remove(fileName)
                        state.value = state.value.filterNot { it.fileName == fileName }
                    }
                } finally {
                    runCatching { response.close() }
                }
            }
        } catch (ce: CancellationException) {
            handleStop(fileName, callRef, fallbackError = null)
            throw ce
        } catch (e: Exception) {
            handleStop(fileName, callRef, fallbackError = "Download failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            synchronized(lock) {
                jobs.remove(fileName)
                calls.remove(fileName)
                // A pause/cancel requested inside the completion race window
                // would otherwise leak and mislabel the NEXT stop of this name.
                pauseRequested.remove(fileName)
                cancelRequested.remove(fileName)
            }
            callRef = null
        }
    }

    /**
     * Streams the response body into [out], checking coroutine activity every
     * chunk (house pattern — see OllamaClient.readPullStream) and throttling
     * progress pushes to at most one per 200 ms / 256 KiB so a fast link cannot
     * flood the StateFlow collectors.
     */
    private suspend fun copyBody(body: ResponseBody, out: FileOutputStream, fileName: String, startAt: Long) {
        val input = body.byteStream()
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var received = startAt
        var lastPushAt = System.nanoTime()
        var lastPushedBytes = received
        while (true) {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("model download cancelled")
            }
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                out.write(buffer, 0, read)
                received += read
                val now = System.nanoTime()
                if (received - lastPushedBytes >= PROGRESS_MIN_BYTES ||
                    now - lastPushAt >= PROGRESS_MIN_INTERVAL_NS
                ) {
                    updateEntry(fileName) { it.copy(received = received) }
                    lastPushAt = now
                    lastPushedBytes = received
                }
            }
        }
        out.flush()
    }

    /**
     * Enqueues the call and suspends; cancelling the calling coroutine cancels
     * HTTP (the one-shot hook OllamaClient uses). Mid-body cancellation does
     * not need OllamaClient's watcher coroutine: pause()/cancel() reach the
     * Call directly through the [calls] map and cancel it themselves.
     */
    private suspend fun awaitCall(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    if (call.isCanceled()) {
                        continuation.resumeWithException(CancellationException("model download cancelled"))
                    } else {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    /**
     * Terminal bookkeeping shared by the pause/cancel/error stop paths:
     * pause → paused row, kept .part; cancel → row gone, .part deleted;
     * anything else → honest error row, .part kept (still resumable).
     */
    private fun handleStop(fileName: String, call: Call?, fallbackError: String?) {
        synchronized(lock) {
            jobs.remove(fileName)
            calls.remove(fileName)
            val cancelling = cancelRequested.remove(fileName)
            val pausing = pauseRequested.remove(fileName)
            state.value = when {
                cancelling -> {
                    File(dir, "$fileName.part").delete()
                    state.value.filterNot { it.fileName == fileName }
                }
                // call.isCanceled() discriminates pause from a real network
                // error: pause() cancels the call BEFORE the IOException from
                // the dying socket reaches this handler.
                pausing || (call != null && call.isCanceled()) ->
                    state.value.map { entry ->
                        if (entry.fileName == fileName) entry.copy(running = false, paused = true, error = null)
                        else entry
                    }
                else ->
                    state.value.map { entry ->
                        if (entry.fileName == fileName) {
                            entry.copy(running = false, paused = false, error = fallbackError ?: "Download stopped")
                        } else entry
                    }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun updateEntry(fileName: String, transform: (OnDeviceDownloadEntry) -> OnDeviceDownloadEntry) {
        synchronized(lock) {
            state.value = state.value.map { if (it.fileName == fileName) transform(it) else it }
        }
    }

    private fun upsertEntry(entry: OnDeviceDownloadEntry) {
        synchronized(lock) {
            state.value = state.value.filterNot { it.fileName == entry.fileName } + entry
        }
    }

    /**
     * null for names that could escape [dir] ("/", "\", "..") or carry no id;
     * otherwise the name with a normalized .gguf extension.
     */
    private fun sanitizeFileName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val withExt = if (name.endsWith(".gguf", ignoreCase = true)) {
            name.dropLast(5) + ".gguf"
        } else {
            "$name.gguf"
        }
        return withExt.takeIf { it != ".gguf" }
    }

    private companion object {
        private const val USER_AGENT = "RoomBrowser-ModelDownload/1.0"
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
        private const val PROGRESS_MIN_BYTES = 256L * 1024
        private const val PROGRESS_MIN_INTERVAL_NS = 200_000_000L
    }
}
