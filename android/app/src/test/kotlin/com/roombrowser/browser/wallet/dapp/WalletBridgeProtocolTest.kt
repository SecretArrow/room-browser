package com.roombrowser.browser.wallet.dapp

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.domain.wallet.model.ChainType
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
        // All five provider surfaces.
        assertThat(script).contains("window.ethereum")
        assertThat(script).contains("window.solana")
        assertThat(script).contains("window.aptos")
        assertThat(script).contains("window.suiWallet")
        assertThat(script).contains("window.tronLink")
        assertThat(script).contains("window.tronWeb")
        // Detection markers + protocol hooks.
        assertThat(script).contains("isMetaMask: true")
        assertThat(script).contains("isPhantom: true")
        assertThat(script).contains("window.RoomWallet.request")
        assertThat(script).contains("__roomWalletResponse")
        assertThat(script).contains("__roomWalletEmit")
        assertThat(script).contains("__roomB64")
    }
}
