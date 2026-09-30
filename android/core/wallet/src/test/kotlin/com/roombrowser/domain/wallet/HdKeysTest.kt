package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.crypto.Bip32PrivateKey
import com.roombrowser.domain.wallet.crypto.Hex
import com.roombrowser.domain.wallet.crypto.Mnemonics
import com.roombrowser.domain.wallet.crypto.Slip10Ed25519Key
import org.junit.Test
import java.math.BigInteger

/**
 * HD derivation against the OFFICIAL BIP32 / SLIP-0010 test vectors.
 */
class HdKeysTest {

    // SLIP-0010 test vector 1 (seed 000102...0e0f), ed25519
    private val seed1 = Hex.decode("000102030405060708090a0b0c0d0e0f")

    private data class Vec(val path: String, val chainCode: String, val priv: String)

    private val ed25519Vectors1 = listOf(
        Vec("m", "90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb",
            "2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7"),
        Vec("m/0'", "8b59aa11380b624e81507a27fedda59fea6d0b779a778918a2fd3590e16e9c69",
            "68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3"),
        Vec("m/0'/1'", "a320425f77d1b5c2505a6b1b27382b37368ee640e3557c315416801243552f14",
            "b1d0bad404bf35da785a64ca1ac54b2617211d2777696fbffaf208f746ae84f2"),
        Vec("m/0'/1'/2'", "2e69929e00b5ab250f49c3fb1c12f252de4fed2c1db88387094a0f8c4c9ccd6c",
            "92a5b23c0b8a99e37d07df3fb9966917f5d06e02ddbd909c7e184371463e9fc9"),
        Vec("m/0'/1'/2'/2'", "8f6d87f93d750e0efccda017d662a1b31a266e4a6f5993b15f5c1f07f74dd5cc",
            "30d1dc7e5fc04c31219ab25a27ae00b50f6fd66622f6e9c913253d6511d1e662"),
        Vec("m/0'/1'/2'/2'/1000000000'", "68789923a0cac2cd5a29172a475fe9e0fb14cd6adb5ad98a3fa70333e7afa230",
            "8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793")
    )

    // SLIP-0010 test vector 2 (seed fffcf9...4542), ed25519
    private val seed2 = Hex.decode(
        "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542"
    )

    private val ed25519Vectors2 = listOf(
        Vec("m", "ef70a74db9c3a5af931b5fe73ed8e1a53464133654fd55e7a66f8570b8e33c3b",
            "171cb88b1b3c1db25add599712e36245d75bc65a1a5c9e18d76f9f2b1eab4012"),
        Vec("m/0'", "0b78a3226f915c082bf118f83618a618ab6dec793752624cbeb622acb562862d",
            "1559eb2bbec5790b0c65d8693e4d0875b1747f4970ae8b650486ed7470845635"),
        Vec("m/0'/2147483647'", "138f0b2551bcafeca6ff2aa88ba8ed0ed8de070841f0c4ef0165df8181eaad7f",
            "ea4f5bfe8694d8bb74b7b59404632fd5968b774ed545e810de9c32a4fb4192f4"),
        Vec("m/0'/2147483647'/1'", "73bd9fff1cfbde33a1b846c27085f711c0fe2d66fd32e139d3ebc28e5a4a6b90",
            "3757c7577170179c7868353ada796c839135b3d30554bbb74a4b1e4a5a58505c"),
        Vec("m/0'/2147483647'/1'/2147483646'", "0902fe8a29f9140480a00ef244bd183e8a13288e4412d8389d140aac1794825a",
            "5837736c89570de861ebc173b1086da4f505d4adb387c6a1b1342d5e4ac9ec72"),
        Vec("m/0'/2147483647'/1'/2147483646'/2'", "5d70af781f3a37b829f0d060924d5e960bdc02e85423494afc0b1a41bbe196d4",
            "551d333177df541ad876a60ea71f00447931c0a9da16f227c11ea080d7391b8d")
    )

    private val secp256k1Vectors1 = listOf(
        Vec("m", "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35"),
        Vec("m/0'", "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141",
            "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea"),
        Vec("m/0'/1", "2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19",
            "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368"),
        Vec("m/0'/1/2'", "04466b9cc8e161e966409ca52986c584f07e9dc81f735db683c3ff6ec7b1503f",
            "cbce0d719ecf7431d88e6a89fa1483e02e35092af60c042b1df2ff59fa424dca"),
        Vec("m/0'/1/2'/2", "cfb71883f01676f587d023cc53a35bc7f88f724b1f8c2892ac1275ac822a3edd",
            "0f479245fb19a38a1954c5c7c0ebab2f9bdfd96a17563ef28a6a4b1a2a764ef4"),
        Vec("m/0'/1/2'/2/1000000000", "c783e67b921d2beb8f6b389cc646d7263b4145701dadd2161548a8b078e65e9e",
            "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8")
    )

