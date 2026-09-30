package com.roombrowser.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.roombrowser.domain.model.ProfileId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Injectable seam over the per-profile vault crypto so the credential
 * repository can be unit-tested with a fake instead of the Android Keystore.
 *
 * The key is the profile's [ProfileId.safeSuffix] — stable, filesystem- and
 * keystore-safe (lowercase hex, no dashes) — so implementations stay
 * stateless and swappable.
 */
interface VaultCryptor {
    fun encrypt(profileKey: String, plaintext: String): String

    fun decrypt(profileKey: String, encoded: String): String
}

/** A vault blob could not be produced or read (Keystore failure, corruption). */
class VaultCryptoException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Per-profile password-vault crypto: one AndroidKeyStore AES-256-GCM key per
 * profile, alias "roomvault-&lt;profile.safeSuffix&gt;" — the same pattern as
 * com.roombrowser.agent.KeyStoreCrypto, but scoped per profile so deleting a
 * profile (see ProfileRepositoryImpl) can destroy exactly its key, and a
 * profile's passwords can never be opened by another profile's key material.
 *
 * Unlike KeyStoreCrypto — which degrades to null so the browser keeps working
 * without its agent keys — the vault FAILS LOUDLY: silently dropping a saved
 * password is worse than an error the user can see, so [encrypt] and [decrypt]
 * throw [VaultCryptoException] instead of returning null. Only [deleteKey]
 * swallows failures: it runs inside profile-deletion cascades that must not
 * abort, and a leftover key with no rows behind it is inert (fail-closed —
 * its ciphertext stays permanently undecryptable, the safe direction).
 *
 * Ciphertext format: base64(iv[12] || ciphertext+tag). Room stores ONLY that
 * string (credentials.password_enc); a plaintext password never reaches disk
 * or any persisted form, and this class never logs at all.
 */
object VaultCrypto : VaultCryptor {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "roomvault-"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Full keystore alias of one profile's vault key. */
    fun aliasFor(profileId: ProfileId): String = ALIAS_PREFIX + profileId.safeSuffix

    override fun encrypt(profileKey: String, plaintext: String): String {
        val key = keyFor(ALIAS_PREFIX + profileKey)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw VaultCryptoException("Vault encryption failed", e)
        }
    }

    override fun decrypt(profileKey: String, encoded: String): String {
        val key = keyFor(ALIAS_PREFIX + profileKey)
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            check(all.size > IV_LEN) { "Vault blob too short" }
            val iv = all.copyOfRange(0, IV_LEN)
            val ciphertext = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw VaultCryptoException("Vault blob unreadable", e)
        }
    }

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun encrypt(profileId: ProfileId, plaintext: String): String =
        encrypt(profileId.safeSuffix, plaintext)

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun decrypt(profileId: ProfileId, encoded: String): String =
        decrypt(profileId.safeSuffix, encoded)

    /**
     * Drops the profile's vault key (profile deletion cascade). Never throws
     * and is a no-op when the key is already gone.
     */
    fun deleteKey(profileId: ProfileId) {
        try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            ks.deleteEntry(aliasFor(profileId))
        } catch (_: Throwable) {
            // Key cleanup must never abort a profile deletion; a key without
            // its rows is inert and its (already deleted) ciphertext would be
            // permanently unrecoverable either way.
        }
    }

    /** Loads — or lazily generates on first use — one profile's vault key. */
    @Synchronized
    private fun keyFor(alias: String): SecretKey {
        try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // StrongBox deliberately NOT required: requiring it would
                    // make the vault unavailable on the many devices without
                    // the hardware; the TEE-backed Keystore is the guarantee
                    // the threat model needs (key material never leaves
                    // secure storage).
                    .build()
            )
            return generator.generateKey()
        } catch (e: Throwable) {
            throw VaultCryptoException("Vault key unavailable", e)
        }
    }
}
