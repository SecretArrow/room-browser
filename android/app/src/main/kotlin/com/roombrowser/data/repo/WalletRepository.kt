package com.roombrowser.data.repo

import com.roombrowser.browser.wallet.DappPermissionRecord
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletActivityRecord
import com.roombrowser.browser.wallet.WalletRepositoryApi
import com.roombrowser.browser.wallet.WalletSummary
import com.roombrowser.data.db.DappPermissionDao
import com.roombrowser.data.db.DappPermissionEntity
import com.roombrowser.data.db.WalletAccountDao
import com.roombrowser.data.db.WalletAccountEntity
import com.roombrowser.data.db.WalletActivityDao
import com.roombrowser.data.db.WalletActivityEntity
import com.roombrowser.data.db.WalletActiveNetworkEntity
import com.roombrowser.data.db.WalletDao
import com.roombrowser.data.db.WalletEntity
import com.roombrowser.data.db.WalletNetworkDao
import com.roombrowser.data.db.WalletNetworkEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.chains.ChainRegistry
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.security.VaultCryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Per-profile multi-chain wallet persistence: Room rows (wallets, accounts,
 * networks, dApp permissions, activity) + per-profile key encryption
 * (security.WalletKeyCrypto / [VaultCryptor]). Implements the frozen
 * com.roombrowser.browser.wallet.WalletRepositoryApi contract.
 *
 * Security invariants:
 *  - the DAOs see ONLY ciphertext: the wallet's mnemonic
 *    (wallets.mnemonic_enc) and imported accounts' private keys
 *    (wallet_accounts.private_key_enc) are AndroidKeyStore AES-256-GCM
 *    blobs under the profile's wallet key (alias
 *    roomwallet-&lt;safeSuffix&gt;); derived accounts store NO key material at
 *    all (their keys are re-derived from the encrypted mnemonic on use);
 *  - plaintext key material crosses this boundary at exactly three points —
 *    [createWallet] and [addImportedAccount] (input, encrypted on the spot)
 *    and [revealMnemonic]/[revealPrivateKey] (output; the UI layer gates
 *    BOTH behind its biometric prompt before calling) — and nothing is ever
 *    logged;
 *  - every read/write is scoped to the profile it was called with (wallet
 *    data is profile data, like tabs and credentials);
 *  - all suspend work runs on [Dispatchers.IO] (Keystore + Room are
 *    blocking); observe* flows are mapped off-IO the same way.
 *
 * One wallet per profile (v1) — enforced by a UNIQUE index, and
 * [createWallet] refuses to run when one exists. Account uniqueness per
 * (wallet, chain, address) is enforced by the schema.
 */
