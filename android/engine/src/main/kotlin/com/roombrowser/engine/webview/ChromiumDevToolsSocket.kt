package com.roombrowser.engine.webview

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.roombrowser.engine.devtools.EngineProtocolSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a devtools probe found, so an attempt that does not prove out can still
 * be reported precisely rather than as a bare null.
 */
internal sealed interface CdpProbeOutcome {
    val reason: String

    /** The transport is up and answered `Runtime.evaluate` with 2. */
    data class Proven(override val reason: String) : CdpProbeOutcome

    /** No abstract-namespace devtools socket exists for this process. */
    data class NoSocket(override val reason: String) : CdpProbeOutcome

    /** `/json/list` answered but the WebSocket upgrade was refused. */
    data class HttpEndpointOnly(override val reason: String) : CdpProbeOutcome

    /** The upgrade succeeded but no reply to `Runtime.evaluate` arrived. */
    data class Unanswered(override val reason: String) : CdpProbeOutcome
}

/**
 * The transport [CdpProtocolSession] runs over, so the session's request and
 * reply bookkeeping can be pinned by a JVM test against a fake instead of a
 * device.
 */
internal interface CdpTransport {
    fun sendText(text: String)
    fun readTextMessage(deadlineMs: Long): String?
    fun close()
}

/**
 * The abstract-namespace socket a WebView exposes when remote debugging is on:
 * `webview_devtools_remote_<pid>`, where the pid is this app's own process.
 *
 * Only a same-UID peer or adb can open it, and it is a kernel-namespace socket
 * with no TCP port, so the app never opens an `adb forward`. The HTTP
 * handshake (`GET /json/list`) and the WebSocket upgrade run on the SAME socket
 * that then carries frames, which is why the members below share one buffer.
 */
