package com.roombrowser.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.wallet.WalletActivityRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingsGroup
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Wallet dashboard — the per-profile multi-chain wallet UI.
 *
 * PROCESS (critical): declared android:process=":browser" on purpose. The
 * wallet engine's session (lock state, accounts, pending dApp requests) is
 * per-process state in the AppGraph of the ':browser' process — running
 * here means this screen shares the engine with the browsing surface and
 * the dApp bridge: unlocking here unlocks the in-page wallet, and decisions
 * made here settle the page's pending request. It never hosts a WebView.
 *
 * ENGINE BINDING: the activity binds the engine to its profile on entry
 * ([WalletEngineApi.bind] is idempotent) and does NOT unbind — the engine
 * is the process-wide session shared with the browser; profile switches
 * restart the ':browser' process, which is the session's teardown path.
 *
 * UNLOCK GATE: a LOCKED wallet triggers the biometric / device-credential
 * gate ONCE on entry ("Wallet"). Failure or a device without any credential
 * keeps the locked pane with a manual "Unlock" retry — wallet CONTENTS are
 * never rendered while locked (PasswordsActivity pattern). Onboarding
 * (NO_WALLET) needs no gate: the wallet does not exist yet, and the
 * create-flow's reveal screen shows the phrase the user just generated in
 * this same session.
 *
 * STATE: the engine IS the state holder — every StateFlow is collected
 * directly here (no second ViewModel). All actions go through
 * [WalletEngineApi] and reflect immediately through those flows.
 */
class WalletActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the status bar, cutouts or the navigation buttons.
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            // No profile to show a wallet for — nothing to do here.
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val engine = graph.walletEngine
        engine.bind(profileId)
        val biometricsAvailable = BiometricGate.canAuthenticate(this)
        setContent {
            // Wear this profile's own theme (snapshot loaded once, exactly
            // like PasswordsActivity — live re-theming belongs to the
            // browsing surface).
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                WalletRoot(
                    engine = engine,
                    profileName = profileName,
                    biometricsAvailable = biometricsAvailable,
                    onClose = { finish() },
                    onUnlockRequest = {
                        // Failure keeps the wallet locked (and this screen on
                        // its locked pane); success unlocks for the session.
                        BiometricGate.unlock(
                            this,
                            "Wallet",
                            { engine.unlock() },
                            { }
                        )
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        fun launch(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, WalletActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

/**
 * Wallet surface: locked gate pane, onboarding (no wallet yet) or the
 * dashboard. All engine work is launched from [rememberCoroutineScope];
 * sheet opens happen only in callbacks, never in composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletRoot(
    engine: WalletEngineApi,
    profileName: String,
    biometricsAvailable: Boolean,
    onClose: () -> Unit,
    onUnlockRequest: () -> Unit
) {
    val context = LocalContext.current
    val lockState by engine.lockState.collectAsState()
    val wallet by engine.wallet.collectAsState()
    val pending by engine.pendingRequests.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ONBOARDING PIN: engine.createWallet/importWallet persist the wallet
    // row the MOMENT they run, which flips lockState NO_WALLET → LOCKED via
    // the repo observer — mid-flow. Without the pin, the when() below would
    // swap the onboarding out of composition at that instant: the
    // recovery-phrase reveal and the confirmation quiz would be UNREACHABLE
    // and a UI-created wallet unrecoverable (its phrase returned by
    // createWallet dies with the discarded composition state). The pin keeps
    // onboarding in place until IT reports ready (quiz done / import done);
    // NO_WALLET always shows onboarding anyway. Dropped state (process death
    // mid-onboarding) resumes on the locked pane — the wallet is usable, but
    // a phrase abandoned before reveal is gone for good (v1 documented risk).
    var onboardingPinned by remember {
        mutableStateOf(engine.lockState.value == WalletLockState.NO_WALLET)
    }
    // True once THIS surface's own onboarding flow left the choice screen
    // (create/import started here) — the ONLY legitimate holder of the pin
    // below. A pin acquired from a STALE lockState (the ':browser' process's
    // Room instance had not yet seen a wallet that another process just
    // created — CI 36842626140, the pipeline e2e's repo-level wallet
    // creation) must RELEASE the moment the engine reports a wallet exists,
    // or the entry gate (locked pane) can never render and a "Create a new
    // wallet" tap would race a second wallet into existence.
    var onboardingFlowStartedHere by remember { mutableStateOf(false) }
    LaunchedEffect(lockState) {
        if (lockState != WalletLockState.NO_WALLET && !onboardingFlowStartedHere) {
            onboardingPinned = false
        }
    }

    // Gate on entry (LOCKED only) — exactly once per composition; every
    // later prompt is a user-driven retry from the locked pane's Unlock
    // button. Onboarding (NO_WALLET) is exempt: nothing to unlock yet.
    LaunchedEffect(Unit) {
        if (engine.lockState.value == WalletLockState.LOCKED) onUnlockRequest()
    }

    fun onMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** Opens a URL outside the app (explorer links). */
    fun openLink(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!opened) onMessage("Could not open link")
    }

    /** Send success: hash in a snackbar, explorer link behind the action. */
    fun onSent(hash: String, explorerUrl: String?) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Sent ${shortenAddress(hash)}",
                actionLabel = explorerUrl?.let { "View" },
                withDismissAction = explorerUrl == null
            )
            if (result == SnackbarResult.ActionPerformed && explorerUrl != null) {
                openLink(explorerUrl)
            }
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics);
        // insets are applied EXPLICITLY below — the TopAppBar handles the
        // status bar itself.
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Padded above the navigation bar (contentWindowInsets is zeroed
            // on this Scaffold, so a bare host would draw under the buttons).
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("Wallet") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close wallet" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (lockState == WalletLockState.UNLOCKED) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    runCatching { engine.refreshBalances() }
                                        .onFailure { onMessage("Balance refresh failed") }
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Refresh balances"
                            }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Above the navigation bar and beside display cutouts.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            when {
                // Pinned onboarding OR no wallet yet → onboarding. Creating/
                // importing does NOT unlock the engine session by design —
                // when onboarding completes, fire the entry gate right away
                // (the user just proved ownership of the phrase in this
                // session) instead of dumping them on the locked pane; on
                // failure/no device credential the pane takes over with its
                // manual retry, exactly like the entry path.
                onboardingPinned || lockState == WalletLockState.NO_WALLET -> WalletOnboarding(
                    engine = engine,
                    profileName = profileName,
                    onMessage = { onMessage(it) },
                    onFlowStarted = { onboardingFlowStartedHere = true },
                    onWalletReady = {
                        onboardingPinned = false
                        onUnlockRequest()
                    }
                )
                lockState == WalletLockState.LOCKED -> LockedWalletPane(
                    biometricsAvailable = biometricsAvailable,
                    onUnlock = onUnlockRequest
                )
                else -> WalletDashboard(
                    engine = engine,
                    walletLabel = wallet?.label ?: "Wallet",
                    profileName = profileName,
                    pendingCount = pending.size,
                    onMessage = { onMessage(it) },
                    onSent = { hash, explorerUrl -> onSent(hash, explorerUrl) },
                    onOpenExplorer = { url -> openLink(url) }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Pending dApp confirmations (this activity's own queue view). The
    // queue drives which sheet is up; the sheet itself settles the request
    // through the engine (Approve / Reject / dismiss-as-reject), so the
    // request leaves the queue and the next one — if any — takes its place.
    // Only shown while UNLOCKED: locked users see nothing but the gate.
    // ------------------------------------------------------------------
    var dismissedRequestId by remember { mutableStateOf<String?>(null) }
    val activeRequest = pending.firstOrNull { it.id != dismissedRequestId }
    if (activeRequest != null && lockState == WalletLockState.UNLOCKED) {
        WalletDappRequestSheet(
            request = activeRequest,
            engine = engine,
            onDismiss = { dismissedRequestId = activeRequest.id }
        )
    }
}

/** The locked pane: no wallet content is ever rendered here. */
@Composable
private fun LockedWalletPane(
    biometricsAvailable: Boolean,
    onUnlock: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Wallet locked",
            style = MaterialTheme.typography.titleMedium,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (biometricsAvailable) {
                "Unlock with your fingerprint, face or device PIN to view and " +
                    "use this profile's wallet."
            } else {
                "This device has no screen lock. Set a PIN, pattern or password " +
                    "in system settings to use the wallet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("Unlock") }
    }
}

/**
 * The unlocked dashboard: header (wallet label + profile), the pending
 * badge, an Accounts / Activity switch, chain-filtered account cards with
 * per-chain Send / Receive, the add-account and network entries, and the
 * local activity list with explorer links. Every sheet open happens in a
 * callback; all lists come straight from the engine's flows.
 */
@Composable
private fun WalletDashboard(
    engine: WalletEngineApi,
    walletLabel: String,
    profileName: String,
    pendingCount: Int,
    onMessage: (String) -> Unit,
    onSent: (hash: String, explorerUrl: String?) -> Unit,
    onOpenExplorer: (url: String) -> Unit
) {
    val context = LocalContext.current
    val extras = LocalRoomExtras.current
    val accounts by engine.accounts.collectAsState()
    val balances by engine.balances.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val activities by engine.activities.collectAsState()

    var showAccountsSection by remember { mutableStateOf(true) }
    var chainFilter by remember { mutableStateOf<ChainType?>(null) }
    var addAccountOpen by remember { mutableStateOf(false) }
    var importKeyOpen by remember { mutableStateOf(false) }
    var networkPickerChain by remember { mutableStateOf<ChainType?>(null) }
    var addNetworkOpen by remember { mutableStateOf(false) }
    var chainlistOpen by remember { mutableStateOf(false) }
    var sendChain by remember { mutableStateOf<ChainType?>(null) }
    var receiveChain by remember { mutableStateOf<ChainType?>(null) }
    var backupOpen by remember { mutableStateOf(false) }

    // Populate balances once per dashboard entry (offline-tolerant: absent
    // balances simply render as "—").
    LaunchedEffect(Unit) {
        runCatching { engine.refreshBalances() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // Header: wallet label + owning profile.
        RoomCard(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            withGradient = false
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(extras.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AccountBalanceWallet,
                        contentDescription = null,
                        tint = extras.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        walletLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = extras.textPrimary
                    )
                    if (profileName.isNotBlank()) {
                        Text(
                            "Profile: $profileName",
                            style = MaterialTheme.typography.labelMedium,
                            color = extras.textSecondary
                        )
                    }
                }
            }
        }
        // Pending badge: the FIRST pending request's sheet is already up;
        // this only announces the queue behind it.
        if (pendingCount > 1) {
            WalletInfoNote(
                "$pendingCount site requests are waiting — answer the open " +
                    "confirmation first; the next one follows."
            )
        }
        // Section switch.
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = showAccountsSection,
                onClick = { showAccountsSection = true },
                label = { Text("Accounts") }
            )
            FilterChip(
                selected = !showAccountsSection,
                onClick = { showAccountsSection = false },
                label = { Text("Activity") }
            )
        }
        if (showAccountsSection) {
            // Chain filter.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = chainFilter == null,
                    onClick = { chainFilter = null },
                    label = { Text("All") }
                )
                ChainType.entries.forEach { chain ->
                    FilterChip(
                        selected = chainFilter == chain,
                        onClick = { chainFilter = chain },
                        label = { Text(chain.displayName) }
                    )
                }
            }
            val visibleAccounts = accounts.filter { account ->
                chainFilter == null || account.chainType == chainFilter
            }
            if (visibleAccounts.isEmpty()) {
                // Local capture: delegated state can't be smart-cast.
                val filter = chainFilter
                if (filter == null) {
                    EmptyState(
                        "No accounts yet",
                        "Accounts appear here once you add one for a chain."
                    )
                } else {
                    EmptyState(
                        "No ${filter.displayName} accounts",
                        "Switch the chain filter or add an account."
                    )
                }
            } else {
                val chains = ChainType.entries.filter { chain ->
                    visibleAccounts.any { it.chainType == chain }
                }
                chains.forEach { chain ->
                    SectionHeader(chain.displayName)
                    visibleAccounts.filter { it.chainType == chain }.forEach { account ->
                        WalletAccountCard(
                            account = account,
                            balance = balances[account.id]
                        )
                    }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { sendChain = chain },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Send on ${chain.displayName}"
                                }
                        ) { Text("Send") }
                        OutlinedButton(
                            onClick = { receiveChain = chain },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Receive on ${chain.displayName}"
                                }
                        ) { Text("Receive") }
                    }
                }
                SectionHeader("Manage")
                SettingsGroup {
                    SettingActionRow(
                        title = "Add account",
                        subtitle = "Derive a new account or import a private key",
                        leadingIcon = Icons.Filled.Add,
                        onClick = { addAccountOpen = true }
                    )
                    // The wallet outlives the phone only if its keys are
                    // written down somewhere else. Onboarding offers this at
                    // the reveal; this is the same export for a wallet that
                    // was created before the user thought about it.
                    SettingActionRow(
                        title = "Export wallet keys",
                        subtitle = "Recovery phrase and imported keys, sealed with a password",
                        leadingIcon = Icons.Filled.FileDownload,
                        onClick = { backupOpen = true }
                    )
                }
                SectionHeader("Networks")
                chains.forEach { chain ->
                    SettingsGroup {
                        SettingActionRow(
                            title = "${chain.displayName} network",
                            value = activeNetworks[chain]?.name ?: "—",
                            leadingIcon = Icons.Filled.Language,
                            onClick = { networkPickerChain = chain }
                        )
                    }
                }
            }
        } else {
            if (activities.isEmpty()) {
                EmptyState(
                    "No wallet activity yet",
                    "Sends and signed requests from this profile appear here."
                )
            } else {
                activities.forEach { record ->
                    WalletActivityRow(record = record, onOpenExplorer = onOpenExplorer)
                }
            }
        }
    }

    // ---------- Sheets (render points; opens happen in callbacks) ----------

    if (addAccountOpen) {
        AddAccountSheet(
            engine = engine,
            onImportKey = {
                addAccountOpen = false
                importKeyOpen = true
            },
            onMessage = { onMessage(it) },
            onDismiss = { addAccountOpen = false }
        )
    }
    if (importKeyOpen) {
        ImportKeySheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { importKeyOpen = false }
        )
    }
    networkPickerChain?.let { chain ->
        NetworkPickerSheet(
            engine = engine,
            chainType = chain,
            onAddNetwork = {
                networkPickerChain = null
                addNetworkOpen = true
            },
            onBrowseChainlist = {
                networkPickerChain = null
                chainlistOpen = true
            },
            onMessage = { onMessage(it) },
            onDismiss = { networkPickerChain = null }
        )
    }
    if (addNetworkOpen) {
        AddNetworkSheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { addNetworkOpen = false }
        )
    }
    if (chainlistOpen) {
        ChainlistSheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { chainlistOpen = false }
        )
    }
    sendChain?.let { chain ->
        SendSheet(
            engine = engine,
            chainType = chain,
            onDismiss = { sendChain = null },
            onSent = { hash, explorerUrl -> onSent(hash, explorerUrl) }
        )
    }
    receiveChain?.let { chain ->
        ReceiveSheet(
            engine = engine,
            chainType = chain,
            onCopyAddress = { address ->
                // Addresses are public — a plain clip (no sensitive flag).
                copyAddress(context, address)
                onMessage("Address copied")
            },
            onDismiss = { receiveChain = null }
        )
    }

    // Null mnemonic: the phrase comes from the vault, so this path needs the
    // unlocked session the dashboard is only rendered behind.
    WalletBackupFlow(
        open = backupOpen,
        engine = engine,
        walletLabel = walletLabel,
        profileLabel = profileName,
        mnemonicInHand = null,
        onMessage = onMessage,
        onDone = { backupOpen = false }
    )
}

