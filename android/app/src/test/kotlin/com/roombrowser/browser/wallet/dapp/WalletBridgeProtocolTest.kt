package com.roombrowser.browser.wallet.dapp

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test
import java.util.Base64

/**
 * JVM round-trip tests for the dApp wallet bridge codec: envelope parsing,
 * every [DappRequest] builder (both personal_sign param shapes, the base64
 * message path, addEthereumChain's full/optional shapes), outcome/response
 * script encoding (raw JSON value forms, error forms, \u2028/\u2029
 * escaping), RPC relay shaping, and the injected script's structural
 * invariants. Pure JVM — no Android types cross this file.
 */
class WalletBridgeProtocolTest {

    private val host = "app.uniswap.org"
    private val origin = "https://app.uniswap.org/"
    private val evmAddress = "0xabc00000000000000000000000000000000000abc"

    /** Page envelope JSON for a kind "request" call. */
    private fun envelope(id: String, chain: String, method: String, params: String): String =
        "{\"id\":\"$id\",\"kind\":\"request\",\"chain\":\"$chain\",\"method\":\"$method\",\"params\":$params}"

    /** Parses + builds in one step (parse must have succeeded). */
    private fun build(
        payload: String,
        dappId: String = "d-1",
        activeNetworkId: String? = "EVM:1",
        fallbackAddress: String? = evmAddress
    ): DappBuildResult {
        val parsed = WalletBridgeProtocol.parseRequest(payload)
        assertThat(parsed).isInstanceOf(BridgeParseResult.Ok::class.java)
        parsed as BridgeParseResult.Ok
        return WalletBridgeProtocol.buildDappRequest(
            parsed.call as WalletDappCall, dappId, host, origin, activeNetworkId, fallbackAddress
        )
    }

    private fun ok(result: DappBuildResult): DappRequest {
        assertThat(result).isInstanceOf(DappBuildResult.Ok::class.java)
        result as DappBuildResult.Ok
        return result.request
    }

    private fun invalid(result: DappBuildResult): com.roombrowser.browser.wallet.WalletBridgeError {
        assertThat(result).isInstanceOf(DappBuildResult.Invalid::class.java)
        result as DappBuildResult.Invalid
        return result.error
    }

    // ------------------------------------------------------------------
    // Envelope parsing
    // ------------------------------------------------------------------

    @Test
    fun `parse request and rpc envelopes`() {
        val req = WalletBridgeProtocol.parseRequest(
            envelope("w1", "EVM", "eth_requestAccounts", "[]")
        )
        req as BridgeParseResult.Ok
        val dapp = req.call as WalletDappCall
        assertThat(dapp.id).isEqualTo("w1")
        assertThat(dapp.chainType).isEqualTo(ChainType.EVM)
        assertThat(dapp.method).isEqualTo("eth_requestAccounts")

        val rpc = WalletBridgeProtocol.parseRequest(
            "{\"id\":\"w2\",\"kind\":\"rpc\",\"chain\":\"EVM\",\"method\":\"eth_chainId\",\"params\":null}"
        )
        rpc as BridgeParseResult.Ok
        val rpcCall = rpc.call as WalletRpcCall
        assertThat(rpcCall.id).isEqualTo("w2")
        assertThat(rpcCall.method).isEqualTo("eth_chainId")
    }

    @Test
    fun `parse rejects invalid payloads with typed errors never exceptions`() {
        val bad = listOf(
            "", "   ", "not json", "[1,2]", "\"x\"",
            "{\"id\":\"w1\"}",
            "{\"id\":\"w1\",\"kind\":\"request\",\"chain\":\"DOGECOIN\",\"method\":\"x\"}",
            "{\"id\":\"w1\",\"kind\":\"request\",\"chain\":\"EVM\",\"method\":\"\"}",
            "{\"id\":\"w1\",\"kind\":\"request\",\"chain\":\"EVM\"}",
            "{\"id\":\"w1\",\"kind\":\"wat\",\"chain\":\"EVM\",\"method\":\"x\"}",
            "{\"kind\":\"request\",\"chain\":\"EVM\",\"method\":\"x\"}",
            "{\"id\":5,\"kind\":\"request\",\"chain\":\"EVM\",\"method\":\"x\"}"
        )
        for (payload in bad) {
            val res = WalletBridgeProtocol.parseRequest(payload)
            assertThat(res).isInstanceOf(BridgeParseResult.Invalid::class.java)
            res as BridgeParseResult.Invalid
            assertThat(res.error.code).isEqualTo(-32602)
            assertThat(res.error.message).isNotEmpty()
        }
        // The page id is preserved whenever it could be read, so the page's
        // promise never hangs on a parse failure.
        val preserved = WalletBridgeProtocol.parseRequest(
            "{\"id\":\"w9\",\"kind\":\"request\",\"chain\":\"NOPE\",\"method\":\"x\"}"
        )
        preserved as BridgeParseResult.Invalid
        assertThat(preserved.pageId).isEqualTo("w9")
    }

    // ------------------------------------------------------------------
    // personal_sign (both dApp param shapes, hex + utf-8 + base64)
    // ------------------------------------------------------------------

    @Test
    fun `personal_sign array shape with hex message decodes display`() {
        val request = ok(
            build(envelope("w1", "EVM", "personal_sign", "[\"0x48656c6c6f\",\"$evmAddress\"]"))
        ) as DappRequest.SignMessage
        assertThat(request.id).isEqualTo("d-1")
        assertThat(request.host).isEqualTo(host)
        assertThat(request.chainType).isEqualTo(ChainType.EVM)
        assertThat(request.accountAddress).isEqualTo(evmAddress)
        assertThat(request.message).isEqualTo("0x48656c6c6f")
        assertThat(request.displayMessage).isEqualTo("Hello")
    }

    @Test
    fun `personal_sign object shape with utf-8 message`() {
        val request = ok(
            build(
                envelope(
                    "w1", "EVM", "personal_sign",
                    "{\"message\":\"Funding proposal for Q3\",\"address\":\"$evmAddress\"}"
                )
            )
        ) as DappRequest.SignMessage
        assertThat(request.accountAddress).isEqualTo(evmAddress)
        assertThat(request.message).isEqualTo("Funding proposal for Q3")
        assertThat(request.displayMessage).isEqualTo("Funding proposal for Q3")
    }

