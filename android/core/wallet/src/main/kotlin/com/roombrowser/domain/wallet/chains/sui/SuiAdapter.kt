package com.roombrowser.domain.wallet.chains.sui

import com.roombrowser.domain.wallet.bcs.BcsReader
import com.roombrowser.domain.wallet.bcs.BcsWriter
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
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
 * Sui adapter.
 *
 * Keys: SLIP-0010 ed25519 at m/44'/784'/0'/0' (official Sui path).
 * Address = blake2b256(0x00 || pubkey) (verified against sui-types'
 * `impl From<&PublicKey> for SuiAddress`).
 *
 * Every user signature commits to blake2b256(intent || data) where intent =
 * [scope, version=0, app_id=0]; scope is 0 for TransactionData and 3 for a
 * PersonalMessage (BCS: uleb-length-prefixed raw bytes). The wire signature
 * is `flag(0x00) || signature(64) || pubkey(32)` base64-encoded.
 *
 * dApp transactions (sui:signTransaction) are parsed best-effort for the
 * confirmation screen — sender, gas, Move calls, transfers — and anything
 * that fails to parse is shown as raw bytes with a warning instead of being
 * silently trusted.
 */
class SuiAdapter(private val rpc: JsonRpcClient = JsonRpcClient()) {

    fun chainType(): ChainType = ChainType.SUI

    fun deriveAccount(seed: ByteArray, index: Int): DerivedSuiKey {
        val key = Slip10Ed25519Key.derive(seed, "m/44'/784'/0'/0'")
        val pubkey = Ed25519.publicKeyFromSeed(key.seed)
        return DerivedSuiKey(
            seed = key.seed,
            publicKey = pubkey,
            address = "0x" + Hex.encode(Hashes.blake2b256(byteArrayOf(0x00) + pubkey)),
            path = "m/44'/784'/0'/0'"
        )
    }

    fun isValidAddress(address: String): Boolean {
        if (!address.startsWith("0x")) return false
        val bytes = Hex.decodeOrNull(address) ?: return false
        return bytes.size == 32
    }

    // ------------------------------------------------------------------
    // Signing
    // ------------------------------------------------------------------

