package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.crypto.Base58
import com.roombrowser.domain.wallet.crypto.Bech32
import com.roombrowser.domain.wallet.crypto.Hashes
import com.roombrowser.domain.wallet.crypto.Hex
import org.junit.Test

/**
 * Base58 / Bech32 against Bitcoin's own test vectors (BIP173 and the
 * reference base58 test set), plus digest checks with published vectors.
 */
class EncodingTest {

    @Test
    fun `base58 encodes bitcoin vectors`() {
        assertThat(Base58.encode("hello world".toByteArray())).isEqualTo("StV1DL6CwTryKyV")
        assertThat(Base58.encode("".toByteArray())).isEqualTo("")
        assertThat(Base58.encode(byteArrayOf(0x00, 0x00))).isEqualTo("11")
        assertThat(Base58.encode("hello world!".toByteArray())).isEqualTo("2yGEbwRFyhPZZckKA")
        assertThat(Base58.encode("hello world! ".toByteArray())).isEqualTo("9hLF3DC57FGaixnB9H")
        assertThat(Base58.encode(Hex.decode("00010966776006953D5567439E5E39F86A0D273BEED61967F6")))
            .isEqualTo("16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM")
    }

    @Test
    fun `base58 roundtrips`() {
        for (s in listOf("StV1DL6CwTryKyV", "1", "11111111111111111111111111111111",
                         "FwzQxHPj38ZiS6RPyFeGhFL9RahBPAA2ZZNWS6sab475", "16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM")) {
            assertThat(Base58.encode(Base58.decode(s))).isEqualTo(s)
        }
    }

    @Test
    fun `base58 rejects invalid characters`() {
        assertThat(Base58.decodeOrNull("0OIl")).isNull() // 0, O, I, l not in alphabet
        assertThat(Base58.decodeOrNull("ab!c")).isNull()
    }

    @Test
    fun `bech32 bip173 vectors`() {
        // BIP173 valid address encodings (witness v0)
        val decoded = Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.first).isEqualTo("bc")
        assertThat(decoded.second[0]).isEqualTo(0) // witness version
        val program = Bech32.from5bit(decoded.second.copyOfRange(1, decoded.second.size))
        assertThat(Hex.encode(program!!)).isEqualTo("751e76e8199196d454941c45d1b3a323f1433bd6")

        // Re-encode must round-trip
        val encoded = Bech32.encode("bc", Bech32.to5bit(byteArrayOf(0) + program))
        assertThat(encoded).isEqualTo("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
    }

    @Test
    fun `bech32 checksum rejects corruption`() {
        assertThat(Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5")).isNull()
        assertThat(Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kV8F3T4")).isNull() // mixed case
    }

    @Test
    fun `blake2b256 of empty input matches rfc7693`() {
        assertThat(Hex.encode(Hashes.blake2b256(ByteArray(0))))
            .isEqualTo("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8")
    }

    @Test
    fun `keccak256 empty input matches the ethereum vector`() {
        assertThat(Hex.encode(Hashes.keccak256(ByteArray(0))))
            .isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
    }

    @Test
    fun `sha3-256 empty input matches nist vector`() {
        assertThat(Hex.encode(Hashes.sha3_256(ByteArray(0))))
            .isEqualTo("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a")
    }

    @Test
    fun `sha256 abc vector`() {
        assertThat(Hex.encode(Hashes.sha256("abc".toByteArray())))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