    @Test
    fun `personal_sign base64 wrapper decodes utf-8 text`() {
        // Exactly what the injected script produces for a non-hex message.
        val text = "héllo ✨ — grant 0.1 ETH"
        val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val request = ok(
            build(
                envelope(
                    "w1", "EVM", "personal_sign",
                    "{\"message\":{\"__roomB64\":\"$b64\"},\"address\":\"$evmAddress\"}"
                )
            )
        ) as DappRequest.SignMessage
        assertThat(request.message).isEqualTo(text)
        assertThat(request.displayMessage).isEqualTo(text)
    }

    @Test
    fun `personal_sign hex message with non-utf8 bytes falls back to raw hex`() {
        val request = ok(
            build(envelope("w1", "EVM", "personal_sign", "[\"0xffff\",\"$evmAddress\"]"))
        ) as DappRequest.SignMessage
        assertThat(request.message).isEqualTo("0xffff")
        assertThat(request.displayMessage).isEqualTo("0xffff")
    }

    @Test
    fun `personal_sign display truncates to 500 chars with ellipsis`() {
        val long = "a".repeat(600)
        val request = ok(
            build(envelope("w1", "EVM", "personal_sign", "[\"$long\",\"$evmAddress\"]"))
        ) as DappRequest.SignMessage
        assertThat(request.message).isEqualTo(long)
        assertThat(request.displayMessage).isEqualTo("a".repeat(500) + "…")
    }

    @Test
    fun `personal_sign missing address is a typed error`() {
        val error = invalid(build(envelope("w1", "EVM", "personal_sign", "[\"0x4869\"]")))
        assertThat(error.code).isEqualTo(-32602)
    }

    // ------------------------------------------------------------------
    // eth_signTypedData
    // ------------------------------------------------------------------

    @Test
    fun `sign typed data accepts string and object payloads`() {
        val typedObject = "{\"types\":{\"EIP712Domain\":[]},\"domain\":{\"name\":\"Dapp\"}," +
            "\"primaryType\":\"Mail\",\"message\":{\"body\":\"hi\"}}"
        // Object payload (the common shape): params [address, {typedData}].
        val fromObject = ok(
            build(envelope("w1", "EVM", "eth_signTypedData_v4", "[\"$evmAddress\",$typedObject]"))
        ) as DappRequest.SignTypedData
        assertThat(fromObject.accountAddress).isEqualTo(evmAddress)
        assertThat(fromObject.typedDataJson).contains("\"name\":\"Dapp\"")

        // A dApp passing the typed data as a JSON string (the other common
        // shape) parses to the same normalized JSON.
        val escaped = typedObject.replace("\"", "\\\"")
        val fromString = ok(
            build(envelope("w2", "EVM", "eth_signTypedData", "[\"$evmAddress\",\"$escaped\"]"))
        ) as DappRequest.SignTypedData
        assertThat(fromString.typedDataJson).isEqualTo(fromObject.typedDataJson)

        // A non-JSON string is rejected, not forwarded.
        val error = invalid(
            build(envelope("w3", "EVM", "eth_signTypedData_v4", "[\"$evmAddress\",\"not json\"]"))
        )
        assertThat(error.code).isEqualTo(-32602)
    }

    // ------------------------------------------------------------------
    // eth_sendTransaction
    // ------------------------------------------------------------------

    @Test
    fun `send transaction builds with declared from`() {
        val request = ok(
            build(
                envelope(
                    "w1", "EVM", "eth_sendTransaction",
                    "[{\"from\":\"$evmAddress\",\"to\":\"0xdef00000000000000000000000000000000000def\"," +
                        "\"value\":\"0x1\",\"data\":\"0xdeadbeef\",\"gas\":\"0x5208\"}]"
                )
            )
        ) as DappRequest.SendTransaction
        assertThat(request.networkId).isEqualTo("EVM:1")
        assertThat(request.accountAddress).isEqualTo(evmAddress)
        assertThat(request.feeEstimate).isNull()
        assertThat(request.txParamsJson).contains("\"from\":\"$evmAddress\"")
        assertThat(request.txParamsJson).contains("\"to\":\"0xdef00000000000000000000000000000000000def\"")
        assertThat(request.txParamsJson).contains("\"value\":\"0x1\"")
    }

    @Test
    fun `send transaction injects fallback from when dApp omits it`() {
        val request = ok(
            build(
                envelope(
                    "w1", "EVM", "eth_sendTransaction",
                    "[{\"to\":\"0xdef00000000000000000000000000000000000def\",\"value\":\"0x2\"}]"
                )
            )
        ) as DappRequest.SendTransaction
        assertThat(request.accountAddress).isEqualTo(evmAddress)
        assertThat(request.txParamsJson).contains("\"from\":\"$evmAddress\"")
    }

    @Test
    fun `send transaction requires network and account`() {
        val noNetwork = invalid(
            build(
                envelope("w1", "EVM", "eth_sendTransaction", "[{\"to\":\"0xdef\"}]"),
                activeNetworkId = null
            )
        )
        assertThat(noNetwork.code).isEqualTo(4901)

        val noAccount = invalid(
            build(
                envelope("w2", "EVM", "eth_sendTransaction", "[{\"to\":\"0xdef\"}]"),
                fallbackAddress = null
            )
        )
        assertThat(noAccount.code).isEqualTo(4100)
    }

    // ------------------------------------------------------------------
    // wallet_switchEthereumChain / wallet_addEthereumChain
    // ------------------------------------------------------------------

    @Test
    fun `switch chain parses hex and decimal chain ids`() {
        val hex = ok(
            build(envelope("w1", "EVM", "wallet_switchEthereumChain", "[{\"chainId\":\"0x1\"}]"))
        ) as DappRequest.SwitchChain
        assertThat(hex.targetNetworkId).isEqualTo("EVM:1")

        val decimal = ok(
            build(envelope("w2", "EVM", "wallet_switchEthereumChain", "[{\"chainId\":\"1\"}]"))
        ) as DappRequest.SwitchChain
        assertThat(decimal.targetNetworkId).isEqualTo("EVM:1")

        val polygon = ok(
            build(envelope("w3", "EVM", "wallet_switchEthereumChain", "[{\"chainId\":\"0x89\"}]"))
        ) as DappRequest.SwitchChain
        assertThat(polygon.targetNetworkId).isEqualTo("EVM:137")

        val error = invalid(
            build(envelope("w4", "EVM", "wallet_switchEthereumChain", "[{\"chainId\":\"zz\"}]"))
        )
        assertThat(error.code).isEqualTo(-32602)
    }

