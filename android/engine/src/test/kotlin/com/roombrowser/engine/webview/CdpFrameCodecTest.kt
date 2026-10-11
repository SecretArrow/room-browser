package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

/**
 * The CDP frame codec, pinned on the JVM.
 *
 * Frame bytes are built by hand rather than by the encoder, so a decoder test
 * proves the decoder against the RFC rather than against its own mirror. The
 * one exception is the round trip, which is exactly the point of that test.
 */
class CdpFrameCodecTest {

    private val maskKey = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)

    // ---------------------------------------------------------------- framing

    @Test
    fun a_client_text_frame_is_masked_and_round_trips() {
        val codec = CdpFrameCodec()
        val text = "{\"id\":1,\"method\":\"Runtime.evaluate\"}"
        val plain = text.toByteArray(Charsets.UTF_8)
        val encoded = codec.encodeTextFrame(text, maskKey)

        // A server rejects an unmasked client frame, so the mask bit and the key
        // are the contract, not a detail.
        assertThat(encoded[1].toInt() and 0x80).isEqualTo(0x80)
        assertThat(encoded[0].toInt() and 0x0F).isEqualTo(CdpFrameCodec.OPCODE_TEXT)
        assertThat(encoded.copyOfRange(2, 6).toList()).isEqualTo(maskKey.toList())
        assertThat(encoded.copyOfRange(6, 6 + plain.size).toList()).isNotEqualTo(plain.toList())

        val read = codec.decode(encoded)
        assertThat(read).isInstanceOf(CdpFrameRead.Message::class.java)
        val message = read as CdpFrameRead.Message
        assertThat(message.opcode).isEqualTo(CdpFrameCodec.OPCODE_TEXT)
        assertThat(message.consumed).isEqualTo(encoded.size)
        assertThat(message.payload.toList()).isEqualTo(plain.toList())
    }

    @Test
    fun masking_is_its_own_inverse() {
        val payload = "masked".toByteArray(Charsets.UTF_8)
        val once = CdpFrameCodec().applyMask(payload, maskKey)
        assertThat(CdpFrameCodec().applyMask(once, maskKey).toList()).isEqualTo(payload.toList())
    }

    @Test
    fun the_encoder_writes_the_expected_wire_bytes() {
        // A fixed mask key, so the whole frame is a literal rather than a
        // property check that a mirrored encoder would also pass.
        val encoded = CdpFrameCodec().encodeTextFrame("hello", maskKey)
        assertThat(encoded.toList()).isEqualTo(
            listOf(
                0x81.toByte(), 0x85.toByte(),
                0x37.toByte(), 0xFA.toByte(), 0x21.toByte(), 0x3D.toByte(),
                0x5F.toByte(), 0x9F.toByte(), 0x4D.toByte(), 0x51.toByte(), 0x58.toByte()
            )
        )
    }

    @Test
    fun an_unmasked_server_frame_is_read_as_is() {
        val bytes = frameBytes(CdpFrameCodec.OPCODE_TEXT, "hello".toByteArray(Charsets.UTF_8))
        val message = CdpFrameCodec().decode(bytes) as CdpFrameRead.Message
        assertThat(message.payload.toString(Charsets.UTF_8)).isEqualTo("hello")
        assertThat(message.consumed).isEqualTo(bytes.size)
    }

    @Test
    fun a_masked_frame_is_unmasked_even_though_servers_do_not_mask() {
        val bytes = frameBytes(
            CdpFrameCodec.OPCODE_TEXT,
            "hello".toByteArray(Charsets.UTF_8),
            maskKey = maskKey
        )
        val message = CdpFrameCodec().decode(bytes) as CdpFrameRead.Message
        assertThat(message.payload.toString(Charsets.UTF_8)).isEqualTo("hello")
    }

    // ------------------------------------------------------------ fragmentation

    @Test
    fun a_fragmented_message_is_reassembled() {
        val codec = CdpFrameCodec()
        val first = frameBytes(CdpFrameCodec.OPCODE_TEXT, "{\"id\":".toByteArray(), fin = false)
        val middle = frameBytes(CdpFrameCodec.OPCODE_CONTINUATION, "42".toByteArray(), fin = false)
        val last = frameBytes(CdpFrameCodec.OPCODE_CONTINUATION, "}".toByteArray())

        assertThat(codec.decode(first)).isEqualTo(CdpFrameRead.Fragment(first.size))
        assertThat(codec.decode(middle)).isEqualTo(CdpFrameRead.Fragment(middle.size))

        val read = codec.decode(last)
        assertThat(read).isInstanceOf(CdpFrameRead.Message::class.java)
        val message = read as CdpFrameRead.Message
        // The message keeps the opcode of the frame that opened it, not the
        // continuation's, or a caller could not tell text from binary.
        assertThat(message.opcode).isEqualTo(CdpFrameCodec.OPCODE_TEXT)
        assertThat(message.consumed).isEqualTo(last.size)
        assertThat(message.payload.toString(Charsets.UTF_8)).isEqualTo("{\"id\":42}")
    }

    @Test
    fun a_ping_between_fragments_does_not_disturb_the_message() {
        val codec = CdpFrameCodec()
        val head = frameBytes(CdpFrameCodec.OPCODE_TEXT, "ab".toByteArray(), fin = false)
        val ping = frameBytes(CdpFrameCodec.OPCODE_PING, byteArrayOf(0x01))
        val tail = frameBytes(CdpFrameCodec.OPCODE_CONTINUATION, "cd".toByteArray())

        codec.decode(head)
        val control = codec.decode(ping)
        assertThat(control).isInstanceOf(CdpFrameRead.Control::class.java)
        assertThat((control as CdpFrameRead.Control).opcode).isEqualTo(CdpFrameCodec.OPCODE_PING)

        val read = codec.decode(tail) as CdpFrameRead.Message
        assertThat(read.payload.toString(Charsets.UTF_8)).isEqualTo("abcd")
    }

    @Test
    fun a_caller_drops_consumed_and_reads_the_next_frame() {
        val codec = CdpFrameCodec()
        val first = frameBytes(CdpFrameCodec.OPCODE_TEXT, "one".toByteArray())
        val second = frameBytes(CdpFrameCodec.OPCODE_TEXT, "two".toByteArray())
        val buffered = first + second

        val read = codec.decode(buffered) as CdpFrameRead.Message
        assertThat(read.consumed).isEqualTo(first.size)
        assertThat(read.payload.toString(Charsets.UTF_8)).isEqualTo("one")

        // The whole contract a socket caller loops on: drop [consumed], call again.
        val rest = buffered.copyOfRange(read.consumed, buffered.size)
        val next = codec.decode(rest) as CdpFrameRead.Message
        assertThat(next.consumed).isEqualTo(rest.size)
        assertThat(next.payload.toString(Charsets.UTF_8)).isEqualTo("two")
    }

    // ------------------------------------------------------------ partial read

    @Test
    fun a_truncated_frame_asks_for_more_bytes() {
        val decoder = CdpFrameCodec()
        val full = frameBytes(CdpFrameCodec.OPCODE_TEXT, "hello".toByteArray())

        assertThat(decoder.decode(full.copyOfRange(0, full.size - 2)))
            .isEqualTo(CdpFrameRead.NeedMoreBytes)
        assertThat(decoder.decode(full.copyOfRange(0, 1))).isEqualTo(CdpFrameRead.NeedMoreBytes)
        assertThat(decoder.decode(ByteArray(0))).isEqualTo(CdpFrameRead.NeedMoreBytes)
    }

    // ------------------------------------------------------------- length forms

    @Test
    fun the_16_bit_length_form_carries_a_126_byte_frame() {
        val codec = CdpFrameCodec()
        val payload = ByteArray(126) { (it % 251).toByte() }
        val encoded = codec.encodeFrame(CdpFrameCodec.OPCODE_BINARY, payload, maskKey)

        assertThat(encoded[1].toInt() and 0x7F).isEqualTo(126)

        val message = codec.decode(encoded) as CdpFrameRead.Message
        assertThat(message.consumed).isEqualTo(encoded.size)
        assertThat(message.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun the_single_byte_length_form_carries_a_125_byte_frame() {
        // 125 is the largest payload the 7-bit form carries: the boundary the
        // next test steps over, so one over-long byte here would be encoded as
        // a 16-bit length and misread.
        val codec = CdpFrameCodec()
        val payload = ByteArray(125) { (it % 251).toByte() }
        val encoded = codec.encodeFrame(CdpFrameCodec.OPCODE_BINARY, payload, maskKey)

        assertThat(encoded[1].toInt() and 0x7F).isEqualTo(125)
        // Two header bytes, the four mask bytes, and no extended length.
        assertThat(encoded.size).isEqualTo(2 + 4 + 125)

        val message = codec.decode(encoded) as CdpFrameRead.Message
        assertThat(message.consumed).isEqualTo(encoded.size)
        assertThat(message.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun the_16_bit_length_form_carries_a_65535_byte_frame() {
        // 65535 is the largest length the 16-bit form carries; 65536 (the other
        // test) is the first that needs the 64-bit form.
        val codec = CdpFrameCodec()
        val payload = ByteArray(65_535) { (it % 251).toByte() }
        val encoded = codec.encodeFrame(CdpFrameCodec.OPCODE_BINARY, payload, maskKey)

        assertThat(encoded[1].toInt() and 0x7F).isEqualTo(126)
        assertThat(encoded[2].toInt() and 0xFF).isEqualTo(0xFF)
        assertThat(encoded[3].toInt() and 0xFF).isEqualTo(0xFF)

        val message = codec.decode(encoded) as CdpFrameRead.Message
        assertThat(message.consumed).isEqualTo(encoded.size)
        assertThat(message.payload.toList()).isEqualTo(payload.toList())
    }

    @Test
    fun the_64_bit_length_form_carries_a_65536_byte_frame() {
        val codec = CdpFrameCodec()
        val payload = ByteArray(65_536) { (it % 251).toByte() }
        val encoded = codec.encodeFrame(CdpFrameCodec.OPCODE_BINARY, payload, maskKey)

        assertThat(encoded[1].toInt() and 0x7F).isEqualTo(127)

        val message = codec.decode(encoded) as CdpFrameRead.Message
        assertThat(message.consumed).isEqualTo(encoded.size)
        assertThat(message.payload.toList()).isEqualTo(payload.toList())
    }

    // ----------------------------------------------------------- control frames

    @Test
    fun control_frames_are_reported_with_their_opcode_and_payload() {
        val codec = CdpFrameCodec()

        val pingRead = codec.decode(frameBytes(CdpFrameCodec.OPCODE_PING, "hb".toByteArray()))
        assertThat(pingRead).isInstanceOf(CdpFrameRead.Control::class.java)
        val ping = pingRead as CdpFrameRead.Control
        assertThat(ping.opcode).isEqualTo(CdpFrameCodec.OPCODE_PING)
        assertThat(ping.payload.toString(Charsets.UTF_8)).isEqualTo("hb")

        val pongRead = codec.decode(frameBytes(CdpFrameCodec.OPCODE_PONG, "hb".toByteArray()))
        assertThat(pongRead).isInstanceOf(CdpFrameRead.Control::class.java)
        val pong = pongRead as CdpFrameRead.Control
        assertThat(pong.opcode).isEqualTo(CdpFrameCodec.OPCODE_PONG)
        assertThat(pong.payload.toString(Charsets.UTF_8)).isEqualTo("hb")

        val closeRead = codec.decode(
            frameBytes(CdpFrameCodec.OPCODE_CLOSE, byteArrayOf(0x03, 0xE8.toByte()))
        )
        assertThat(closeRead).isInstanceOf(CdpFrameRead.Control::class.java)
        val close = closeRead as CdpFrameRead.Control
        assertThat(close.opcode).isEqualTo(CdpFrameCodec.OPCODE_CLOSE)
        assertThat(close.payload.toList()).isEqualTo(listOf(0x03.toByte(), 0xE8.toByte()))
    }

    // ------------------------------------------------------------- malformed

    @Test
    fun a_fragmented_control_frame_is_an_error() {
        val bytes = frameBytes(CdpFrameCodec.OPCODE_PING, byteArrayOf(0x01), fin = false)
        assertThat(CdpFrameCodec().decode(bytes)).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun a_control_frame_with_an_oversized_payload_is_an_error() {
        val bytes = frameBytes(CdpFrameCodec.OPCODE_CLOSE, ByteArray(126))
        assertThat(CdpFrameCodec().decode(bytes)).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun an_unknown_opcode_is_an_error_value_not_a_throw() {
        val bytes = frameBytes(0x3, "x".toByteArray())
        assertThat(CdpFrameCodec().decode(bytes)).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun reserved_bits_are_refused() {
        val bytes = frameBytes(CdpFrameCodec.OPCODE_TEXT, "x".toByteArray())
        bytes[0] = (bytes[0].toInt() or 0x40).toByte()
        assertThat(CdpFrameCodec().decode(bytes)).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun a_continuation_with_nothing_to_continue_is_an_error() {
        val bytes = frameBytes(CdpFrameCodec.OPCODE_CONTINUATION, "x".toByteArray(), fin = false)
        assertThat(CdpFrameCodec().decode(bytes)).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun a_new_data_frame_during_fragmentation_is_an_error() {
        val codec = CdpFrameCodec()
        codec.decode(frameBytes(CdpFrameCodec.OPCODE_TEXT, "a".toByteArray(), fin = false))
        val read = codec.decode(frameBytes(CdpFrameCodec.OPCODE_TEXT, "b".toByteArray()))
        assertThat(read).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun a_frame_over_the_configured_limit_is_an_error() {
        val codec = CdpFrameCodec(maxPayloadBytes = 4)
        val bytes = frameBytes(CdpFrameCodec.OPCODE_TEXT, "12345".toByteArray())
        assertThat(codec.decode(bytes)).isInstanceOf(CdpFrameRead.Invalid::class.java)
    }

    @Test
    fun a_declared_length_beyond_the_limit_is_refused_before_its_bytes_arrive() {
        // fin + binary, masked, 64-bit length 2^31 -- and not one payload byte.
        val header = byteArrayOf(
            0x82.toByte(), 0xFF.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00
        )
        val read = CdpFrameCodec().decode(header)
        assertThat(read).isInstanceOf(CdpFrameRead.Invalid::class.java)
        // Refused outright, not reported as merely incomplete: otherwise a peer
        // could make the caller wait for two gigabytes that never come.
        assertThat(read).isNotEqualTo(CdpFrameRead.NeedMoreBytes)
    }

    // ---------------------------------------------------------------- handshake

    @Test
    fun accept_matches_the_rfc_example_vector() {
        // RFC 6455 section 1.3, a fixed published vector.
        assertThat(CdpFrameCodec().acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
            .isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
    }

    @Test
    fun a_client_key_is_sixteen_random_bytes_in_base64() {
        val codec = CdpFrameCodec()
        val first = codec.newKey()
        val second = codec.newKey()
        assertThat(Base64.getDecoder().decode(first).size).isEqualTo(16)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun a_matching_upgrade_is_accepted_and_a_wrong_accept_is_refused() {
        val codec = CdpFrameCodec()
        val key = "dGhlIHNhbXBsZSBub25jZQ=="

        val good = response(
            101,
            mapOf(
                "Upgrade" to "websocket",
                "Connection" to "Upgrade",
                "Sec-WebSocket-Accept" to codec.acceptFor(key)
            )
        )
        assertThat(codec.upgradeAccepted(key, good)).isTrue()

        val wrongAccept = response(
            101,
            mapOf(
                "Upgrade" to "websocket",
                "Connection" to "Upgrade",
                "Sec-WebSocket-Accept" to "AAAAAAAAAAAAAAAAAAAAAAAAAAA="
            )
        )
        assertThat(codec.upgradeAccepted(key, wrongAccept)).isFalse()

        val noUpgrade = response(
            101,
            mapOf("Sec-WebSocket-Accept" to codec.acceptFor(key))
        )
        assertThat(codec.upgradeAccepted(key, noUpgrade)).isFalse()

        val notSwitching = response(
            200,
            mapOf(
                "Upgrade" to "websocket",
                "Connection" to "Upgrade",
                "Sec-WebSocket-Accept" to codec.acceptFor(key)
            )
        )
        assertThat(codec.upgradeAccepted(key, notSwitching)).isFalse()
    }

    @Test
    fun the_upgrade_reply_is_parsed_with_a_case_insensitive_header_lookup() {
        val raw = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)

        val read = CdpFrameCodec().parseHttpResponse(raw)
        assertThat(read).isInstanceOf(CdpHttpRead.Response::class.java)
        val response = (read as CdpHttpRead.Response).response
        assertThat(response.statusCode).isEqualTo(101)
        assertThat(response.reasonPhrase).isEqualTo("Switching Protocols")
        assertThat(response.header("sec-websocket-accept"))
            .isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        assertThat(response.header("SEC-WEBSOCKET-ACCEPT"))
            .isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        assertThat(response.header("upgrade")).isEqualTo("websocket")
        assertThat(response.body).isEmpty()
        assertThat(response.consumed).isEqualTo(raw.size)
    }

    @Test
    fun the_json_list_reply_is_parsed_with_its_body() {
        val body = "[{\"id\":\"PAGE\",\"webSocketDebuggerUrl\":" +
            "\"ws://127.0.0.1:9222/devtools/page/1\"}]"
        val raw = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.UTF_8)

        val read = CdpFrameCodec().parseHttpResponse(raw)
        assertThat(read).isInstanceOf(CdpHttpRead.Response::class.java)
        val response = (read as CdpHttpRead.Response).response
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.reasonPhrase).isEqualTo("OK")
        assertThat(response.header("content-type"))
            .isEqualTo("application/json; charset=UTF-8")
        assertThat(response.body).isEqualTo(body)
    }

    @Test
    fun an_http_reply_consumes_only_its_own_bytes() {
        val body = "{\"a\":1}"
        val response = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.UTF_8)
        // The first WebSocket frame of the same socket read, buffered behind the
        // reply: a length computed against the buffer end would swallow it.
        val trailing = byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xFA.toByte(), 0x21, 0x3D)
        val buffered = response + trailing

        val read = CdpFrameCodec().parseHttpResponse(buffered)
        assertThat(read).isInstanceOf(CdpHttpRead.Response::class.java)
        val parsed = (read as CdpHttpRead.Response).response

        assertThat(parsed.body).isEqualTo(body)
        // Consumed counts the reply's own bytes only, so the caller drops exactly
        // those and the frame after it survives intact.
        assertThat(parsed.consumed).isEqualTo(response.size)
        assertThat(buffered.copyOfRange(parsed.consumed, buffered.size).toList())
            .isEqualTo(trailing.toList())
    }

    @Test
    fun an_incomplete_http_reply_asks_for_more_bytes() {
        val decoder = CdpFrameCodec()
        val headerOnly = ("HTTP/1.1 200 OK\r\n" + "Content-Length: 10\r\n").toByteArray()
        assertThat(decoder.parseHttpResponse(headerOnly)).isEqualTo(CdpHttpRead.NeedMoreBytes)

        // Headers complete, body still short of its declared length.
        val shortBody = (
            "HTTP/1.1 200 OK\r\n" + "Content-Length: 10\r\n" + "\r\n" + "abc"
            ).toByteArray()
        assertThat(decoder.parseHttpResponse(shortBody)).isEqualTo(CdpHttpRead.NeedMoreBytes)
    }

    @Test
    fun a_status_line_that_is_not_http_is_an_error() {
        val raw = "NOT HTTP AT ALL\r\n\r\n".toByteArray()
        assertThat(CdpFrameCodec().parseHttpResponse(raw))
            .isInstanceOf(CdpHttpRead.Invalid::class.java)
    }

    @Test
    fun a_chunked_reply_is_refused_rather_than_read_as_empty() {
        // The parser has no chunk decoder, so a body without Content-Length must
        // surface as an error and not as a silent empty answer.
        val raw = (
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\nhello\r\n0\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        assertThat(CdpFrameCodec().parseHttpResponse(raw))
            .isInstanceOf(CdpHttpRead.Invalid::class.java)
    }

    // ------------------------------------------------------------------ helper

    private fun response(status: Int, headers: Map<String, String>): CdpHttpResponse =
        CdpHttpResponse(status, "", headers.mapKeys { it.key.lowercase() }, "", 0)

    /** A frame built by hand, so a decoder test does not lean on the encoder. */
    private fun frameBytes(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        maskKey: ByteArray? = null
    ): ByteArray {
        val out = ArrayList<Byte>()
        val maskBit = if (maskKey == null) 0x00 else 0x80
        out.add(((if (fin) 0x80 else 0x00) or opcode).toByte())
        val size = payload.size
        when {
            size < 126 -> out.add((maskBit or size).toByte())
            size <= 0xFFFF -> {
                out.add((maskBit or 126).toByte())
                out.add(((size ushr 8) and 0xFF).toByte())
                out.add((size and 0xFF).toByte())
            }
            else -> {
                out.add((maskBit or 127).toByte())
                for (shift in 7 downTo 0) {
                    out.add(((size.toLong() ushr (8 * shift)) and 0xFFL).toByte())
                }
            }
        }
        if (maskKey != null) {
            maskKey.forEach { out.add(it) }
            payload.forEachIndexed { index, byte ->
                out.add((byte.toInt() xor maskKey[index % 4].toInt()).toByte())
            }
        } else {
            payload.forEach { out.add(it) }
        }
        return out.toByteArray()
    }
}
