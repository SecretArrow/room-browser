package com.roombrowser.domain.wallet.chains

import com.roombrowser.domain.wallet.chains.aptos.AptosAdapter
import com.roombrowser.domain.wallet.chains.bitcoin.BitcoinAdapter
import com.roombrowser.domain.wallet.chains.cosmos.CosmosAdapter
import com.roombrowser.domain.wallet.chains.evm.ChainlistClient
import com.roombrowser.domain.wallet.chains.evm.EvmNetworkSnapshot
import com.roombrowser.domain.wallet.chains.evm.EvmAdapter
import com.roombrowser.domain.wallet.chains.solana.SolanaAdapter
import com.roombrowser.domain.wallet.chains.sui.SuiAdapter
import com.roombrowser.domain.wallet.chains.tron.TronAdapter
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.rpc.JsonRpcClient

/**
 * Central registry that maps chain types to their adapters and default
 * networks. The app layer asks this facade for everything chain-related —
 * new chains are added by creating an adapter and registering it here,
 * without touching the wallet engine.
 */
class ChainRegistry(rpcClient: JsonRpcClient = JsonRpcClient()) {

    val evm: EvmAdapter = EvmAdapter(rpcClient)
    val solana: SolanaAdapter = SolanaAdapter(rpcClient)
    val aptos: AptosAdapter = AptosAdapter(rpcClient)
    val sui: SuiAdapter = SuiAdapter(rpcClient)
    val cosmos: CosmosAdapter = CosmosAdapter(rpcClient)
    val bitcoin: BitcoinAdapter = BitcoinAdapter(rpcClient)
    val tron: TronAdapter = TronAdapter(rpcClient)

    val chainlist: ChainlistClient = ChainlistClient(rpcClient)

    fun defaultNetworks(chainType: ChainType): List<NetworkConfig> = when (chainType) {
        ChainType.EVM -> EvmNetworkDefaults
        ChainType.SOLANA -> SolanaAdapter.defaultNetworks()
        ChainType.APTOS -> AptosAdapter.defaultNetworks()
        ChainType.SUI -> SuiAdapter.defaultNetworks()
        ChainType.COSMOS -> CosmosAdapter.defaultNetworks()
        ChainType.BITCOIN -> BitcoinAdapter.defaultNetworks()
        ChainType.TRON -> TronAdapter.defaultNetworks()
    }

    fun allDefaultNetworks(): List<NetworkConfig> = ChainType.entries.flatMap { defaultNetworks(it) }

    companion object {
        /** The default EVM set: major mainnets first, then testnets. */
        val EvmNetworkDefaults: List<NetworkConfig> by lazy {
            EvmNetworkSnapshot.entries.sortedWith(
                compareBy({ it.testnet }, { it.chainId != 1L }, { it.name })
            ).map { entry ->
                NetworkConfig.evm(entry.chainId, entry.name, entry.rpcUrls, entry.symbol, entry.explorer, entry.testnet)
            }
        }
    }
}
