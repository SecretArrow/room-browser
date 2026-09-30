package com.roombrowser.domain.wallet.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.crypto.Sign.SignatureData
import java.math.BigInteger

/**
 * Signing primitives.
 *
 * secp256k1 goes through web3j (battle-tested ECDSA with RFC6979 nonces and
 * recovery-id computation). Bitcoin and several Cosmos chains additionally
 * require the canonical low-S form, which is normalized here (flipping S
 * flips the recovery-id parity, so v is adjusted in the same step).
 *
 * ed25519 uses BouncyCastle's lightweight signer (Solana / Aptos / Sui).
 */
object Signing {

    private val HALF_N: BigInteger = Bip32PrivateKey.CURVE_N.shiftRight(1)

    // ------------------------------------------------------------------
    // ed25519 (Solana, Aptos, Sui)
    // ------------------------------------------------------------------

    fun ed25519PublicKey(seed32: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed32, 0).generatePublicKey().encoded

    fun ed25519Sign(seed32: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed32, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey32: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey32, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: IllegalArgumentException) {
            false
        }

    // ------------------------------------------------------------------
    // secp256k1 (EVM, Cosmos, TRON, Bitcoin)
    // ------------------------------------------------------------------

    /**
     * Signs a 32-byte digest with ECDSA (RFC6979 nonce). The returned v is the
     * web3j recovery id + 27; S is normalized to low-S form.
     */
    fun secp256k1SignDigest(privateKey: BigInteger, digest: ByteArray): SignatureData {
        require(digest.size == 32) { "Digest must be 32 bytes" }
        val keyPair = ECKeyPair(privateKey, publicKeyFromPrivate(privateKey))
        val raw = Sign.signMessage(digest, keyPair, false)
        return lowS(raw)
    }

    /** Normalizes S to <= n/2 and flips the recovery parity when needed. */
    fun lowS(sig: SignatureData): SignatureData {
        val s = BigInteger(1, sig.s)
        return if (s.compareTo(HALF_N) <= 0) {
            sig
        } else {
            val header = sig.v[0].toInt()
            val flipped = (header xor 1).toByte()
            SignatureData(flipped, sig.r, Bip32PrivateKey.ser256(Bip32PrivateKey.CURVE_N.subtract(s)))
        }
    }

    /** Plain 64-byte r||s signature (used by Cosmos + TRON). */
    fun secp256k1SignDigest64(privateKey: BigInteger, digest: ByteArray): ByteArray {
        val sig = secp256k1SignDigest(privateKey, digest)
        return sig.r + sig.s
    }

    /**
     * Bitcoin "signed message" (Electrum/GPU format): the recoverable
     * 65-byte signature is header(27+4+recId) || r || s over the
     * double-SHA256 of magic || varint(len) || message.
     */
    fun bitcoinSignMessage(privateKey: BigInteger, message: ByteArray): ByteArray {
        val digest = bitcoinMessageDigest(message)
        val sig = secp256k1SignDigest(privateKey, digest)
        val recId = (sig.v[0].toInt() - 27) and 3
        val header = 27 + 4 + recId
        return byteArrayOf(header.toByte()) + sig.r + sig.s
    }

    fun bitcoinMessageDigest(message: ByteArray): ByteArray {
        // Both the magic prefix and the message are length-prefixed
        // (varstr): 0x18 || "Bitcoin Signed Message:\n" || varint(len) || msg.
        val magic = "Bitcoin Signed Message:\n".toByteArray(Charsets.US_ASCII)
        return Hashes.sha256d(varInt(magic.size.toLong()) + magic + varInt(message.size.toLong()) + message)
    }

    private fun varInt(value: Long): ByteArray = when {
        value < 0xfd -> byteArrayOf(value.toByte())
        value <= 0xffff -> byteArrayOf(0xfd.toByte(), (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())
        else -> byteArrayOf(
            0xfe.toByte(), (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(), ((value shr 24) and 0xff).toByte()
        )
    }

    /**
     * Verifies a Bitcoin signed message against the expected compressed
     * public key (used by tests to prove recovery round-trips).
     */
    fun bitcoinVerifyMessage(publicKeyCompressed: ByteArray, message: ByteArray, signature65: ByteArray): Boolean {
        if (signature65.size != 65) return false
        val header = signature65[0].toInt() and 0xff
        if (header < 27 || header > 34) return false
        val digest = bitcoinMessageDigest(message)
        // Bitcoin's compact format sets bit 2 (+4) on the header for
        // compressed keys; web3j's recovery wants the bare recId + 27.
        val recId = (header - 27) and 3
        val sig = SignatureData(
            (27 + recId).toByte(),
            signature65.copyOfRange(1, 33),
            signature65.copyOfRange(33, 65)
        )
        return try {
            // signedMessageHashToKey returns the recovered public key in
            // web3j's x||y BigInteger encoding; publicKeyPoint decodes it.
            val recovered = Sign.signedMessageHashToKey(digest, sig)
            Bip32PrivateKey.publicKeyPoint(recovered).getEncoded(true)
                .contentEquals(publicKeyCompressed)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun publicKeyFromPrivate(privateKey: BigInteger): BigInteger =
        Sign.publicKeyFromPrivate(privateKey)
}

/** Ed25519 helpers re-exported under a friendlier name for adapters. */
object Ed25519 {
    fun publicKeyFromSeed(seed32: ByteArray): ByteArray = Signing.ed25519PublicKey(seed32)
    fun sign(seed32: ByteArray, message: ByteArray): ByteArray = Signing.ed25519Sign(seed32, message)
    fun verify(publicKey32: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Signing.ed25519Verify(publicKey32, message, signature)
}