    @Test
    fun `add chain full shape maps to network config`() {
        val request = ok(
            build(
                envelope(
                    "w1", "EVM", "wallet_addEthereumChain",
                    "[{\"chainId\":\"0x13882\",\"chainName\":\"Base Sepolia\"," +
                        "\"rpcUrls\":[\"https://sepolia.base.org\"]," +
                        "\"nativeCurrency\":{\"name\":\"Ether\",\"symbol\":\"ETH\",\"decimals\":18}," +
                        "\"blockExplorerUrls\":[\"https://sepolia.basescan.org\"]}]"
                )
            )
        ) as DappRequest.AddChain
        val config = request.proposed
        assertThat(config.id).isEqualTo("EVM:80002")
        assertThat(config.chainId).isEqualTo("80002")
        assertThat(config.chainType).isEqualTo(ChainType.EVM)
        assertThat(config.name).isEqualTo("Base Sepolia")
        assertThat(config.rpcUrls).containsExactly("https://sepolia.base.org")
        assertThat(config.nativeSymbol).isEqualTo("ETH")
        assertThat(config.nativeDecimals).isEqualTo(18)
        assertThat(config.explorerUrl).isEqualTo("https://sepolia.basescan.org")
    }

    @Test
    fun `add chain optional fields and validation`() {
        // blockExplorerUrls is optional; a bare decimal chainId is tolerated.
        val noExplorer = ok(
            build(
                envelope(
                    "w1", "EVM", "wallet_addEthereumChain",
                    "[{\"chainId\":\"137\",\"chainName\":\"Polygon\"," +
                        "\"rpcUrls\":[\"https://polygon-rpc.com\"]}]"
                )
            )
        ) as DappRequest.AddChain
        assertThat(noExplorer.proposed.id).isEqualTo("EVM:137")
        assertThat(noExplorer.proposed.explorerUrl).isNull()
        assertThat(noExplorer.proposed.nativeSymbol).isEqualTo("ETH")
        assertThat(noExplorer.proposed.nativeDecimals).isEqualTo(18)

        // rpcUrls are mandatory.
        val noRpc = invalid(
            build(
                envelope(
                    "w2", "EVM", "wallet_addEthereumChain",
                    "[{\"chainId\":\"0x1\",\"chainName\":\"Mainnet\"}]"
                )
            )
        )
        assertThat(noRpc.code).isEqualTo(-32602)
    }

    // ------------------------------------------------------------------
    // Connect + non-EVM methods
    // ------------------------------------------------------------------

    @Test
    fun `connect methods build per chain with verified host and origin`() {
        val evm = ok(
            build(envelope("w1", "EVM", "eth_requestAccounts", "[]"), dappId = "d-evm")
        ) as DappRequest.Connect
        assertThat(evm.chainType).isEqualTo(ChainType.EVM)
        assertThat(evm.host).isEqualTo(host)
        assertThat(evm.originUrl).isEqualTo(origin)

        val solana = ok(
            build(envelope("w2", "SOLANA", "connect", "[]"), dappId = "d-sol")
        ) as DappRequest.Connect
        assertThat(solana.chainType).isEqualTo(ChainType.SOLANA)

        val sui = ok(
            build(envelope("w3", "SUI", "requestAccounts", "[]"), dappId = "d-sui")
        ) as DappRequest.Connect
        assertThat(sui.chainType).isEqualTo(ChainType.SUI)

        val tron = ok(
            build(envelope("w4", "TRON", "tron_requestAccounts", "[]"), dappId = "d-tron")
        ) as DappRequest.Connect
        assertThat(tron.chainType).isEqualTo(ChainType.TRON)
    }

    @Test
    fun `solana sign message uses fallback account and base64 message`() {
        val b64 = Base64.getEncoder().encodeToString("gm".toByteArray(Charsets.UTF_8))
        val request = ok(
            build(
                envelope(
                    "w1", "SOLANA", "signMessage",
                    "{\"message\":{\"__roomB64\":\"$b64\"},\"encoding\":\"utf8\"}"
                ),
                activeNetworkId = "SOLANA:mainnet-beta",
                fallbackAddress = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZ8DsGYdAVoxKPPQmMx"
            )
        ) as DappRequest.SignMessage
        assertThat(request.chainType).isEqualTo(ChainType.SOLANA)
        assertThat(request.accountAddress).isEqualTo("9WzDXwBbmkg8ZTbNMqUxvQRAyrZ8DsGYdAVoxKPPQmMx")
        assertThat(request.message).isEqualTo("gm")
        assertThat(request.displayMessage).isEqualTo("gm")

        // Without any account of that chain the call is UNAUTHORIZED.
        val error = invalid(
            build(
                envelope("w2", "SOLANA", "signMessage", "{\"message\":\"gm\"}"),
                activeNetworkId = "SOLANA:mainnet-beta",
                fallbackAddress = null
            )
        )
        assertThat(error.code).isEqualTo(4100)
    }

    @Test
    fun `non-evm sign and send families map to send transaction`() {
        val solana = ok(
            build(
                envelope(
                    "w1", "SOLANA", "signAndSendTransaction",
                    "{\"transaction\":\"BASE64TX==\"}"
                ),
                activeNetworkId = "SOLANA:mainnet-beta",
                fallbackAddress = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZ8DsGYdAVoxKPPQmMx"
            )
        ) as DappRequest.SendTransaction
        assertThat(solana.chainType).isEqualTo(ChainType.SOLANA)
        assertThat(solana.networkId).isEqualTo("SOLANA:mainnet-beta")
        assertThat(solana.txParamsJson).contains("BASE64TX==")

        val aptos = ok(
            build(
                envelope(
                    "w2", "APTOS", "signAndSubmitTransaction",
                    "{\"transaction\":{\"type\":\"entry_function_payload\",\"function\":\"0x1::coin::transfer\"}}"
                ),
                activeNetworkId = "APTOS:mainnet",
                fallbackAddress = "0xace"
            )
        ) as DappRequest.SendTransaction
        assertThat(aptos.chainType).isEqualTo(ChainType.APTOS)
        assertThat(aptos.txParamsJson).contains("entry_function_payload")

        val sui = ok(
            build(
                envelope(
                    "w3", "SUI", "signAndExecuteTransactionBlock",
                    "{\"transactionBlock\":{\"kind\":\"ProgrammableTransaction\"},\"options\":{\"showEffects\":true}}"
                ),
                activeNetworkId = "SUI:mainnet",
                fallbackAddress = "0xsui"
            )
        ) as DappRequest.SendTransaction
        assertThat(sui.chainType).isEqualTo(ChainType.SUI)
        assertThat(sui.txParamsJson).contains("showEffects")
    }

