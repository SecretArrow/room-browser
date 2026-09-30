package com.roombrowser.browser

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.BrowserAgentController
import com.roombrowser.browser.engine.DnsMonitor
import com.roombrowser.browser.engine.DownloadEngine
import com.roombrowser.browser.engine.NetworkIdentity
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.db.BookmarkEntity
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.db.HistoryEntity
import com.roombrowser.data.db.SiteSettingEntity
import com.roombrowser.data.db.TabEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.PendingNetDecision
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.credentials.CredentialDomainMatcher
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.theme.RoomThemeSpec
import com.roombrowser.domain.theme.ThemeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Current page presentation state (drives the omnibox + error pages). */
data class PageState(
    val url: String = "about:home",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val secure: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isHomepage: Boolean = true,
    val isPrivate: Boolean = false,
    val desktopMode: Boolean = false
)

sealed interface PageError {
    data class NoInternet(val url: String) : PageError
    data class Ssl(val url: String, val message: String) : PageError
    data class DnsFailure(val url: String) : PageError
    data class Generic(val url: String, val message: String?) : PageError
}

/** Navigation lifecycle events (consumed by the AI agent to await loads). */
sealed interface PageEvent {
    data class Started(val url: String, val at: Long) : PageEvent
    data class Finished(val url: String, val title: String, val at: Long) : PageEvent
}

/** Site-shield snapshot for the current page. */
data class ShieldsState(
    val host: String = "",
    val adsBlocked: Int = 0,
    val trackersBlocked: Int = 0,
    val httpsUpgrades: Int = 0,
    val shieldsDisabled: Boolean = false
)

/**
 * BrowserViewModel — owns the tab model, the active WebView, filtering,
 * downloads, DNS state and network-identity warnings for exactly ONE
 * profile (the process-bound one).
 */
