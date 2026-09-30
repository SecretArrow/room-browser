package com.roombrowser.browser.ui

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.LocalRoomExtras
import kotlinx.coroutines.launch

/** Screen routing inside the browser activity. */
sealed interface BrowserRoute {
    data object Browser : BrowserRoute
    data object Tabs : BrowserRoute
    data object Bookmarks : BrowserRoute
    data object History : BrowserRoute
    data object Downloads : BrowserRoute
    data object PrivacyDashboard : BrowserRoute
    data object Settings : BrowserRoute
    data object ProfileSettings : BrowserRoute
    data object About : BrowserRoute
}

/**
 * The browser shell: omnibox, toolbar, WebView host, homepage, error
 * pages, IP conflict warning, find-in-page and reader mode.
 */
@Composable
fun BrowserScreen(
    activity: Activity,
    viewModel: BrowserViewModel,
    initialUrl: String?,
    onSwitchProfile: (targetProfileId: ProfileId) -> Unit
) {
    var route by remember { mutableStateOf<BrowserRoute>(BrowserRoute.Browser) }
    var agentPanelExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message by viewModel.snackbar.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.snackbar.value = null
        }
    }

    val agentMessage by viewModel.agent.messages.collectAsState()
    LaunchedEffect(agentMessage) {
        agentMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.agent.messages.value = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAllProfiles()
        if (initialUrl != null) viewModel.loadUrl(initialUrl, newTab = true)
    }

    var showPageActions by remember { mutableStateOf(false) }
    var showQuickSwitcher by remember { mutableStateOf(false) }
    var showShields by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    // System-Back exit confirmation — a page with no back history left must
    // NEVER leave the app without an explicit user decision (user mandate:
    // "kalau yang dibuka bukan url dasar jangan keluarkan app, cukup tampilkan
    // konfirmasi dulu").
    var showExitConfirm by remember { mutableStateOf(false) }

    // "Switch Profile" decision from the network warning activity: re-open
    // the quick switcher once the engine resumes (counter, so every new
    // request re-fires the LaunchedEffect).
    val switcherSignal by viewModel.quickSwitcherSignal.collectAsState()
    LaunchedEffect(switcherSignal) {
        if (switcherSignal > 0) showQuickSwitcher = true
    }

    // AI settings & chat history live in their OWN activities (default
    // process) — the browser surface simply launches them and, for chat
    // history, receives the picked session back as a result.
    val agentSessionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val sessionId = result.data?.getLongExtra(
            com.roombrowser.agent.ui.AgentSessionsActivity.EXTRA_SESSION_ID, -1L
        ) ?: -1L
        if (sessionId > 0) {
            viewModel.agent.openSession(sessionId)
            agentPanelExpanded = true
        }
    }

    fun launchAgentSettings() {
        com.roombrowser.agent.ui.AgentSettingsActivity.launch(
            activity, viewModel.profileId.value
        )
    }

    fun launchAgentSessions() {
        agentSessionsLauncher.launch(
            Intent(activity, com.roombrowser.agent.ui.AgentSessionsActivity::class.java).apply {
                putExtra(
                    com.roombrowser.agent.ui.AgentSessionsActivity.EXTRA_PROFILE_ID,
                    viewModel.profileId.value
                )
            }
        )
    }

    Scaffold(
        // Whole-scaffold background follows the profile theme — the glass
        // bottom bar and every routed screen sit on a cohesive canvas.
        containerColor = LocalRoomExtras.current.background,
        // Keyboard: same semantics as the previous adjustResize window — the
        // whole browser UI (toolbar included) rides above the IME. IME insets
        // are consumed here so the agent composer's imePadding() never
        // double-applies.
        modifier = Modifier.imePadding(),
        // Insets are applied EXPLICITLY (bottomBar + content Box below) —
        // deterministic, no double-counting, on every API level 28..35+.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BrowserBottomBar(
                // THE fix for the 3-button collision: the toolbar is padded
                // above the system Back / Home / Recents bar (plus display
                // cutouts in landscape).
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                ),
                viewModel = viewModel,
                onOpenTabs = { route = BrowserRoute.Tabs },
                onShowPageActions = { showPageActions = true }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Below the status bar / beside cutouts: omnibox, tab strip
                // and every routed screen start INSIDE the safe area.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
        ) {
            when (route) {
                BrowserRoute.Browser -> BrowserContent(
                    viewModel = viewModel,
                    onShowShields = { showShields = true },
                    onOpenPrivacyDashboard = { route = BrowserRoute.PrivacyDashboard }
                )
                BrowserRoute.Tabs -> TabGridScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Bookmarks -> BookmarksScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.History -> HistoryScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Downloads -> DownloadsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.PrivacyDashboard -> PrivacyDashboardScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Settings -> BrowserSettingsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.ProfileSettings -> ProfileSettingsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Settings })
                BrowserRoute.About -> AboutScreen(onClose = { route = BrowserRoute.Settings })
            }

            // The floating AI agent panel lives above the browsing surface.
            if (route == BrowserRoute.Browser && viewModel.customView == null) {
                com.roombrowser.agent.ui.AgentPanelHost(
                    viewModel = viewModel,
                    expanded = agentPanelExpanded,
                    onExpandedChange = { agentPanelExpanded = it },
                    onOpenSettings = { launchAgentSettings() },
                    onOpenSessions = { launchAgentSessions() }
                )
            }

            // Fullscreen media view
            viewModel.customView?.let { view ->
                AndroidView(
                    factory = { context ->
                        FrameLayout(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            addView(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // System Back button — a browser must NEVER die on the first press.
    // Priority (most specific first):
    //   fullscreen video → reader mode → find-in-page → agent panel →
    //   sub-screen route → web history → exit confirmation → background.
    // ModalBottomSheets/dialogs register their own (later = higher
    // priority) callbacks, so they close themselves before this runs.
    // The web-history branch actually WORKS now: canGoBack is live-tracked
    // via doUpdateVisitedHistory, so Back walks pages like a real browser.
    // When a non-home page has no history left, an explicit confirmation
    // (Exit / Back to start page / Cancel) stands between the user and
    // leaving the app. The homepage keeps the instant-background contract
    // (E2EBrowseFlowTest.system_back_backgrounds_app_without_killing_engine).
    // ------------------------------------------------------------------
    BackHandler {
        when {
            viewModel.customView != null -> viewModel.exitFullscreen()
            viewModel.readerContent != null -> viewModel.exitReaderMode()
            showFindBar -> {
                viewModel.clearFindInPage()
                showFindBar = false
            }
            agentPanelExpanded -> agentPanelExpanded = false
            // Sub-screens whose in-app back returns to their PARENT screen
            // (ProfileSettings / About open from the Settings screen) must
            // land in the SAME place from the system Back gesture.
            route == BrowserRoute.ProfileSettings || route == BrowserRoute.About -> route = BrowserRoute.Settings
            route != BrowserRoute.Browser -> route = BrowserRoute.Browser
            viewModel.pageState.canGoBack -> viewModel.goBack()
            !viewModel.pageState.isHomepage -> showExitConfirm = true
            else -> activity.moveTaskToBack(true)   // keep engine + tabs alive
        }
    }

    // ---------- Sheets & dialogs ----------

    if (showPageActions) {
        PageActionsSheet(
            viewModel = viewModel,
            onDismiss = { showPageActions = false },
            onShowFindBar = { showFindBar = true; showPageActions = false },
            onTranslate = { showTranslateDialog = true; showPageActions = false },
            onShowQr = { showQrDialog = true; showPageActions = false },
            onOpenSettings = { route = BrowserRoute.Settings; showPageActions = false },
            onOpenProfileSettings = { route = BrowserRoute.ProfileSettings; showPageActions = false },
            onOpenAbout = { route = BrowserRoute.About; showPageActions = false },
            onOpenAgent = { agentPanelExpanded = true; showPageActions = false },
            onOpenAgentSettings = { launchAgentSettings(); showPageActions = false },
            onOpenAgentSessions = { launchAgentSessions(); showPageActions = false },
            onOpenBookmarks = { route = BrowserRoute.Bookmarks; showPageActions = false },
            onOpenDownloads = { route = BrowserRoute.Downloads; showPageActions = false },
            onOpenHistory = { route = BrowserRoute.History; showPageActions = false },
            onShowQuickSwitcher = { showQuickSwitcher = true; showPageActions = false },
            onShowShields = { showShields = true; showPageActions = false }
        )
    }

    if (showQuickSwitcher) {
        ProfileQuickSwitcherSheet(
            viewModel = viewModel,
            onDismiss = { showQuickSwitcher = false },
            onSwitch = { target ->
                showQuickSwitcher = false
                onSwitchProfile(target)
            }
        )
    }

    if (showShields) {
        ShieldsSheet(
            viewModel = viewModel,
            onDismiss = { showShields = false }
        )
    }

    if (showFindBar) {
        FindInPageBar(
            onFind = { viewModel.findInPage(it) },
            onNext = { viewModel.findInPageNavigate(true, it) },
            onPrevious = { viewModel.findInPageNavigate(false, it) },
            onClose = {
                viewModel.clearFindInPage()
                showFindBar = false
            }
        )
    }

    if (showTranslateDialog) {
        TranslateDialog(
            viewModel = viewModel,
            onDismiss = { showTranslateDialog = false }
        )
    }

    if (showQrDialog) {
        QrShareDialog(
            content = viewModel.pageState.url,
            onDismiss = { showQrDialog = false }
        )
    }

    // ---------- Password vault sheets (render points only) ----------
    // The offer appears when the user focuses a login form on a page whose
    // host family has saved logins (vault unlocked for the session). The
    // save prompt appears after a login form submits — including while the
    // vault is LOCKED; the biometric gate for "Save" needs an Activity,
    // which the ViewModel does not have, so the sheet's Save action borrows
    // this one through a callback (never started from recomposition).
    viewModel.vaultOffer?.let { offer ->
        VaultOfferSheet(
            host = offer.host,
            credentials = offer.credentials,
            onPick = { viewModel.fillVaultCredential(it) },
            onDismiss = { viewModel.dismissVaultOffer() }
        )
    }

    viewModel.vaultSavePrompt?.let { prompt ->
        VaultSaveSheet(
            host = prompt.host,
            username = prompt.username,
            onSave = {
                viewModel.savePromptedLogin { onSuccess, onFailure ->
                    val fragmentActivity = activity as? FragmentActivity
                    if (fragmentActivity != null) {
                        BiometricGate.unlock(fragmentActivity, "Password vault", onSuccess, onFailure)
                    } else {
                        // No fragment host = no biometric prompt = no unlock.
                        onFailure()
                    }
                }
            },
            onNotNow = { viewModel.dismissVaultSavePrompt() }
        )
    }

    // ---------- Wallet dApp confirmation sheet (render point only) ------
    // The engine (bound to this profile, shared with the wallet dashboard)
    // owns the pending-request queue; the FIRST pending request renders as
    // a confirmation sheet. The sheet itself settles the request through
    // engine.decideDappRequest (Approve or Reject — outside-tap/back =
    // reject), after which it leaves composition on its own; onDismiss is
    // deliberately empty for that reason. The queue is read through the
    // ViewModel's MIRROR flow (walletRequests) — reading the engine's own
    // flow here would lazy-load the crypto stack during first composition
    // and stall the first frames; the engine reference is only resolved
    // inside the non-empty branch, i.e. strictly after the deferred bind.
    val pendingWalletRequests by viewModel.walletRequests.collectAsState()
    pendingWalletRequests.firstOrNull()?.let { request ->
        WalletDappRequestSheet(
            request = request,
            engine = viewModel.walletEngine,
            onDismiss = { }
        )
    }

    // ---------- System-Back exit confirmation (non-home, no history) ------
    // Fired by the BackHandler's `!isHomepage` branch: the current page has
    // no back history left, so leaving the app requires an EXPLICIT choice.
    // "Exit" keeps the engine + tabs alive via moveTaskToBack (same contract
    // as the homepage back), "Back to start page" returns to about:home with
    // a clean per-tab history, "Cancel" (or outside-tap) simply stays.
    if (showExitConfirm) {
        val page = viewModel.pageState
        val host = UrlIntelligence.hostOf(page.url)?.let { "You are viewing $it." } ?: ""
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit Room Browser?") },
            text = {
                Text(
                    (if (host.isBlank()) "" else "$host ") +
                        "This page has no back history left. Your tabs and the engine stay alive in the background."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        activity.moveTaskToBack(true)
                    }
                ) { Text("Exit") }
            },
            dismissButton = {
                Column {
                    TextButton(
                        onClick = {
                            showExitConfirm = false
                            viewModel.goHome()
                        }
                    ) { Text("Back to start page") }
                    TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }

    viewModel.readerContent?.let { reader ->
        ReaderScreen(
            content = reader,
            onClose = { viewModel.exitReaderMode() }
        )
    }
    // NOTE: the profile network warning is NOT a dialog anymore — a pending
    // decision launches the full-screen NetworkWarningActivity (BrowserActivity
    // owns the launch loop; while the gate stands no URL can load).
}
