package com.roombrowser.browser.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.PageError
import com.roombrowser.browser.StatCategories
import com.roombrowser.browser.engine.NetworkIdentity
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.qr.QrScannerActivity
import com.roombrowser.ui.common.LoadingBar
import com.roombrowser.ui.common.ProfileAvatar
import com.roombrowser.ui.common.StatTile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    data object AgentSettings : BrowserRoute
    data object AgentSessions : BrowserRoute
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
    var showIpWarning by remember { mutableStateOf(true) }

    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)?.let { text ->
            viewModel.onQrResult(text)
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.let { viewModel.onOmniBoxInput(it) }
    }

    Scaffold(
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
                onShowPageActions = { showPageActions = true },
                onShowQuickSwitcher = { showQuickSwitcher = true },
                onOpenBookmarks = { route = BrowserRoute.Bookmarks },
                onBackHome = { route = BrowserRoute.Browser }
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
                    onOpenPrivacyDashboard = { route = BrowserRoute.PrivacyDashboard },
                    onOpenDownloads = { route = BrowserRoute.Downloads },
                    onOpenHistory = { route = BrowserRoute.History },
                    onQrScan = {
                        qrLauncher.launch(Intent(activity, QrScannerActivity::class.java))
                    },
                    onVoiceInput = {
                        runCatching {
                            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
                            }
                            voiceLauncher.launch(speechIntent)
                        }.onFailure {
                            viewModel.snackbar.value = "Voice input unavailable"
                        }
                    }
                )
                BrowserRoute.Tabs -> TabGridScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Bookmarks -> BookmarksScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.History -> HistoryScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Downloads -> DownloadsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.PrivacyDashboard -> PrivacyDashboardScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Settings -> BrowserSettingsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.ProfileSettings -> ProfileSettingsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Settings })
                BrowserRoute.AgentSettings -> com.roombrowser.agent.ui.AgentSettingsScreen(
                    viewModel = viewModel,
                    onClose = { route = BrowserRoute.Browser }
                )
                BrowserRoute.AgentSessions -> com.roombrowser.agent.ui.AgentSessionsScreen(
                    viewModel = viewModel,
                    onClose = { route = BrowserRoute.Browser },
                    onOpenSession = { id ->
                        viewModel.agent.openSession(id)
                        route = BrowserRoute.Browser
                        agentPanelExpanded = true
                    }
                )
                BrowserRoute.About -> AboutScreen(onClose = { route = BrowserRoute.Settings })
            }

            // The floating AI agent panel lives above the browsing surface.
            if (route == BrowserRoute.Browser && viewModel.customView == null) {
                com.roombrowser.agent.ui.AgentPanelHost(
                    viewModel = viewModel,
                    expanded = agentPanelExpanded,
                    onExpandedChange = { agentPanelExpanded = it },
                    onOpenSettings = { route = BrowserRoute.AgentSettings },
                    onOpenSessions = { route = BrowserRoute.AgentSessions }
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
    //   sub-screen route → web history → background the app.
    // ModalBottomSheets/dialogs register their own (later = higher
    // priority) callbacks, so they close themselves before this runs.
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
            route != BrowserRoute.Browser -> route = BrowserRoute.Browser
            viewModel.pageState.canGoBack -> viewModel.goBack()
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
            onOpenAgentSettings = { route = BrowserRoute.AgentSettings; showPageActions = false },
            onOpenAgentSessions = { route = BrowserRoute.AgentSessions; showPageActions = false }
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

    viewModel.readerContent?.let { reader ->
        ReaderScreen(
            content = reader,
            onClose = { viewModel.exitReaderMode() }
        )
    }
    val netState = viewModel.netState
    if (netState is NetworkIdentity.NetState.Conflict && showIpWarning) {
        IpWarningDialog(
            conflict = netState,
            showProfileName = viewModel.globalSettings.showPreviousProfileName,
            showLastSeen = viewModel.globalSettings.showLastSeenTime,
            onContinue = { viewModel.dismissIpWarning(); showIpWarning = false },
            onSwitchProfile = {
                viewModel.dismissIpWarning()
                showIpWarning = false
                showQuickSwitcher = true
            },
            onNetworkSettings = { viewModel.dismissIpWarning(); showIpWarning = false },
            onDontWarnAgain = {
                viewModel.suppressIpWarning()
                showIpWarning = false
            },
            onRecheck = { viewModel.recheckNetwork() }
        )
    }
}
