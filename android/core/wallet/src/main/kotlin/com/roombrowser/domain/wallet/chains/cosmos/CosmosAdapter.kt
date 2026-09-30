package com.roombrowser.domain.wallet.chains.cosmos

import com.roombrowser.domain.wallet.crypto.Bech32
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Signing
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import com.roombrowser.domain.wallet.wire.ProtoReader
import com.roombrowser.domain.wallet.wire.ProtoWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger

/**
 * Cosmos SDK chain adapter (Keplr-compatible signing).
 *
 * Keys: BIP44 at m/44'/{coinType}'/0'/0/i (coinType 118 by default,
 * Injective uses 60). Address = bech32(hrp, ripemd160(sha256(compressed
 * pubkey))).
 *
 * Signing supports both SIGN_MODE_LEGACY_AMINO_JSON (canonical sorted-key
 * JSON → sha256) and SIGN_MODE_DIRECT (SignDoc protobuf → sha256), plus
 * Keplr's signArbitrary (a "sign/MsgSignData" amino doc). Broadcast goes
 * through the chain's LCD /cosmos/tx/v1beta1/txs endpoint.
 */
class CosmosAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) {

    fun chainType(): ChainType = ChainType.COSMOS

    fun deriveAccount(seed: ByteArray, network: NetworkConfig, index: Int): DerivedCosmosKey {
        val coinType = network.coinType ?: 118
        val path = "m/44'/$coinType'/0'/0/$index"
        val key = Bip32PrivateKey.derive(seed, path)
        val hrp = network.bech32Hrp ?: "cosmos"
        val address = bech32Address(key.compressedPublicKey, hrp)
        return DerivedCosmosKey(
            privateKey = key.key,
            compressedPublicKey = key.compressedPublicKey,
            address = address,
            hrp = hrp,
            path = path
        )
    }

    fun bech32Address(compressedPublicKey: ByteArray, hrp: String): String {
        val hash160 = Hashes.ripemd160(Hashes.sha256(compressedPublicKey))
        // Cosmos bech32 data is the raw address bytes — no witness version.
        return Bech32.encode(hrp, Bech32.convertBits(hash160))
    }

    fun isValidAddress(address: String, hrp: String?): Boolean {
        val decoded = Bech32.decode(address) ?: return false
        if (hrp != null && decoded.first != hrp) return false
        val raw = Bech32.from5bit(decoded.second) ?: return false
        return raw.size == 20
    }

    // ------------------------------------------------------------------
    // SIGN_MODE_DIRECT
    // ------------------------------------------------------------------

    data class DirectSignDoc(
        val bodyBytes: ByteArray,
        val authInfoBytes: ByteArray,
        val chainId: String,
        val accountNumber: String
    )

    /** Serializes the SignDoc protobuf and signs its sha256 digest. */
    fun signDirect(privateKey: BigInteger, doc: DirectSignDoc): CosmosSignature {
        val signDoc = ProtoWriter()
            .writeBytes(1, doc.bodyBytes)
            .writeBytes(2, doc.authInfoBytes)
            .writeString(3, doc.chainId)
            .writeVarint(4, doc.accountNumber.toLongOrNull() ?: 0L)
            .bytes()
        val digest = Hashes.sha256(signDoc)
        val sig = Signing.secp256k1SignDigest64(privateKey, digest)
        return CosmosSignature(
            signatureBase64 = java.util.Base64.getEncoder().encodeToString(sig),
            signDocBytes = signDoc
        )
    }

    // ------------------------------------------------------------------
    // SIGN_MODE_LEGACY_AMINO_JSON + signArbitrary
    // ------------------------------------------------------------------

