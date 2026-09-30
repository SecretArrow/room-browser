package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.bcs.BcsReader
import com.roombrowser.domain.wallet.bcs.BcsWriter
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.wire.ProtoReader
import com.roombrowser.domain.wallet.wire.ProtoWriter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

/**
 * BCS + protobuf wire roundtrips and the JSON-RPC client (with a mock
 * server: success, JSON-RPC error, HTTP failure and offline behaviour).
 */
class SerializationAndRpcTest {

    // ------------------------------------------------------------------
    // BCS
    // ------------------------------------------------------------------

    @Test
    fun `bcs writer reader roundtrip`() {
        val bytes = BcsWriter()
            .writeU8(7)
            .writeBool(true)
            .writeU64(0x1122334455667788UL.toLong())
            .writeString("héllo wörld")
            .writeBytes(byteArrayOf(1, 2, 3))
            .bytes()
        val reader = BcsReader(bytes)
        assertThat(reader.readU8()).isEqualTo(7)
        assertThat(reader.readBool()).isTrue()
        assertThat(reader.readU64()).isEqualTo(0x1122334455667788UL.toLong())
        assertThat(reader.readString()).isEqualTo("héllo wörld")
        assertThat(reader.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `bcs uleb128 encoding matches solana compact-u16`() {
        // Solana: signature count / vector lengths are ULEB128.
        assertThat(BcsWriter().writeUleb128(0).bytes()).isEqualTo(byteArrayOf(0))
        assertThat(BcsWriter().writeUleb128(1).bytes()).isEqualTo(byteArrayOf(1))
        assertThat(BcsWriter().writeUleb128(127).bytes()).isEqualTo(byteArrayOf(127))
        assertThat(BcsWriter().writeUleb128(128).bytes()).isEqualTo(byteArrayOf(0x80.toByte(), 1))
        assertThat(BcsWriter().writeUleb128(16383).bytes())
            .isEqualTo(byteArrayOf(0xFF.toByte(), 0x7F))
    }

    @Test
    fun `bcs u128 decimal parse`() {
        val le = BcsWriter.parseU128("1") // 1 → little-endian 01 00...
        assertThat(le[0]).isEqualTo(1)
        val max = BcsWriter.parseU128("340282366920938463463374607431768211455")
        assertThat(max.all { it == 0xFF.toByte() }).isTrue()
    }

    @Test
    fun `bcs reader rejects truncated input`() {
        val thrown = runCatching { BcsReader(byteArrayOf(0x05, 0x01)).readBytes() }
        assertThat(thrown.isFailure).isTrue()
    }

    // ------------------------------------------------------------------
    // Protobuf wire
    // ------------------------------------------------------------------

    @Test
    fun `protobuf writer reader roundtrip`() {
        val any = ProtoWriter.any(
            "/cosmos.bank.v1beta1.MsgSend",
            ProtoWriter().writeString(1, "cosmos1from").writeString(2, "cosmos1to").bytes()
        )
        val body = ProtoWriter().writeBytes(1, any).writeString(2, "memo").bytes()
        val fields = ProtoReader(body).all()
        assertThat(fields).hasSize(2)
        assertThat(fields[0].number).isEqualTo(1)
        val inner = fields[0].asReader().all()
        assertThat(String(inner[0].bytes, Charsets.UTF_8)).isEqualTo("/cosmos.bank.v1beta1.MsgSend")
        val msg = ProtoReader(inner[1].bytes).all()
        assertThat(String(msg[0].bytes, Charsets.UTF_8)).isEqualTo("cosmos1from")
        assertThat(String(msg[1].bytes, Charsets.UTF_8)).isEqualTo("cosmos1to")
        assertThat(String(fields[1].bytes, Charsets.UTF_8)).isEqualTo("memo")
    }

    @Test
    fun `protobuf varint encoding`() {
        val bytes = ProtoWriter().writeVarint(1, 150).bytes()
        assertThat(bytes).isEqualTo(byteArrayOf(0x08, 0x96.toByte(), 0x01)) // canonical from protobuf docs
        assertThat(ProtoReader(bytes).all()[0].varint).isEqualTo(150)
    }

    @Test
    fun `protobuf rejects malformed input`() {
        val thrown = runCatching { ProtoReader(byteArrayOf(0xFF.toByte())).all() }
        assertThat(thrown.isFailure).isTrue()
    }

    // ------------------------------------------------------------------
    // JSON-RPC (MockWebServer)
    // ------------------------------------------------------------------

    @Test
    fun `json rpc success result`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x1234"}"""))
        server.start()
        val client = JsonRpcClient()
        val result = client.call(server.url("/").toString(), "eth_chainId")
        assertThat(result.toString().replace("\"", "")).isEqualTo("0x1234")
        val request = server.takeRequest()
        assertThat(request.body.readUtf8()).contains("\"method\":\"eth_chainId\"")
        server.shutdown()
    }

    @Test
    fun `json rpc error surfaces as rpc exception`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"insufficient funds"}}""")
        )
        server.start()
        val client = JsonRpcClient()
        val thrown = runCatching {
            client.call(server.url("/").toString(), "eth_sendRawTransaction", listOf(JsonPrimitive("0xdead")))
        }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(WalletException.RpcError::class.java)
        assertThat(thrown.exceptionOrNull()).hasMessageThat().contains("insufficient funds")
        server.shutdown()
    }

    @Test
    fun `json rpc http failure surfaces clearly`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val client = JsonRpcClient()
        val thrown = runCatching {
            client.call(server.url("/").toString(), "eth_gasPrice")
        }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(WalletException.RpcError::class.java)
        server.shutdown()
    }

    @Test
    fun `json rpc unreachable endpoint is network unavailable`() = runTest {
        val client = JsonRpcClient()
        val thrown = runCatching {
            // RFC 5737 TEST-NET address: guaranteed unroutable.
            client.call("http://192.0.2.1:1/", "eth_chainId")
        }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(WalletException.NetworkUnavailable::class.java)
    }
}