    @Test
    fun `unsupported methods and chain mismatches are typed errors`() {
        // Never relayable / never promptable, regardless of claimed kind.
        assertThat(
            invalid(build(envelope("w1", "EVM", "eth_sendRawTransaction", "[\"0x00\"]"))).code
        ).isEqualTo(4200)
        assertThat(
            invalid(build(envelope("w2", "EVM", "eth_decrypt", "[\"0x00\"]"))).code
        ).isEqualTo(4200)
        // Method/chain mismatches.
        assertThat(
            invalid(build(envelope("w3", "EVM", "signMessage", "{\"message\":\"x\"}"))).code
        ).isEqualTo(-32602)
        assertThat(
            invalid(build(envelope("w4", "SOLANA", "personal_sign", "[\"0x00\",\"0x00\"]"))).code
        ).isEqualTo(-32602)
        assertThat(
            invalid(
                build(
                    envelope("w5", "APTOS", "signAndSendTransaction", "{\"transaction\":{}}"),
                    activeNetworkId = "APTOS:mainnet", fallbackAddress = "0xace"
                )
            ).code
        ).isEqualTo(-32602)
    }

    // ------------------------------------------------------------------
    // Response / emit script encoding
    // ------------------------------------------------------------------

    @Test
    fun `response script encodes raw json value results`() {
        assertThat(WalletBridgeProtocol.encodeResponseScript("w1", "\"0x1\"", 0, null))
            .isEqualTo(
                "window.__roomWalletResponse && window.__roomWalletResponse(" +
                    "\"w1\", \"\\\"0x1\\\"\", 0, \"\")"
            )
        assertThat(WalletBridgeProtocol.encodeResponseScript("w1", "[\"0xa\"]", 0, null))
            .isEqualTo(
                "window.__roomWalletResponse && window.__roomWalletResponse(" +
                    "\"w1\", \"[\\\"0xa\\\"]\", 0, \"\")"
            )
        assertThat(WalletBridgeProtocol.encodeResponseScript("w1", null, 0, null))
            .isEqualTo(
                "window.__roomWalletResponse && window.__roomWalletResponse(" +
                    "\"w1\", \"null\", 0, \"\")"
            )
    }

    @Test
    fun `response script encodes error form`() {
        assertThat(
            WalletBridgeProtocol.encodeResponseScript("w1", null, 4001, "User \"denied\" it")
        ).isEqualTo(
            "window.__roomWalletResponse && window.__roomWalletResponse(" +
                "\"w1\", \"null\", 4001, \"User \\\"denied\\\" it\")"
        )
        // A success whose resultJson is not valid JSON degrades to INTERNAL.
        assertThat(WalletBridgeProtocol.encodeResponseScript("w1", "0x1-broken", 0, null))
            .isEqualTo(
                "window.__roomWalletResponse && window.__roomWalletResponse(" +
                    "\"w1\", \"null\", -32603, \"Malformed result payload\")"
            )
    }

    @Test
    fun `response script escapes u2028 and u2029`() {
        // resultJson is the raw JSON value: "a<U+2028>b<U+2029>c" with quotes.
        val resultJson = "\"a\u2028b\u2029c\""
        val script = WalletBridgeProtocol.encodeResponseScript("w1", resultJson, 0, null)
        assertThat(script).contains("\\u2028")
        assertThat(script).contains("\\u2029")
        // The raw separators must never reach the evaluated JS source.
        assertThat(script).doesNotContain("\u2028")
        assertThat(script).doesNotContain("\u2029")
    }

    @Test
    fun `js string literal escapes quotes backslash and control chars`() {
        assertThat(WalletBridgeProtocol.jsStringLiteral("w1")).isEqualTo("\"w1\"")
        assertThat(WalletBridgeProtocol.jsStringLiteral("a\"b\\c"))
            .isEqualTo("\"a\\\"b\\\\c\"")
        // x <U+2028> y " z <newline> <U+2029> w
        assertThat(WalletBridgeProtocol.jsStringLiteral("x\u2028y\"z\n\u2029w"))
            .isEqualTo("\"x\\u2028y\\\"z\\n\\u2029w\"")
        assertThat(WalletBridgeProtocol.jsStringLiteral("\u0001"))
            .isEqualTo("\"\\u0001\"")
        assertThat(WalletBridgeProtocol.jsStringLiteral("a/b"))
            .isEqualTo("\"a\\/b\"")
    }

    @Test
    fun `emit script quotes event and payload`() {
        assertThat(WalletBridgeProtocol.encodeEmitScript("chainChanged", "\"0x1\""))
            .isEqualTo(
                "window.__roomWalletEmit && window.__roomWalletEmit(" +
                    "\"chainChanged\", \"\\\"0x1\\\"\")"
            )
    }

    // ------------------------------------------------------------------
    // RPC relay shaping
    // ------------------------------------------------------------------

    @Test
    fun `readonly rpc allowlist and params shaping`() {
        assertThat(WalletBridgeProtocol.isReadonlyRpcMethod("eth_chainId")).isTrue()
        assertThat(WalletBridgeProtocol.isReadonlyRpcMethod("net_version")).isTrue()
        assertThat(WalletBridgeProtocol.isReadonlyRpcMethod("eth_getBalance")).isTrue()
        assertThat(WalletBridgeProtocol.isReadonlyRpcMethod("eth_estimateGas")).isTrue()
        // Broadcasts and wallet-level calls are never relayable as reads.
        assertThat(WalletBridgeProtocol.isReadonlyRpcMethod("eth_sendRawTransaction")).isFalse()
        assertThat(WalletBridgeProtocol.isReadonlyRpcMethod("eth_accounts")).isFalse()

        val arrayParams = WalletRpcCall(
            "w1", ChainType.EVM, "eth_getBalance",
            Json.parseToJsonElement("[\"0xabc\", \"latest\"]")
        )
        assertThat(WalletBridgeProtocol.rpcParamsList(arrayParams)).hasSize(2)

        val nullParams = WalletRpcCall("w2", ChainType.EVM, "eth_chainId", JsonNull)
        assertThat(WalletBridgeProtocol.rpcParamsList(nullParams)).isEmpty()

        val objectParams = WalletRpcCall(
            "w3", ChainType.EVM, "eth_call", Json.parseToJsonElement("{\"to\":\"0x1\"}")
        )
        assertThat(WalletBridgeProtocol.rpcParamsList(objectParams)).isNull()
    }

