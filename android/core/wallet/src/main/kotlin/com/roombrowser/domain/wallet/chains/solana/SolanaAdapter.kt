package com.roombrowser.domain.wallet.chains.solana

import com.roombrowser.domain.wallet.bcs.BcsReader
import com.roombrowser.domain.wallet.bcs.BcsWriter
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Slip10Ed25519Key
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import com.roombrowser.domain.wallet.rpc.JsonRpcClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Solana adapter.
 *
 * Keys: SLIP-0010 ed25519 at m/44'/501'/i'/0' (the Phantom/Backpack standard).
 * Addresses are base58-encoded ed25519 public keys; signatures sign the raw
 * message bytes (Solana has no domain separator for personal messages —
 * Phantom signs exactly the bytes it is given).
 *
 * Transactions arrive from dApps as base64-serialized wire transactions; the
 * signature commits to the message bytes following the signature section.
 */
class SolanaAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) {

    fun chainType(): ChainType = ChainType.SOLANA

    fun deriveAccount(seed: ByteArray, index: Int): DerivedSolanaKey {
        val key = Slip10Ed25519Key.derive(seed, "m/44'/501'/$index'/0'")
        val pubkey = Ed25519.publicKeyFromSeed(key.seed)
        return DerivedSolanaKey(
            seed = key.seed,
            publicKey = pubkey,
            address = Base58.encode(pubkey),
            path = "m/44'/501'/$index'/0'"
        )
    }

    fun isValidAddress(address: String): Boolean {
        val bytes = Base58.decodeOrNull(address) ?: return false
        return bytes.size == 32
    }

    // ------------------------------------------------------------------
    // Message signing (window.solana.signMessage)
    // ------------------------------------------------------------------

    fun signMessage(seed32: ByteArray, message: ByteArray): String =
        Base58.encode(Ed25519.sign(seed32, message))

    // ------------------------------------------------------------------
    // Transaction signing (signTransaction / signAndSendTransaction)
    // ------------------------------------------------------------------

    data class ParsedTransaction(
        val messageBytes: ByteArray,
        val staticSigners: List<String>,
        val requiredSignatureCount: Int
    )

    /** Parses a base64 wire transaction; validates signer membership. */
    fun parseTransaction(base64Tx: String): ParsedTransaction {
        val raw = try {
            java.util.Base64.getDecoder().decode(base64Tx)
        } catch (_: IllegalArgumentException) {
            throw WalletException.InvalidParams("Invalid base64 transaction")
        }
        val reader = BcsReader(raw)
        val signatureCount = reader.readUleb128().toInt()
        if (signatureCount < 1 || signatureCount > 64) {
            throw WalletException.InvalidParams("Unreasonable signature count $signatureCount")
        }
        var offset = 0
        // Skip signature section (each signature is exactly 64 bytes).
        var i = 0
        while (i < signatureCount) {
            reader.skip(64)
            i++
        }
        offset = raw.size - reader.remaining()
        val messageBytes = raw.copyOfRange(offset, raw.size)
        val message = BcsReader(messageBytes)
        // Versioned messages: first byte >= 0x80 → versioned (v0 = 0x80).
        if ((messageBytes[0].toInt() and 0xff) >= 0x80) message.skip(1)
        val numRequiredSignatures = message.readU8()
        val numReadonlySigned = message.readU8()
        val numReadonlyUnsigned = message.readU8()
        val keyCount = message.readUleb128().toInt()
        val signers = mutableListOf<String>()
        for (k in 0 until keyCount) {
            val keyBytes = message.readFixed(32)
            if (k < numRequiredSignatures) signers += Base58.encode(keyBytes)
        }
        return ParsedTransaction(messageBytes, signers, numRequiredSignatures)
    }

