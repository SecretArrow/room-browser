package com.roombrowser.domain.wallet.chains.evm

/**
 * Bundled snapshot of popular EVM networks (curated from the
 * chainid.network / Chainlist dataset — public RPC endpoints only).
 * Used as the offline fallback when the live Chainlist catalog is
 * unreachable; everything the user adds lives in the app database.
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
        Entry(1, "Ethereum Mainnet", "ETH", listOf("https://eth.llamarpc.com", "https://api.mycryptoapi.com/eth"), "https://etherscan.io", false),
        Entry(10, "OP Mainnet", "ETH", listOf("https://mainnet.optimism.io", "https://optimism-rpc.publicnode.com"), "https://optimistic.etherscan.io", false),
        Entry(56, "BNB Smart Chain Mainnet", "BNB", listOf("https://bsc-dataseed1.bnbchain.org", "https://bsc-dataseed2.bnbchain.org"), "https://bscscan.com", false),
        Entry(137, "Polygon Mainnet", "POL", listOf("https://polygon-rpc.com", "https://polygon.drpc.org"), "https://polygonscan.com", false),
        Entry(8453, "Base", "ETH", listOf("https://mainnet.base.org/", "https://developer-access-mainnet.base.org/"), "https://basescan.org", false),
        Entry(42161, "Arbitrum One", "ETH", listOf("https://arb1.arbitrum.io/rpc", "https://arbitrum-one-rpc.publicnode.com"), "https://arbiscan.io", false),
        Entry(43114, "Avalanche C-Chain", "AVAX", listOf("https://api.avax.network/ext/bc/C/rpc", "https://avalanche-c-chain-rpc.publicnode.com"), "https://snowscan.xyz", false),
        Entry(250, "Fantom Opera", "FTM", listOf("https://rpc.ftm.tools", "https://fantom-rpc.publicnode.com"), "https://ftmscan.com", false),
        Entry(100, "Gnosis", "XDAI", listOf("https://rpc.gnosischain.com", "https://rpc.gnosis.gateway.fm"), "https://gnosisscan.io", false),
        Entry(324, "zkSync Mainnet", "ETH", listOf("https://mainnet.era.zksync.io", "https://zksync.drpc.org"), "https://explorer.zksync.io", false),
        Entry(59144, "Linea", "ETH", listOf("https://rpc.linea.build", "https://linea-rpc.publicnode.com"), "https://lineascan.build", false),
        Entry(7777777, "Zora", "ETH", listOf("https://rpc.zora.energy/"), "https://explorer.zora.energy", false),
        Entry(11155111, "Ethereum Sepolia", "ETH", listOf("https://rpc.sepolia.org", "https://rpc2.sepolia.org"), "https://sepolia.etherscan.io", true),
        Entry(80002, "Amoy", "POL", listOf("https://rpc-amoy.polygon.technology", "https://polygon-amoy-bor-rpc.publicnode.com"), "https://amoy.polygonscan.com", true),
        Entry(97, "BNB Smart Chain Testnet", "tBNB", listOf("https://data-seed-prebsc-1-s1.bnbchain.org:8545", "https://data-seed-prebsc-2-s1.bnbchain.org:8545"), "https://testnet.bscscan.com", true),
        Entry(421614, "Arbitrum Sepolia", "ETH", listOf("https://sepolia-rollup.arbitrum.io/rpc", "https://arbitrum-sepolia-rpc.publicnode.com"), "https://sepolia.arbiscan.io", true),
        Entry(11155420, "OP Sepolia Testnet", "ETH", listOf("https://sepolia.optimism.io", "https://optimism-sepolia.drpc.org"), "https://sepolia-optimism.etherscan.io", true),
        Entry(84532, "Base Sepolia Testnet", "ETH", listOf("https://sepolia.base.org", "https://base-sepolia-rpc.publicnode.com"), "https://sepolia.basescan.org", true),
        Entry(1313161554, "Aurora Mainnet", "ETH", listOf("https://aurora.mainnet.aurora.dev", "https://mainnet.aurora.dev"), "https://explorer.aurora.dev", false),
        Entry(1088, "Metis Andromeda Mainnet", "METIS", listOf("https://andromeda.metis.io/?owner=1088", "https://metis.drpc.org"), "https://andromeda-explorer.metis.io", false),
        Entry(1284, "Moonbeam", "GLMR", listOf("https://rpc.moonbeam.network", "https://rpc.api.moonbeam.network"), "https://moonbeam.moonscan.io", false),
        Entry(1285, "Moonriver", "MOVR", listOf("https://rpc.moonriver.moonbeam.network", "https://rpc.api.moonriver.moonbeam.network"), "https://moonriver.moonscan.io", false),
        Entry(1663, "Horizen Gobi Testnet", "tZEN", listOf("https://api.horizon-moonriver.etherscan.io"), null, true),
        Entry(30, "Rootstock Mainnet", "RBTC", listOf("https://main-rpc.httpontech.com?APIKEY=free", "https://public-node.rsk.co"), "https://explorer.rsk.co", false),
    )
}
