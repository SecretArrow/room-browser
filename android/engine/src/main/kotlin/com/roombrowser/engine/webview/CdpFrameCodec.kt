package com.roombrowser.engine.webview

import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * The CDP WebSocket's byte arithmetic with no socket under it, so the framing,
 * masking and handshake digest are pinned by a JVM test rather than a device.
 * Malformed or truncated input is a value, never a throw: bytes arrive in
 * chunks and a peer can send nonsense.
 */
internal class CdpFrameCodec(
    private val maxPayloadBytes: Int = MAX_PAYLOAD_BYTES
) {

    /**
     * Read one frame from the front of [buffer]. [CdpFrameRead.NeedMoreBytes]
     * means the buffer ended inside a frame; every other result reports the
     * [CdpFrameRead.consumed] bytes it accounts for, which the caller drops
     * before calling again. A message split across frames is held and emitted
     * once, its pieces reported as [CdpFrameRead.Fragment] so the caller still
     * advances.
     */
    fun decode(buffer: ByteArray): CdpFrameRead {
        if (buffer.size < 2) return CdpFrameRead.NeedMoreBytes

        val first = buffer[0].toInt() and 0xFF
        val second = buffer[1].toInt() and 0xFF
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        val lengthCode = second and 0x7F

        // Both decidable from the first two bytes, so they are refused before a
        // declared length can ask the buffer for anything.
        if ((first and 0x70) != 0) return invalid("reserved bits set")
        when (opcode) {
            OPCODE_CONTINUATION, OPCODE_TEXT, OPCODE_BINARY,
            OPCODE_CLOSE, OPCODE_PING, OPCODE_PONG -> Unit
            else -> return invalid("unknown opcode $opcode")
        }

        val lengthBytes: Int
        val payloadLength: Long
        when (lengthCode) {
            126 -> {
                if (buffer.size < 4) return CdpFrameRead.NeedMoreBytes
                lengthBytes = 2
                payloadLength = ((buffer[2].toLong() and 0xFFL) shl 8) or
                    (buffer[3].toLong() and 0xFFL)
            }
            127 -> {
                if (buffer.size < 10) return CdpFrameRead.NeedMoreBytes
                lengthBytes = 8
                var value = 0L
                for (i in 2 until 10) value = (value shl 8) or (buffer[i].toLong() and 0xFFL)
                payloadLength = value
            }
            else -> {
                lengthBytes = 0
                payloadLength = lengthCode.toLong()
            }
        }

        // A negative here is the 64-bit form with its top bit set; it and an
        // over-limit length are refused before their bytes are awaited.
        if (payloadLength < 0L || payloadLength > maxPayloadBytes.toLong()) {
            return invalid("declared payload of $payloadLength bytes is outside the $maxPayloadBytes byte limit")
        }

        val headerLength = 2 + lengthBytes + if (masked) MASK_KEY_BYTES else 0
        val totalLength = headerLength + payloadLength.toInt()
        if (buffer.size < totalLength) return CdpFrameRead.NeedMoreBytes

        val raw = buffer.copyOfRange(headerLength, totalLength)
        val payload = if (masked) {
            applyMask(raw, buffer.copyOfRange(2 + lengthBytes, 2 + lengthBytes + MASK_KEY_BYTES))
        } else {
            raw
        }

        if (opcode >= OPCODE_CLOSE) {
            if (!fin) return invalid("a control frame cannot be fragmented")
            if (payload.size > MAX_CONTROL_PAYLOAD_BYTES) {
                return invalid("a control frame carries at most $MAX_CONTROL_PAYLOAD_BYTES bytes")
            }
            return CdpFrameRead.Control(opcode, payload, totalLength)
        }

        return when (opcode) {
            OPCODE_CONTINUATION -> continueMessage(payload, fin, totalLength)
            else -> openOrComplete(opcode, payload, fin, totalLength)
        }
    }

    private fun openOrComplete(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean,
        consumed: Int
    ): CdpFrameRead {
        if (fragmentOpcode != NO_MESSAGE) {
            return invalid("a new data frame arrived while a message was still open")
        }
        if (fin) return CdpFrameRead.Message(opcode, payload, consumed)
        fragmentOpcode = opcode
        fragment = payload
        return CdpFrameRead.Fragment(consumed)
    }

    private fun continueMessage(payload: ByteArray, fin: Boolean, consumed: Int): CdpFrameRead {
        if (fragmentOpcode == NO_MESSAGE) {
            return invalid("a continuation frame arrived with no message open")
        }
        val joined = fragment + payload
        if (joined.size > maxPayloadBytes) {
            return invalid("a fragmented message is outside the $maxPayloadBytes byte limit")
        }
        if (fin) {
            val message = CdpFrameRead.Message(fragmentOpcode, joined, consumed)
            fragmentOpcode = NO_MESSAGE
            fragment = ByteArray(0)
            return message
        }
        fragment = joined
        return CdpFrameRead.Fragment(consumed)
    }

    private fun invalid(reason: String): CdpFrameRead {
        fragmentOpcode = NO_MESSAGE
        fragment = ByteArray(0)
        return CdpFrameRead.Invalid(reason)
    }

    /**
     * One client frame. WebSocket requires client frames to be masked -- a
     * server closes the connection on an unmasked one -- so the mask is not
     * optional here.
     */
    fun encodeFrame(
        opcode: Int,
        payload: ByteArray,
        maskKey: ByteArray,
        fin: Boolean = true
    ): ByteArray {
        require(maskKey.size == MASK_KEY_BYTES) { "a WebSocket mask key is exactly four bytes" }
        val lengthBytes = when {
            payload.size < 126 -> 0
            payload.size <= 0xFFFF -> 2
            else -> 8
        }
        val sizeCode = when (lengthBytes) {
            0 -> payload.size
            2 -> 126
            else -> 127
        }
        val header = ByteArray(2 + lengthBytes + MASK_KEY_BYTES)
        header[0] = ((if (fin) 0x80 else 0x00) or (opcode and 0x0F)).toByte()
        header[1] = (MASK_BIT or sizeCode).toByte()
        val length = payload.size.toLong()
        for (i in 0 until lengthBytes) {
            header[2 + i] = ((length ushr (8 * (lengthBytes - 1 - i))) and 0xFFL).toByte()
        }
        maskKey.copyInto(header, 2 + lengthBytes)
        return header + applyMask(payload, maskKey)
    }

    fun encodeTextFrame(
        text: String,
        maskKey: ByteArray = newMaskKey(),
        fin: Boolean = true
    ): ByteArray = encodeFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8), maskKey, fin)

    /** XOR with the four-byte key, repeated. Its own inverse, so it masks and unmasks. */
    fun applyMask(payload: ByteArray, maskKey: ByteArray): ByteArray {
        require(maskKey.size == MASK_KEY_BYTES) { "a WebSocket mask key is exactly four bytes" }
        val out = payload.copyOf()
        for (i in out.indices) {
            out[i] = (out[i].toInt() xor maskKey[i % MASK_KEY_BYTES].toInt()).toByte()
        }
        return out
    }

    fun newMaskKey(): ByteArray = ByteArray(MASK_KEY_BYTES).also { Random.nextBytes(it) }

    /** A client `Sec-WebSocket-Key`: base64 of 16 random bytes. */
    fun newKey(): String = Base64.getEncoder().encodeToString(
        ByteArray(WEBSOCKET_KEY_BYTES).also { Random.nextBytes(it) }
    )

    /**
     * The `Sec-WebSocket-Accept` a server must send back for [secWebSocketKey]:
     * base64 of SHA-1 over the key and the RFC's fixed constant. Fixed by RFC
     * 6455, so it is checkable against the published vector.
     */
    fun acceptFor(secWebSocketKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((secWebSocketKey + WEBSOCKET_GUID).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** Whether [response] is the `101` that accepts [secWebSocketKey]. */
    fun upgradeAccepted(secWebSocketKey: String, response: CdpHttpResponse): Boolean {
        if (response.statusCode != 101) return false
        if (!response.header("upgrade").orEmpty().contains("websocket", ignoreCase = true)) return false
        if (!response.header("connection").orEmpty().contains("upgrade", ignoreCase = true)) return false
        return response.header("sec-websocket-accept") == acceptFor(secWebSocketKey)
    }

    /**
     * One HTTP/1.1 response's status line, headers and body, as far as
     * [bytes] carries them. The same parser reads both replies on the socket:
     * the `101` upgrade carries no body and `GET /json/list` carries an
     * ordinary one. Chunked encoding is refused rather than read as empty.
     */
    fun parseHttpResponse(bytes: ByteArray): CdpHttpRead {
        val bodyStart = headerEnd(bytes)
        if (bodyStart < 0) return CdpHttpRead.NeedMoreBytes

        val head = bytes.copyOfRange(0, bodyStart - 4).toString(Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val matched = STATUS_LINE.matchEntire(statusLine)
            ?: return CdpHttpRead.Invalid("not an HTTP status line: \"$statusLine\"")
        val statusCode = matched.groupValues[1].toIntOrNull()
            ?: return CdpHttpRead.Invalid("status code is not a number: \"${matched.groupValues[1]}\"")
        val reasonPhrase = matched.groupValues[2]

        // Keys are lowercased so a caller never has to match the wire's casing.
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        if (headers["transfer-encoding"].orEmpty().contains("chunked", ignoreCase = true)) {
            return CdpHttpRead.Invalid("chunked transfer encoding is not handled by this parser")
        }

        val contentLength = headers["content-length"]?.let { declared ->
            declared.toIntOrNull()
                ?: return CdpHttpRead.Invalid("content-length is not a number: \"$declared\"")
        } ?: 0
        if (contentLength < 0 || contentLength > maxPayloadBytes) {
            return CdpHttpRead.Invalid("content-length $contentLength is outside the $maxPayloadBytes byte limit")
        }
        val bodyEnd = bodyStart.toLong() + contentLength.toLong()
        if (bytes.size.toLong() < bodyEnd) return CdpHttpRead.NeedMoreBytes

        val body = bytes.copyOfRange(bodyStart, bodyEnd.toInt()).toString(Charsets.UTF_8)
        return CdpHttpRead.Response(
            CdpHttpResponse(statusCode, reasonPhrase, headers, body, bodyEnd.toInt())
        )
    }

    /** The index just past the header block's blank line, or -1 while it is incomplete. */
    private fun headerEnd(bytes: ByteArray): Int {
        for (i in 0..(bytes.size - 4)) {
            if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) {
                return i + 4
            }
        }
        return -1
    }

    private var fragmentOpcode = NO_MESSAGE
    private var fragment = ByteArray(0)

    companion object {
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA

        /** A backstop against a peer asking for an unallocatable buffer. */
        const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

        private const val MASK_KEY_BYTES = 4
        private const val WEBSOCKET_KEY_BYTES = 16
        private const val MASK_BIT = 0x80
        private const val MAX_CONTROL_PAYLOAD_BYTES = 125
        private const val NO_MESSAGE = -1
        private const val CR: Byte = 13
        private const val LF: Byte = 10
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private val STATUS_LINE = Regex("""^HTTP/\d\.\d\s+(\d{3})(?:\s+(.*))?$""")
    }
}

/**
 * One step of [CdpFrameCodec.decode], and the bytes it accounted for. Nothing
 * here throws: an incomplete frame is [NeedMoreBytes] and a frame this protocol
 * does not allow is [Invalid] with the reason.
 */
internal sealed interface CdpFrameRead {

    val consumed: Int

    /** A whole data message, its fragments already joined. */
    data class Message(val opcode: Int, val payload: ByteArray, override val consumed: Int) : CdpFrameRead

    /** A whole control frame: close, ping or pong. */
    data class Control(val opcode: Int, val payload: ByteArray, override val consumed: Int) : CdpFrameRead

    /** One fragment consumed; the message it belongs to is not complete yet. */
    data class Fragment(override val consumed: Int) : CdpFrameRead

    /** The buffer ends inside a frame. Append what the socket gave you and call again. */
    object NeedMoreBytes : CdpFrameRead {
        override val consumed: Int = 0
    }

    /** The bytes are not a frame this protocol allows; the transport is done. */
    data class Invalid(val reason: String) : CdpFrameRead {
        override val consumed: Int = 0
    }
}

/** The result of [CdpFrameCodec.parseHttpResponse]. */
internal sealed interface CdpHttpRead {
    data class Response(val response: CdpHttpResponse) : CdpHttpRead
    object NeedMoreBytes : CdpHttpRead
    data class Invalid(val reason: String) : CdpHttpRead
}

/** An HTTP reply, with its header names folded to lower case for lookup. */
internal data class CdpHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, String>,
    val body: String,
    val consumed: Int
) {
    fun header(name: String): String? = headers[name.lowercase()]
}