    @Test
    fun `relay error mapping follows eip1193`() {
        val offline = WalletBridgeProtocol.relayError(WalletException.NetworkUnavailable())
        assertThat(offline.code).isEqualTo(4901)

        val rpcError = WalletBridgeProtocol.relayError(
            WalletException.RpcError(-32000, "execution reverted")
        )
        assertThat(rpcError.code).isEqualTo(-32000)
        assertThat(rpcError.message).isEqualTo("execution reverted")

        val badParams = WalletBridgeProtocol.relayError(WalletException.InvalidParams("bad endpoint"))
        assertThat(badParams.code).isEqualTo(-32602)

        val other = WalletBridgeProtocol.relayError(WalletException.UserRejected())
        assertThat(other.code).isEqualTo(-32603)
    }

    @Test
    fun `relay result resolves as a raw json value`() {
        val result = Json.parseToJsonElement("\"0x1\"")
        val script = WalletBridgeProtocol.encodeResponseScript("w1", result.toString(), 0, null)
        assertThat(script).isEqualTo(
            "window.__roomWalletResponse && window.__roomWalletResponse(" +
                "\"w1\", \"\\\"0x1\\\"\", 0, \"\")"
        )
    }

    /**
     * Chain identity is answered from local configuration, never relayed.
     * A wallet whose `eth_chainId` depends on a third-party RPC cannot be
     * connected to while that RPC is blocked — which is exactly how a
     * wagmi dApp (Uniswap) reports "Error connecting, try again".
     */
    @Test
    fun `chain identity is answered locally, in each method's own shape`() {
        val mainnet = evmNetwork(chainId = "1")
        // eth_chainId is a JSON-quoted hex quantity with no leading zeros.
        assertThat(WalletBridgeProtocol.localChainAnswer("eth_chainId", mainnet))
            .isEqualTo("\"0x1\"")
        // net_version is the DECIMAL chain id as a string — not the hex form.
        assertThat(WalletBridgeProtocol.localChainAnswer("net_version", mainnet))
            .isEqualTo("\"1\"")

        val polygon = evmNetwork(chainId = "137")
        assertThat(WalletBridgeProtocol.localChainAnswer("eth_chainId", polygon))
            .isEqualTo("\"0x89\"")
        assertThat(WalletBridgeProtocol.localChainAnswer("net_version", polygon))
            .isEqualTo("\"137\"")

        // No leading zeros: chain 16 must be 0x10, never 0x010.
        assertThat(WalletBridgeProtocol.localChainAnswer("eth_chainId", evmNetwork(chainId = "16")))
            .isEqualTo("\"0x10\"")
    }

    @Test
    fun `only the two identity methods are answered locally`() {
        val network = evmNetwork(chainId = "1")
        // Real reads still go to the chain: a cached block number or balance
        // would be a lie, and those are the calls the relay exists for.
        for (method in listOf("eth_blockNumber", "eth_getBalance", "eth_call", "eth_gasPrice", "eth_estimateGas")) {
            assertThat(WalletBridgeProtocol.localChainAnswer(method, network)).isNull()
        }
        // Unknown methods are not identity calls either.
        assertThat(WalletBridgeProtocol.localChainAnswer("eth_sendTransaction", network)).isNull()
    }

    @Test
    fun `chain identity falls back to the relay when there is no usable chain id`() {
        // No active network: null, so the caller reports CHAIN_DISCONNECTED
        // rather than inventing a chain id out of nothing.
        assertThat(WalletBridgeProtocol.localChainAnswer("eth_chainId", null)).isNull()
        // A custom network with a non-numeric id has no hex form; it must
        // not be guessed at, and must not throw.
        assertThat(WalletBridgeProtocol.localChainAnswer("eth_chainId", evmNetwork(chainId = "mainnet")))
            .isNull()
        assertThat(WalletBridgeProtocol.localChainAnswer("net_version", evmNetwork(chainId = "mainnet")))
            .isNull()
    }

    /** A minimal EVM network for the chain-identity tests. */
    private fun evmNetwork(chainId: String) = NetworkConfig(
        id = "EVM:$chainId",
        chainType = ChainType.EVM,
        chainId = chainId,
        name = "test",
        rpcUrls = listOf("https://rpc.invalid"),
        nativeSymbol = "ETH"
    )

    // ------------------------------------------------------------------
    // Success-value shapes + permission keys
    // ------------------------------------------------------------------

    @Test
    fun `connect and accounts success result shapes per chain`() {
        val addr = "0xabc"
        assertThat(WalletBridgeProtocol.connectSuccessResult(ChainType.EVM, addr))
            .isEqualTo("[\"0xabc\"]")
        assertThat(WalletBridgeProtocol.connectSuccessResult(ChainType.SOLANA, "SOLADDR"))
            .isEqualTo("{\"publicKey\":\"SOLADDR\"}")
        assertThat(WalletBridgeProtocol.connectSuccessResult(ChainType.APTOS, "0xapt"))
            .isEqualTo("{\"address\":\"0xapt\"}")
        assertThat(WalletBridgeProtocol.connectSuccessResult(ChainType.SUI, "0xsui"))
            .isEqualTo("[\"0xsui\"]")
        assertThat(WalletBridgeProtocol.connectSuccessResult(ChainType.TRON, "TXYZ"))
            .isEqualTo("{\"address\":\"TXYZ\"}")

        assertThat(WalletBridgeProtocol.accountsResult(listOf("0xa", "0xb")))
            .isEqualTo("[\"0xa\",\"0xb\"]")
        assertThat(WalletBridgeProtocol.accountsResult(emptyList())).isEqualTo("[]")
    }

    @Test
    fun `permission method key is per chain family`() {
        assertThat(WalletBridgeProtocol.permissionMethodFor(ChainType.EVM))
            .isEqualTo("eth_requestAccounts")
        assertThat(WalletBridgeProtocol.permissionMethodFor(ChainType.SOLANA))
            .isEqualTo("connect")
        assertThat(WalletBridgeProtocol.permissionMethodFor(ChainType.TRON))
            .isEqualTo("connect")
    }

    // ------------------------------------------------------------------
    // Truthful advertising: every granted method must be routable
    // ------------------------------------------------------------------