    /**
     * Signs a dApp-provided transaction. Returns the base64 transaction with
     * our signature placed at the signer's slot (Phantom semantics: the
     * signature array position must match the signer's account-key index
     * among required signers).
     */
    fun signTransaction(seed32: ByteArray, ourAddress: String, base64Tx: String): String {
        val raw = java.util.Base64.getDecoder().decode(base64Tx)
        val parsed = parseTransaction(base64Tx)
        val signerIndex = parsed.staticSigners.indexOf(ourAddress)
        if (signerIndex < 0) {
            throw WalletException.InvalidParams("Transaction does not list this account as a signer")
        }
        val signature = Ed25519.sign(seed32, parsed.messageBytes)
        // Signature count <= 64 encodes as a single uleb byte.
        val firstByte = raw[0].toInt() and 0xff
        require(firstByte and 0x80 == 0) { "Signature count varint too long" }
        val out = raw.copyOf()
        val slotStart = 1 + 64 * signerIndex
        if (slotStart + 64 > out.size) {
            throw WalletException.InvalidParams("Signature slot out of bounds")
        }
        System.arraycopy(signature, 0, out, slotStart, 64)
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    // ------------------------------------------------------------------
    // Send (dashboard)
    // ------------------------------------------------------------------

    suspend fun sendNative(
        network: NetworkConfig,
        seed32: ByteArray,
        fromAddress: String,
        toAddress: String,
        lamports: Long
    ): BroadcastResult {
        val endpoint = network.rpcUrls.firstOrNull()
            ?: return BroadcastResult.Error("Network has no RPC endpoint")
        val blockhash = getLatestBlockhash(endpoint)
            ?: return BroadcastResult.Error("Could not fetch a recent blockhash")
        val fromKey = Base58.decode(fromAddress)
        val toKey = Base58.decode(toAddress)
        val instruction = BcsWriter()
            .writeU8(2) // SystemInstruction::Transfer
            .writeU64(lamports)
            .bytes()
        // Message: header (1 required sig, 0 readonly signed, 1 readonly
        // unsigned), keys [from, to], blockhash, 1 instruction.
        val message = BcsWriter()
            .writeU8(1).writeU8(0).writeU8(1)
            .writeUleb128(2).writeRaw(fromKey).writeRaw(toKey)
            .writeRaw(blockhash)
            .writeUleb128(1)
            .writeU8(2) // program id index = 2 (system program)
            .writeUleb128(2).writeU8(0).writeU8(1) // account indices: from, to
            .writeBytes(instruction)
            .bytes()
        val signature = Ed25519.sign(seed32, message)
        val tx = BcsWriter()
            .writeUleb128(1).writeRaw(signature)
            .writeRaw(message)
            .bytes()
        return broadcast(endpoint, java.util.Base64.getEncoder().encodeToString(tx))
    }

    suspend fun broadcast(endpoint: String, base64Tx: String): BroadcastResult = try {
        val config = buildJsonObject { put("encoding", JsonPrimitive("base64")) }
        val result = rpc.call(
            endpoint, "sendTransaction",
            listOf(JsonPrimitive(base64Tx), config)
        )
        BroadcastResult.Ok(result.jsonPrimitive.content)
    } catch (e: WalletException) {
        BroadcastResult.Error(e.message ?: "Broadcast failed")
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getBalance(network: NetworkConfig, address: String): Long? {
        val endpoint = network.rpcUrls.firstOrNull() ?: return null
        val config = buildJsonObject { put("commitment", JsonPrimitive("confirmed")) }
        val result = rpc.callObject(
            endpoint, "getBalance",
            listOf(JsonPrimitive(address), config)
        )
        return result["value"]?.jsonPrimitive?.content?.toLongOrNull()
    }

    suspend fun getLatestBlockhash(endpoint: String): ByteArray? = try {
        val config = buildJsonObject { put("commitment", JsonPrimitive("confirmed")) }
        val result = rpc.callObject(endpoint, "getLatestBlockhash", listOf(config))
        val blockhash = result["value"]?.jsonObject?.get("blockhash")?.jsonPrimitive?.content ?: return null
        Base58.decode(blockhash)
    } catch (_: WalletException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** SPL token holdings (uiAmount + mint) for the dashboard token list. */
    suspend fun getSplTokens(network: NetworkConfig, owner: String): List<SplToken> {
        val endpoint = network.rpcUrls.firstOrNull() ?: return emptyList()
        return try {
            val filter = buildJsonObject { put("programId", JsonPrimitive(TOKEN_PROGRAM)) }
            val config = buildJsonObject { put("encoding", JsonPrimitive("jsonParsed")) }
            val result = rpc.call(
                endpoint, "getTokenAccountsByOwner",
                listOf(JsonPrimitive(owner), filter, config)
            )
            result.jsonArray.mapNotNull { element ->
                val parsed = element.jsonObject["account"]?.jsonObject?.get("data")
                    ?.jsonObject?.get("parsed")?.jsonObject ?: return@mapNotNull null
                val info = parsed["info"]?.jsonObject ?: return@mapNotNull null
                val amount = info["tokenAmount"]?.jsonObject ?: return@mapNotNull null
                val uiAmount = amount["uiAmountString"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SplToken(mint = info["mint"]?.jsonPrimitive?.content ?: "?", uiAmount = uiAmount)
            }
        } catch (_: WalletException) {
            emptyList()
        } catch (_: kotlinx.serialization.SerializationException) {
            emptyList()
        }
    }

    suspend fun getSignatureStatus(endpoint: String, signature: String): String? = try {
        val signatures = kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(signature)) }
        val result = rpc.call(endpoint, "getSignatureStatuses", listOf(signatures))
        result.jsonArray.firstOrNull()?.jsonObject?.get("confirmationStatus")?.jsonPrimitive?.content
    } catch (_: WalletException) {
        null
    }

    data class SplToken(val mint: String, val uiAmount: String)

    data class DerivedSolanaKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val address: String,
        val path: String
    )

    companion object {
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

        val MAINNET = NetworkConfig(
            id = "SOLANA:mainnet-beta",
            chainType = ChainType.SOLANA,
            chainId = "mainnet-beta",
            name = "Solana Mainnet",
            rpcUrls = listOf("https://api.mainnet-beta.solana.com"),
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            explorerUrl = "https://solscan.io",
            isTestnet = false
        )
        val DEVNET = NetworkConfig(
            id = "SOLANA:devnet",
            chainType = ChainType.SOLANA,
            chainId = "devnet",
            name = "Solana Devnet",
            rpcUrls = listOf("https://api.devnet.solana.com"),
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            explorerUrl = "https://solscan.io",
            isTestnet = true
        )
        val TESTNET = NetworkConfig(
            id = "SOLANA:testnet",
            chainType = ChainType.SOLANA,
            chainId = "testnet",
            name = "Solana Testnet",
            rpcUrls = listOf("https://api.testnet.solana.com"),
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            explorerUrl = "https://solscan.io",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(MAINNET, DEVNET, TESTNET)
    }
}
