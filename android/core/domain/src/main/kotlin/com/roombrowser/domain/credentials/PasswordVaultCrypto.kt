package com.roombrowser.domain.credentials

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The passphrase does not open this vault blob — the GCM tag rejected it
 * (wrong passphrase, or the blob was tampered with).
 */
class VaultAuthException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/**
 * The blob is not a valid pbkdf2-sha256-aes256-gcm payload (bad scheme name,
 * bad base64, wrong IV length, unusable iteration count).
 */
class VaultFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Passphrase-based vault encryption for EXPORT / IMPORT of credentials.
 *
 * Pure JVM on purpose (javax.crypto only, no Android): an export file must be
 * creatable and readable by the same code on any device, and the crypto must
 * stay unit-testable without one — mirroring how core/wallet keeps its keys
 * pure. This is deliberately separate from the per-profile AndroidKeyStore
 * vault (com.roombrowser.security.VaultCrypto): a Keystore key can never
 * leave the device, but an export file must, so exports are sealed under a
 * user-chosen passphrase instead.
 *
 * Scheme "pbkdf2-sha256-aes256-gcm": PBKDF2-HMAC-SHA256 stretches the
 * passphrase into a 256-bit AES key (210 000 iterations — OWASP 2023+ guidance
 * for PBKDF2-HMAC-SHA256 — with a fresh 16-byte salt per blob), then AES-256-GCM
 * seals the UTF-8 plaintext with a fresh 12-byte IV. GCM's authentication tag
 * is what turns a wrong passphrase into a clean [VaultAuthException] instead
 * of silently decrypted garbage.
 */
object PasswordVaultCrypto {

    const val SCHEME = "pbkdf2-sha256-aes256-gcm"

    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * Everything needed to decrypt later except the passphrase. All binary
     * fields are base64 (java.util.Base64). The field set is also the natural
     * JSON shape for an export file, so the UI layer can serialize it as-is.
     */
    data class VaultCipherData(
        val scheme: String = SCHEME,
        val saltB64: String,
        val iterations: Int = ITERATIONS,
        val ivB64: String,
        val ciphertextB64: String
    )

    fun encrypt(plaintext: String, passphrase: CharArray): VaultCipherData {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return VaultCipherData(
            saltB64 = b64.encodeToString(salt),
            ivB64 = b64.encodeToString(iv),
            ciphertextB64 = b64.encodeToString(ciphertext)
        )
    }

    fun decrypt(data: VaultCipherData, passphrase: CharArray): String {
        if (data.scheme != SCHEME) {
            throw VaultFormatException("Unsupported vault scheme: ${data.scheme}")
        }
        if (data.iterations < 1) {
            throw VaultFormatException("Invalid iteration count: ${data.iterations}")
        }
        val salt = decode("salt", data.saltB64)
        if (salt.isEmpty()) {
            throw VaultFormatException("salt must not be empty")
        }
        val iv = decode("iv", data.ivB64)
        if (iv.size != IV_BYTES) {
            throw VaultFormatException("iv must be $IV_BYTES bytes, was ${iv.size}")
        }
        val ciphertext = decode("ciphertext", data.ciphertextB64)
        val key = try {
            deriveKey(passphrase, salt, data.iterations)
        } catch (e: IllegalArgumentException) {
            throw VaultFormatException("Unusable iteration count: ${data.iterations}", e)
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw VaultAuthException("Wrong passphrase or tampered vault data", e)
        }
    }

    /** Best-effort zeroization of in-memory secret material. */
    fun wipe(chars: CharArray) {
        chars.fill('\u0000')
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            // The spec keeps a copy of the password chars; drop it as soon as
            // the key exists so it is not left lying on the heap.
            spec.clearPassword()
        }
    }

    private fun decode(field: String, b64: String): ByteArray =
        try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw VaultFormatException("Malformed base64 in '$field'", e)
        }
}