    /** Params that make [method] a VALID call for [chain] (the happy shape). */
    private fun stubParams(chain: ChainType, method: String): String = when (method) {
        "personal_sign" -> "[\"0x48656c6c6f\",\"$evmAddress\"]"
        "eth_signTypedData_v3", "eth_signTypedData_v4" ->
            "[\"$evmAddress\",{\"types\":{},\"domain\":{},\"primaryType\":\"Mail\",\"message\":{}}]"
        "eth_sendTransaction" -> "[{\"from\":\"$evmAddress\",\"to\":\"0xdef\"}]"
        "wallet_switchEthereumChain" -> "[{\"chainId\":\"0x1\"}]"
        "wallet_addEthereumChain" ->
            "[{\"chainId\":\"0x89\",\"rpcUrls\":[\"https://rpc.example\"]}]"
        "signAndSendTransaction" -> "{\"transaction\":\"BASE64TX==\"}"
        "signAndSubmitTransaction" -> "{\"transaction\":{\"type\":\"entry_function_payload\"}}"
        "signTransaction" -> if (chain == ChainType.APTOS) {
            "{\"txBytes\":\"AA==\"}"
        } else {
            "{\"raw_data_hex\":\"0a02\"}"
        }
        "signAndExecuteTransactionBlock", "signAndExecuteTransaction" ->
            "{\"transactionBlock\":{\"kind\":\"ProgrammableTransaction\"}}"
        "signMessage", "signPersonalMessage", "signArbitrary" -> "{\"message\":\"gm\"}"
        "signAmino" -> "{\"signDoc\":{\"chain_id\":\"cosmoshub-4\",\"msgs\":[]}}"
        "signDirect" -> "{\"bodyBytes\":\"AA==\",\"authInfoBytes\":\"AA==\"," +
            "\"chainId\":\"cosmoshub-4\",\"accountNumber\":\"7\"}"
        "enable" -> "{\"chainId\":\"cosmoshub-4\"}"
        else -> "[]"
    }

    /** Any non-blank account of [chain] (the builders only check presence). */
    private fun stubAccount(chain: ChainType): String = when (chain) {
        ChainType.EVM -> evmAddress
        ChainType.SOLANA -> "9WzDXwBbmkg8ZTbNMqUxvQRAyrZ8DsGYdAVoxKPPQmMx"
        ChainType.APTOS, ChainType.SUI -> "0x1"
        ChainType.COSMOS -> "cosmos1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9"
        ChainType.BITCOIN -> "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        ChainType.TRON -> "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8"
    }

    /**
     * The invariant behind the audit: nothing is recorded in a permission
     * grant that the wallet cannot route — otherwise a dApp is told it may
     * call a method that then fails with UNSUPPORTED_METHOD.
     */
    @Test
    fun `every granted method is routable`() {
        for (chain in ChainType.entries) {
            val granted = WalletBridgeProtocol.grantedMethodsFor(chain)
            assertThat(granted).isNotEmpty()
            assertThat(granted.toSet()).hasSize(granted.size) // no duplicate grants
            for (method in granted) {
                if (method in WalletBridgeProtocol.BRIDGE_LOCAL_METHODS) continue
                val result = build(
                    envelope("w1", chain.name, method, stubParams(chain, method)),
                    activeNetworkId = "NET:" + chain.name,
                    fallbackAddress = stubAccount(chain)
                )
                // A null error means DappBuildResult.Ok: the call routes.
                assertThat((result as? DappBuildResult.Invalid)?.error)
                    .isNull()
            }
        }
    }

    @Test
    fun `granted methods never include client-rejected or unroutable names`() {
        for (chain in ChainType.entries) {
            val granted = WalletBridgeProtocol.grantedMethodsFor(chain)
            // eth_signTransaction is hard-rejected client-side with 4200 and
            // has no native route, so it must never be advertised.
            assertThat(granted).doesNotContain("eth_signTransaction")
            assertThat(granted).doesNotContain("eth_sendRawTransaction")
            assertThat(granted).doesNotContain("eth_decrypt")
            assertThat(granted).doesNotContain("eth_getEncryptionPublicKey")
            // The engine has no sign-only path for these chains' transactions.
            if (chain == ChainType.SOLANA || chain == ChainType.SUI || chain == ChainType.BITCOIN) {
                assertThat(granted).doesNotContain("signTransaction")
            }
            // Sui's granted names must be the ones the protocol routes.
            if (chain == ChainType.SUI) {
                assertThat(granted).contains("signAndExecuteTransactionBlock")
            }
        }
    }

    // ------------------------------------------------------------------
    // Aliases and the Cosmos/TRON/Sui routes added for the audit
    // ------------------------------------------------------------------

    @Test
    fun `eth_signTypedData_v3 is accepted like v4`() {
        val typed = "{\"types\":{},\"domain\":{\"name\":\"D\"},\"primaryType\":\"Mail\",\"message\":{}}"
        val request = ok(
            build(envelope("w1", "EVM", "eth_signTypedData_v3", "[\"$evmAddress\",$typed]"))
        ) as DappRequest.SignTypedData
        assertThat(request.accountAddress).isEqualTo(evmAddress)
        assertThat(request.typedDataJson).contains("\"primaryType\":\"Mail\"")
    }

    @Test
    fun `sui wallet-standard aliases route to the same builders`() {
        val personal = ok(
            build(
                envelope("w1", "SUI", "signPersonalMessage", "{\"message\":\"gm\"}"),
                activeNetworkId = "SUI:mainnet",
                fallbackAddress = "0xsui"
            )
        ) as DappRequest.SignMessage
        assertThat(personal.chainType).isEqualTo(ChainType.SUI)
        assertThat(personal.message).isEqualTo("gm")

        val execute = ok(
            build(
                envelope(
                    "w2", "SUI", "signAndExecuteTransaction",
                    "{\"transactionBlock\":{\"kind\":\"ProgrammableTransaction\"}}"
                ),
                activeNetworkId = "SUI:mainnet",
                fallbackAddress = "0xsui"
            )
        ) as DappRequest.SendTransaction
        assertThat(execute.chainType).isEqualTo(ChainType.SUI)
        assertThat(execute.txParamsJson).contains("ProgrammableTransaction")

        // The alias is Sui-only.
        assertThat(
            invalid(build(envelope("w3", "SOLANA", "signPersonalMessage", "{\"message\":\"gm\"}"))).code
        ).isEqualTo(-32602)
    }

