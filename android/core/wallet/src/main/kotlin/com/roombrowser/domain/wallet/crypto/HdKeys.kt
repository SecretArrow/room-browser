package com.roombrowser.domain.wallet.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger

/**
 * BIP32 hierarchical deterministic keys over secp256k1 (EVM, Cosmos, Bitcoin,
 * TRON). Implements the exact derivation scheme from BIP32 (verified against
 * the BIP32 and SLIP-0010 secp256k1 test vectors in Bip32Test).
 *
 * Public keys are always handled in compressed 33-byte form; the uncompressed
 * form is derived on demand (EVM/TRON address hashing needs the 64-byte form).
 */
class Bip32PrivateKey(
    /** The key material: a scalar in [1, n-1]. */
    val key: BigInteger,
    val chainCode: ByteArray,
    val depth: Int,
    /** Big-endian first 4 bytes of the parent's compressed public key hash. */
    val parentFingerprint: Int
) {
    val compressedPublicKey: ByteArray by lazy {
        publicKeyPoint(key).getEncoded(true)
    }

    /** Uncompressed public key WITHOUT the 0x04 prefix (64 bytes: x || y). */
    val uncompressedPublicKey64: ByteArray by lazy {
        val p = publicKeyPoint(key)
        p.affineXCoord.encoded + p.affineYCoord.encoded
    }

    fun child(index: Long): Bip32PrivateKey {
        require(index in 0..0xFFFFFFFFL) { "Index out of range" }
        val data = if (index >= HARDENED_BIT) {
            // Hardened: 0x00 || ser256(parent key) || ser32(index) — the 0x00
            // pads the private key to 33 bytes (BIP32 CKDpriv).
            byteArrayOf(0x00) + ser256(key) + ser32(index)
        } else {
            // Normal: serP(parent public key) || ser32(index)
            compressedPublicKey + ser32(index)
        }
        val i = Hashes.hmacSha512(chainCode, data)
        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)
        val ilInt = BigInteger(1, il)
        require(ilInt.signum() > 0 && ilInt < CURVE_N) { "Derived IL out of range (BIP32)" }
        val childKey = ilInt.add(key).mod(CURVE_N)
        require(childKey.signum() > 0) { "Derived child key is zero (BIP32)" }
        return Bip32PrivateKey(childKey, ir, depth + 1, fingerprint)
    }

    val fingerprint: Int
        get() = Hashes.ripemd160(Hashes.sha256(compressedPublicKey)).copyOfRange(0, 4).let {
            ((it[0].toInt() and 0xff) shl 24) or ((it[1].toInt() and 0xff) shl 16) or
                ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff)
        }

    companion object {
        private val X9 = CustomNamedCurves.getByName("secp256k1")
        val CURVE: ECDomainParameters = ECDomainParameters(X9.curve, X9.g, X9.n, X9.h)
        val CURVE_N: BigInteger = X9.n
        const val HARDENED_BIT = 0x80000000L

        /**
         * The secp256k1 public-key point for [key]:
         *  - a private-key scalar (≤ 256 bits) → G · key, or
         *  - a public key in web3j's x||y BigInteger encoding (≥ 257 bits:
         *    x in the high 256 bits, y in the low 256 bits) → the decoded
         *    point. org.web3j.crypto.Sign recovers public keys in exactly
         *    that encoding, so signature round-trips through web3j land on
         *    this branch.
         */
        fun publicKeyPoint(key: BigInteger): org.bouncycastle.math.ec.ECPoint =
            if (key.signum() > 0 && key.bitLength() > 256) {
                val x = key.shiftRight(256)
                val y = key.and(BigInteger.TWO.pow(256).subtract(BigInteger.ONE))
                val p = X9.curve.field.characteristic
                require(x.signum() > 0 && x < p && y < p) { "x||y public key out of field range" }
                require(
                    y.multiply(y).subtract(x.multiply(x).multiply(x)).subtract(BigInteger.valueOf(7))
                        .mod(p).signum() == 0
                ) { "x||y public key is not a point on secp256k1" }
                X9.curve.createPoint(x, y).normalize()
            } else {
                X9.g.multiply(key).normalize()
            }

        fun master(seed: ByteArray): Bip32PrivateKey {
            val i = Hashes.hmacSha512("Bitcoin seed".toByteArray(Charsets.US_ASCII), seed)
            val il = BigInteger(1, i.copyOfRange(0, 32))
            require(il.signum() > 0 && il < CURVE_N) { "Master key out of range" }
            return Bip32PrivateKey(il, i.copyOfRange(32, 64), 0, 0)
        }

        /** Parses "m/44'/60'/0'/0/2" (leading "m" or "m/" optional). */
        fun parsePath(path: String): List<Long> {
            require(path.isNotEmpty()) { "Empty derivation path" }
            val body = when {
                path == "m" || path == "M" -> return emptyList()
                path.startsWith("m/") || path.startsWith("M/") -> path.substring(2)
                else -> path
            }
            if (body.isEmpty()) return emptyList()
            return body.split('/').map { segment ->
                val hardened = segment.endsWith("'") || segment.endsWith("h") || segment.endsWith("H")
                val number = segment.trimEnd('\'', 'h', 'H')
                val idx = number.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid path segment '$segment'")
                require(idx in 0..HARDENED_BIT) { "Path index out of range" }
                if (hardened) idx or HARDENED_BIT else idx
            }
        }

        fun derive(seed: ByteArray, path: String): Bip32PrivateKey {
            var key = master(seed)
            for (index in parsePath(path)) key = key.child(index)
            return key
        }

        fun ser32(n: Long): ByteArray = byteArrayOf(
            (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
        )

        fun ser256(n: BigInteger): ByteArray {
            val raw = n.toByteArray() // may include a leading zero or be short
            val out = ByteArray(32)
            when {
                raw.size == 32 -> System.arraycopy(raw, 0, out, 0, 32)
                raw.size > 32 -> System.arraycopy(raw, raw.size - 32, out, 0, 32)
                else -> System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
            }
            return out
        }
    }
}

