package com.roombrowser.domain.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * PBKDF2 + AES-GCM round trips and failure semantics of the export/import
 * vault: wrong passphrase and tampered data must fail LOUD and typed, never
 * decrypt into silent garbage.
 */
class PasswordVaultCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `roundtrips plaintext and stamps scheme parameters`() {
        val data = PasswordVaultCrypto.encrypt("s3cret-P@ssw0rd!", passphrase)

        assertThat(data.scheme).isEqualTo("pbkdf2-sha256-aes256-gcm")
        assertThat(data.iterations).isEqualTo(210_000)

        assertThat(PasswordVaultCrypto.decrypt(data, passphrase)).isEqualTo("s3cret-P@ssw0rd!")
    }

    @Test
    fun `unicode and empty plaintext roundtrip`() {
        for (plain in listOf("", "пароль 🔐", "line1\nline2")) {
            val data = PasswordVaultCrypto.encrypt(plain, passphrase)
            assertThat(PasswordVaultCrypto.decrypt(data, passphrase)).isEqualTo(plain)
        }
    }

    @Test
    fun `wrong passphrase throws VaultAuthException`() {
        val data = PasswordVaultCrypto.encrypt("secret", passphrase)

        val thrown = assertThrows(VaultAuthException::class.java) {
            PasswordVaultCrypto.decrypt(data, "wrong passphrase".toCharArray())
        }
        // The GCM tag is the proof, not a heuristic.
        assertThat(thrown.cause).isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `tampered ciphertext throws instead of decrypting garbage`() {
        val data = PasswordVaultCrypto.encrypt("secret", passphrase)
        val bytes = Base64.getDecoder().decode(data.ciphertextB64)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = data.copy(
            ciphertextB64 = Base64.getEncoder().encodeToString(bytes)
        )

        assertThrows(VaultAuthException::class.java) {
            PasswordVaultCrypto.decrypt(tampered, passphrase)
        }
    }

    @Test
    fun `salt and iv are unique per encryption`() {
        val first = PasswordVaultCrypto.encrypt("same plaintext", passphrase)
        val second = PasswordVaultCrypto.encrypt("same plaintext", passphrase)

        assertThat(first.saltB64).isNotEqualTo(second.saltB64)
        assertThat(first.ivB64).isNotEqualTo(second.ivB64)
        // Different salt+iv ⇒ different ciphertext even for equal plaintext.
        assertThat(first.ciphertextB64).isNotEqualTo(second.ciphertextB64)

        // Both blobs still open under the same passphrase.
        assertThat(PasswordVaultCrypto.decrypt(first, passphrase)).isEqualTo("same plaintext")
        assertThat(PasswordVaultCrypto.decrypt(second, passphrase)).isEqualTo("same plaintext")
    }

    @Test
    fun `malformed data throws VaultFormatException`() {
        val data = PasswordVaultCrypto.encrypt("secret", passphrase)

        // Wrong scheme name.
        assertThrows(VaultFormatException::class.java) {
            PasswordVaultCrypto.decrypt(data.copy(scheme = "aes-ecb"), passphrase)
        }
        // Broken base64.
        assertThrows(VaultFormatException::class.java) {
            PasswordVaultCrypto.decrypt(data.copy(saltB64 = "!!!not-base64!!!"), passphrase)
        }
        // IV of the wrong length.
        assertThrows(VaultFormatException::class.java) {
            PasswordVaultCrypto.decrypt(
                data.copy(ivB64 = Base64.getEncoder().encodeToString(ByteArray(11))),
                passphrase
            )
        }
        // Unusable iteration count.
        assertThrows(VaultFormatException::class.java) {
            PasswordVaultCrypto.decrypt(data.copy(iterations = 0), passphrase)
        }
    }

    @Test
    fun `wipe zeroes the char array`() {
        val chars = "hunter2".toCharArray()
        PasswordVaultCrypto.wipe(chars)
        assertThat(chars.toList()).containsExactly(
            '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000'
        ).inOrder()
    }
}
