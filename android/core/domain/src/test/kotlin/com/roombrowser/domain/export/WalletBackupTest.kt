package com.roombrowser.domain.export

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.VaultAuthException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import org.junit.Assert.assertThrows

/**
 * JVM tests for the wallet-keys export.
 *
 * The load-bearing one is [the recovery phrase never appears in the file]:
 * everything else here is ergonomics, and that one is the reason this format
 * is not a plaintext `.txt`. A backup that quietly wrote the seed in the
 * clear would pass every other test in this class.
 */
class WalletBackupTest {

    private lateinit var previousZone: TimeZone

    @Before
    fun fixTimeZone() {
        // Fixed so the formatted timestamps can be asserted exactly; the zone
        // is the device's in production, only the FORMAT is pinned there.
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(previousZone)
    }

    private val at = 1_759_400_000_000L // 2025-10-02 10:13:20 UTC

    private val phrase = "abandon ability able about above absent absorb abstract " +
        "absurd abuse access accident"

    private fun contents(
        mnemonic: String? = phrase,
        accounts: List<WalletBackup.KeyEntry> = listOf(
            WalletBackup.KeyEntry("EVM", "EVM 1", "0x1111111111111111", "m/44'/60'/0'/0/0"),
            WalletBackup.KeyEntry("Solana", "Solana 1", "So1anaAddr", "m/44'/501'/0'/0'")
        )
    ) = WalletBackup.Contents(
        walletLabel = "Main",
        createdAt = at,
        mnemonic = mnemonic,
        accounts = accounts
    )

    private fun header() = WalletBackup.Header(profileLabel = "Work", exportedAt = at)

    // ------------------------------------------------------------ the point

    @Test
    fun `the recovery phrase never appears in the file`() {
        val file = WalletBackup.seal(contents(), header(), "correct horse battery".toCharArray())

        // Not the phrase, and not any single word of it: a file that leaked
        // one word would leak the whole thing to a dictionary attack, and a
        // word-by-word check catches an encoding change that a whole-phrase
        // check would sail past.
        assertThat(file).doesNotContain(phrase)
        phrase.split(" ").forEach { word ->
            assertThat(file).doesNotContain(word)
        }
        assertThat(file).doesNotContain("Main")
        assertThat(file).doesNotContain("m/44'")
    }

    @Test
    fun `a sealed file opens with its passphrase and yields the keys document`() {
        val passphrase = "correct horse battery".toCharArray()
        val file = WalletBackup.seal(contents(), header(), passphrase)

        val opened = WalletBackup.open(file, "correct horse battery".toCharArray())

        // Round trip through open(), not through the raw cipher: what this
        // proves is that THIS file is readable by THIS code.
        assertThat(opened).contains("Room Browser - wallet keys")
        assertThat(opened).contains("Wallet: Main")
        assertThat(opened).contains("Profile: Work")
        assertThat(opened).contains("  1. abandon")
        assertThat(opened).contains(" 12. accident")
        assertThat(opened).contains("0x1111111111111111")
        assertThat(opened).contains("m/44'/60'/0'/0/0")
    }

    @Test
    fun `the wrong passphrase fails cleanly instead of returning garbage`() {
        val file = WalletBackup.seal(contents(), header(), "right passphrase".toCharArray())

        assertThrows(VaultAuthException::class.java) {
            WalletBackup.open(file, "wrong passphrase".toCharArray())
        }
    }