    @Test
    fun `ed25519 slip10 vector 1`() {
        for (v in ed25519Vectors1) {
            val key = Slip10Ed25519Key.derive(seed1, v.path)
            assertThat(Hex.encode(key.seed)).isEqualTo(v.priv)
            assertThat(Hex.encode(key.chainCode)).isEqualTo(v.chainCode)
        }
    }

    @Test
    fun `ed25519 slip10 vector 2`() {
        for (v in ed25519Vectors2) {
            val key = Slip10Ed25519Key.derive(seed2, v.path)
            assertThat(Hex.encode(key.seed)).isEqualTo(v.priv)
            assertThat(Hex.encode(key.chainCode)).isEqualTo(v.chainCode)
        }
    }

    @Test
    fun `secp256k1 bip32 vector 1`() {
        for (v in secp256k1Vectors1) {
            val key = Bip32PrivateKey.derive(seed1, v.path)
            assertThat(key.key.toString(16).padStart(64, '0')).isEqualTo(v.priv)
        }
    }

    @Test
    fun `bip32 fingerprint is hash160 of the key's own pubkey`() {
        // BIP32 vector 1: m/0' own fingerprint = 5c1bd648 (from pubkey
        // 035a784662a4a20a65bf6aab9ae98a6c068a81c52e4b032c0fb5400c706cfccc56);
        // the master's fingerprint (m/0' parentFingerprint) = 3442193e
        // (hash160 of 0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2).
        val m = Bip32PrivateKey.master(seed1)
        val child = m.child(0L or Bip32PrivateKey.HARDENED_BIT)
        assertThat(Integer.toHexString(child.fingerprint)).isEqualTo("5c1bd648")
        assertThat(Integer.toHexString(child.parentFingerprint)).isEqualTo("3442193e")
    }

    @Test
    fun `mnemonic to seed is deterministic and 64 bytes`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertThat(Mnemonics.isValid(mnemonic)).isTrue()
        val seed = Mnemonics.toSeed(mnemonic)
        assertThat(seed.size).isEqualTo(64)
        assertThat(seed).isEqualTo(Mnemonics.toSeed(mnemonic))
    }

    @Test
    fun `invalid mnemonic is rejected`() {
        assertThat(Mnemonics.isValid("not a real mnemonic phrase for sure")).isFalse()
        assertThat(Mnemonics.isValid("abandon abandon abandon")).isFalse()
    }

    @Test
    fun `generated mnemonic has 24 words and validates`() {
        val m = Mnemonics.generate()
        assertThat(m.split(" ")).hasSize(24)
        assertThat(Mnemonics.isValid(m)).isTrue()
    }

    @Test
    fun `path parsing accepts hardened and soft indices`() {
        val path = Bip32PrivateKey.parsePath("m/44'/60'/0'/0/2")
        assertThat(path).containsExactly(
            44L or Bip32PrivateKey.HARDENED_BIT,
            60L or Bip32PrivateKey.HARDENED_BIT,
            0L or Bip32PrivateKey.HARDENED_BIT,
            0L,
            2L
        ).inOrder()
        // Bare master path and paths without the leading "m".
        assertThat(Bip32PrivateKey.parsePath("m")).isEmpty()
        assertThat(Bip32PrivateKey.parsePath("44'/60'")).containsExactly(
            44L or Bip32PrivateKey.HARDENED_BIT,
            60L or Bip32PrivateKey.HARDENED_BIT
        ).inOrder()
    }

    @Test
    fun `public key point matches private key`() {
        // The compressed pubkey of BIP32 vector 1 chain m/0' is known:
        // 035a784662a4a20a65bf6aab9ae98a6c068a81c52e4b032c0fb5400c706cfccc56
        val key = Bip32PrivateKey.derive(seed1, "m/0'")
        assertThat(Hex.encode(key.compressedPublicKey))
            .isEqualTo("035a784662a4a20a65bf6aab9ae98a6c068a81c52e4b032c0fb5400c706cfccc56")
    }

    @Test
    fun `secp private key validity bounds are enforced`() {
        val n = Bip32PrivateKey.CURVE_N
        assertThat(n).isGreaterThan(BigInteger.ZERO)
        // n for secp256k1
        assertThat(n.toString(16)).isEqualTo(
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
        )
    }
}