/**
 * SLIP-0010 hierarchical deterministic keys over ed25519 (Solana, Aptos, Sui).
 *
 * ed25519 only supports hardened derivation; the master key comes from
 * HMAC-SHA512("ed25519 seed", seed) and every child from
 * HMAC-SHA512(chainCode, 0x00 || ser256(parentKey) || ser32(index | 0x80000000)).
 * Verified against the official SLIP-0010 ed25519 test vectors in Slip10Test.
 */
class Slip10Ed25519Key(
    /** 32-byte ed25519 private seed. */
    val seed: ByteArray,
    val chainCode: ByteArray,
    val depth: Int
) {
    init {
        require(seed.size == 32) { "ed25519 seed must be 32 bytes" }
    }

    val publicKey: ByteArray by lazy { Ed25519.publicKeyFromSeed(seed) }

    fun child(index: Long): Slip10Ed25519Key {
        require(index in 0..0xFFFFFFFFL) { "Index out of range" }
        val hardened = index or Bip32PrivateKey.HARDENED_BIT
        val data = byteArrayOf(0x00) + seed + Bip32PrivateKey.ser32(hardened)
        val i = Hashes.hmacSha512(chainCode, data)
        return Slip10Ed25519Key(i.copyOfRange(0, 32), i.copyOfRange(32, 64), depth + 1)
    }

    companion object {
        fun master(seed: ByteArray): Slip10Ed25519Key {
            val i = Hashes.hmacSha512("ed25519 seed".toByteArray(Charsets.US_ASCII), seed)
            return Slip10Ed25519Key(i.copyOfRange(0, 32), i.copyOfRange(32, 64), 0)
        }

        fun derive(seed: ByteArray, path: String): Slip10Ed25519Key {
            var key = master(seed)
            for (index in Bip32PrivateKey.parsePath(path)) {
                // ed25519 derivation is hardened-only; a plain index means the
                // same hardened index (matching how wallets treat m/44'/...').
                key = key.child(index)
            }
            return key
        }
    }
}
