package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.chains.sui.SuiAdapter
import com.roombrowser.domain.wallet.model.ChainType
import org.junit.Test

/**
 * Invariants the bundled network presets have to satisfy to be worth shipping.
 *
 * A preset is the only network most users ever touch: it is seeded into every
 * profile before the user sees the wallet, and a preset that does not answer
 * makes the whole chain family look broken. These are the rules that are
 * cheap to check here and expensive to discover from a phone — a family whose
 * first entry is a TESTNET is the sharp one, because seeding enables the first
 * entry of each family, so a reordered list silently starts every new wallet
 * on a testnet.
 *
 * What this file cannot check is whether the hosts are still alive; that was
 * probed against the live endpoints when the presets were last set, and the
 * comment beside each list records the date and what answered.
 */
class NetworkPresetsTest {

    private val registry = ChainRegistry()

    @Test
    fun `every family lists its mainnet first, because seeding enables the first`() {
        ChainType.entries.forEach { chain ->
            val first = registry.defaultNetworks(chain).first()
            assertThat(first.chainType).isEqualTo(chain)
            assertThat(first.isTestnet).isFalse()
        }
    }

    @Test
    fun `every family offers at least one network`() {
        ChainType.entries.forEach { chain ->
            assertThat(registry.defaultNetworks(chain)).isNotEmpty()
        }
    }

    @Test
    fun `every preset has a usable rpc endpoint and an id that cannot collide`() {
        val all = registry.allDefaultNetworks()
        assertThat(all).isNotEmpty()

        all.forEach { config ->
            assertThat(config.id).isNotEmpty()
            assertThat(config.rpcUrls).isNotEmpty()
            assertThat(config.rpcUrls.filter { it.isBlank() }).isEmpty()
            // Cleartext RPC would be both a downgrade and an Android
            // network-security-policy violation, so the shape is load-bearing.
            assertThat(config.rpcUrls.all { it.startsWith("https://") }).isTrue()
            assertThat(config.nativeSymbol).isNotEmpty()
        }

        // The id IS the profile's row key. Two presets sharing one would
        // collapse into a single row, and the second would simply vanish.
        assertThat(all.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `a preset's id is its chain type and chain id`() {
        registry.allDefaultNetworks().forEach { config ->
            assertThat(config.id).isEqualTo("${config.chainType}:${config.chainId}")
        }
    }

    @Test
    fun `the Sui presets do not point at the deprecated public fullnodes`() {
        // Sui's own fullnodes answer every JSON-RPC call with
        // `-32601 Method not found. JSON-RPC on public fullnodes has been
        // deprecated`. That is a protocol answer rather than a transport
        // failure, so RpcEndpointChain does not fail over past it: pointing a
        // preset there means every read and every send on the profile errors.
        val sui = registry.defaultNetworks(ChainType.SUI)

        sui.forEach { config ->
            assertThat(config.rpcUrls.filter { it.endsWith(".sui.io") }).isEmpty()
        }
        assertThat(SuiAdapter.MAINNET.rpcUrls.first())
            .isEqualTo("https://sui-rpc.publicnode.com")
        assertThat(SuiAdapter.TESTNET.rpcUrls.first())
            .isEqualTo("https://sui-testnet-rpc.publicnode.com")
    }

    @Test
    fun `no Sui devnet preset is shipped while it has no public JSON-RPC`() {
        // Every public devnet endpoint checked on 2026-10-03 is either gone
        // or proxies the deprecated fullnode. Shipping one would be shipping a
        // network that cannot work, which is why the EVM list drops networks
        // the same way (Horizen Gobi).
        assertThat(registry.defaultNetworks(ChainType.SUI).map { it.id })
            .containsExactly("SUI:mainnet", "SUI:testnet")
    }

    @Test
    fun `every preset names an explorer the app can build a transaction link for`() {
        // The link is "<explorerUrl>/<path>/<hash>", so a base with its own
        // path or query already baked in would produce a mangled URL.
        registry.allDefaultNetworks().forEach { config ->
            val explorer = config.explorerUrl
            assertThat(explorer).isNotNull()
            assertThat(explorer!!.startsWith("https://")).isTrue()
            assertThat(explorer).doesNotContain("?")
            assertThat(explorer.trimEnd('/')).isEqualTo(explorer)
        }
    }
}