class BrowserViewModel(
    application: Application,
    val profileId: ProfileId
) : AndroidViewModel(application) {

    private val graph = (application as RoomBrowserApp).graph
    private val browserRepo: BrowserRepository = graph.browserRepo
    private val appState = graph.appState

    var profile by mutableStateOf(Profile(id = profileId, name = "", createdAt = 0))
        private set

    /** This profile's ACTIVE theme — the whole engine UI re-themes from it. */
    var themeSpec by mutableStateOf(BuiltInThemes.default())
        private set

    private val tabManager = TabManager()
    private val dnsMonitor = DnsMonitor()
    val networkIdentity = NetworkIdentity(appState, browserRepo, graph.ipConflictDetector)

    /**
     * Per-WebView dApp bridges (window.ethereum & friends). Weak keys: a
     * destroyed engine's bridge must not outlive it — GC reclaims both. Used
     * to push accountsChanged/chainChanged events to every live page after
     * the user switches networks or accounts in the wallet dashboard.
     */
    private val walletBridges = java.util.WeakHashMap<WebView, com.roombrowser.browser.wallet.dapp.WalletBridge>()

    /** Last-seen wallet state, so event collectors only emit on CHANGE. */
    private var lastWalletChainIds: Map<com.roombrowser.domain.wallet.model.ChainType, String> = emptyMap()
    private var lastWalletEvmAddresses: List<String> = emptyList()

    lateinit var downloadEngine: DownloadEngine
        private set

    private var httpClient: OkHttpClient = OkHttpClient()

    var pageState by mutableStateOf(PageState())
        private set
    var pageError by mutableStateOf<PageError?>(null)
        private set
    var shieldsState by mutableStateOf(ShieldsState())
        private set
    var tabs by mutableStateOf<List<TabEntity>>(emptyList())
        private set
    var activeTabId by mutableStateOf<String?>(null)
        private set
    var bookmarks by mutableStateOf<List<BookmarkEntity>>(emptyList())
        private set
    var recentHistory by mutableStateOf<List<HistoryEntity>>(emptyList())
        private set
    var downloads by mutableStateOf<List<DownloadEntity>>(emptyList())
        private set

    /**
     * Live transfer rates, id -> bytes per second. Not persisted and not part
     * of the download record: a speed is a measurement of a moment, and a stored
     * one would be replayed to the user as if it were current.
     */
    var downloadSpeeds by mutableStateOf<Map<Long, Long>>(emptyMap())
        private set
    var globalSettings by mutableStateOf(BrowserGlobalSettings())
        private set
    var dnsState by mutableStateOf<DnsMonitor.DnsState>(DnsMonitor.DnsState.System)
        private set
    var netState by mutableStateOf<NetworkIdentity.NetState>(NetworkIdentity.NetState.Idle)
        private set
    var privacyStats by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var readerContent by mutableStateOf<ReaderContent?>(null)
        private set
    /** All profiles (for the quick switcher). */
    var allProfiles by mutableStateOf<List<Profile>>(emptyList())
        private set

    /** Transient UI messages consumed by the Compose layer. */
    val snackbar = MutableStateFlow<String?>(null)

    /** Set once the persisted-tab restore finished — incoming navigation
     *  requests (EXTRA_INITIAL_URL, QR, share-intents) wait for it so they
     *  can never be overridden by the restore picking the first tab. */
    private val restored = MutableStateFlow(false)

    /**
     * True while a profile-network warning decision is PENDING: no URL may
     * load. Armed from a fresh [NetworkIdentity.checkOnOpen] conflict or
     * from the persisted pending decision (process death can never bypass
     * it); released only by an explicit user decision in
     * NetworkWarningActivity.
     */
    private val networkGate = MutableStateFlow(false)

    /** Gate as observable state (BrowserActivity re-launches the warning
     *  activity while it stands — system Back can never dismiss it). */
    val networkGateState: StateFlow<Boolean> = networkGate

    /** Persisted conflict payload behind the pending decision — feeds the
     *  NetworkWarningActivity intent extras. */
    var pendingNetWarning: PendingNetDecision? = null
        private set

    /** One-shot counter: >0 asks BrowserScreen to (re)open the profile
     *  quick switcher ("Switch Profile" decision on the network warning). */
    val quickSwitcherSignal = MutableStateFlow(0)

    /** The live WebView of the ACTIVE tab — each tab owns its own engine
     *  (kept alive in its TabManager session while in the background).
     *  Compose state, so WebViewHost swaps the attached engine the moment
     *  this changes (never into a stale parent, never a missed reattach). */
    var activeWebView: WebView? by mutableStateOf<WebView?>(null)
        private set

    /** Latest page-load event (see [PageEvent]) — for the agent's nav waiting. */
    @Volatile
    var lastPageEvent: PageEvent? = null
        private set

    /** AI agent controller (chat + autonomous browsing) for this profile. */
    val agent: BrowserAgentController = BrowserAgentController(
        application, profileId, this, OkHttpClient()
    )

    /** Site-settings snapshot for the interception thread (thread-safe). */
    @Volatile
    private var siteSettingsSnapshot: Map<String, SiteSettingEntity> = emptyMap()

    lateinit var webViewClient: RoomWebViewClient
        private set
    lateinit var webChromeClient: RoomWebChromeClient
        private set

    /** Fullscreen media view state. */
    var customView by mutableStateOf<View?>(null)
        private set
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    /** Pending permission requests from the web engine. */
    var pendingPermission by mutableStateOf<PendingPermission?>(null)
        private set

    data class PendingPermission(
        val request: PermissionRequest,
        val kinds: Set<PermissionKind>,
        val originUrl: String
    )

    var pendingGeolocation by mutableStateOf<PendingGeolocation?>(null)
        private set

    data class PendingGeolocation(
        val origin: String?,
        val callback: android.webkit.GeolocationPermissions.Callback,
        val host: String
    )

    /** File-chooser bridge for <input type=file>. */
    var fileChooserCallback: ValueCallback<Array<Uri>>? = null
        private set

    private val clientCallbacks = object : RoomWebViewClient.Callbacks {
        override fun currentUrlHost(): String? = UrlIntelligence.hostOf(pageState.url)
        override fun siteSettingFor(host: String): SiteSettingEntity? = siteSettingsSnapshot[host]
        override fun recordBlockEvent(host: String, category: String) {
            recordBlock(host, category)
        }
        override fun onBlocked(host: String, category: FilterEngine.FilterCategory) {
            refreshShields()
        }
        override fun onHttpsUpgrade(host: String) {
            emitMessage("Upgraded to HTTPS: $host")
        }
        override fun onPopupBlocked() {
            recordBlock(UrlIntelligence.hostOf(pageState.url) ?: "", StatCategories.POPUP)
            emitMessage("Popup blocked")
        }
        override fun onSuspiciousSite(url: String, signals: List<String>) {
            emitMessage("Caution: ${signals.joinToString()}")
        }
        override fun onPageStarted(url: String) {
            lastPageEvent = PageEvent.Started(url, SystemClock.elapsedRealtime())
            pageError = null
            pageState = pageState.copy(url = url, loading = true, progress = 5, isHomepage = false)
            // A navigation retires the vault offer: the login field it was
            // collected for belonged to the outgoing document. The save
            // prompt deliberately SURVIVES navigation — a form submit is
            // itself a navigation, and the user must still be able to save.
            invalidateVaultOfferOnNavigation()
        }
        override fun onPageFinished(url: String, title: String) {
            lastPageEvent = PageEvent.Finished(url, title, SystemClock.elapsedRealtime())
            pageState = pageState.copy(
                url = url,
                title = title,
                loading = false,
                progress = 100,
                secure = url.startsWith("https://"),
                isHomepage = url == "about:home" || (url == "about:blank" && title.isBlank())
            )
            persistCurrentTab(url, title)
            if (!pageState.isPrivate) recordVisit(url, title)
            captureThumbnail()
            refreshShields()
            refreshStats()
        }
        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
            // The single source of truth for the Back / Forward buttons and
            // the system-Back web-history branch. Without this the nav bar
            // stayed grey forever (canGoBack was never reported).
            if (pageState.canGoBack != canGoBack || pageState.canGoForward != canGoForward) {
                pageState = pageState.copy(canGoBack = canGoBack, canGoForward = canGoForward)
            }
        }
        override fun onReceivedError(url: String, errorCode: Int, description: String?) {
            pageError = when (errorCode) {
                android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> PageError.DnsFailure(url)
                android.webkit.WebViewClient.ERROR_CONNECT,
                android.webkit.WebViewClient.ERROR_TIMEOUT -> PageError.NoInternet(url)
                else -> PageError.Generic(url, description)
            }
            pageState = pageState.copy(loading = false)
        }
        override fun onSslError(url: String, error: SslError) {
            pageError = PageError.Ssl(url, sslErrorText(error))
            pageState = pageState.copy(loading = false)
        }
        override fun openInNewTab(url: String, isPrivate: Boolean) {
            viewModelScope.launch { openNewTab(url, isPrivate) }
        }
    }

    private val chromeCallbacks = object : RoomWebChromeClient.ChromeCallbacks {
        override fun onProgress(progress: Int) {
            pageState = pageState.copy(progress = progress, loading = progress < 100)
        }
        override fun onTitleChanged(title: String) {
            pageState = pageState.copy(title = title)
        }
        override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
            customView = view
            customViewCallback = callback
        }
        override fun onHideCustomView() {
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
        override fun onPermissionRequest(
            request: PermissionRequest,
            kinds: Set<PermissionKind>,
            originUrl: String
        ) {
            pendingPermission = PendingPermission(request, kinds, originUrl)
        }
        override fun onGeolocationPermissions(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback
        ) {
            pendingGeolocation = PendingGeolocation(
                origin, callback,
                origin?.let { UrlIntelligence.hostOf(it) } ?: ""
            )
        }
        override fun onFileChooserIntent(
            intent: android.content.Intent,
            callback: RoomWebChromeClient.FileChooserResult
        ) {
            fileChooserLauncherIntent = intent
            fileChooserResult = callback
        }
        override fun openNewWindow(url: String) {
            viewModelScope.launch { openNewTab(url, isPrivate = false) }
        }
        override fun onPopupBlocked() {
            // handled by the WebViewClient path
        }
        override fun currentUrl(): String? = pageState.url
    }

    var fileChooserLauncherIntent: android.content.Intent? = null
        private set
    var fileChooserResult: RoomWebChromeClient.FileChooserResult? = null
        private set

    init {
        webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
        webChromeClient = RoomWebChromeClient(profile, chromeCallbacks)
        viewModelScope.launch { initialize() }
        observeFlows()
    }

    private suspend fun initialize() {
        try {
            // A pending network decision survives process death: arm the gate
            // BEFORE anything — the tab restore included — can load a URL.
            val pendingDecision = appState.pendingNetDecision()
            if (pendingDecision != null) {
                if (pendingDecision.profileId == profileId.value) {
                    pendingNetWarning = pendingDecision
                    networkGate.value = true
                } else {
                    // Stale flag from another profile's context (a switch
                    // raced the decision) — this profile is not gated by it.
                    appState.clearPendingNetDecision()
                }
            }
            profile = graph.profileRepo.getProfile(profileId) ?: profile
            themeSpec = BuiltInThemes.resolveOrDefault(profile.themeJson)
            webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
            webChromeClient = RoomWebChromeClient(profile, chromeCallbacks)
            globalSettings = appState.globalSettingsSnapshot()
            httpClient = dnsMonitor.apply(globalSettings, profile)
            agent.updateClient(httpClient)
            agent.start()
            downloadEngine = DownloadEngine(getApplication(), browserRepo, httpClient, profileId)
            downloadEngine.ensureChannels()
            // Re-queue anything the previous engine left mid-flight and restart
            // the queue — without this a download interrupted by a profile
            // switch stayed at RUNNING forever and blocked every later one.
            downloadEngine.recover()
            viewModelScope.launch {
                downloadEngine.speeds.collect { downloadSpeeds = it }
            }
            appState.setActiveProfile(profileId.value)
            graph.profileRepo.touch(profileId, System.currentTimeMillis())
            loadSiteSettingsSnapshot()

            // Wallet engine follows the SAME profile binding as everything
            // else in this process. Bind BEFORE tabs restore so an early
            // dApp call (restored page auto-connecting) meets a bound
            // engine. NOT unbound in onCleared: the ':browser' process is
            // one-profile-per-process and dies with this ViewModel's
            // activity — an unbind here could yank the session out from
            // under WalletActivity, which shares the same engine instance.
            graph.walletEngine.bind(profileId)
            observeWalletEvents()

            // Restore persisted tabs: the first tab's engine is built RIGHT HERE
            // via selectTab (lazily, with a reload) — previously the restored
            // tab showed its URL in the omnibox but never got a live engine,
            // leaving a blank surface until the next navigation.
            val open = browserRepo.openTabs(profileId)
            tabManager.restore(open)
            tabs = open
            // The ACTIVE tab is persisted per profile via last_viewed_at
            // (touched on every selectTab): restore the most-recently-viewed
            // open tab, falling back to the first one in display order.
            val resumeTab = open.maxByOrNull { it.lastViewedAt } ?: open.firstOrNull()
            activeTabId = resumeTab?.id
            if (resumeTab != null) {
                selectTab(resumeTab.id)
            }
            if (open.isEmpty()) {
                openNewTab("about:home", isPrivate = false)
            }

            val profiles = graph.profileRepo.profiles()
            allProfiles = profiles
            networkIdentity.checkOnOpen(httpClient, profile, globalSettings, profiles)
            refreshStats()
            refreshBookmarks()
        } finally {
            // Release the navigation gate even on failure — a broken restore
            // must not leave incoming URLs waiting forever.
            restored.value = true
        }
    }

    suspend fun refreshAllProfiles() {
        allProfiles = graph.profileRepo.profiles()
    }

    private fun observeFlows() {
        viewModelScope.launch {
            browserRepo.observeTabs(profileId).collect { list -> tabs = list }
        }
        viewModelScope.launch {
            // Live per-profile theming: the Theme Studio (default process)
            // writes profiles.theme_json; multi-instance invalidation delivers
            // the change here and the whole browser recomposes.
            graph.profileRepo.observeProfile(profileId).collect { p ->
                if (p != null) {
                    val settingsChanged = p.settings != profile.settings
                    profile = p
                    themeSpec = BuiltInThemes.resolveOrDefault(p.themeJson)
                    if (settingsChanged) {
                        webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
                        webChromeClient = RoomWebChromeClient(profile, chromeCallbacks)
                        reconfigureAllWebViews()
                    }
                }
            }
        }
        viewModelScope.launch {
            browserRepo.observeBookmarks(profileId).collect { bookmarks = it }
        }
        viewModelScope.launch {
            browserRepo.observeRecentHistory(profileId).collect { recentHistory = it }
        }
        viewModelScope.launch {
            browserRepo.observeDownloads(profileId).collect { downloads = it }
        }
        viewModelScope.launch {
            appState.globalSettings.collect { globalSettings = it }
        }
        viewModelScope.launch {
            networkIdentity.netState.collect { state ->
                netState = state
                if (state is NetworkIdentity.NetState.Conflict) {
                    armNetworkWarning(state)
                }
            }
        }
        viewModelScope.launch {
            dnsMonitor.state.collect { dnsState = it }
        }
    }

    // ---------- Navigation ----------

    fun onOmniBoxInput(input: String) {
        val settings = profileSettings()
        val (_, url) = UrlIntelligence.classify(input, settings.searchEngineId)
        if (url.isNotBlank()) loadUrl(url)
    }

    fun loadUrl(url: String, newTab: Boolean = false, isPrivate: Boolean = false) {
        if (url == "about:home" && !newTab) {
            // SAME-tab return to the start page. newTab=true must NEVER take
            // this branch: it used to reset the CURRENT tab's page state
            // instead of opening a tab — the "New Tab overwrote my tab" bug.
            pageState = PageState(isPrivate = isPrivate)
            return
        }
        viewModelScope.launch {
            // Serialize against the persisted-tab restore (an incoming
            // initial/QR/share URL must land AFTER the restore chose the
            // active tab, never before) and against a pending network
            // decision: while the warning stands, the suspended coroutine
            // IS the queue — it resumes and loads the moment the user
            // decides (see resolveNetworkWarning).
            restored.first { it }
            networkGate.first { !it }
            val targetId = activeTabId
            if (newTab || targetId == null) {
                openNewTab(url, isPrivate)
            } else {
                val webView = activeWebView ?: createWebView().also { engine ->
                    activeWebView = engine
                    // The engine belongs to THIS tab: its session must exist
                    // before attaching, or the engine goes untracked (leak +
                    // state loss on reselect).
                    tabs.firstOrNull { it.id == targetId }?.let { tabManager.ensureSession(it) }
                    tabManager.attachWebView(targetId, engine)
                }
                webView.loadUrl(url)
            }
        }
    }

    fun goBack() { activeWebView?.goBack() }
    fun goForward() { activeWebView?.goForward() }
    fun reload() { activeWebView?.reload() }
    fun stopLoading() { activeWebView?.stopLoading() }

    /**
     * Returns the active tab to the start page (about:home) — the "Back to
     * start page" action of the exit-confirmation dialog. The tab's WebView
     * is destroyed (not reused) so the NEXT navigation starts with a clean
     * history instead of secretly back-stepping into the abandoned page.
     */
    fun goHome() {
        destroyActiveWebView()
        pageState = PageState(isPrivate = pageState.isPrivate)
        pageError = null
        val id = activeTabId
        if (id != null) {
            viewModelScope.launch {
                val tab = browserRepo.tab(id) ?: return@launch
                browserRepo.updateTab(tab.copy(url = "about:home", title = "", lastViewedAt = System.currentTimeMillis()))
            }
        }
    }

    /**
     * Leaves fullscreen (custom-view) media mode. Called by the system Back
     * handler so the first Back press exits fullscreen video instead of
     * killing the engine activity.
     */
    fun exitFullscreen() {
        if (customView != null) {
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
    }

    // ---------- Tabs ----------

    suspend fun openNewTab(url: String = "about:home", isPrivate: Boolean = false) {
        val entity = browserRepo.newTab(profileId, url = url, title = "", isPrivate = isPrivate)
        tabs = browserRepo.openTabs(profileId)
        activeTabId = entity.id
        pageState = pageState.copy(
            isPrivate = isPrivate,
            url = url,
            title = "",
            isHomepage = url == "about:home",
            loading = url != "about:home",
            // A brand-new tab starts with a clean history — the previous
            // tab's back/forward state must NOT leak into it.
            canGoBack = false,
            canGoForward = false
        )
        if (url != "about:home") {
            // PER-TAB WebView: every tab gets its OWN engine instance so
            // web history stays tab-scoped (no cross-tab back-stepping) and
            // switching tabs never reloads a still-live page. The session is
            // created BEFORE the attach — an untracked engine was why young
            // tabs reloaded (and leaked) instead of switching cleanly.
            val webView = createWebView()
            activeWebView = webView
            tabManager.ensureSession(entity)
            tabManager.attachWebView(entity.id, webView)
            // Nothing may load while a network decision is pending; the
            // load fires the moment the user decides.
            networkGate.first { !it }
            webView.loadUrl(url)
        } else {
            // The previous tab keeps its engine alive in its OWN session;
            // the new homepage tab simply has no engine of its own.
            activeWebView = null
        }
        evictStaleWebViews(entity.id)
    }

    fun selectTab(id: String) {
        if (id == activeTabId && activeWebView != null) return
        activeTabId = id
        val tab = tabs.firstOrNull { it.id == id } ?: return
        pageState = pageState.copy(
            url = tab.url,
            title = tab.title,
            isPrivate = tab.isPrivate,
            isHomepage = tab.url == "about:home",
            loading = false,
            desktopMode = false
        )
        // Every selected tab has a session — the engine's lifetime owner.
        tabManager.ensureSession(tab)
        if (tab.url == "about:home") {
            // Homepage tabs keep no live engine: pageState above already
            // shows the start page; drop any stale engine the session may
            // still hold so switching back never resurrects a dead page.
            tabManager.get(id)?.webView?.let { destroyWebViewQuiet(it) }
            activeWebView = null
            pageState = pageState.copy(canGoBack = false, canGoForward = false)
        } else {
            // Reuse the tab's OWN WebView when it is still alive (instant,
            // state-preserving switch); lazily create one only for tabs that
            // never had — or were LRU-evicted from — a live engine, restoring
            // the saved back/forward bundle when present (entity-URL reload
            // as the fallback). While a network decision is pending, no
            // engine is created at all: nothing may load.
            val webView = engineFor(tab)
            if (webView != null) {
                activeWebView = webView
                // History state belongs to the selected tab's engine.
                pageState = pageState.copy(canGoBack = webView.canGoBack(), canGoForward = webView.canGoForward())
            } else {
                activeWebView = null
                pageState = pageState.copy(canGoBack = false, canGoForward = false)
            }
        }
        // Persist the ACTIVE tab per profile: initialize() restores the
        // open tab with the max last_viewed_at (fallback: first).
        viewModelScope.launch { browserRepo.touchTab(id, System.currentTimeMillis()) }
        applyCurrentSiteSettings()
        evictStaleWebViews(id)
    }

    /**
     * The tab's own live engine when it has one; otherwise a FRESH engine
     * created now — restored from the session's saved back/forward bundle
     * when present (LRU eviction / close), falling back to a reload of the
     * entity URL. Returns null while a network decision is pending.
     */
    private fun engineFor(tab: TabEntity): WebView? {
        val live = tabManager.get(tab.id)?.webView
        if (live != null) return live
        if (networkGate.value) return null
        val webView = createWebView()
        tabManager.ensureSession(tab)
        tabManager.attachWebView(tab.id, webView)
        val saved = tabManager.engineState(tab.id)
        var restored = false
        if (saved != null) {
            runCatching { webView.restoreState(saved) }
            restored = webView.copyBackForwardList().size > 0
        }
        if (!restored) webView.loadUrl(tab.url)
        return webView
    }

    fun closeTab(id: String) {
        viewModelScope.launch {
            // Destroy THIS tab's engine before dropping the session — its
            // back/forward state is captured first so reopening the tab
            // restores the page (and its history) instead of a bare reload.
            tabManager.get(id)?.webView?.let { webView ->
                saveEngineStateBeforeDestroy(id, webView)
                destroyWebViewQuiet(webView)
            }
            tabManager.remove(id)
            browserRepo.closeTab(id)
            val remaining = browserRepo.openTabs(profileId)
            tabs = remaining
            if (activeTabId == id) {
                activeWebView = null
                // Activate the most-recently-viewed remaining tab of THIS
                // profile (last_viewed_at), not just the last in position.
                val next = remaining.maxByOrNull { it.lastViewedAt }
                activeTabId = next?.id
                if (next != null) selectTab(next.id) else pageState = PageState()
            }
            if (remaining.none { it.isPrivate }) {
                ProfileEngine.clearSessionArtifacts(getApplication())
            }
        }
    }

    fun reopenClosedTab() {
        viewModelScope.launch {
            val closed = browserRepo.recentlyClosed(profileId).firstOrNull() ?: return@launch
            browserRepo.reopenTab(closed.id)
            tabs = browserRepo.openTabs(profileId)
            selectTab(closed.id)
        }
    }

    fun duplicateTab() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        viewModelScope.launch { openNewTab(tab.url, tab.isPrivate) }
    }

    /** onlyLeft: null = all others, true = left, false = right */
    fun closeOtherTabs(onlyLeft: Boolean? = null) {
        val id = activeTabId ?: return
        viewModelScope.launch {
            val open = browserRepo.openTabs(profileId)
            val activePos = open.firstOrNull { it.id == id }?.position ?: 0
            open.filter { tab ->
                when (onlyLeft) {
                    null -> tab.id != id
                    true -> tab.position < activePos
                    else -> tab.position > activePos
                }
            }.forEach { tab ->
                // Per-tab engines: release each closed tab's engine + session,
                // not just its database row — with the history bundle saved
                // first (a reopened tab gets its page back).
                tabManager.get(tab.id)?.webView?.let { webView ->
                    saveEngineStateBeforeDestroy(tab.id, webView)
                    destroyWebViewQuiet(webView)
                }
                tabManager.remove(tab.id)
                browserRepo.closeTab(tab.id)
            }
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun moveTab(id: String, position: Int) {
        viewModelScope.launch {
            browserRepo.moveTab(id, position)
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun groupTab(id: String, group: String?) {
        viewModelScope.launch {
            browserRepo.groupTab(id, group)
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun pinTab(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            browserRepo.pinTab(id, !tab.isPinned)
            tabs = browserRepo.openTabs(profileId)
        }
    }

    fun startPrivateTab() {
        viewModelScope.launch { openNewTab("about:home", isPrivate = true) }
    }

    private fun createWebView(): WebView {
        val webView = ProfileEngine.createWebView(getApplication(), profile)
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        // Password-manager page bridge: page JS sees window.RoomVault (the
        // document-start detection script comes from ProfileEngine.configure).
        // Every call is host-validated against THIS WebView's URL inside
        // RoomVaultBridge before it reaches [vaultCallbacks].
        webView.addJavascriptInterface(
            RoomVaultBridge(webView, vaultCallbacks),
            RoomVaultBridge.JS_INTERFACE_NAME
        )
        // Wallet dApp bridge: page JS sees window.ethereum / window.solana /
        // window.aptos / window.suiWallet / window.tronLink (the provider
        // script itself comes from ProfileEngine.configure's document-start
        // install). Every call is host-validated against THIS WebView's URL
        // inside WalletBridge before anything reaches the engine, and the
        // engine settles each request through the confirmation UI.
        val walletBridge = com.roombrowser.browser.wallet.dapp.WalletBridge(
            engineProvider = { graph.walletEngine },
            activeNetworkProvider = { chain ->
                graph.walletEngine.activeNetworks.value[chain]
            },
            webViewRef = java.lang.ref.WeakReference(webView)
        )
        walletBridges[webView] = walletBridge
        webView.addJavascriptInterface(
            walletBridge,
            com.roombrowser.browser.wallet.dapp.WalletBridge.JS_INTERFACE_NAME
        )
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(url, contentDisposition, mimeType)
            // The WebView's own UA, not a fresh one: the download must present
            // the same device identity as the page that linked to it.
            download(url, name, mimeType, userAgent)
        }
        return webView
    }

    // ---------- Per-tab WebView lifecycle ----------

    /** Destroys the ACTIVE tab's engine (fresh history on next navigation). */
    private fun destroyActiveWebView() {
        val webView = activeWebView ?: return
        activeWebView = null
        destroyWebViewQuiet(webView)
    }

    /** Detaches [webView] from its session, view tree and the renderer —
     *  never throws, safe for already-released engines. */
    private fun destroyWebViewQuiet(webView: WebView) {
        tabManager.detachWebView(webView)
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }

    /**
     * Live-engine budget: at most [MAX_LIVE_WEBVIEWS] engines stay alive at
     * once (each holds renderer memory). Oldest BACKGROUND tabs are evicted
     * first; their back/forward state is captured into the session BEFORE
     * the destroy, so re-selecting the tab restores its page and history
     * (entity-URL reload only when no bundle exists) — graceful
     * degradation, never a leak, never a lost page.
     */
    private fun evictStaleWebViews(keepId: String) {
        val live = tabManager.liveWebViewSessions()
        if (live.size <= MAX_LIVE_WEBVIEWS) return
        val excess = live.size - MAX_LIVE_WEBVIEWS
        tabManager.lruVictims(keepId).take(excess).forEach { victim ->
            victim.webView?.let { webView ->
                saveEngineStateBeforeDestroy(victim.id, webView)
                destroyWebViewQuiet(webView)
            }
        }
    }

    /**
     * Captures the engine's back/forward state (history stack, scroll and
     * form data as far as WebView allows) under the OWNING tab's id — a
     * bundle can never be restored into a different tab.
     */
    private fun saveEngineStateBeforeDestroy(id: String, webView: WebView) {
        val bundle = Bundle()
        runCatching { webView.saveState(bundle) }
        if (!bundle.isEmpty) tabManager.saveEngineState(id, bundle)
    }

    /** Destroys EVERY live engine (profile switch / final teardown). */
    fun destroyAllWebViews() {
        tabManager.liveWebViewSessions().forEach { session ->
            session.webView?.let { destroyWebViewQuiet(it) }
        }
        activeWebView = null
    }

    /** Re-applies profile settings (JS, UA, zoom, cookies…) to EVERY live
     *  engine — per-tab engines in the background must not keep stale
     *  settings until they happen to be re-selected. */
    private fun reconfigureAllWebViews() {
        tabManager.liveWebViewSessions().forEach { session ->
            session.webView?.let { ProfileEngine.configure(it, profile) }
        }
    }

    // ---------- WebView lifecycle helpers ----------

    fun captureThumbnail() {
        val view = activeWebView ?: return
        val id = activeTabId ?: return
        if (view.width == 0 || view.height == 0) return
        runCatching {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.RGB_565)
            view.draw(Canvas(bmp))
            tabManager.captureThumbnail(id, bmp)
        }
    }

    private fun persistCurrentTab(url: String, title: String) {
        val id = activeTabId ?: return
        viewModelScope.launch {
            val tab = browserRepo.tab(id) ?: return@launch
            browserRepo.updateTab(tab.copy(url = url, title = title, lastViewedAt = System.currentTimeMillis()))
        }
    }

    /** Synchronous tab persistence used by the profile-switch executor. */
    suspend fun persistActiveTabNow() {
        val id = activeTabId ?: return
        val url = pageState.url
        val title = pageState.title
        val tab = browserRepo.tab(id) ?: return
        browserRepo.updateTab(tab.copy(url = url, title = title, lastViewedAt = System.currentTimeMillis()))
    }

    /** Detach (do not destroy twice) the shared WebView — used on switch. */
    fun detachWebView() {
        activeWebView = null
    }

    /** Release in-memory caches — used by the profile-switch executor. */
    fun clearInMemoryState() {
        tabManager.clear()
        readerContent = null
        pageError = null
        pendingPermission = null
        pendingGeolocation = null
        customView = null
        siteSettingsSnapshot = emptyMap()
    }

    // ---------- Bookmarks / history ----------

    fun toggleBookmark() {
        val url = pageState.url.takeIf { it != "about:home" } ?: return
        viewModelScope.launch {
            if (browserRepo.isBookmarked(profileId, url)) {
                browserRepo.bookmarks(profileId).firstOrNull { it.url == url }?.let {
                    browserRepo.deleteBookmark(it.id)
                }
            } else {
                browserRepo.addBookmark(profileId, url, pageState.title.ifBlank { url })
            }
            refreshBookmarks()
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { browserRepo.deleteBookmark(id); refreshBookmarks() }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { browserRepo.deleteHistoryItem(id) }
    }

    fun clearHistory(since: Long) {
        viewModelScope.launch { browserRepo.clearHistory(profileId, since) }
    }

    // ---------- Downloads ----------

    fun download(url: String, suggestedName: String, mime: String, userAgent: String? = null) {
        if (::downloadEngine.isInitialized) {
            downloadEngine.enqueue(url, suggestedName, mime, userAgent)
        }
    }

    fun pauseDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.pause(id) }
    fun resumeDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.resume(id) }
    fun cancelDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.cancel(id) }
    fun retryDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.retry(id) }
    fun deleteDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.delete(id) }
    fun openDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.open(id) }
    fun shareDownload(id: Long) { if (::downloadEngine.isInitialized) downloadEngine.share(id) }

    // ---------- Shields & site settings ----------

    fun refreshShields() {
        viewModelScope.launch {
            val host = UrlIntelligence.hostOf(pageState.url) ?: return@launch
            val counts = browserRepo.statCountsForHost(profileId, host, System.currentTimeMillis() - DAY_MS)
            val setting = browserRepo.siteSetting(profileId, host)
            val map = counts.associate { it.category to it.count }
            shieldsState = ShieldsState(
                host = host,
                adsBlocked = map[StatCategories.AD] ?: 0,
                trackersBlocked = (map[StatCategories.TRACKER] ?: 0) + (map[StatCategories.CROSS_SITE_TRACKER] ?: 0),
                httpsUpgrades = map[StatCategories.HTTPS_UPGRADE] ?: 0,
                shieldsDisabled = setting?.shieldsDisabled == true
            )
        }
    }

    fun toggleShieldsForSite(disabled: Boolean) {
        val host = shieldsState.host.ifBlank { UrlIntelligence.hostOf(pageState.url) ?: "" }
        if (host.isBlank()) return
        viewModelScope.launch {
            val existing = browserRepo.siteSetting(profileId, host)
            browserRepo.upsertSiteSetting(
                (existing ?: SiteSettingEntity(profileId = profileId.value, host = host)).copy(
                    shieldsDisabled = disabled
                )
            )
            loadSiteSettingsSnapshot()
            refreshShields()
            emitMessage(if (disabled) "Shields off for $host" else "Shields on for $host")
        }
    }

    fun setSiteSetting(transform: (SiteSettingEntity) -> SiteSettingEntity) {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        viewModelScope.launch {
            val existing = browserRepo.siteSetting(profileId, host)
                ?: SiteSettingEntity(profileId = profileId.value, host = host)
            browserRepo.upsertSiteSetting(transform(existing))
            loadSiteSettingsSnapshot()
            applyCurrentSiteSettings()
            refreshShields()
        }
    }

    fun clearSiteDataForCurrentSite() {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        viewModelScope.launch {
            val webView = activeWebView ?: return@launch
            webView.clearCache(true)
            CookieManager.getInstance().removeSessionCookies(null)
            withContext(Dispatchers.IO) {
                // engine data for this profile dir is wiped; per-site granularity
                // is best-effort on WebView (documented in PROFILE_ISOLATION.md)
                ProfileEngine.clearEngineStorage(getApplication(), profileId)
            }
            emitMessage("Site data cleared for $host")
        }
    }

    fun applyCurrentSiteSettings() {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        val setting = siteSettingsSnapshot[host] ?: return
        val webView = activeWebView ?: return
        setting.jsEnabled?.let { webView.settings.javaScriptEnabled = it }
        setting.desktopMode?.let {
            ProfileEngine.applyDesktopMode(webView, profile, it)
            pageState = pageState.copy(desktopMode = it)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            !(setting.cookiesBlocked ?: profile.settings.blockThirdPartyCookies)
        )
    }

    private fun loadSiteSettingsSnapshot() {
        viewModelScope.launch {
            siteSettingsSnapshot = browserRepo.allSiteSettings(profileId).associateBy { it.host }
        }
    }

    // ---------- Privacy dashboard ----------

    fun refreshStats() {
        viewModelScope.launch {
            val counts = browserRepo.statCounts(profileId, System.currentTimeMillis() - 30 * DAY_MS)
            privacyStats = counts.associate { it.category to it.count }
        }
    }

    private fun recordBlock(host: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            browserRepo.recordBlock(profileId, host, category)
        }
    }

    private fun recordVisit(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            browserRepo.recordVisit(profileId, url, title)
        }
    }

    // ---------- Permissions (web engine) ----------

    fun grantPendingPermission() {
        val pending = pendingPermission ?: return
        val host = UrlIntelligence.hostOf(pending.originUrl) ?: ""
        viewModelScope.launch {
            val decision = browserRepo.permissionFor(profileId, host, PermissionKind.CAMERA)
            // Real grants only for explicit ALLOW decisions; ASK keeps prompting
            if (decision == com.roombrowser.domain.model.PermissionDecision.ALLOW) {
                pending.request.grant(pending.request.resources)
            } else {
                pending.request.deny()
            }
            pendingPermission = null
        }
    }

    fun denyPendingPermission() {
        pendingPermission?.request?.deny()
        pendingPermission = null
    }

    fun setPermission(kind: PermissionKind, decision: com.roombrowser.domain.model.PermissionDecision) {
        val host = UrlIntelligence.hostOf(pageState.url) ?: return
        viewModelScope.launch {
            browserRepo.setPermission(profileId, host, kind, decision)
            emitMessage("${kind.name.lowercase().replaceFirstChar { it.uppercase() }}: $decision for $host")
        }
    }

    fun respondGeolocation(allow: Boolean) {
        val pending = pendingGeolocation ?: return
        pending.callback.invoke(pending.origin, allow, false)
        pendingGeolocation = null
    }

    // ---------- Clear data ----------

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearSiteData: Boolean,
        clearDownloads: Boolean,
        clearPermissions: Boolean,
        since: Long
    ) {
        viewModelScope.launch {
            if (clearHistory) browserRepo.clearHistory(profileId, since)
            if (clearDownloads) downloads.map { it.id }.forEach { downloadEngine.delete(it) }
            if (clearPermissions) {
                browserRepo.allSiteSettings(profileId).forEach {
                    browserRepo.resetPermissions(profileId, it.host)
                }
            }
            withContext(Dispatchers.Main) {
                if (clearCache) activeWebView?.clearCache(true)
                if (clearCookies || clearSiteData) {
                    ProfileEngine.clearEngineStorage(getApplication(), profileId)
                }
            }
            emitMessage("Browsing data cleared")
            refreshStats()
        }
    }

    // ---------- Find in page ----------

    fun findInPage(query: String) {
        activeWebView?.findAllAsync(query)
    }

    fun findInPageNavigate(forward: Boolean, query: String) {
        val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
        activeWebView?.evaluateJavascript(
            "window.find('$escaped', false, ${!forward}, true)"
        ) { }
    }

    fun clearFindInPage() {
        activeWebView?.clearMatches()
    }

    // ---------- Reader mode ----------

    data class ReaderContent(
        val title: String,
        val byline: String,
        val html: String,
        val url: String
    )

    fun enterReaderMode() {
        val webView = activeWebView ?: return
        val url = pageState.url
        webView.evaluateJavascript(READER_SCRIPT) { result ->
            val json = result?.let { unescapeJson(it) }
            if (json.isNullOrBlank() || json == "null") {
                emitMessage("Reader mode: page could not be simplified")
                return@evaluateJavascript
            }
            runCatching {
                val obj = JSONObject(json)
                readerContent = ReaderContent(
                    title = obj.optString("title"),
                    byline = obj.optString("byline"),
                    html = obj.optString("html"),
                    url = url
                )
            }.onFailure { emitMessage("Reader mode: page could not be simplified") }
        }
    }

    fun exitReaderMode() { readerContent = null }

    private fun unescapeJson(raw: String): String? = runCatching {
        if (raw == "null") return null
        // evaluateJavascript returns a JSON-encoded string
        val arr = JSONArray("[$raw]")
        arr.optString(0)
    }.getOrNull()

    // ---------- Desktop mode ----------

    fun toggleDesktopMode() {
        val webView = activeWebView ?: return
        val newValue = !pageState.desktopMode
        ProfileEngine.applyDesktopMode(webView, profile, newValue)
        pageState = pageState.copy(desktopMode = newValue)
        setSiteSetting { it.copy(desktopMode = newValue) }
        webView.reload()
    }

    // ---------- Settings ----------

    suspend fun updateSettings(newSettings: ProfileSettings) {
        graph.profileManager.updateSettings(profileId, newSettings)
        profile = profile.copy(settings = newSettings)
        reconfigureAllWebViews()
        httpClient = dnsMonitor.apply(globalSettings, profile)
        agent.updateClient(httpClient)
        val profiles = graph.profileRepo.profiles()
        networkIdentity.checkOnOpen(httpClient, profile, globalSettings, profiles)
    }

    suspend fun updateGlobalSettings(newGlobal: BrowserGlobalSettings) {
        appState.saveGlobalSettings(newGlobal)
        globalSettings = newGlobal
        httpClient = dnsMonitor.apply(newGlobal, profile)
        agent.updateClient(httpClient)
    }

    /** Apply a new theme to THIS profile (Theme Studio "Apply"). */
    fun updateTheme(spec: RoomThemeSpec) {
        themeSpec = spec.sanitized()
        viewModelScope.launch {
            graph.profileRepo.updateTheme(profileId, ThemeJson.encode(themeSpec))
        }
    }

    fun profileSettings(): ProfileSettings = profile.settings

    // ---------- Device identity ----------

    /**
     * Point this profile at a device, or null to take its device away.
     *
     * Goes through the profile manager rather than [updateSettings] because
     * assigning a device also clears any UA preset (one identity, one
     * control), and the open pages then have to be reconfigured with the new
     * UA.
     */
    suspend fun setDevice(deviceId: String?) {
        graph.profileManager.setDevice(profileId, deviceId)
        // Re-read rather than patch the local copy: assigning a device also
        // clears the UA fields, and the manager is what decides that.
        profile = graph.profileRepo.getProfile(profileId) ?: profile
        reconfigureAllWebViews()
    }

    /** A device no other profile is presenting as. */
    suspend fun pickFreeDevice(): Device? =
        graph.profileManager.pickFreeDeviceId(profileId)?.let { Devices.find(it) }

    /** Device ids other profiles are already presenting as. */
    suspend fun devicesInUse(): Set<String> = graph.profileManager.devicesInUse(except = profileId)

    // ---------- QR ----------

    fun onQrResult(text: String) {
        val (_, url) = UrlIntelligence.classify(text, profileSettings().searchEngineId)
        if (url.isNotBlank()) loadUrl(url)
    }

    // ---------- Network identity / warning gate ----------

    /**
     * Arms the network-decision gate: the conflict payload is PERSISTED so
     * process death or activity recreation can never bypass the decision —
     * BrowserActivity re-launches NetworkWarningActivity while it stands.
     */
    private suspend fun armNetworkWarning(conflict: NetworkIdentity.NetState.Conflict) {
        if (networkGate.value) return
        val payload = PendingNetDecision(
            profileId = profileId.value,
            ip = conflict.currentIp,
            previousProfileName = conflict.previousProfileName,
            lastSeenAt = conflict.lastSeenAt
        )
        appState.setPendingNetDecision(payload)
        pendingNetWarning = payload
        networkGate.value = true
    }

    /**
     * Applies the user's decision from NetworkWarningActivity: clears the
     * persisted pending state and the gate, then rebuilds the active tab's
     * engine (restoring its saved state). Loads that queued while the gate
     * stood resume on their own — they were suspended on [networkGate].
     */
    fun resolveNetworkWarning(decision: NetworkWarningDecision) {
        viewModelScope.launch {
            when (decision) {
                // Persisted suppression: this IP never warns again
                // (IpConflictDetector honors suppressed IPs).
                NetworkWarningDecision.SUPPRESS -> networkIdentity.suppressCurrentIp()
                // Session-level acknowledgement (same semantics the old
                // dialog's Continue had).
                else -> networkIdentity.dismissWarning()
            }
            appState.clearPendingNetDecision()
            pendingNetWarning = null
            networkGate.value = false
            // Engines withheld while the gate stood are created now. A
            // queued navigation may already have created one (it resumed
            // the instant the gate flipped) — selectTab then early-returns.
            activeTabId?.let { selectTab(it) }
            if (decision == NetworkWarningDecision.SWITCH) {
                quickSwitcherSignal.value += 1
            }
        }
    }

    /**
     * Fallback for a pending flag whose payload can no longer be decoded:
     * the decision cannot be presented, and holding the gate would brick
     * the profile — clear the stale state instead (the normal path always
     * has a payload; this is the documented corruption valve).
     */
    fun discardUnreadableNetworkWarning() {
        viewModelScope.launch {
            appState.clearPendingNetDecision()
            pendingNetWarning = null
            networkGate.value = false
            emitMessage("Network warning state was unreadable and has been reset")
        }
    }

    /** UI message from outside the ViewModel's own flows (sheets, dialogs). */
    fun postMessage(message: String) {
        viewModelScope.launch { snackbar.emit(message) }
    }

    /** Re-check after a network change / from settings (spec 74.6). A fresh
     *  conflict re-arms the gate and re-launches the warning activity. */
    fun recheckNetwork() {
        viewModelScope.launch {
            val profiles = graph.profileRepo.profiles()
            networkIdentity.recheck(httpClient, profile, globalSettings, profiles)
        }
    }

    // ---------- Quick switcher: create profile ----------

    /** Accent colors cycled through by the quick-create dialog. */
    private val quickCreateColors = longArrayOf(
        0xFF6750A4L, 0xFF2196F3L, 0xFF00897BL, 0xFF43A047L,
        0xFFF4511EL, 0xFFD81B60L, 0xFF5C6BC0L
    )

    /** Default suggestion for the quick-create dialog: first free "Profile N". */
    fun suggestedProfileName(): String {
        val taken = allProfiles.map { it.name.lowercase() }.toSet()
        var n = allProfiles.size + 1
        while ("profile $n" in taken) n++
        return "Profile $n"
    }

    /**
     * Creates a profile from the quick switcher. AppGraph works in the
     * ':browser' process (Room multi-instance invalidation), so the row is
     * visible everywhere immediately. Throws on invalid/duplicate names —
     * the caller owns the failure UX (snackbar + stay).
     */
    suspend fun createProfileFromSwitcher(name: String): Profile {
        val color = quickCreateColors[allProfiles.size % quickCreateColors.size]
        val created = graph.profileManager.create(
            name = name,
            icon = "\uD83D\uDC64",
            colorArgb = color
        )
        allProfiles = graph.profileRepo.profiles()
        return created
    }

    // ---------- Password vault: login autofill + save prompt ----------

    /**
     * Offer-sheet payload: the logins matching the login form the user just
     * focused. Holds decrypted [SavedCredential]s (the sheet itself only ever
     * RENDERS username/title/domain — passwords are passed straight to the
     * page fill and nowhere else).
     */
    data class VaultOffer(
        val host: String,
        val credentials: List<SavedCredential>
    )

    /**
     * Save-prompt payload. The reported password exists ONLY in this state
     * (plus the sheet argument) until the user taps Save — never in a log, a
     * cache or any other field — and disappears with the prompt.
     */
    data class VaultSavePrompt(
        val host: String,
        val username: String,
        val password: String
    )

    /** Offer sheet state (rendered by BrowserScreen → VaultOfferSheet). */
    var vaultOffer by mutableStateOf<VaultOffer?>(null)
        private set

    /** Save-prompt sheet state (rendered by BrowserScreen → VaultSaveSheet). */
    var vaultSavePrompt by mutableStateOf<VaultSavePrompt?>(null)
        private set

    /**
     * The process-wide wallet engine (bound to this profile in
     * [initialize]). Exposed so BrowserScreen can render the dApp
     * confirmation sheets for [com.roombrowser.browser.wallet.WalletEngineApi.pendingRequests]
     * — the engine IS the state holder; there is no parallel copy here.
     */
    val walletEngine: com.roombrowser.browser.wallet.WalletEngineApi
        get() = graph.walletEngine

    /**
     * Push wallet state changes to every live page: chainChanged (EVM hex
     * chainId) when the active network per chain changes, accountsChanged
     * when the EVM account set changes. Collectors are cancel-and-replace
     * (one generation alive — the observeTabCounts lesson) and only emit on
     * an actual CHANGE, so re-binds never spam pages with synthetic events.
     */
    private var walletEventsJob: kotlinx.coroutines.Job? = null

    private fun observeWalletEvents() {
        walletEventsJob?.cancel()
        lastWalletChainIds = emptyMap()
        lastWalletEvmAddresses = emptyList()
        walletEventsJob = viewModelScope.launch {
            launch {
                walletEngine.activeNetworks.collect { active ->
                    if (active != lastWalletChainIds) {
                        val previous = lastWalletChainIds
                        lastWalletChainIds = active
                        // The FIRST population stays silent — pages ask for
                        // the current chain themselves via eth_chainId. Any
                        // LATER change emits chainChanged (EVM: hex chainId).
                        if (previous.isNotEmpty()) {
                            active.forEach { (chain, network) ->
                                if (previous[chain] != network.chainId &&
                                    chain == com.roombrowser.domain.wallet.model.ChainType.EVM
                                ) {
                                    val hex = "0x" + network.chainId.toLongOrNull(10)
                                        ?.toString(16)?.lowercase() ?: network.chainId
                                    emitWalletEvent("chainChanged", "\"$hex\"")
                                }
                            }
                        }
                    }
                }
            }
            launch {
                walletEngine.accounts.collect { accounts ->
                    val evm = accounts
                        .filter { it.chainType == com.roombrowser.domain.wallet.model.ChainType.EVM }
                        .map { it.address }
                    if (evm != lastWalletEvmAddresses) {
                        val previous = lastWalletEvmAddresses
                        lastWalletEvmAddresses = evm
                        // The INITIAL population stays silent — pages ask for
                        // accounts themselves via eth_accounts. Any LATER
                        // change (add/remove account) emits to every page.
                        if (previous.isNotEmpty()) {
                            val jsonArray = evm.joinToString(
                                prefix = "[", separator = ",", postfix = "]"
                            ) { address -> "\"$address\"" }
                            emitWalletEvent("accountsChanged", jsonArray)
                        }
                    }
                }
            }
        }
    }

    /** Relay one EIP-1193 event to every live WebView's wallet bridge. */
    private fun emitWalletEvent(event: String, payloadJson: String) {
        val bridges = walletBridges.values.toList()
        bridges.forEach { it.emitEvent(event, payloadJson) }
    }

    /**
     * Hosts whose offer the user dismissed for the CURRENT page — a
     * re-focused login field must not re-summon a sheet the user just closed.
     * Cleared on every navigation (a fresh page is a fresh question).
     */
    private val dismissedOfferHosts = mutableSetOf<String>()

    /** Wired into every engine in [createWebView] (via RoomVaultBridge). */
    private val vaultCallbacks = object : RoomVaultBridge.Callbacks {
        override fun onCredentialsRequested(webView: WebView, host: String, href: String) {
            handleVaultRequest(webView, host)
        }

        override fun onCredentialReported(
            webView: WebView,
            host: String,
            username: String,
            password: String
        ) {
            handleVaultReport(webView, host, username, password)
        }
    }

    /**
     * The user focused a password field on [webView]. Offers appear ONLY for
     * an already-unlocked session: a locked vault answers with silence —
     * focusing a login field must NEVER trigger a biometric prompt. Only the
     * ACTIVE tab's engine may surface UI (a background tab's page cannot).
     */
    private fun handleVaultRequest(webView: WebView, host: String) {
        if (webView !== activeWebView) return
        if (!graph.credentialRepo.isUnlocked.value) return
        if (vaultOffer != null) return
        if (host in dismissedOfferHosts) return
        viewModelScope.launch {
            val matches = runCatching {
                graph.credentialRepo.findForDomain(profileId, host)
            }.getOrNull() ?: return@launch
            // The active tab may have changed while the lookup ran.
            if (webView !== activeWebView || matches.isEmpty()) return@launch
            vaultOffer = VaultOffer(host, matches)
        }
    }

    /**
     * A login form submitted on the active page. POLICY: the save prompt is
     * allowed to appear while the vault is LOCKED (first-run users have
     * nothing saved yet — the prompt is the discovery path) and the
     * biometric gate runs only when the user actually taps Save. Private
     * tabs persist nothing, so they never prompt. Duplicate suppression
     * (same profile+domain+username AND same password) needs decrypted rows
     * and therefore only runs while unlocked; locked reports skip the
     * comparison and prompt (the check is re-run at Save time).
     */
    private fun handleVaultReport(
        webView: WebView,
        host: String,
        username: String,
        password: String
    ) {
        if (webView !== activeWebView) return
        if (password.isEmpty()) return
        if (pageState.isPrivate) return
        viewModelScope.launch {
            if (graph.credentialRepo.isUnlocked.value) {
                val duplicate = runCatching {
                    graph.credentialRepo.findForDomain(profileId, host)
                }.getOrNull()?.any { it.username == username && it.password == password } == true
                if (duplicate) return@launch
            }
            if (webView !== activeWebView) return@launch
            vaultSavePrompt = VaultSavePrompt(host, username, password)
        }
    }

    /** Offer sheet dismissed (outside tap / Back): same page stays quiet. */
    fun dismissVaultOffer() {
        vaultOffer?.let { dismissedOfferHosts.add(it.host) }
        vaultOffer = null
    }

    /** "Not now" on the save prompt: forget the reported login entirely. */
    fun dismissVaultSavePrompt() {
        vaultSavePrompt = null
    }

    /** Navigation hook (onPageStarted): retires the offer + its suppressions. */
    private fun invalidateVaultOfferOnNavigation() {
        vaultOffer = null
        dismissedOfferHosts.clear()
    }

    /**
     * Fills the picked login into the page that requested it. SECURITY: the
     * active engine's CURRENT url is re-validated against the offer's host
     * right before the values are handed to JS — credentials only ever enter
     * the page that asked for them (the bridge validated the same host family
     * when the request arrived; this closes the focus→pick window against a
     * navigation or tab switch in between). The payload is JSON-quoted —
     * values are never naively interpolated into a JS string.
     */
    fun fillVaultCredential(credential: SavedCredential) {
        val offer = vaultOffer ?: return
        val webView = activeWebView
        val currentHost = webView?.url?.let { UrlIntelligence.hostOf(it) }
        vaultOffer = null
        if (webView == null || currentHost == null) return
        if (!CredentialDomainMatcher.matches(offer.host, currentHost)) return
        val payload = JSONObject()
            .put("u", credential.username)
            .put("p", credential.password)
            .toString()
        webView.evaluateJavascript(
            "window.__roomVaultFill && window.__roomVaultFill(${jsStringLiteral(payload)})",
            null
        )
    }

    /** [value] as a double-quoted JS string literal (JSON quoting rules). */
    private fun jsStringLiteral(value: String): String =
        JSONObject.quote(value)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    /**
     * "Save" on the save-prompt sheet.
     *
     * [gateProvider] runs the biometric / device-credential gate and must
     * invoke exactly one callback — the gate is UI-owned because the
     * ViewModel has no Activity (BrowserScreen lends it its own). On gate
     * success this process's vault is unlocked for the session (repo
     * contract: the gate must have genuinely passed before unlock()) and the
     * login is stored; on failure the user sees "Vault locked — not saved"
     * and nothing is written. The duplicate check is re-run here because the
     * prompt-time check was skipped while locked.
     */
    fun savePromptedLogin(
        gateProvider: (onSuccess: () -> Unit, onFailure: () -> Unit) -> Unit
    ) {
        val prompt = vaultSavePrompt ?: return
        // The sheet is gone the moment Save is tapped — a cancelled biometric
        // prompt must not resurrect it.
        vaultSavePrompt = null
        val commit: () -> Unit = {
            if (!graph.credentialRepo.isUnlocked.value) {
                graph.credentialRepo.unlock()
            }
            viewModelScope.launch {
                val duplicate = runCatching {
                    graph.credentialRepo.findForDomain(profileId, prompt.host)
                }.getOrNull()?.any {
                    it.username == prompt.username && it.password == prompt.password
                } == true
                if (duplicate) {
                    emitMessage("Login already saved")
                    return@launch
                }
                runCatching {
                    graph.credentialRepo.save(
                        profileId = profileId,
                        domain = prompt.host,
                        username = prompt.username,
                        password = prompt.password,
                        title = null
                    )
                }.onSuccess {
                    emitMessage("Login saved for ${prompt.host}")
                }.onFailure {
                    emitMessage("Vault locked — not saved")
                }
            }
        }
        if (graph.credentialRepo.isUnlocked.value) {
            commit()
        } else {
            // Positional call: a function-type value cannot take named
            // arguments (K2 prohibits them for function types).
            gateProvider(
                commit,
                { emitMessage("Vault locked — not saved") }
            )
        }
    }

    // ---------- Search suggestions ----------

    suspend fun fetchSuggestions(query: String): List<String> {
        if (!profileSettings().searchSuggestions || query.isBlank()) return emptyList()
        val engine = com.roombrowser.domain.model.SearchEngines.byId(profileSettings().searchEngineId)
        val template = engine.suggestionUrlTemplate ?: return emptyList()
        val url = template.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
        return withContext(Dispatchers.IO) {
            runCatching {
                httpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    parseSuggestions(body)
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun parseSuggestions(body: String): List<String> = runCatching {
        when {
            body.trimStart().startsWith("[") -> {
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    (arr.opt(i) as? String) ?: (arr.opt(i) as? JSONArray)?.optString(0)
                }.filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun refreshBookmarks() {
        viewModelScope.launch { bookmarks = browserRepo.bookmarks(profileId) }
    }

    private fun emitMessage(message: String) {
        viewModelScope.launch { snackbar.emit(message) }
    }

    private fun sslErrorText(error: SslError): String = when (error.primaryError) {
        SslError.SSL_EXPIRED -> "The certificate has expired."
        SslError.SSL_IDMISMATCH -> "The certificate hostname does not match."
        SslError.SSL_NOTYETVALID -> "The certificate is not yet valid."
        SslError.SSL_UNTRUSTED -> "The certificate authority is not trusted."
        SslError.SSL_INVALID -> "A generic certificate error occurred."
        SslError.SSL_DATE_INVALID -> "The certificate date is invalid."
        else -> "The site's certificate could not be verified."
    }

    override fun onCleared() {
        runCatching { agent.shutdown() }
        // Per-tab engines must not outlive the ViewModel's scope.
        runCatching { destroyAllWebViews() }
        if (::downloadEngine.isInitialized) downloadEngine.shutdown()
        super.onCleared()
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Live per-tab engine budget — beyond this, oldest background
         *  tabs lose their engine (rebuilt lazily on re-selection). */
        const val MAX_LIVE_WEBVIEWS = 4

        const val READER_SCRIPT = """
            (function(){
              var candidates = document.querySelectorAll('article, main, [role=main], .post, #content, .content');
              var best = null; var bestScore = -1;
              candidates.forEach(function(el){
                var text = el.innerText || '';
                var score = text.length + (el.querySelector('p') ? text.length : 0);
                if (score > bestScore && text.length > 250) { best = el; bestScore = score; }
              });
              if (!best) best = document.body;
              var clone = best.cloneNode(true);
              clone.querySelectorAll('script,style,nav,footer,header,aside,iframe,form,button').forEach(function(n){n.remove();});
              return JSON.stringify({
                title: document.title,
                byline: (document.querySelector('[rel=author],.author,.byline')||{}).innerText || '',
                html: clone.innerHTML
              });
            })()
        """
    }
}