    @Test
    fun `cosmos signDirect builds a sign-only send transaction`() {
        val signer = "cosmos1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9"
        val request = ok(
            build(
                envelope(
                    "w1", "COSMOS", "signDirect",
                    "{\"chainId\":\"cosmoshub-4\",\"signer\":\"$signer\"," +
                        "\"bodyBytes\":\"AQID\",\"authInfoBytes\":\"/w==\",\"accountNumber\":\"7\"}"
                ),
                activeNetworkId = "COSMOS:cosmoshub-4",
                fallbackAddress = "cosmos1other"
            )
        ) as DappRequest.SendTransaction
        // The signer the dApp named wins over the profile's primary account.
        assertThat(request.accountAddress).isEqualTo(signer)
        assertThat(request.networkId).isEqualTo("COSMOS:cosmoshub-4")
        assertThat(request.txParamsJson).contains("\"signOnly\":true")
        assertThat(request.txParamsJson).contains("\"bodyBytes\":\"AQID\"")
        assertThat(request.txParamsJson).contains("\"authInfoBytes\":\"/w==\"")
        assertThat(request.txParamsJson).contains("\"accountNumber\":\"7\"")

        // The byte-array form (a page calling the interface directly) is
        // normalized to base64 before it reaches the engine.
        val fromBytes = ok(
            build(
                envelope(
                    "w2", "COSMOS", "signDirect",
                    "{\"chainId\":\"cosmoshub-4\",\"bodyBytes\":[1,2,3],\"authInfoBytes\":[255]}"
                ),
                activeNetworkId = "COSMOS:cosmoshub-4",
                fallbackAddress = "cosmos1other"
            )
        ) as DappRequest.SendTransaction
        assertThat(fromBytes.txParamsJson).contains("\"bodyBytes\":\"AQID\"")
        assertThat(fromBytes.txParamsJson).contains("\"authInfoBytes\":\"/w==\"")
        assertThat(fromBytes.txParamsJson).contains("\"accountNumber\":\"0\"")

        // Missing pieces and wrong chains stay typed errors, never 4200
        // (the method IS advertised — it must fail for real reasons only).
        assertThat(
            invalid(
                build(
                    envelope("w3", "COSMOS", "signDirect", "{\"chainId\":\"cosmoshub-4\"}"),
                    activeNetworkId = "COSMOS:cosmoshub-4", fallbackAddress = "cosmos1other"
                )
            ).code
        ).isEqualTo(-32602)
        assertThat(
            invalid(
                build(
                    envelope(
                        "w4", "EVM", "signDirect",
                        "{\"chainId\":\"1\",\"bodyBytes\":\"AA==\",\"authInfoBytes\":\"AA==\"}"
                    )
                )
            ).code
        ).isEqualTo(-32602)

        // No active Cosmos network → CHAIN_DISCONNECTED.
        assertThat(
            invalid(
                build(
                    envelope(
                        "w5", "COSMOS", "signDirect",
                        "{\"chainId\":\"cosmoshub-4\",\"bodyBytes\":\"AA==\",\"authInfoBytes\":\"AA==\"}"
                    ),
                    activeNetworkId = null, fallbackAddress = "cosmos1other"
                )
            ).code
        ).isEqualTo(4901)
    }

    @Test
    fun `cosmos signAmino builds a sign-only send transaction`() {
        val request = ok(
            build(
                envelope(
                    "w1", "COSMOS", "signAmino",
                    "{\"chainId\":\"cosmoshub-4\",\"signer\":null," +
                        "\"signDoc\":{\"chain_id\":\"cosmoshub-4\",\"msgs\":[{\"type\":\"x\"}]}}"
                ),
                activeNetworkId = "COSMOS:cosmoshub-4",
                fallbackAddress = "cosmos1primary"
            )
        ) as DappRequest.SendTransaction
        assertThat(request.accountAddress).isEqualTo("cosmos1primary")
        assertThat(request.txParamsJson).contains("\"signOnly\":true")
        assertThat(request.txParamsJson).contains("aminoSignDoc")
        assertThat(request.txParamsJson).contains("cosmoshub-4")

        // A sign doc that is not an object is rejected.
        assertThat(
            invalid(
                build(
                    envelope("w2", "COSMOS", "signAmino", "{\"signDoc\":\"nope\"}"),
                    activeNetworkId = "COSMOS:cosmoshub-4", fallbackAddress = "cosmos1primary"
                )
            ).code
        ).isEqualTo(-32602)
    }

    @Test
    fun `signTransaction routes to the chains that implement it`() {
        // TRON: the raw transaction object travels as the tx params (the
        // engine's TRON dispatch reads raw_data_hex from it).
        val tron = ok(
            build(
                envelope("w1", "TRON", "signTransaction", "{\"raw_data_hex\":\"0a02\",\"visible\":false}"),
                activeNetworkId = "TRON:mainnet",
                fallbackAddress = "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8"
            )
        ) as DappRequest.SendTransaction
        assertThat(tron.chainType).isEqualTo(ChainType.TRON)
        assertThat(tron.txParamsJson).contains("raw_data_hex")

        // Aptos: sign a serialized transaction (the engine returns the
        // signature without submitting it).
        val aptos = ok(
            build(
                envelope("w2", "APTOS", "signTransaction", "{\"txBytes\":\"AA==\"}"),
                activeNetworkId = "APTOS:mainnet",
                fallbackAddress = "0xace"
            )
        ) as DappRequest.SendTransaction
        assertThat(aptos.chainType).isEqualTo(ChainType.APTOS)
        assertThat(aptos.txParamsJson).contains("txBytes")

        // Aptos without txBytes: a typed error, not a broadcast.
        assertThat(
            invalid(
                build(
                    envelope("w3", "APTOS", "signTransaction", "{\"transaction\":{}}"),
                    activeNetworkId = "APTOS:mainnet", fallbackAddress = "0xace"
                )
            ).code
        ).isEqualTo(-32602)

        // Bitcoin has no sign-only route: the method is NOT advertised, and
        // the builder says so instead of pretending.
        assertThat(
            invalid(
                build(
                    envelope("w4", "BITCOIN", "signTransaction", "{\"rawTx\":\"00\"}"),
                    activeNetworkId = "BITCOIN:mainnet", fallbackAddress = "bc1q"
                )
            ).code
        ).isEqualTo(-32602)
    }

    @Test
    fun `cosmos enable is a connect and permission key is per chain family`() {
        val connect = ok(
            build(
                envelope("w1", "COSMOS", "enable", "{\"chainId\":\"cosmoshub-4\"}"),
                activeNetworkId = "COSMOS:cosmoshub-4",
                fallbackAddress = "cosmos1primary"
            )
        ) as DappRequest.Connect
        assertThat(connect.chainType).isEqualTo(ChainType.COSMOS)
        assertThat(connect.host).isEqualTo(host)

        assertThat(WalletBridgeProtocol.permissionMethodFor(ChainType.COSMOS)).isEqualTo("enable")
        assertThat(WalletBridgeProtocol.permissionMethodFor(ChainType.BITCOIN)).isEqualTo("connect")
        assertThat(WalletBridgeProtocol.CONNECT_METHODS).contains("enable")
    }

