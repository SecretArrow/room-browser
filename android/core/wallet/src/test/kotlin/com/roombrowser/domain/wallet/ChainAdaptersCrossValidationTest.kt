package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.chains.aptos.AptosAdapter
import com.roombrowser.domain.wallet.chains.bitcoin.BitcoinAdapter
import com.roombrowser.domain.wallet.chains.cosmos.CosmosAdapter
import com.roombrowser.domain.wallet.chains.evm.EvmAdapter
import com.roombrowser.domain.wallet.chains.solana.SolanaAdapter
import com.roombrowser.domain.wallet.chains.sui.SuiAdapter
import com.roombrowser.domain.wallet.chains.tron.TronAdapter
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Ed25519
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Mnemonics
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.math.BigInteger

/**
 * Cross-validation of every chain adapter against reference values produced
 * by the OFFICIAL SDK of each ecosystem (ethers, @solana/web3.js, aptos-ts-sdk,
 * @mysten/sui, tronweb, bitcoinjs, cosmjs). If one of these tests fails, the
 * wallet is NOT compatible with that ecosystem's existing dApps — the failing
 * behaviour must be fixed before shipping.
 */
class ChainAdaptersCrossValidationTest {

    companion object {
        const val SEED = "c5338cd251c22daa8c9c9cc94f498cc8a5c7e1d2e75287a5dda91096fe64efa5"
        const val ABANDON_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }

    private val evm = EvmAdapter()
    private val solana = SolanaAdapter()
    private val aptos = AptosAdapter()
    private val sui = SuiAdapter()
    private val tron = TronAdapter()
    private val bitcoin = BitcoinAdapter()
    private val cosmos = CosmosAdapter()

    // ==================================================================
    // EVM
    // ==================================================================

    @Test
    fun `evm address matches ethers`() {
        val address = evm.addressFromPrivateKey(BigInteger(SEED, 16))
        assertThat(address).isEqualTo("0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26")
    }

    @Test
    fun `evm personal_sign matches ethers`() {
        val sig = evm.personalSign(BigInteger(SEED, 16), "hello world".toByteArray())
        assertThat(sig).isEqualTo(
            "0xafd01485a8983b1307b5fb5cb05e9e3bcb5c66653c9389af2db667714fba75b8" +
                "3450d760fc980dcf93b9f6ad785e6210f6f4629752c2075c1da1b1a8339e6cf11b"
        )
    }

    /** The canonical EIP-712 Mail example from the EIP itself. */
    private val typedDataJson = """
        {
          "types": {
            "EIP712Domain": [
              {"name": "name", "type": "string"},
              {"name": "version", "type": "string"},
              {"name": "chainId", "type": "uint256"},
              {"name": "verifyingContract", "type": "address"}
            ],
            "Person": [
              {"name": "name", "type": "string"},
              {"name": "wallet", "type": "address"}
            ],
            "Mail": [
              {"name": "from", "type": "Person"},
              {"name": "to", "type": "Person"},
              {"name": "contents", "type": "string"}
            ]
          },
          "primaryType": "Mail",
          "domain": {
            "name": "Ether Mail",
            "version": "1",
            "chainId": 1,
            "verifyingContract": "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"
          },
          "message": {
            "from": {"name": "Cow", "wallet": "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},
            "to": {"name": "Bob", "wallet": "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},
            "contents": "Hello, Bob!"
          }
        }
    """.trimIndent()

    @Test
    fun `evm signTypedData_v4 matches ethers`() {
        val sig = evm.signTypedData(BigInteger(SEED, 16), typedDataJson)
        assertThat(sig).isEqualTo(
            "0x0bf2f375eb7f118e8c52cd872c1646b55b42b2fa1316f0202df5c04ab939f3cf" +
                "1d1cb93e8bfccb2a9427cbbec9e7394567d3e7f637c4746fca56a9f159a09f221b"
        )
    }