    /** Canonical amino JSON: keys sorted, no whitespace. */
    fun canonicalAminoJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { key ->
            "\"" + escapeJson(key) + "\":" + canonicalAminoJson(element[key]!!)
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalAminoJson(it) }
        is JsonPrimitive -> when {
            element.isString -> "\"" + escapeJson(element.content) + "\""
            else -> element.content
        }
        else -> throw WalletException.InvalidParams("Unsupported amino JSON element")
    }

    private fun escapeJson(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Signs an amino StdSignDoc (given as a JsonElement). */
    fun signAmino(privateKey: BigInteger, signDoc: JsonElement): CosmosSignature {
        val canonical = canonicalAminoJson(signDoc)
        val digest = Hashes.sha256(canonical.toByteArray(Charsets.UTF_8))
        val sig = Signing.secp256k1SignDigest64(privateKey, digest)
        return CosmosSignature(
            signatureBase64 = java.util.Base64.getEncoder().encodeToString(sig),
            signDocBytes = canonical.toByteArray(Charsets.UTF_8)
        )
    }

    /** Keplr signArbitrary — signs data wrapped in a sign/MsgSignData doc. */
    fun signArbitrary(privateKey: BigInteger, chainId: String, signer: String, data: String): CosmosSignature {
        val dataBase64 = if (isBase64(data)) data
        else java.util.Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))
        val doc = buildJsonObject {
            put("chain_id", JsonPrimitive(""))
            put("account_number", JsonPrimitive("0"))
            put("sequence", JsonPrimitive("0"))
            put("memo", JsonPrimitive(""))
            put("fee", buildJsonObject {
                put("gas", JsonPrimitive("0"))
                put("amount", JsonArray(emptyList()))
            })
            put("msgs", JsonArray(listOf(buildJsonObject {
                put("type", JsonPrimitive("sign/MsgSignData"))
                put("value", buildJsonObject {
                    put("signer", JsonPrimitive(signer))
                    put("data", JsonPrimitive(dataBase64))
                })
            })))
        }
        return signAmino(privateKey, doc)
    }

    private fun isBase64(text: String): Boolean = try {
        java.util.Base64.getDecoder().decode(text)
        text.length % 4 == 0 && text.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
    } catch (_: IllegalArgumentException) {
        false
    }

    // ------------------------------------------------------------------
    // TxBody parsing (confirmation screen)
    // ------------------------------------------------------------------

    data class ParsedMessage(val typeUrl: String, val summary: String, val understood: Boolean)

    /** Best-effort TxBody parse for display; unknown messages show raw fields. */
    fun parseBody(bodyBytes: ByteArray): List<ParsedMessage> = try {
        val body = ProtoReader(bodyBytes).all()
        body.filter { it.number == 1 && it.wireType == 2 }.map { field ->
            val any = field.asReader().all()
            val typeUrl = any.firstOrNull { it.number == 1 }?.let { String(it.bytes, Charsets.UTF_8) } ?: ""
            val value = any.firstOrNull { it.number == 2 }?.bytes ?: ByteArray(0)
            ParsedMessage(typeUrl, summarizeMessage(typeUrl, value), understood = true)
        }
    } catch (_: IllegalArgumentException) {
        listOf(ParsedMessage("", "Unparseable transaction", understood = false))
    }

    private fun summarizeMessage(typeUrl: String, value: ByteArray): String {
        if (typeUrl == "/cosmos.bank.v1beta1.MsgSend") {
            val fields = ProtoReader(value).all()
            val from = fields.firstOrNull { it.number == 1 }?.let { String(it.bytes, Charsets.UTF_8) } ?: "?"
            val to = fields.firstOrNull { it.number == 2 }?.let { String(it.bytes, Charsets.UTF_8) } ?: "?"
            val amounts = fields.filter { it.number == 3 }.mapNotNull { coin ->
                val cf = coin.asReader().all()
                val denom = cf.firstOrNull { it.number == 1 }?.let { String(it.bytes, Charsets.UTF_8) } ?: "?"
                val amount = cf.firstOrNull { it.number == 2 }?.let { String(it.bytes, Charsets.UTF_8) } ?: "?"
                "$amount $denom"
            }
            return "Send ${amounts.joinToString(", ")} from $from to $to"
        }
        return typeUrl.substringAfterLast('.').replace("Msg", "Msg ")
    }

    // ------------------------------------------------------------------
    // Dashboard send (bank MsgSend, SIGN_MODE_DIRECT)
    // ------------------------------------------------------------------

    suspend fun sendNative(
        network: NetworkConfig,
        privateKey: BigInteger,
        compressedPublicKey: ByteArray,
        fromAddress: String,
        toAddress: String,
        amount: String,
        denom: String
    ): BroadcastResult {
        val lcd = network.lcdUrl?.trimEnd('/')
            ?: return BroadcastResult.Error("Network has no LCD endpoint")
        val account = try {
            rpc.getJson("$lcd/cosmos/auth/v1beta1/accounts/$fromAddress").jsonObject
        } catch (e: WalletException) {
            return BroadcastResult.Error("Account query failed: ${e.message}")
        }
        val accountInfo = account["account"]?.jsonObject
        val sequence = accountInfo?.get("sequence")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val accountNumber = accountInfo?.get("account_number")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        val coin = ProtoWriter().writeString(1, denom).writeString(2, amount).bytes()
        val msgSend = ProtoWriter()
            .writeString(1, fromAddress)
            .writeString(2, toAddress)
            .writeBytes(3, coin)
            .bytes()
        val anyMsg = ProtoWriter.any("/cosmos.bank.v1beta1.MsgSend", msgSend)
        val body = ProtoWriter().writeBytes(1, anyMsg).writeString(2, "").bytes() // memo = ""

        val pubKeyProto = ProtoWriter().writeBytes(1, compressedPublicKey).bytes()
        val pubKeyAny = ProtoWriter.any("/cosmos.crypto.secp256k1.PubKey", pubKeyProto)
        val single = ProtoWriter().writeVarint(1, 1L).bytes() // SignMode.DIRECT = 1
        val modeInfo = ProtoWriter().writeBytes(1, single).bytes()
        val signerInfo = ProtoWriter()
            .writeBytes(1, pubKeyAny)
            .writeBytes(2, modeInfo)
            .writeVarint(3, sequence)
            .bytes()
        val feeCoin = ProtoWriter().writeString(1, denom).writeString(2, "2500").bytes()
        val gasLimit = 200_000L
        val fee = ProtoWriter().writeBytes(1, feeCoin).writeVarint(3, gasLimit).bytes()
        val authInfo = ProtoWriter().writeBytes(1, signerInfo).writeBytes(2, fee).bytes()

        val signature = signDirect(
            privateKey,
            DirectSignDoc(
                bodyBytes = body,
                authInfoBytes = authInfo,
                chainId = network.chainId,
                accountNumber = accountNumber.toString()
            )
        )
        val txRaw = ProtoWriter()
            .writeBytes(1, body)
            .writeBytes(2, authInfo)
            .writeBytes(3, java.util.Base64.getDecoder().decode(signature.signatureBase64))
            .bytes()
        return broadcastTx(lcd, txRaw)
    }

    suspend fun broadcastTx(lcd: String, txRaw: ByteArray): BroadcastResult = try {
        val request = buildJsonObject {
            put("tx_bytes", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(txRaw)))
            put("mode", JsonPrimitive("BROADCAST_MODE_SYNC"))
        }
        val response = rpc.postJson("$lcd/cosmos/tx/v1beta1/txs", request).jsonObject
        val txResponse = response["tx_response"]?.jsonObject
        val hash = txResponse?.get("txhash")?.jsonPrimitive?.content
            ?: return BroadcastResult.Error("Broadcast returned no txhash")
        val code = txResponse["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (code != 0) {
            val rawLog = txResponse["raw_log"]?.jsonPrimitive?.content ?: "code $code"
            BroadcastResult.Error(rawLog)
        } else {
            BroadcastResult.Ok(hash)
        }
    } catch (e: WalletException) {
        BroadcastResult.Error(e.message ?: "Broadcast failed")
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getBalance(network: NetworkConfig, address: String): String? {
        val lcd = network.lcdUrl?.trimEnd('/') ?: return null
        return try {
            val balances = rpc.getJson("$lcd/cosmos/bank/v1beta1/balances/$address").jsonObject
            val first = balances["balances"]?.jsonArray?.firstOrNull()?.jsonObject ?: return "0"
            val amount = first["amount"]?.jsonPrimitive?.content ?: "0"
            val denom = first["denom"]?.jsonPrimitive?.content ?: ""
            "$amount $denom"
        } catch (_: WalletException) {
            null
        }
    }

    data class CosmosSignature(
        val signatureBase64: String,
        val signDocBytes: ByteArray
    )

    data class DerivedCosmosKey(
        val privateKey: BigInteger,
        val compressedPublicKey: ByteArray,
        val address: String,
        val hrp: String,
        val path: String
    )

    companion object {
        const val PUBKEY_SECP256K1 = "tendermint/PubKeySecp256k1"

        fun defaultNetworks(): List<NetworkConfig> = listOf(
            NetworkConfig(
                id = "COSMOS:cosmoshub-4",
                chainType = ChainType.COSMOS,
                chainId = "cosmoshub-4",
                name = "Cosmos Hub",
                rpcUrls = listOf("https://rpc.cosmos.network"),
                lcdUrl = "https://api.cosmos.network",
                nativeSymbol = "ATOM",
                nativeDecimals = 6,
                explorerUrl = "https://www.mintscan.io/cosmos",
                bech32Hrp = "cosmos",
                coinType = 118
            ),
            NetworkConfig(
                id = "COSMOS:osmosis-1",
                chainType = ChainType.COSMOS,
                chainId = "osmosis-1",
                name = "Osmosis",
                rpcUrls = listOf("https://rpc.osmosis.zone"),
                lcdUrl = "https://lcd.osmosis.zone",
                nativeSymbol = "OSMO",
                nativeDecimals = 6,
                explorerUrl = "https://www.mintscan.io/osmosis",
                bech32Hrp = "osmo",
                coinType = 118
            ),
            NetworkConfig(
                id = "COSMOS:celestia",
                chainType = ChainType.COSMOS,
                chainId = "celestia",
                name = "Celestia",
                rpcUrls = listOf("https://rpc.celestia.pops"),
                lcdUrl = "https://api.celestia.pops",
                nativeSymbol = "TIA",
                nativeDecimals = 6,
                explorerUrl = "https://www.mintscan.io/celestia",
                bech32Hrp = "celestia",
                coinType = 118
            ),
            NetworkConfig(
                id = "COSMOS:injective-1",
                chainType = ChainType.COSMOS,
                chainId = "injective-1",
                name = "Injective",
                rpcUrls = listOf("https://injective-rpc.polkachu.com"),
                lcdUrl = "https://injective-api.polkachu.com",
                nativeSymbol = "INJ",
                nativeDecimals = 18,
                explorerUrl = "https://explorer.injective.network",
                bech32Hrp = "inj",
                coinType = 60
            )
        )
    }
}
