package com.roombrowser.domain.wallet.chains.evm

/**
 * Bundled snapshot of popular EVM networks (curated from the
 * chainid.network / Chainlist dataset — public RPC endpoints only).
 * Used as the offline fallback when the live Chainlist catalog is
 * unreachable; everything the user adds lives in the app database.
 *
 * Every URL here was verified to resolve AND to answer `eth_chainId`
 * with the matching chain id. A dead entry is not harmless: a dead FIRST
 * url costs every user a connect timeout before the working one is reached,
 * and a network whose ONLY url is dead can never be added at all.
 *
 * Two kinds of entry were removed on that basis:
 *  - Horizen Gobi Testnet (1663) is gone entirely. The canonical
 *    chainlist record (`_data/chains/eip155-1663.json`) lists
 *    `"rpc": []`, `"explorers": []` and `"status": "deprecated"` — it
 *    has no public endpoint to point at, so offering it would only
 *    promise a network that can never connect. Users can still add it
 *    by hand as a custom network if they run their own node.
 *  - Dead FIRST urls with a live fallback (Amoy's
 *    `rpc-amoy.polygon.technology`, Aurora's `aurora.mainnet.aurora.dev`,
 *    Rootstock's `main-rpc.httpontech.com`) were dropped so the working
 *    endpoint is tried first.
 *
 * ## The second sweep (and why it will not be the last)
 *
 * Every url was re-probed with `eth_chainId` and the answer compared to the
 * entry's chain id. Seven networks had rotted since the first pass, and the
 * failure modes are worth naming because they are what a curation pass has to
 * look for:
 *
 *  - Ethereum's primary, `eth.llamarpc.com`, answered HTTP 525 — a Cloudflare
 *    TLS handshake failure at the provider's own origin. Its backup
 *    `api.mycryptoapi.com/eth` answered 403. Ethereum, the one network every
 *    user has, had no working endpoint at all.
 *  - Moonbeam and Moonriver were worse than dead: `rpc.moonbeam.network` no
 *    longer resolves, and the `rpc.api.*` hosts fail the TLS handshake
 *    outright. A TLS failure is what produces the "Connection not secure"
 *    the user sees, and no amount of retrying fixes a wrong certificate.
 *  - Fantom's `rpc.ftm.tools` now demands an API key (401) and its backup
 *    `fantom-rpc.publicnode.com` answers 404; both Sepolia urls were dead too.
 *
 * The lesson is not "curate harder". Any list of free endpoints rots, which
 * is exactly why every RPC call now fails over across a network's urls
 * instead of using the first one — see
 * [com.roombrowser.domain.wallet.rpc.RpcEndpointChain]. Curation decides where
 * a user starts; failover decides whether they get anywhere when it dies.
 */
object EvmNetworkSnapshot {
    data class Entry(
        val chainId: Long,
        val name: String,
        val symbol: String,
        val rpcUrls: List<String>,
        val explorer: String?,
        val testnet: Boolean
    )

    val entries: List<Entry> = listOf(
        Entry(1, "Ethereum Mainnet", "ETH", listOf("https://ethereum-rpc.publicnode.com", "https://eth.drpc.org", "https://cloudflare-eth.com"), "https://etherscan.io", false),
        Entry(10, "OP Mainnet", "ETH", listOf("https://mainnet.optimism.io", "https://optimism-rpc.publicnode.com"), "https://optimistic.etherscan.io", false),
        Entry(56, "BNB Smart Chain Mainnet", "BNB", listOf("https://bsc-dataseed1.bnbchain.org", "https://bsc-dataseed2.bnbchain.org"), "https://bscscan.com", false),
        Entry(137, "Polygon Mainnet", "POL", listOf("https://polygon-bor-rpc.publicnode.com", "https://polygon.drpc.org"), "https://polygonscan.com", false),
        Entry(8453, "Base", "ETH", listOf("https://mainnet.base.org/", "https://base-rpc.publicnode.com", "https://base.drpc.org"), "https://basescan.org", false),
        Entry(42161, "Arbitrum One", "ETH", listOf("https://arb1.arbitrum.io/rpc", "https://arbitrum-one-rpc.publicnode.com"), "https://arbiscan.io", false),
        Entry(43114, "Avalanche C-Chain", "AVAX", listOf("https://api.avax.network/ext/bc/C/rpc", "https://avalanche-c-chain-rpc.publicnode.com"), "https://snowscan.xyz", false),
        Entry(250, "Fantom Opera", "FTM", listOf("https://rpc.fantom.network", "https://fantom.drpc.org"), "https://explorer.fantom.network", false),
        Entry(100, "Gnosis", "XDAI", listOf("https://rpc.gnosischain.com", "https://rpc.gnosis.gateway.fm"), "https://gnosisscan.io", false),
        Entry(324, "zkSync Mainnet", "ETH", listOf("https://mainnet.era.zksync.io", "https://zksync.drpc.org"), "https://explorer.zksync.io", false),
        Entry(59144, "Linea", "ETH", listOf("https://rpc.linea.build", "https://linea-rpc.publicnode.com"), "https://lineascan.build", false),
        Entry(7777777, "Zora", "ETH", listOf("https://rpc.zora.energy/"), "https://explorer.zora.energy", false),
        Entry(11155111, "Ethereum Sepolia", "ETH", listOf("https://ethereum-sepolia-rpc.publicnode.com", "https://1rpc.io/sepolia"), "https://sepolia.etherscan.io", true),
        Entry(80002, "Amoy", "POL", listOf("https://polygon-amoy-bor-rpc.publicnode.com"), "https://amoy.polygonscan.com", true),
        Entry(97, "BNB Smart Chain Testnet", "tBNB", listOf("https://data-seed-prebsc-1-s1.bnbchain.org:8545", "https://data-seed-prebsc-2-s1.bnbchain.org:8545"), "https://testnet.bscscan.com", true),
        Entry(421614, "Arbitrum Sepolia", "ETH", listOf("https://sepolia-rollup.arbitrum.io/rpc", "https://arbitrum-sepolia-rpc.publicnode.com"), "https://sepolia.arbiscan.io", true),
        Entry(11155420, "OP Sepolia Testnet", "ETH", listOf("https://sepolia.optimism.io", "https://optimism-sepolia.drpc.org"), "https://sepolia-optimism.etherscan.io", true),
        Entry(84532, "Base Sepolia Testnet", "ETH", listOf("https://sepolia.base.org", "https://base-sepolia-rpc.publicnode.com"), "https://sepolia.basescan.org", true),
        Entry(1313161554, "Aurora Mainnet", "ETH", listOf("https://mainnet.aurora.dev"), "https://explorer.aurora.dev", false),
        Entry(1088, "Metis Andromeda Mainnet", "METIS", listOf("https://andromeda.metis.io/?owner=1088", "https://metis.drpc.org"), "https://andromeda-explorer.metis.io", false),
        Entry(1284, "Moonbeam", "GLMR", listOf("https://moonbeam.drpc.org", "https://moonbeam.api.onfinality.io/public"), "https://moonbeam.subscan.io", false),
        Entry(1285, "Moonriver", "MOVR", listOf("https://moonriver.drpc.org", "https://moonriver.api.onfinality.io/public"), "https://moonriver.subscan.io", false),
        Entry(30, "Rootstock Mainnet", "RBTC", listOf("https://public-node.rsk.co"), "https://explorer.rsk.co", false),
    )
}