    @Test
    fun `evm derivation from mnemonic matches the standard BIP44 vector`() {
        val seed = Mnemonics.toSeed(ABANDON_MNEMONIC)
        val account = evm.deriveAccount(seed, 0)
        assertThat(account.address).isEqualTo("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
        assertThat(account.path).isEqualTo("m/44'/60'/0'/0/0")
    }

    @Test
    fun `evm address validation accepts checksummed and lowercase`() {
        assertThat(evm.isValidAddress("0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26")).isTrue()
        assertThat(evm.isValidAddress("0x417aa4b5a8bf239d05c03c7c0c0231ecf7620c26")).isTrue()
        assertThat(evm.isValidAddress("0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c27")).isFalse() // bad checksum
        assertThat(evm.isValidAddress("notanaddress")).isFalse()
    }

    // ==================================================================
    // Solana
    // ==================================================================

    @Test
    fun `solana address and signature match web3_js`() {
        val seed32 = Hex.decode(SEED)
        val pubkey = Ed25519.publicKeyFromSeed(seed32)
        val address = com.roombrowser.domain.wallet.crypto.Base58.encode(pubkey)
        assertThat(address).isEqualTo("FwzQxHPj38ZiS6RPyFeGhFL9RahBPAA2ZZNWS6sab475")
        // Signature bytes are the RFC8032 ed25519 signature (verified
        // against the official Aptos SDK vector); Solana returns them base58.
        val sig = solana.signMessage(seed32, "hello world".toByteArray())
        assertThat(sig).isEqualTo(
            "4AgE4r29rZ7FUXbzKF9TauTBHKWJ3FkcXgaWBuZtXKbrTxnwZfaBSw1wfBBJ3bD6pCPoYQDSzpGWASFnAQpLpyzN"
        )
        assertThat(solana.isValidAddress(address)).isTrue()
    }

    // ==================================================================
    // Aptos
    // ==================================================================

    @Test
    fun `aptos address matches the official ts-sdk vector`() {
        val seed32 = Hex.decode(SEED)
        val pubkey = Ed25519.publicKeyFromSeed(seed32)
        assertThat(Hex.encode0x(pubkey))
            .isEqualTo("0xde19e5d1880cac87d57484ce9ed2e84cf0f9599f12e7cc3a52e4e7657a763f2c")
        val adapter = AptosAdapter()
        // Deriving at m/54'/6'/0'/0'/0 from a *seed* — construct directly:
        val address = "0x" + com.roombrowser.domain.wallet.crypto.Hashes.sha3_256(
            pubkey + byteArrayOf(0x00)
        ).let { Hex.encode(it) }
        assertThat(address).isEqualTo("0x978c213990c4833df71548df7ce49d54c759d6b6d932de22b24d56060b7af2aa")
        assertThat(adapter.isValidAddress(address)).isTrue()
    }

    @Test
    fun `aptos ed25519 signature matches the official ts-sdk vector`() {
        val seed32 = Hex.decode(SEED)
        val sig = Ed25519.sign(seed32, "hello world".toByteArray())
        assertThat("0x" + Hex.encode(sig)).isEqualTo(
            "0x9e653d56a09247570bb174a389e85b9226abd5c403ea6c504b386626a145158c" +
                "d4efd66fc5e071c0e19538a96a05ddbda24d3c51e1e6a9dacc6bb1ce775cce07"
        )
    }

    @Test
    fun `aptos mnemonic derivation matches the official ts-sdk path`() {
        val mnemonic = "shoot island position soft burden budget tooth cruel issue economy destroy above"
        val seed = Mnemonics.toSeed(mnemonic)
        // m/44'/637'/0'/0'/44' — official path, official private seed.
        val key = com.roombrowser.domain.wallet.crypto.Slip10Ed25519Key.derive(seed, "m/44'/637'/0'/0'/44'")
        assertThat(Hex.encode(key.seed)).isEqualTo(
            "eb70332d79a384f57052b34282748be65a57548513b3b99ee1bd858244b36d28"
        )
    }

    @Test
    fun `aptos wallet sign message uses the APTOS full-message format`() {
        val result = AptosAdapter().signWalletMessage(
            Hex.decode(SEED), "hello world", "my-nonce",
            includeAddress = false, ourAddress = null,
            includeApplication = false, application = null,
            includeChainId = false, chainId = null
        )
        assertThat(result.fullMessage).isEqualTo("APTOS\nmessage: hello world\nnonce: my-nonce")
        assertThat(result.prefix).isEqualTo("APTOS")
        // The signature must verify against the ed25519 public key.
        assertThat(
            Ed25519.verify(
                Ed25519.publicKeyFromSeed(Hex.decode(SEED)),
                result.fullMessage.toByteArray(), Hex.decode(result.signatureHex.removePrefix("0x"))
            )
        ).isTrue()
    }

    // ==================================================================
    // Sui
    // ==================================================================

    @Test
    fun `sui address matches mysten sdk`() {
        val seed32 = Hex.decode(SEED)
        val pubkey = Ed25519.publicKeyFromSeed(seed32)
        val address = "0x" + com.roombrowser.domain.wallet.crypto.Hashes.blake2b256(
            byteArrayOf(0x00) + pubkey
        ).let { Hex.encode(it) }
        assertThat(address).isEqualTo("0x21ba6e3bcecaa6c683027e4b4fbd8d4de71f139e7ad7e89e43cb7b792602d63d")
        assertThat(sui.isValidAddress(address)).isTrue()
    }

    @Test
    fun `sui signPersonalMessage matches mysten sdk`() {
        val seed32 = Hex.decode(SEED)
        val pubkey = Ed25519.publicKeyFromSeed(seed32)
        val messageB64 = java.util.Base64.getEncoder().encodeToString("hello world".toByteArray())
        val sig = sui.signPersonalMessage(seed32, pubkey, messageB64)
        assertThat(sig).isEqualTo(
            "AA+EtCqa0/iysm2m0LZtZiMqRygxEcB5p7YCg5HM1uucdm5E575KZC43v/9STcRGP45GKHsXOykr68RK8ieg2Q3eGeXRiAysh9V0hM6e0uhM8PlZnxLnzDpS5OdlenY/LA=="
        )
    }

    @Test
    fun `sui signTransaction matches mysten sdk`() {
        val seed32 = Hex.decode(SEED)
        val pubkey = Ed25519.publicKeyFromSeed(seed32)
        val sig = sui.signTransaction(seed32, pubkey, "AAAN3L6+/L667g==")
        assertThat(sig).isEqualTo(
            "AC4+xvYxamdwuz0846VpWUa/l0vwt7xY//ArTKr0QAMG28208rGaHf7BAZG0MH1wpK1h25UXTjweKXKI1MGL9A3eGeXRiAysh9V0hM6e0uhM8PlZnxLnzDpS5OdlenY/LA=="
        )
    }

    // ==================================================================
    // TRON
    // ==================================================================

    @Test
    fun `tron address and signMessageV2 match tronweb`() {
        val priv = BigInteger(SEED, 16)
        val address = tron.addressFromPrivateKey(priv)
        assertThat(address).isEqualTo("TFwRqwWNov6cPN531NnhAKnLHPa6V1JtVu")
        assertThat(tron.isValidAddress(address)).isTrue()
        val sig = tron.signMessageV2(priv, "hello world".toByteArray())
        assertThat(sig).isEqualTo(
            "0xffbda635290d2bb871fab8400c92011b923f907476bd37471f5a21962a6ae86b" +
                "638f766709234fa175ca3369cc350781521b8720b77bec1935c6c112e911f3e51c"
        )
        assertThat(tron.addressToHex(address)).startsWith("41")
        assertThat(tron.hexToAddress(tron.addressToHex(address))).isEqualTo(address)
    }

    // ==================================================================
    // Bitcoin
    // ==================================================================

    @Test
    fun `bitcoin message digest and signature match bitcoinjs`() {
        val priv = BigInteger(SEED, 16)
        assertThat("0x" + com.roombrowser.domain.wallet.crypto.Hex.encode(
            com.roombrowser.domain.wallet.crypto.Signing.bitcoinMessageDigest("hello world".toByteArray())
        )).isEqualTo("0x0b6b6ce07bc55ee4aeba0098a5e5d2c8986cab228a54199723f9962316633733")
        val sig = bitcoin.signMessage(priv, "hello world".toByteArray())
        assertThat(sig).isEqualTo(
            "ICtDzelooYy9zN+FDgeQ8UrZ8gOWw/dtsDPEHzRmOoR9UYGTylMHPs1XvRtW9anpD6qaFATD3FAG5pWtjzV/0hg="
        )
        // Recovery round-trip against the compressed public key.
        val pubkey = Bip32PrivateKey.publicKeyPoint(priv).getEncoded(true)
        assertThat(
            com.roombrowser.domain.wallet.crypto.Signing.bitcoinVerifyMessage(
                pubkey, "hello world".toByteArray(),
                java.util.Base64.getDecoder().decode(sig)
            )
        ).isTrue()
    }

    @Test
    fun `bitcoin p2wpkh address matches bitcoinjs`() {
        val pubkey = Hex.decode("03aec70d57487f8753690c53a15d734c8568945bf9effe821f90aa0210bc5b8c01")
        assertThat(bitcoin.p2wpkhAddress(pubkey, testnet = false))
            .isEqualTo("bc1q6st6ar40sznz36w33vyl0eq700jvyg4estcfx8")
        assertThat(bitcoin.isValidAddress("bc1q6st6ar40sznz36w33vyl0eq700jvyg4estcfx8", testnet = false)).isTrue()
    }

    /** Official BIP143 native-P2WPKH test vector (sigHash check). */
    @Test
    fun `bitcoin bip143 sighash matches the official vector`() {
        val adapter = BitcoinAdapter()
        val utxo = BitcoinAdapter.Utxo(
            txid = "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a",
            vout = 1,
            valueSats = 600000000,
            confirmed = true
        )
        val pubkey = Hex.decode("025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357")
        val pubkeyHash = com.roombrowser.domain.wallet.crypto.Hashes.ripemd160(
            com.roombrowser.domain.wallet.crypto.Hashes.sha256(pubkey)
        )
        assertThat(Hex.encode(pubkeyHash)).isEqualTo("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1")

        // Official values from BIP143 (two-input tx; input #2 is the P2WPKH).
        val sighash = adapter.bip143Sighash(
            pubkeyHash = pubkeyHash,
            utxo = utxo,
            hashPrevouts = Hex.decode("96b827c8483d4e9b96712b6713a7b68d6e8003a781feba36c31143470b4efd37"),
            hashSequence = Hex.decode("52b0a642eea2fb7ae638c36f6252b6750293dbe574a806984b8e4d8548339a3b"),
            hashOutputs = Hex.decode("863ef3e1a92afbfdb97f31ad0fc7683ee943e9abcf2501590ff8f6551f47e5e5"),
            locktime = 0x11,
            version = 1 // the vector transaction is a version-1 transaction
        )
        assertThat(Hex.encode(sighash))
            .isEqualTo("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670")
    }

    /** Self-consistent sign→rebuild-sighash→recover roundtrip of the tx builder. */
    @Test
    fun `bitcoin transaction builder produces a verifiable witness`() {
        val adapter = BitcoinAdapter()
        val priv = BigInteger(SEED, 16)
        val pubkey = Bip32PrivateKey.publicKeyPoint(priv).getEncoded(true)
        val pubkeyHash = com.roombrowser.domain.wallet.crypto.Hashes.ripemd160(
            com.roombrowser.domain.wallet.crypto.Hashes.sha256(pubkey)
        )
        val toAddress = adapter.p2wpkhAddress(pubkey, testnet = true)
        val utxo = BitcoinAdapter.Utxo(
            txid = "1111111111111111111111111111111111111111111111111111111111111111",
            vout = 0, valueSats = 50000, confirmed = true
        )
        val outputs = listOf(BitcoinAdapter.TxOutput(toAddress, 40000))
        val raw = adapter.signP2wpkhTransaction(priv, pubkeyHash, listOf(utxo), outputs)

        // Segwit serialization: nVersion | 00 01 | vin | vout | witness | nLocktime
        assertThat(raw[4]).isEqualTo(0x00)
        assertThat(raw[5]).isEqualTo(0x01)

        // Walk to the witness section.
        var offset = 6
        val inputCount = raw[offset].toInt() and 0xff
        offset += 1
        repeat(inputCount) {
            offset += 32 + 4 // outpoint
            val scriptLen = raw[offset].toInt() and 0xff
            offset += 1 + scriptLen + 4
        }
        val outputCount = raw[offset].toInt() and 0xff
        offset += 1
        repeat(outputCount) {
            offset += 8
            val scriptLen = raw[offset].toInt() and 0xff
            offset += 1 + scriptLen
        }
        val witnessItems = raw[offset].toInt() and 0xff
        assertThat(witnessItems).isEqualTo(2)
        offset += 1
        val sigLen = raw[offset].toInt() and 0xff
        offset += 1
        val sigDer = raw.copyOfRange(offset, offset + sigLen)
        offset += sigLen
        val pkLen = raw[offset].toInt() and 0xff
        assertThat(pkLen).isEqualTo(33)
        val witnessPubkey = raw.copyOfRange(offset + 1, offset + 1 + pkLen)
        assertThat(witnessPubkey).isEqualTo(pubkey)

        // Recompute the BIP143 sighash the same way the builder did.
        val hashes = com.roombrowser.domain.wallet.crypto.Hashes
        val hashPrevouts = hashes.sha256d(Hex.decode(utxo.txid) + adapter.le32(utxo.vout))
        val hashSequence = hashes.sha256d(adapter.le32(0xFFFFFFFFL))
        val program = com.roombrowser.domain.wallet.crypto.Hashes.ripemd160(hashes.sha256(pubkey))
        val outputBytes = adapter.le64(40000L) + adapter.compactSize(22) + byteArrayOf(0x00, 0x14) + program
        val hashOutputs = hashes.sha256d(outputBytes)
        val sighash = adapter.bip143Sighash(pubkeyHash, utxo, hashPrevouts, hashSequence, hashOutputs, 0)

        // Recover the signing key from the witness signature (try all 4 headers).
        val der = sigDer.copyOfRange(0, sigDer.size - 1) // strip SIGHASH_ALL
        val (r, s) = parseDer(der)
        val rB = derPadded(r)
        val sB = derPadded(s)
        var recovered: BigInteger? = null
        for (header in 27..30) {
            val candidate = runCatching {
                org.web3j.crypto.Sign.signedMessageHashToKey(
                    sighash, org.web3j.crypto.Sign.SignatureData(header.toByte(), rB, sB)
                )
            }.getOrNull() ?: continue
            if (Bip32PrivateKey.publicKeyPoint(candidate).getEncoded(true).contentEquals(pubkey)) {
                recovered = candidate
                break
            }
        }
        // web3j's signedMessageHashToKey returns the recovered PUBLIC key in
        // its x||y BigInteger encoding (recovering the private key from a
        // signature is cryptographically impossible), so the round-trip proof
        // compares the recovered key with OUR public key.
        assertThat(recovered).isEqualTo(org.web3j.crypto.Sign.publicKeyFromPrivate(priv))
    }

    private fun parseDer(der: ByteArray): Pair<BigInteger, BigInteger> {
        // DER: 30 LL 02 LR <r> 02 LS <s>
        var offset = 3 // skip 30 LL 02
        val rLen = der[offset].toInt() and 0xff
        offset += 1
        val r = BigInteger(1, der.copyOfRange(offset, offset + rLen))
        offset += rLen + 1 // skip r bytes + the 02 tag
        val sLen = der[offset].toInt() and 0xff
        offset += 1
        val s = BigInteger(1, der.copyOfRange(offset, offset + sLen))
        return r to s
    }

    private fun derPadded(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == 32) return raw
        val out = ByteArray(32)
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    // ==================================================================
    // Cosmos
    // ==================================================================

    private val cosmosHub = NetworkConfig(
        id = "COSMOS:cosmoshub-4", chainType = ChainType.COSMOS, chainId = "cosmoshub-4",
        name = "Cosmos Hub", rpcUrls = listOf("https://rpc.cosmos.network"),
        lcdUrl = "https://api.cosmos.network", nativeSymbol = "ATOM",
        bech32Hrp = "cosmos", coinType = 118
    )

    @Test
    fun `cosmos address and pubkey match cosmjs`() {
        val seed = Mnemonics.toSeed(ABANDON_MNEMONIC)
        val account = cosmos.deriveAccount(seed, cosmosHub, 0)
        assertThat(account.address).isEqualTo("cosmos19rl4cm2hmr8afy4kldpxz3fka4jguq0auqdal4")
        assertThat(Hex.encode(account.compressedPublicKey))
            .isEqualTo("024f4e2ad99c34d60b9ba6283c9431a8418af8673212961f97a77b6377fcd05b62")
        assertThat(cosmos.isValidAddress(account.address, "cosmos")).isTrue()
        assertThat(cosmos.isValidAddress(account.address, "osmo")).isFalse()
    }

    @Test
    fun `cosmos signDirect matches cosmjs`() {
        val seed = Mnemonics.toSeed(ABANDON_MNEMONIC)
        val account = cosmos.deriveAccount(seed, cosmosHub, 0)
        val signature = cosmos.signDirect(
            account.privateKey,
            CosmosAdapter.DirectSignDoc(
                bodyBytes = java.util.Base64.getDecoder().decode(
                    "Co0BChwvY29zbW9zLmJhbmsudjFiZXRhMS5Nc2dTZW5kEm0KLWNvc21vczE5cmw0Y20yaG1yOGFmeTRrbGRweHozZmthNGpndXEwYXVxZGFsNBItY29zbW9zMXN5YXZ5Mm5wdnN3Mmt5YXl1YXVldWM4ZzljNHZtM2pyMGd2aHhqGg0KBXVhdG9tEgQxMDAwEgl0ZXN0IG1lbW8="
                ),
                authInfoBytes = java.util.Base64.getDecoder().decode(
                    "CgoKABIECgIIARgFEhMKDQoFdWF0b20SBDI1MDAQwJoM"
                ),
                chainId = "cosmoshub-4",
                accountNumber = "17"
            )
        )
        assertThat(signature.signatureBase64).isEqualTo(
            "tVNb+4Mc/NVhDF5GgnY1pSM+KS8UbpuKccKG1hUrnQZTKHSsVQf/Wws7R22g5kHXp0O7OwdtMjOPlzRgv0dphQ=="
        )
    }

    @Test
    fun `cosmos signAmino matches cosmjs`() {
        val seed = Mnemonics.toSeed(ABANDON_MNEMONIC)
        val account = cosmos.deriveAccount(seed, cosmosHub, 0)
        val docJson = """
            {
              "chain_id": "cosmoshub-4",
              "account_number": "17",
              "sequence": "5",
              "fee": {"gas": "200000", "amount": [{"denom": "uatom", "amount": "2500"}]},
              "memo": "test memo",
              "msgs": [{
                "type": "cosmos-sdk/MsgSend",
                "value": {
                  "from_address": "${account.address}",
                  "to_address": "cosmos1syavy2npvsw2kyayuaueuc8g9c4vm3jr0gvhxj",
                  "amount": [{"denom": "uatom", "amount": "1000"}]
                }
              }]
            }
        """.trimIndent()
        val doc = Json.parseToJsonElement(docJson)
        val signature = cosmos.signAmino(account.privateKey, doc)
        assertThat(signature.signatureBase64).isEqualTo(
            "XaaxHGDPE0Bj8cOJBEAF1TR1KyYA0oA8x8nYHLUwFuscg9ue91ydX9A1JQAADyxbOi+3Pm3YSAoadlR6fQynXg=="
        )
    }

    @Test
    fun `cosmos amino canonical json sorts keys`() {
        val doc = Json.parseToJsonElement("""{"z":1,"a":{"y":"2","b":false}}""")
        val canonical = cosmos.canonicalAminoJson(doc)
        assertThat(canonical).isEqualTo("""{"a":{"b":false,"y":"2"},"z":1}""")
    }

    @Test
    fun `cosmos body parsing summarizes MsgSend`() {
        val bodyBytes = java.util.Base64.getDecoder().decode(
            "Co0BChwvY29zbW9zLmJhbmsudjFiZXRhMS5Nc2dTZW5kEm0KLWNvc21vczE5cmw0Y20yaG1yOGFmeTRrbGRweHozZmthNGpndXEwYXVxZGFsNBItY29zbW9zMXN5YXZ5Mm5wdnN3Mmt5YXl1YXVldWM4ZzljNHZtM2pyMGd2aHhqGg0KBXVhdG9tEgQxMDAwEgl0ZXN0IG1lbW8="
        )
        val messages = cosmos.parseBody(bodyBytes)
        assertThat(messages).hasSize(1)
        assertThat(messages[0].typeUrl).isEqualTo("/cosmos.bank.v1beta1.MsgSend")
        assertThat(messages[0].understood).isTrue()
        assertThat(messages[0].summary).contains("1000 uatom")
    }
}