    /** sui:signPersonalMessage — message is base64; returns base64 signature. */
    fun signPersonalMessage(seed32: ByteArray, publicKey: ByteArray, messageBase64: String): String {
        val message = try {
            java.util.Base64.getDecoder().decode(messageBase64)
        } catch (_: IllegalArgumentException) {
            throw WalletException.InvalidParams("Invalid base64 message")
        }
        val bcs = BcsWriter().writeBytes(message).bytes()
        val intent = byteArrayOf(INTENT_PERSONAL_MESSAGE.toByte(), 0, 0)
        val digest = Hashes.blake2b256(intent + bcs)
        val signature = Ed25519.sign(seed32, digest)
        return java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x00) + signature + publicKey)
    }

    /** sui:signTransaction — signs the dApp's txBytes; returns base64 signature. */
    fun signTransaction(seed32: ByteArray, publicKey: ByteArray, txBytesBase64: String): String {
        val raw = try {
            java.util.Base64.getDecoder().decode(txBytesBase64)
        } catch (_: IllegalArgumentException) {
            throw WalletException.InvalidParams("Invalid base64 transaction")
        }
        val intent = byteArrayOf(INTENT_TRANSACTION_DATA.toByte(), 0, 0)
        val digest = Hashes.blake2b256(intent + raw)
        val signature = Ed25519.sign(seed32, digest)
        return java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x00) + signature + publicKey)
    }

    // ------------------------------------------------------------------
    // Transaction parsing (confirmation screen)
    // ------------------------------------------------------------------

    data class ParsedTransaction(
        val sender: String,
        val gasBudgetMist: Long,
        val gasPriceMist: Long,
        val commandSummaries: List<String>,
        val understood: Boolean
    )

    fun parseTransaction(txBytesBase64: String, ourAddress: String): ParsedTransaction {
        val raw = try {
            java.util.Base64.getDecoder().decode(txBytesBase64)
        } catch (_: IllegalArgumentException) {
            throw WalletException.InvalidParams("Invalid base64 transaction")
        }
        try {
            val reader = BcsReader(raw)
            val dataVariant = reader.readU8()
            if (dataVariant != 0) throw IllegalArgumentException("Unknown TransactionData variant $dataVariant")
            val kindVariant = reader.readU8()
            if (kindVariant != 0) throw IllegalArgumentException("Non-programmable transaction kind $kindVariant")

            // ProgrammableTransaction { inputs: Vec<CallArg>, commands: Vec<Command> }
            val inputCount = reader.readUleb128().toInt()
            val pureInputs = mutableListOf<ByteArray>()
            repeat(inputCount) {
                when (reader.readU8()) {
                    0 -> pureInputs += reader.readBytes()               // CallArg::Pure
                    1 -> when (reader.readU8()) {                        // CallArg::Object
                        0 -> reader.skip(32 + 8 + 32)                    // ImmOrOwnedObject(ObjectRef)
                        1 -> {                                          // SharedObject
                            reader.skip(32 + 8)
                            reader.skip(1)                               // mutability enum byte
                        }
                        2 -> reader.skip(32 + 8 + 32)                    // Receiving(ObjectRef)
                        else -> throw IllegalArgumentException("ObjectArg variant")
                    }
                    else -> throw IllegalArgumentException("CallArg variant")
                }
            }
            val commandCount = reader.readUleb128().toInt()
            val summaries = mutableListOf<String>()
            repeat(commandCount) {
                when (val command = reader.readU8()) {
                    0 -> { // MoveCall(ProgrammableMoveCall)
                        reader.skip(32)                                  // package
                        val module = reader.readString()
                        val function = reader.readString()
                        skipTypeTags(reader)                             // type_arguments
                        skipArguments(reader)                            // arguments
                        summaries += "Move call: $module::$function"
                    }
                    1 -> { // TransferObjects(objects: Vec<Argument>, to: Argument)
                        skipArguments(reader)
                        skipArgument(reader)
                        summaries += "Transfer objects"
                    }
                    2 -> { // SplitCoins(coin, amounts)
                        skipArgument(reader)
                        skipArguments(reader)
                        summaries += "Split coins"
                    }
                    3 -> { // MergeCoins(target, coins)
                        skipArgument(reader)
                        skipArguments(reader)
                        summaries += "Merge coins"
                    }
                    4 -> { // Publish(modules, deps)
                        val modules = reader.readUleb128().toInt()
                        repeat(modules) { reader.readBytes() }
                        val deps = reader.readUleb128().toInt()
                        repeat(deps) { reader.skip(32) }
                        summaries += "Publish package"
                    }
                    5 -> { // MakeMoveVec(Option<TypeTag>, elements)
                        val hasTag = reader.readU8()
                        if (hasTag == 1) skipTypeTag(reader)
                        skipArguments(reader)
                        summaries += "Make vector"
                    }
                    6 -> { // Upgrade(modules, deps, package, ticket)
                        val modules = reader.readUleb128().toInt()
                        repeat(modules) { reader.readBytes() }
                        val deps = reader.readUleb128().toInt()
                        repeat(deps) { reader.skip(32) }
                        reader.skip(32)
                        skipArgument(reader)
                        summaries += "Upgrade package"
                    }
                    else -> throw IllegalArgumentException("Command variant $command")
                }
            }

            val sender = "0x" + Hex.encode(reader.readAddress())
            if (!sender.equals(ourAddress, ignoreCase = true)) {
                throw WalletException.InvalidParams("Transaction sender $sender is not this account")
            }
            // GasData { payment: Vec<ObjectRef>, owner: address, price: u64, budget: u64 }
            val paymentCount = reader.readUleb128().toInt()
            repeat(paymentCount) { reader.skip(32 + 8 + 32) }
            reader.skip(32)
            val price = reader.readU64()
            val budget = reader.readU64()
            // TransactionExpiration (enum; None = 0 with no payload).
            reader.readU8()

            return ParsedTransaction(
                sender = sender,
                gasBudgetMist = budget,
                gasPriceMist = price,
                commandSummaries = summaries,
                understood = true
            )
        } catch (e: WalletException) {
            throw e
        } catch (_: IllegalArgumentException) {
            // Fall back to an "unparsed" view; the confirmation screen then
            // shows the raw transaction with a warning instead of a summary.
            return ParsedTransaction(
                sender = "",
                gasBudgetMist = 0,
                gasPriceMist = 0,
                commandSummaries = emptyList(),
                understood = false
            )
        } catch (_: IllegalStateException) {
            return ParsedTransaction("", 0, 0, emptyList(), understood = false)
        }
    }

    private fun skipTypeTags(reader: BcsReader) {
        val count = reader.readUleb128().toInt()
        repeat(count) { skipTypeTag(reader) }
    }

    private fun skipTypeTag(reader: BcsReader) {
        when (reader.readU8()) {
            0, 1, 2, 3, 4, 5, 8, 9, 10 -> {} // scalar tags
            6 -> skipTypeTag(reader)          // vector<T>
            7 -> {                            // struct { address, module, name, type_params }
                reader.skip(32)
                reader.readString()
                reader.readString()
                skipTypeTags(reader)
            }
            else -> throw IllegalArgumentException("TypeTag variant")
        }
    }

    private fun skipArgument(reader: BcsReader) {
        when (reader.readU8()) {
            0 -> {}                           // GasCoin
            1, 2 -> reader.readUleb128()      // Input(u16) | Result(u16)
            3 -> { reader.readUleb128(); reader.readUleb128() } // NestedResult
            else -> throw IllegalArgumentException("Argument variant")
        }
    }

    private fun skipArguments(reader: BcsReader) {
        val count = reader.readUleb128().toInt()
        repeat(count) { skipArgument(reader) }
    }

    // ------------------------------------------------------------------
    // Broadcast
    // ------------------------------------------------------------------

    suspend fun executeTransactionBlock(
        network: NetworkConfig,
        txBytesBase64: String,
        signatureBase64: String
    ): BroadcastResult {
        val endpoint = network.rpcUrls.firstOrNull()
            ?: return BroadcastResult.Error("Network has no RPC endpoint")
        return try {
            val options = buildJsonObject {
                put("showEffects", JsonPrimitive(true))
                put("showEvents", JsonPrimitive(true))
            }
            val signatures = kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(signatureBase64)) }
            val result = rpc.call(
                endpoint, "sui_executeTransactionBlock",
                listOf(
                    JsonPrimitive(txBytesBase64),
                    signatures,
                    options,
                    JsonPrimitive("WaitForLocalExecution")
                )
            ).jsonObject
            val digest = result["digest"]?.jsonPrimitive?.content
                ?: return BroadcastResult.Error("No digest in response")
            BroadcastResult.Ok(digest)
        } catch (e: WalletException) {
            BroadcastResult.Error(e.message ?: "Broadcast failed")
        }
    }

    // ------------------------------------------------------------------
    // Dashboard send (SUI transfer PTB)
    // ------------------------------------------------------------------

    suspend fun sendSui(
        network: NetworkConfig,
        seed32: ByteArray,
        publicKey: ByteArray,
        fromAddress: String,
        toAddress: String,
        amountMist: Long
    ): BroadcastResult {
        val endpoint = network.rpcUrls.firstOrNull()
            ?: return BroadcastResult.Error("Network has no RPC endpoint")
        val gasCoin = pickGasCoin(endpoint, fromAddress)
            ?: return BroadcastResult.Error("No SUI gas object found")
        val gasPrice = getReferenceGasPrice(endpoint) ?: 1000
        val budget = 10_000_000L // 0.01 SUI — safe default for a simple transfer

        val toBytes = Hex.decode(toAddress.removePrefix("0x"))
        // ProgrammableTransaction:
        //   inputs = [Pure(u64 amount), Pure(recipient address)]
        //   commands = [
        //     SplitCoins(GasCoin, [Input(0)]),
        //     TransferObjects([Result(0)], Input(1))
        //   ]
        val writer = BcsWriter()
            .writeUleb128(2) // 2 inputs
            .writeU8(0).writeBytes(BcsWriter().writeU64(amountMist).bytes()) // Pure(amount)
            .writeU8(0).writeBytes(toBytes)                                  // Pure(recipient)
            .writeUleb128(2) // 2 commands
            // SplitCoins: cmd=2, Argument::GasCoin(0), [Argument::Input(0)]
            .writeU8(2).writeU8(0).writeUleb128(1).writeU8(1).writeUleb128(0)
            // TransferObjects: cmd=1, [Argument::Result(0)], Argument::Input(1)
            .writeU8(1).writeUleb128(1).writeU8(2).writeUleb128(0).writeU8(1).writeUleb128(1)
        val kindBytes = writer.bytes()

        val txData = BcsWriter()
            .writeU8(0)                                   // TransactionData::V1
            .writeU8(0)                                   // kind: ProgrammableTransaction
            .writeRaw(kindBytes)
            .writeRaw(Hex.decode(fromAddress.removePrefix("0x"))) // sender
            .writeUleb128(1)                              // payment: 1 gas object
            .writeRaw(Hex.decode(gasCoin.objectId.removePrefix("0x")))
            .writeU64(gasCoin.version)
            .writeRaw(Hex.decode(gasCoin.digest.removePrefix("0x")))
            .writeRaw(Hex.decode(fromAddress.removePrefix("0x"))) // owner
            .writeU64(gasPrice)
            .writeU64(budget)
            .writeU8(0)                                   // expiration: None
            .bytes()
        val txBytesBase64 = java.util.Base64.getEncoder().encodeToString(txData)
        val signature = signTransaction(seed32, publicKey, txBytesBase64)
        return executeTransactionBlock(network, txBytesBase64, signature)
    }

    data class GasCoinRef(val objectId: String, val version: Long, val digest: String, val balanceMist: Long)

    private suspend fun pickGasCoin(endpoint: String, owner: String): GasCoinRef? = try {
        val filter = buildJsonObject { put("StructType", JsonPrimitive("0x2::coin::Coin<0x2::sui::SUI>")) }
        val options = buildJsonObject { put("showContent", JsonPrimitive(true)) }
        val result = rpc.call(
            endpoint, "suix_getOwnedObjects",
            listOf(JsonPrimitive(owner), filter, options)
        ).jsonObject
        val coins = result["data"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["data"]?.jsonObject?.get("objectId")?.jsonPrimitive?.content ?: return@mapNotNull null
            val version = obj["data"]?.jsonObject?.get("version")?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val digest = obj["data"]?.jsonObject?.get("digest")?.jsonPrimitive?.content ?: return@mapNotNull null
            val balance = obj["data"]?.jsonObject?.get("content")?.jsonObject
                ?.get("fields")?.jsonObject?.get("balance")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            GasCoinRef(id, version, digest, balance)
        } ?: emptyList()
        coins.maxByOrNull { it.balanceMist }
    } catch (_: WalletException) {
        null
    }

    suspend fun getReferenceGasPrice(endpoint: String): Long? = try {
        rpc.call(endpoint, "suix_getReferenceGasPrice").jsonPrimitive.content.toLongOrNull()
    } catch (_: WalletException) {
        null
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    suspend fun getBalance(network: NetworkConfig, address: String): Long? {
        val endpoint = network.rpcUrls.firstOrNull() ?: return null
        return try {
            val result = rpc.callObject(endpoint, "suix_getBalance", listOf(JsonPrimitive(address)))
            result["totalBalance"]?.jsonPrimitive?.content?.toLongOrNull()
        } catch (_: WalletException) {
            null
        }
    }

    data class DerivedSuiKey(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val address: String,
        val path: String
    )

    companion object {
        const val INTENT_TRANSACTION_DATA = 0x00
        const val INTENT_PERSONAL_MESSAGE = 0x03

        val MAINNET = NetworkConfig(
            id = "SUI:mainnet",
            chainType = ChainType.SUI,
            chainId = "mainnet",
            name = "Sui Mainnet",
            rpcUrls = listOf("https://fullnode.mainnet.sui.io"),
            nativeSymbol = "SUI",
            nativeDecimals = 9,
            explorerUrl = "https://suiscan.xyz",
            isTestnet = false
        )
        val TESTNET = NetworkConfig(
            id = "SUI:testnet",
            chainType = ChainType.SUI,
            chainId = "testnet",
            name = "Sui Testnet",
            rpcUrls = listOf("https://fullnode.testnet.sui.io"),
            nativeSymbol = "SUI",
            nativeDecimals = 9,
            explorerUrl = "https://suiscan.xyz",
            isTestnet = true
        )
        val DEVNET = NetworkConfig(
            id = "SUI:devnet",
            chainType = ChainType.SUI,
            chainId = "devnet",
            name = "Sui Devnet",
            rpcUrls = listOf("https://fullnode.devnet.sui.io"),
            nativeSymbol = "SUI",
            nativeDecimals = 9,
            explorerUrl = "https://suiscan.xyz",
            isTestnet = true
        )

        fun defaultNetworks(): List<NetworkConfig> = listOf(MAINNET, TESTNET, DEVNET)
    }
}