internal class ChromiumDevToolsSocket(
    private val pid: Int = Process.myPid()
) : CdpTransport {

    private var socket: LocalSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var rawBuffer: ByteArray = ByteArray(0)

    private val writeLock = Any()
    private val codec = CdpFrameCodec()

    /**
     * Connect, list targets, upgrade and run `1+1`. On [CdpProbeOutcome.Proven]
     * the socket is left OPEN for a session to take over; every other outcome
     * closes it before returning.
     */
    fun probe(): CdpProbeOutcome {
        if (!connect()) return CdpProbeOutcome.NoSocket(NO_SOCKET_REASON)

        val path = runCatching { targetPath() }.getOrNull()
        if (path == null) {
            close()
            return CdpProbeOutcome.HttpEndpointOnly(ENDPOINT_ONLY_REASON)
        }

        val key = codec.newKey()
        val upgraded = runCatching { upgrade(path, key) }.getOrDefault(false)
        if (!upgraded) {
            close()
            return CdpProbeOutcome.HttpEndpointOnly(ENDPOINT_ONLY_REASON)
        }

        val reply = runCatching { roundTrip(EVALUATE_REQUEST, nowMs() + EVALUATE_TIMEOUT_MS) }.getOrNull()
        if (reply != null && evaluatedNumber(reply) == 2) {
            return CdpProbeOutcome.Proven(PROVEN_REASON)
        }

        close()
        return CdpProbeOutcome.Unanswered(UNANSWERED_REASON)
    }

    /** Write one masked text frame. Throws if the socket is already closed. */
    override fun sendText(text: String) =
        sendFrame(CdpFrameCodec.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /**
     * Blocking read of the next data message; null on close or error. A
     * [deadlineMs] of 0 waits indefinitely, which is what a session reader
     * wants: the socket closing is what ends it.
     */
    override fun readTextMessage(deadlineMs: Long): String? {
        val source = input ?: return null
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            when (val read = codec.decode(rawBuffer)) {
                is CdpFrameRead.Message -> {
                    rawBuffer = dropConsumed(read.consumed)
                    if (read.opcode == CdpFrameCodec.OPCODE_TEXT || read.opcode == CdpFrameCodec.OPCODE_BINARY) {
                        return read.payload.toString(Charsets.UTF_8)
                    }
                }
                is CdpFrameRead.Control -> {
                    rawBuffer = dropConsumed(read.consumed)
                    when (read.opcode) {
                        // A pong racing the close is not an error worth killing
                        // the reader thread over.
                        CdpFrameCodec.OPCODE_PING -> runCatching {
                            sendFrame(CdpFrameCodec.OPCODE_PONG, read.payload)
                        }
                        CdpFrameCodec.OPCODE_CLOSE -> return null
                        else -> Unit
                    }
                }
                is CdpFrameRead.Fragment -> rawBuffer = dropConsumed(read.consumed)
                is CdpFrameRead.Invalid -> return null
                CdpFrameRead.NeedMoreBytes -> if (!fill(chunk, source, deadlineMs)) return null
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        rawBuffer = ByteArray(0)
    }

    private fun connect(): Boolean {
        val local = LocalSocket()
        return try {
            local.connect(
                LocalSocketAddress(SOCKET_NAME_PREFIX + pid, LocalSocketAddress.Namespace.ABSTRACT)
            )
            local.soTimeout = READ_TIMEOUT_MS
            socket = local
            input = local.inputStream
            output = local.outputStream
            true
        } catch (t: Throwable) {
            runCatching { local.close() }
            false
        }
    }

    /** The path of the first target that names a WebSocket URL. */
    private fun targetPath(): String? {
        writeRequest(GET_TARGETS_REQUEST)
        val response = readHttpResponse(nowMs() + HANDSHAKE_TIMEOUT_MS) ?: return null
        if (response.statusCode != 200) return null
        val targets = runCatching { JSONArray(response.body) }.getOrNull() ?: return null
        for (i in 0 until targets.length()) {
            val url = targets.optJSONObject(i)?.optString("webSocketDebuggerUrl").orEmpty()
            if (url.isEmpty()) continue
            val path = runCatching { URI(url).rawPath }.getOrNull()
            if (!path.isNullOrEmpty()) return path
        }
        return null
    }

    private fun upgrade(path: String, key: String): Boolean {
        writeRequest(
            "GET $path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "\r\n"
        )
        val response = readHttpResponse(nowMs() + HANDSHAKE_TIMEOUT_MS) ?: return false
        return codec.upgradeAccepted(key, response)
    }

    private fun roundTrip(text: String, deadlineMs: Long): String? {
        sendText(text)
        while (true) {
            val message = readTextMessage(deadlineMs) ?: return null
            if (cdpMessageId(message) == EVALUATE_ID) return message
        }
    }

    private fun readHttpResponse(deadlineMs: Long): CdpHttpResponse? {
        val source = input ?: return null
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            when (val read = codec.parseHttpResponse(rawBuffer)) {
                is CdpHttpRead.Response -> {
                    rawBuffer = dropConsumed(read.response.consumed)
                    return read.response
                }
                CdpHttpRead.NeedMoreBytes -> if (!fill(chunk, source, deadlineMs)) return null
                is CdpHttpRead.Invalid -> return null
            }
        }
    }

    /** Read more into [rawBuffer]. True to keep going, false on EOF, error or an expired deadline. */
    private fun fill(chunk: ByteArray, source: InputStream, deadlineMs: Long): Boolean {
        val count = try {
            source.read(chunk)
        } catch (e: SocketTimeoutException) {
            return !(deadlineMs > 0 && nowMs() >= deadlineMs)
        } catch (t: Throwable) {
            return false
        }
        if (count < 0) return false
        if (count > 0) rawBuffer = rawBuffer + chunk.copyOf(count)
        return true
    }

    private fun writeRequest(text: String) {
        val out = output ?: error("devtools socket is closed")
        // Headers are ASCII; ISO-8859-1 keeps every byte as-is.
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        // Throws rather than no-op: a dropped frame the caller is awaiting a
        // reply to would otherwise stall until the request timeout.
        val out = output ?: error("devtools socket is closed")
        val frame = codec.encodeFrame(opcode, payload, codec.newMaskKey())
        synchronized(writeLock) {
            out.write(frame)
            out.flush()
        }
    }

    private fun dropConsumed(consumed: Int): ByteArray =
        if (consumed <= 0 || consumed >= rawBuffer.size) ByteArray(0)
        else rawBuffer.copyOfRange(consumed, rawBuffer.size)

    private fun evaluatedNumber(reply: String): Int? = runCatching {
        JSONObject(reply).optJSONObject("result")?.optJSONObject("result")?.optInt("value")
    }.getOrNull()

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private const val SOCKET_NAME_PREFIX = "webview_devtools_remote_"
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val READ_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val EVALUATE_TIMEOUT_MS = 10_000L
        private const val EVALUATE_ID = 1

        private const val EVALUATE_REQUEST =
            "{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"1+1\",\"returnByValue\":true}}"

        private const val GET_TARGETS_REQUEST =
            "GET /json/list HTTP/1.1\r\nHost: localhost\r\n\r\n"

        const val NO_SOCKET_REASON = "this WebView provider exposes no devtools socket"
        const val ENDPOINT_ONLY_REASON = "HTTP endpoint present, WebSocket refused"
        const val UNANSWERED_REASON = "protocol unanswered"
        const val PROVEN_REASON = "Runtime.evaluate answered 2"
    }
}

