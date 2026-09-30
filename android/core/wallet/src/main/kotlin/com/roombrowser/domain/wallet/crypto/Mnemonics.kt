package com.roombrowser.domain.wallet.crypto

import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom

/**
 * BIP39 mnemonic support (English wordlist, 24 words for new wallets).
 * Wraps web3j's implementation: mnemonic → validation → 64-byte BIP39 seed.
 */
object Mnemonics {

    /** Generates a fresh 24-word mnemonic from OS entropy. */
    fun generate(): String {
        val entropy = ByteArray(32) // 256 bits → 24 words
        SecureRandom().nextBytes(entropy)
        return MnemonicUtils.generateMnemonic(entropy)
    }

    /** True when [mnemonic] is a valid BIP39 mnemonic (checksum included). */
    fun isValid(mnemonic: String): Boolean {
        val words = normalize(mnemonic).split(' ')
        // BIP39 mnemonic lengths: 12 / 15 / 18 / 21 / 24 words.
        if (words.size !in intArrayOf(12, 15, 18, 21, 24)) return false
        if (words.any { it.isEmpty() }) return false
        return try {
            MnemonicUtils.validateMnemonic(words.joinToString(" "))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** BIP39 seed (64 bytes) with an empty passphrase. */
    fun toSeed(mnemonic: String): ByteArray =
        MnemonicUtils.generateSeed(mnemonic.trim().lowercase(), "")

    /** Normalizes whitespace/case for storage and comparisons. */
    fun normalize(mnemonic: String): String =
        mnemonic.trim().split(Regex("\\s+")).joinToString(" ") { it.lowercase() }
}