class WalletRepository(
    private val walletDao: WalletDao,
    private val accountDao: WalletAccountDao,
    private val networkDao: WalletNetworkDao,
    private val permissionDao: DappPermissionDao,
    private val activityDao: WalletActivityDao,
    private val crypto: VaultCryptor
) : WalletRepositoryApi {

    /**
     * The default-network catalogue. Built lazily (and only once): pure-JVM
     * chain adapters with no side effects at construction, so wallet CRUD
     * paths that never seed networks pay nothing for them.
     */
    private val registry: ChainRegistry by lazy { ChainRegistry() }

    /** NetworkConfig / method-list codec; encodeDefaults keeps payloads complete. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- wallet ------------------------------------------------------------

    /** Live wallet row of the profile (null until one is created). */
    override fun observeWallet(profileId: ProfileId): Flow<WalletSummary?> =
        walletDao.observeByProfile(profileId.value)
            .map { it?.toSummary() }
            .flowOn(Dispatchers.IO)

    /** The profile's wallet, or null when none exists yet. */
    override suspend fun wallet(profileId: ProfileId): WalletSummary? =
        withContext(Dispatchers.IO) { walletDao.byProfile(profileId.value)?.toSummary() }

    /**
     * Creates the profile's wallet from a mnemonic: the mnemonic is
     * encrypted under the profile's wallet key and ONLY the ciphertext is
     * stored. Deriving the first accounts is the engine's job — this layer
     * just persists the wallet.
     *
     * @throws IllegalStateException when a wallet already exists for the
     * profile (one wallet per profile in v1).
     * @throws IllegalArgumentException when [mnemonic] is blank.
     */
    override suspend fun createWallet(
        profileId: ProfileId,
        label: String,
        mnemonic: String
    ): WalletSummary {
        require(mnemonic.isNotBlank()) { "Wallet mnemonic must not be blank" }
        return withContext(Dispatchers.IO) {
            walletDao.byProfile(profileId.value)?.let {
                throw IllegalStateException("A wallet already exists for this profile")
            }
            val entity = WalletEntity(
                id = UUID.randomUUID().toString(),
                profileId = profileId.value,
                label = label,
                mnemonicEnc = crypto.encrypt(profileId.safeSuffix, mnemonic),
                createdAt = System.currentTimeMillis()
            )
            walletDao.upsert(entity)
            entity.toSummary()
        }
    }

    /**
     * Deletes the wallet and every wallet-owned row of the profile: its
     * accounts, network list + active choices, dApp permissions and
     * recorded activity. Idempotent — a profile without a wallet is a
     * no-op. The profile's wallet KEY is not touched here; it is destroyed
     * by the profile-deletion cascade (ProfileRepositoryImpl), which also
     * wipes these tables.
     */
    override suspend fun deleteWallet(profileId: ProfileId) {
        withContext(Dispatchers.IO) {
            walletDao.byProfile(profileId.value)?.let { wallet ->
                accountDao.deleteForWallet(wallet.id)
            }
            walletDao.deleteAllForProfile(profileId.value)
            networkDao.deleteAllForProfile(profileId.value)
            networkDao.deleteActiveNetworksForProfile(profileId.value)
            permissionDao.deleteAllForProfile(profileId.value)
            activityDao.deleteAllForProfile(profileId.value)
        }
    }

    /**
     * Decrypts and returns the wallet's mnemonic, or null when the wallet
     * has none (imported-accounts-only wallet) or no wallet exists. The UI
     * layer MUST run its biometric gate before calling this — the
     * repository returns plaintext on purpose so the reveal screen can show
     * it; it never logs or persists it.
     */
    override suspend fun revealMnemonic(profileId: ProfileId): String? =
        withContext(Dispatchers.IO) {
            walletDao.byProfile(profileId.value)?.mnemonicEnc
                ?.let { crypto.decrypt(profileId.safeSuffix, it) }
        }

    // -- accounts ----------------------------------------------------------

    /** Live account list of the profile's wallet (empty while no wallet). */
    override fun observeAccounts(profileId: ProfileId): Flow<List<WalletAccountRecord>> =
        accountDao.observeForProfile(profileId.value)
            .map { rows -> rows.map { it.toRecord() } }
            .flowOn(Dispatchers.IO)

    /** The profile's accounts (empty while no wallet). */
    override suspend fun accounts(profileId: ProfileId): List<WalletAccountRecord> =
        withContext(Dispatchers.IO) { accountDao.forProfile(profileId.value).map { it.toRecord() } }

    /**
     * Persists a mnemonic-derived account. Stores NO key material — derived
     * keys are re-computed from the encrypted mnemonic when signing — only
     * the address, [path] and label.
     *
     * @throws IllegalStateException when the profile has no wallet.
     */
    override suspend fun addDerivedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        path: String,
        label: String
    ): WalletAccountRecord = withContext(Dispatchers.IO) {
        val wallet = requireWallet(profileId)
        val entity = WalletAccountEntity(
            id = UUID.randomUUID().toString(),
            walletId = wallet.id,
            chainType = chainType.name,
            address = address,
            label = label,
            path = path,
            source = WalletAccountRecord.Source.DERIVED.name,
            privateKeyEnc = null,
            createdAt = System.currentTimeMillis()
        )
        accountDao.upsert(entity)
        entity.toRecord()
    }

    /**
     * Persists an imported account: the private key is encrypted under the
     * profile's wallet key and ONLY the ciphertext is stored; the record's
     * path is "" (imports have no derivation path).
     *
     * @throws IllegalStateException when the profile has no wallet.
     */
    override suspend fun addImportedAccount(
        profileId: ProfileId,
        chainType: ChainType,
        address: String,
        privateKey: String,
        label: String
    ): WalletAccountRecord = withContext(Dispatchers.IO) {
        val wallet = requireWallet(profileId)
        val entity = WalletAccountEntity(
            id = UUID.randomUUID().toString(),
            walletId = wallet.id,
            chainType = chainType.name,
            address = address,
            label = label,
            path = "",
            source = WalletAccountRecord.Source.IMPORTED.name,
            privateKeyEnc = crypto.encrypt(profileId.safeSuffix, privateKey),
            createdAt = System.currentTimeMillis()
        )
        accountDao.upsert(entity)
        entity.toRecord()
    }

    /** Renames one account by id. */
    override suspend fun renameAccount(accountId: String, label: String) {
        withContext(Dispatchers.IO) { accountDao.rename(accountId, label) }
    }

    /** Deletes one account by id (idempotent). */
    override suspend fun removeAccount(accountId: String) {
        withContext(Dispatchers.IO) { accountDao.delete(accountId) }
    }

    /**
     * Decrypts and returns an imported account's private key; null for
     * derived accounts (no stored key) and unknown ids. The UI layer MUST
     * run its biometric gate before calling this — like the mnemonic, the
     * plaintext exists only for the caller, never in logs or storage.
     */
    override suspend fun revealPrivateKey(accountId: String): String? =
        withContext(Dispatchers.IO) {
            val account = accountDao.byId(accountId) ?: return@withContext null
            val profileKey = walletDao.byId(account.walletId)?.profileId
                ?: return@withContext null
            account.privateKeyEnc?.let { crypto.decrypt(ProfileId(profileKey).safeSuffix, it) }
        }

    /**
     * The next free BIP44 account index for the chain: the highest index
     * found among the profile's DERIVED accounts' paths (the LAST path
     * element, e.g. "m/44'/60'/0'/0/3" reads 3) plus one — 0 when the chain
     * has no derived accounts yet. Non-contiguous holes are respected
     * (0, 2 and 5 in storage give 6), and imported accounts never count
     * (they carry no derivation path).
     */
    override suspend fun nextDerivationIndex(profileId: ProfileId, chainType: ChainType): Int =
        withContext(Dispatchers.IO) {
            accountDao.derivedForProfileChain(profileId.value, chainType.name)
                .maxOfOrNull { it.path.substringAfterLast('/').toIntOrNull() ?: -1 }
                ?.plus(1)
                ?: 0
        }

    // -- networks ----------------------------------------------------------

    /** Live network list of the profile (see [ensureDefaultNetworks]). */
    override fun observeNetworks(profileId: ProfileId): Flow<List<NetworkRecord>> =
        networkDao.observeForProfile(profileId.value)
            .map { rows -> rows.map { it.toRecord() } }
            .flowOn(Dispatchers.IO)

    /** The profile's networks (call [ensureDefaultNetworks] first to seed). */
    override suspend fun networks(profileId: ProfileId): List<NetworkRecord> =
        withContext(Dispatchers.IO) { networkDao.forProfile(profileId.value).map { it.toRecord() } }

    /**
     * Idempotently seeds the default-network catalogue for the profile:
     * every [ChainRegistry] default is inserted with the first network of
     * each chain family ENABLED (Ethereum Mainnet for EVM, the family
     * mainnet for the others) and the rest present but disabled. Rows that
     * already exist are NEVER overridden — a disabled or edited default
     * stays exactly as the user left it — so calling this before every
     * network read is always safe.
     */
    override suspend fun ensureDefaultNetworks(profileId: ProfileId) {
        withContext(Dispatchers.IO) {
            val existing = networkDao.forProfile(profileId.value).mapTo(mutableSetOf()) { it.id }
            val defaults = registry.allDefaultNetworks()
            val enabledByDefault = defaults
                .groupBy { it.chainType }
                .mapValues { (_, family) -> family.first().id }
            defaults.forEach { config ->
                if (config.id !in existing) {
                    networkDao.upsert(
                        WalletNetworkEntity(
                            id = config.id,
                            profileId = profileId.value,
                            enabled = enabledByDefault[config.chainType] == config.id,
                            isCustom = false,
                            payload = json.encodeToString(NetworkConfig.serializer(), config)
                        )
                    )
                }
            }
        }
    }

    /**
     * Inserts — or replaces — the profile's row for [config].id as a CUSTOM
     * network (payload refreshed, enabled). Upserting with a default's id
     * takes that row over as custom (the engine validates ids before
     * calling; only the row's payload/state change, its key stays).
     */
    override suspend fun upsertCustomNetwork(profileId: ProfileId, config: NetworkConfig) {
        withContext(Dispatchers.IO) {
            networkDao.upsert(
                WalletNetworkEntity(
                    id = config.id,
                    profileId = profileId.value,
                    enabled = true,
                    isCustom = true,
                    payload = json.encodeToString(NetworkConfig.serializer(), config)
                )
            )
        }
    }

    /**
     * Removes the custom network — a no-op for seeded defaults (disable
     * those with [setNetworkEnabled] instead) and for unknown ids.
     */
    override suspend fun removeCustomNetwork(profileId: ProfileId, networkId: String) {
        withContext(Dispatchers.IO) {
            if (networkDao.byProfileAndId(profileId.value, networkId)?.isCustom == true) {
                networkDao.delete(profileId.value, networkId)
            }
        }
    }

    /** Enables/disables one network; a no-op for unknown ids. */
    override suspend fun setNetworkEnabled(
        profileId: ProfileId,
        networkId: String,
        enabled: Boolean
    ) {
        withContext(Dispatchers.IO) {
            networkDao.setEnabled(profileId.value, networkId, enabled)
        }
    }

    /**
     * The profile's active network for the chain: the stored explicit
     * choice (see [setActiveNetwork]) when its row still exists and is
     * enabled, else the FIRST enabled network of the chain in list order
     * (defaults before customs, then id ascending — after
     * [ensureDefaultNetworks] that is the chain's mainnet), else null
     * (chain fully disabled).
     */
    override suspend fun activeNetwork(profileId: ProfileId, chainType: ChainType): NetworkConfig? =
        withContext(Dispatchers.IO) {
            val storedChoice = networkDao.activeNetwork(profileId.value, chainType.name)
            val chosen = storedChoice?.let {
                networkDao.byProfileAndId(profileId.value, it.networkId)
                    ?.takeIf { row -> row.enabled }
                    ?.toConfig()
            }
            chosen ?: networkDao.forProfile(profileId.value)
                .filter { it.enabled }
                .map { it.toRecord() }
                .firstOrNull { it.config.chainType == chainType }
                ?.config
        }

    /**
     * Stores the profile's active network for a chain. Selecting a disabled
     * network enables it (an active network must be usable), and the row
     * must belong to [chainType] — the id and the chain are a matched pair.
     *
     * @throws IllegalArgumentException when [networkId] is unknown for the
     * profile or belongs to a different chain.
     */
    override suspend fun setActiveNetwork(
        profileId: ProfileId,
        chainType: ChainType,
        networkId: String
    ) {
        withContext(Dispatchers.IO) {
            val row = networkDao.byProfileAndId(profileId.value, networkId)
                ?: throw IllegalArgumentException("Unknown network $networkId for this profile")
            require(row.toConfig().chainType == chainType) {
                "Network $networkId does not belong to chain ${chainType.name}"
            }
            if (!row.enabled) {
                networkDao.setEnabled(profileId.value, networkId, true)
            }
            networkDao.upsertActive(
                WalletActiveNetworkEntity(
                    profileId = profileId.value,
                    chainType = chainType.name,
                    networkId = networkId
                )
            )
        }
    }

    // -- dApp permissions --------------------------------------------------

    /**
     * Grants (or re-grants) a dApp permission: the (profile, host, chain,
     * account) row keeps its id and is refreshed with the new method list
     * and a new grantedAt. The host must be the WebView-VERIFIED host.
     */
    override suspend fun grantDappPermission(
        profileId: ProfileId,
        host: String,
        chainType: ChainType,
        accountAddress: String,
        methods: List<String>
    ) {
        withContext(Dispatchers.IO) {
            val existing = permissionDao.byKey(profileId.value, host, chainType.name, accountAddress)
            permissionDao.upsert(
                DappPermissionEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    profileId = profileId.value,
                    host = host,
                    chainType = chainType.name,
                    accountAddress = accountAddress,
                    methodsJson = json.encodeToString(ListSerializer(String.serializer()), methods),
                    grantedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Revokes every account permission of the (profile, host, chain) pair —
     * the contract's revoke granularity is host+chain, so all accounts of
     * that pair go at once.
     */
    override suspend fun revokeDappPermission(
        profileId: ProfileId,
        host: String,
        chainType: ChainType
    ) {
        withContext(Dispatchers.IO) {
            permissionDao.revokeForHostAndChain(profileId.value, host, chainType.name)
        }
    }

    /** The profile's permissions for one host (all chains/accounts). */
    override suspend fun dappPermissions(
        profileId: ProfileId,
        host: String
    ): List<DappPermissionRecord> =
        withContext(Dispatchers.IO) {
            permissionDao.forHost(profileId.value, host).map { it.toRecord() }
        }

    /** Every dApp permission of the profile (the permissions manager list). */
    override suspend fun allDappPermissions(profileId: ProfileId): List<DappPermissionRecord> =
        withContext(Dispatchers.IO) {
            permissionDao.allForProfile(profileId.value).map { it.toRecord() }
        }

    // -- activity ----------------------------------------------------------

    /** Live activity feed of the profile (newest first). */
    override fun observeActivities(profileId: ProfileId): Flow<List<WalletActivityRecord>> =
        activityDao.observeForProfile(profileId.value)
            .map { rows -> rows.map { it.toRecord() } }
            .flowOn(Dispatchers.IO)

    /** Records one wallet activity row (the caller mints the record id). */
    override suspend fun recordActivity(record: WalletActivityRecord) {
        withContext(Dispatchers.IO) {
            activityDao.upsert(
                WalletActivityEntity(
                    id = record.id,
                    profileId = record.profileId.value,
                    chainType = record.chainType.name,
                    networkName = record.networkName,
                    kind = record.kind.name,
                    accountAddress = record.accountAddress,
                    toAddress = record.toAddress,
                    displayAmount = record.displayAmount,
                    hash = record.hash,
                    explorerUrl = record.explorerUrl,
                    createdAt = record.createdAt
                )
            )
        }
    }

    // -- mapping helpers ----------------------------------------------------

    private suspend fun requireWallet(profileId: ProfileId): WalletEntity =
        walletDao.byProfile(profileId.value)
            ?: throw IllegalStateException("No wallet exists for this profile")

    private fun WalletEntity.toSummary(): WalletSummary = WalletSummary(
        id = id,
        profileId = ProfileId(profileId),
        label = label,
        createdAt = createdAt,
        hasMnemonic = mnemonicEnc != null
    )

    private fun WalletAccountEntity.toRecord(): WalletAccountRecord = WalletAccountRecord(
        id = id,
        walletId = walletId,
        chainType = toChainType(chainType),
        address = address,
        label = label,
        path = path,
        source = WalletAccountRecord.Source.valueOf(source)
    )

    private fun WalletNetworkEntity.toRecord(): NetworkRecord =
        NetworkRecord(config = toConfig(), enabled = enabled, isCustom = isCustom)

    private fun WalletNetworkEntity.toConfig(): NetworkConfig =
        json.decodeFromString(NetworkConfig.serializer(), payload)

    private fun DappPermissionEntity.toRecord(): DappPermissionRecord = DappPermissionRecord(
        id = id,
        profileId = ProfileId(profileId),
        host = host,
        chainType = toChainType(chainType),
        accountAddress = accountAddress,
        methods = json.decodeFromString(ListSerializer(String.serializer()), methodsJson),
        grantedAt = grantedAt
    )

    private fun WalletActivityEntity.toRecord(): WalletActivityRecord = WalletActivityRecord(
        id = id,
        profileId = ProfileId(profileId),
        chainType = toChainType(chainType),
        networkName = networkName,
        kind = WalletActivityRecord.Kind.valueOf(kind),
        accountAddress = accountAddress,
        toAddress = toAddress,
        displayAmount = displayAmount,
        hash = hash,
        explorerUrl = explorerUrl,
        createdAt = createdAt
    )

    /**
     * Chain names are written by this repository only; an unknown value
     * means the row predates a schema change and fails loudly instead of
     * silently mapping to a wrong chain.
     */
    private fun toChainType(name: String): ChainType =
        ChainType.fromName(name) ?: throw IllegalArgumentException("Unknown chain type stored: $name")
}
