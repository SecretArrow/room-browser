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
 * Per-profile wallet key crypto: one AndroidKeyStore AES-256-GCM key per
 * profile, alias "roomwallet-&lt;profile.safeSuffix&gt;" — the same pattern as
 * [VaultCrypto], but a SEPARATE key from the password vault's so the two
 * subsystems never share key material: rotating or losing one vault can
 * never expose or break the other, and each profile-deletion cascade
 * destroys exactly its own wallet key.
 *
 * It seals the wallet's seed mnemonic and imported accounts' private keys
 * (the only wallet secrets that persist — derived account keys are
 * re-derived from the encrypted mnemonic on use and never stored).
 *
 * Like the password vault, the wallet crypto FAILS LOUDLY: silently
 * dropping a mnemonic or an imported key is worse than an error the user
 * can see, so [encrypt] and [decrypt] throw [VaultCryptoException] instead
 * of returning null. Only [deleteKey] swallows failures: it runs inside
 * profile-deletion cascades that must not abort, and a leftover key with
 * no rows behind it is inert (fail-closed — its ciphertext stays
 * permanently undecryptable, the safe direction).
 *
 * Ciphertext format: base64(iv[12] || ciphertext+tag). Room stores ONLY
 * that string (wallets.mnemonic_enc / wallet_accounts.private_key_enc); a
 * plaintext mnemonic or private key never reaches disk or any persisted
 * form, and this class never logs at all.
 */
object WalletKeyCrypto : VaultCryptor {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "roomwallet-"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Full keystore alias of one profile's wallet key. */
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
            throw VaultCryptoException("Wallet encryption failed", e)
        }
    }

    override fun decrypt(profileKey: String, encoded: String): String {
        val key = keyFor(ALIAS_PREFIX + profileKey)
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            check(all.size > IV_LEN) { "Wallet blob too short" }
            val iv = all.copyOfRange(0, IV_LEN)
            val ciphertext = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw VaultCryptoException("Wallet blob unreadable", e)
        }
    }

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun encrypt(profileId: ProfileId, plaintext: String): String =
        encrypt(profileId.safeSuffix, plaintext)

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun decrypt(profileId: ProfileId, encoded: String): String =
        decrypt(profileId.safeSuffix, encoded)

    /**
     * Drops the profile's wallet key (profile deletion cascade). Never
     * throws and is a no-op when the key is already gone.
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

    /** Loads — or lazily generates on first use — one profile's wallet key. */
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
                    // make the wallet unavailable on the many devices without
                    // the hardware; the TEE-backed Keystore is the guarantee
                    // the threat model needs (key material never leaves
                    // secure storage).
                    .build()
            )
            return generator.generateKey()
        } catch (e: Throwable) {
            throw VaultCryptoException("Wallet key unavailable", e)
        }
    }
}