/**
 * A live [EngineProtocolSession] over an already-upgraded [CdpTransport].
 * Only JSON strings cross: no socket or engine type is named above this module.
 */
internal class CdpProtocolSession(
    private val socket: CdpTransport
) : EngineProtocolSession {

    private val pending = HashMap<Int, CompletableDeferred<String>>()
    private val pendingLock = Any()
    private val nextId = AtomicInteger(FIRST_SESSION_REQUEST_ID)

    private val eventFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val events: Flow<String> = eventFlow.asSharedFlow()

    @Volatile
    private var closed = false

    /**
     * How many requests are awaiting a reply. Exposed so a test can prove a
     * failed write or a close leaves the map empty rather than leaking an
     * entry.
     */
    internal val pendingCount: Int get() = synchronized(pendingLock) { pending.size }

    private val reader = Thread({ readLoop() }, "room-cdp-reader").apply {
        isDaemon = true
        start()
    }

    override suspend fun send(method: String, paramsJson: String): String = withContext(Dispatchers.IO) {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<String>()
        // The write sits INSIDE the guarded region: a transport that throws on
        // the way out (already closed, broken pipe) must still drop its pending
        // entry, or the map leaks one deferred per failed request.
        try {
            synchronized(pendingLock) {
                check(!closed) { "the protocol session is closed" }
                pending[id] = reply
            }
            socket.sendText(request(id, method, paramsJson))
            withTimeout(REQUEST_TIMEOUT_MS) { reply.await() }
        } finally {
            synchronized(pendingLock) { pending.remove(id) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        socket.close()
    }

    private fun readLoop() {
        while (!closed) {
            val message = socket.readTextMessage(0L) ?: break
            val id = cdpMessageId(message)
            val reply = if (id != null) synchronized(pendingLock) { pending.remove(id) } else null
            if (reply != null) {
                reply.complete(message)
            } else {
                eventFlow.tryEmit(message)
            }
        }
        cancelPending()
    }

    private fun cancelPending() {
        val waiting = synchronized(pendingLock) {
            pending.values.toList().also { pending.clear() }
        }
        waiting.forEach { it.cancel() }
    }

    private fun request(id: Int, method: String, paramsJson: String): String {
        val params = if (paramsJson.isBlank()) "{}" else paramsJson
        return "{\"id\":$id,\"method\":${JSONObject.quote(method)},\"params\":$params}"
    }

    private companion object {
        // The probe used id 1 for its `1+1`, so a session starts above it.
        const val FIRST_SESSION_REQUEST_ID = 2
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}

/** The numeric `id` of a CDP message, or null for a notification. */
internal fun cdpMessageId(message: String): Int? = runCatching {
    val json = JSONObject(message)
    if (json.has("id")) json.optInt("id") else null
}.getOrNull()