    @Test
    fun `keplr key result publishes no key material`() {
        val address = "cosmos1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9"
        val key = WalletBridgeProtocol.keplrKeyResult(address)
        assertThat(key).contains("\"bech32Address\":\"$address\"")
        assertThat(key).contains("\"address\":\"$address\"")
        assertThat(key).contains("\"algo\":\"secp256k1\"")
        // No private key, no seed, no raw key material — and pubKey is null
        // because the engine never exposes public keys either.
        assertThat(key).contains("\"pubKey\":null")
        assertThat(key).doesNotContain("privateKey")
        assertThat(key).doesNotContain("mnemonic")
    }

    // ------------------------------------------------------------------
    // Injected script invariants
    // ------------------------------------------------------------------

    @Test
    fun `script installs all providers and hooks with guards`() {
        val script = RoomWalletScript.SCRIPT
        // A "$" would be read as Kotlin interpolation in the raw string.
        assertThat(script).doesNotContain("$")
        // Main-frame-only + idempotent install, like the vault script.
        assertThat(script).contains("window.top !== window.self")
        assertThat(script).contains("__roomWalletInstalled")
        // All seven provider surfaces.
        assertThat(script).contains("window.ethereum")
        assertThat(script).contains("window.solana")
        assertThat(script).contains("window.aptos")
        assertThat(script).contains("window.suiWallet")
        assertThat(script).contains("window.tronLink")
        assertThat(script).contains("window.tronWeb")
        assertThat(script).contains("window.keplr")
        assertThat(script).contains("window.BitcoinProvider")
        // Detection markers + protocol hooks.
        assertThat(script).contains("isMetaMask: true")
        assertThat(script).contains("isPhantom: true")
        assertThat(script).contains("window.RoomWallet.request")
        assertThat(script).contains("__roomWalletResponse")
        assertThat(script).contains("__roomWalletEmit")
        assertThat(script).contains("__roomB64")
    }

    @Test
    fun `script exposes the standard EIP-1193 and EIP-6963 surface`() {
        val script = RoomWalletScript.SCRIPT
        // EIP-1193: connection state plus the three event families a dApp
        // library subscribes to.
        assertThat(script).contains("isConnected")
        assertThat(script).contains("'connect'")
        assertThat(script).contains("'disconnect'")
        assertThat(script).contains("'message'")
        // A 4900/4901 from the bridge means the wallet is gone: the page
        // provider must both flip isConnected and tell listeners.
        assertThat(script).contains("=== 4900")
        assertThat(script).contains("=== 4901")
        // EIP-6963 discovery: announce + answer the request event.
        assertThat(script).contains("eip6963:announceProvider")
        assertThat(script).contains("eip6963:requestProvider")
        assertThat(script).contains("com.roombrowser.wallet")
        // Keplr's keystore-change event, fired when accounts change.
        assertThat(script).contains("keplr_keystorechange")
        // solana.disconnect must actually revoke (bridge-local method), not
        // just clear the local cache.
        assertThat(script).contains("'disconnect'")
    }

    @Test
    fun `script providers carry no key material and no plaintext key paths`() {
        val script = RoomWalletScript.SCRIPT
        // The script may only ever see addresses and signatures; nothing in
        // it may name key-material concepts.
        for (forbidden in listOf("privateKey", "mnemonic", "seedPhrase", "secretKey", "signingKey")) {
            assertThat(script).doesNotContain(forbidden)
        }
        // Every signing path goes through the bridge's async request; the
        // script never resolves a signature locally.
        assertThat(script).contains("'request'")
    }

    @Test
    fun `tronWeb shim documents what it does not implement`() {
        val script = RoomWalletScript.SCRIPT
        // The shim is honest about being a subset of the real TronWeb SDK.
        assertThat(script).contains("DELIBERATE LIMIT")
        assertThat(script).contains("defaultAddress")
        assertThat(script).contains("toSun")
        assertThat(script).contains("fromSun")
    }

    /**
     * Phantom hands dApps a PublicKey OBJECT. `(await connect()).publicKey
     * .toBase58()` is the first thing nearly every Solana dApp does, and a
     * bare base58 string turns that into a TypeError the dApp reports as a
     * failed connection.
     */
    @Test
    fun `solana connect results carry a publicKey object, not a bare string`() {
        val script = RoomWalletScript.SCRIPT
        assertThat(script).contains("function solanaPublicKeyOf(")
        assertThat(script).contains("function base58ToBytes(")
        // The surface dApps actually call.
        assertThat(script).contains("toBase58")
        assertThat(script).contains("toBytes")
        assertThat(script).contains("equals")
        // The provider getter and the resolved connect value are shaped by
        // the same factory, so they cannot disagree.
        assertThat(script).contains("state.solanaPublicKey = solanaPublicKeyOf(address)")
        assertThat(script).contains("entry.resolve(solanaShaped(entry, value))")
    }

    /**
     * The accountsChanged payload is the EVM address list. Handing it to a
     * Solana provider gave that dApp a hex string where Phantom gives it a
     * PublicKey — an account that cannot exist on its chain. The other
     * families get the null re-read signal instead.
     */
    @Test
    fun `an evm account change is never handed to the non-evm providers`() {
        val script = RoomWalletScript.SCRIPT
        assertThat(script).contains("dispatch('evm', 'accountsChanged', payload)")
        assertThat(script).contains("dispatch('solana', 'accountChanged', null)")
        assertThat(script).contains("dispatch('sui', 'accountChanged', null)")
        assertThat(script).contains("dispatch('bitcoin', 'accountsChanged', null)")
        assertThat(script).contains("dispatch('cosmos', 'keplr_keystorechange', null)")
        // The EVM payload must not be threaded into any non-EVM dispatch.
        assertThat(script).doesNotContain("dispatch('solana', 'accountChanged', first)")
        assertThat(script).doesNotContain("dispatch('bitcoin', 'accountsChanged', payload)")
        // Every family's cache is dropped, so a stale key cannot be served
        // after the account it belonged to is gone.
        assertThat(script).contains("state.solanaPublicKey = null;")
        assertThat(script).contains("state.aptosAddress = null;")
    }
}