    @Test
    fun `a tampered ciphertext is rejected`() {
        val passphrase = "correct horse battery".toCharArray()
        val file = WalletBackup.seal(contents(), header(), passphrase)
        // Flip one base64 character inside the ciphertext field. GCM's tag is
        // what makes this an error rather than a subtly wrong document.
        val marker = "\"ciphertextB64\": \""
        val start = file.indexOf(marker) + marker.length
        val original = file[start]
        val tampered = file.replaceRange(start, start + 1, if (original == 'A') "B" else "A")

        assertThrows(VaultAuthException::class.java) {
            WalletBackup.open(tampered, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `two exports of the same wallet are different files`() {
        val passphrase = "correct horse battery".toCharArray()

        val first = WalletBackup.seal(contents(), header(), passphrase)
        val second = WalletBackup.seal(contents(), header(), passphrase)

        // Fresh salt and IV per seal: identical files would tell an observer
        // that nothing changed between two exports, and would let one
        // precomputed table attack every file the user ever writes.
        assertThat(first).isNotEqualTo(second)
    }

    // ------------------------------------------------------------- contents

    @Test
    fun `imported keys are carried and derived accounts are not`() {
        val file = WalletBackup.seal(
            contents(
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "EVM 1", "0xderived", "m/44'/60'/0'/0/0"),
                    WalletBackup.KeyEntry(
                        "EVM", "Imported", "0ximported", "", privateKey = "0xdeadbeef"
                    )
                )
            ),
            header(),
            "correct horse battery".toCharArray()
        )

        val opened = WalletBackup.open(file, "correct horse battery".toCharArray())

        assertThat(opened).contains("NOT restored by the recovery phrase")
        assertThat(opened).contains("private key: 0xdeadbeef")
        // The derived account is listed for reference, but its key is
        // re-derived from the phrase — writing it would duplicate the secret.
        assertThat(opened).contains("re-derived from the phrase above")
        assertThat(opened).doesNotContain("private key: 0xderived")
    }

    @Test
    fun `a wallet with no phrase says so rather than showing an empty section`() {
        val file = WalletBackup.seal(
            contents(
                mnemonic = null,
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "Imported", "0xabc", "", privateKey = "0xkey")
                )
            ),
            header(),
            "correct horse battery".toCharArray()
        )

        val opened = WalletBackup.open(file, "correct horse battery".toCharArray())

        assertThat(opened).contains("Recovery phrase: none")
        assertThat(opened).contains("private key: 0xkey")
    }

    @Test
    fun `a wallet with nothing to restore is refused, not written as a decoy`() {
        val empty = contents(mnemonic = null, accounts = emptyList())

        assertThat(empty.isEmpty).isTrue()
        assertThrows(IllegalArgumentException::class.java) {
            WalletBackup.seal(empty, header(), "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `an empty passphrase is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletBackup.seal(contents(), header(), CharArray(0))
        }
    }

    // ---------------------------------------------------------------- file

    @Test
    fun `a file that is not ours is rejected with a readable reason`() {
        val passphrase = "correct horse battery".toCharArray()

        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open("{\"hello\":1}", passphrase)
        }
        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open("", passphrase)
        }
    }

    @Test
    fun `a file from a newer Room Browser is refused rather than guessed at`() {
        val file = WalletBackup.seal(contents(), header(), "correct horse battery".toCharArray())
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99")

        val thrown = assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open(file, "correct horse battery".toCharArray())
        }
        assertThat(thrown).hasMessageThat().contains("newer")
    }

    @Test
    fun `a profile export is not mistaken for a wallet keys file`() {
        // Both formats carry a `vault` block; `kind` is what tells them
        // apart, and getting this wrong would mean trying to restore a
        // bookmark list as a seed phrase.
        val foreign = """
            {
              "formatVersion": 2,
              "vault": {
                "scheme": "pbkdf2-sha256-aes256-gcm",
                "saltB64": "AAAA", "iterations": 210000,
                "ivB64": "AAAAAAAAAAAAAAAA", "ciphertextB64": "AAAA"
              }
            }
        """.trimIndent()

        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open(foreign, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `the filename sorts by date and survives every filesystem`() {
        val name = WalletBackup.fileName("Main Wallet", at)

        assertThat(name).isEqualTo("room-browser-wallet-keys-main-wallet-20251002-101320.txt")
        assertThat(name).doesNotContain(" ")
        assertThat(name).doesNotContain(":")
        assertThat(name.all { it.code < 128 }).isTrue()
    }

    @Test
    fun `a label that is all punctuation still yields a usable filename`() {
        val name = WalletBackup.fileName("***", at)

        assertThat(name).isEqualTo("room-browser-wallet-keys-wallet-20251002-101320.txt")
    }
}