/**
 * One account: label, shortened address, balance from the engine's
 * `balances` map (keyed by account id). "—" while absent; a balance error
 * renders small under the identity block.
 */
@Composable
private fun WalletAccountCard(
    account: WalletAccountRecord,
    balance: BalanceResult?
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        withGradient = false
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(extras.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = extras.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.label.ifBlank { account.chainType.displayName },
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    shortenAddress(account.address),
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
                if (balance is BalanceResult.Error) {
                    Text(
                        balance.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                when (val current = balance) {
                    is BalanceResult.Ok -> "${current.amount} ${current.symbol}"
                    else -> "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textPrimary
            )
        }
    }
}

/** One locally-recorded wallet activity row with its explorer link. */
@Composable
private fun WalletActivityRow(
    record: WalletActivityRecord,
    onOpenExplorer: (url: String) -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        withGradient = false
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(extras.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    activityKindIcon(record.kind),
                    contentDescription = null,
                    tint = extras.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.displayAmount,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                record.toAddress?.let {
                    Text(
                        "To ${shortenAddress(it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    record.networkName + " · " + formatTime(record.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                record.hash?.let {
                    Text(
                        shortenAddress(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            record.explorerUrl?.let { url ->
                IconButton(
                    onClick = { onOpenExplorer(url) },
                    modifier = Modifier.semantics {
                        contentDescription = "View on explorer"
                    }
                ) {
                    Icon(Icons.Filled.Public, contentDescription = null, tint = extras.icon)
                }
            }
        }
    }
}

/** Kind icon for an activity record. */
private fun activityKindIcon(kind: WalletActivityRecord.Kind): ImageVector = when (kind) {
    WalletActivityRecord.Kind.SEND -> Icons.Filled.ArrowUpward
    WalletActivityRecord.Kind.DAPP_SEND -> Icons.Filled.Language
    WalletActivityRecord.Kind.SIGN_MESSAGE -> Icons.Filled.Description
    WalletActivityRecord.Kind.SIGN_TRANSACTION -> Icons.Filled.Receipt
}

/** Locale-aware short date + time for activity rows. */
private fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

/**
 * Places an address on the clipboard. Addresses are PUBLIC identity
 * (unlike passwords): a plain clip is correct; the snackbar that follows
 * announces what was copied.
 */
private fun copyAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", address))
}
