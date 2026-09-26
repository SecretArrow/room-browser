package com.roombrowser.browser

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
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
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val tabManager = TabManager()
    private val dnsMonitor = DnsMonitor()
    val networkIdentity = NetworkIdentity(appState, browserRepo, graph.ipConflictDetector)

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

    /** The live WebView for the active tab (single shared engine instance). */
    var activeWebView: WebView? = null
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
        profile = graph.profileRepo.getProfile(profileId) ?: profile
        webViewClient = RoomWebViewClient(profile, graph.filterEngine, clientCallbacks)
        webChromeClient = RoomWebChromeClient(profile, chromeCallbacks)
        globalSettings = appState.globalSettingsSnapshot()
        httpClient = dnsMonitor.apply(globalSettings, profile)
        agent.updateClient(httpClient)
        agent.start()
        downloadEngine = DownloadEngine(getApplication(), browserRepo, httpClient)
        downloadEngine.ensureChannels()
        appState.setActiveProfile(profileId.value)
        graph.profileRepo.touch(profileId, System.currentTimeMillis())
        loadSiteSettingsSnapshot()

        // Restore persisted tabs (lazy: state only; WebView on demand)
        val open = browserRepo.openTabs(profileId)
        tabManager.restore(open)
        tabs = open
        activeTabId = open.firstOrNull()?.id
        if (activeTabId != null) {
            val first = open.first()
            pageState = pageState.copy(
                url = first.url,
                title = first.title,
                isPrivate = first.isPrivate,
                isHomepage = first.url == "about:home"
            )
        }
        if (open.isEmpty()) {
            openNewTab("about:home", isPrivate = false)
        }

        val profiles = graph.profileRepo.profiles()
        allProfiles = profiles
        networkIdentity.checkOnOpen(httpClient, profile, globalSettings, profiles)
        refreshStats()
        refreshBookmarks()
    }

    suspend fun refreshAllProfiles() {
        allProfiles = graph.profileRepo.profiles()
    }

    private fun observeFlows() {
        viewModelScope.launch {
            browserRepo.observeTabs(profileId).collect { list -> tabs = list }
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
            networkIdentity.netState.collect { netState = it }
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
        if (url == "about:home") {
            pageState = PageState(isPrivate = isPrivate)
            return
        }
        viewModelScope.launch {
            if (newTab || activeTabId == null) {
                openNewTab(url, isPrivate)
            } else {
                val webView = activeWebView ?: createWebView().also {
                    activeWebView = it
                    tabManager.attachWebView(activeTabId!!, it)
                }
                webView.loadUrl(url)
            }
        }
    }

    fun goBack() { activeWebView?.goBack() }
    fun goForward() { activeWebView?.goForward() }
    fun reload() { activeWebView?.reload() }
    fun stopLoading() { activeWebView?.stopLoading() }

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
            loading = url != "about:home"
        )
        if (url != "about:home") {
            val webView = activeWebView ?: createWebView().also { activeWebView = it }
            tabManager.attachWebView(entity.id, webView)
            webView.loadUrl(url)
        }
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
        val webView = activeWebView ?: createWebView().also { activeWebView = it }
        tabManager.attachWebView(id, webView)
        if (tab.url != "about:home") webView.loadUrl(tab.url)
        applyCurrentSiteSettings()
    }

    fun closeTab(id: String) {
        viewModelScope.launch {
            browserRepo.closeTab(id)
            val remaining = browserRepo.openTabs(profileId)
            tabs = remaining
            if (activeTabId == id) {
                val next = remaining.lastOrNull()
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
            }.forEach { browserRepo.closeTab(it.id) }
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
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(url, contentDisposition, mimeType)
            download(url, name, mimeType)
        }
        return webView
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

    fun download(url: String, suggestedName: String, mime: String) {
        if (::downloadEngine.isInitialized) {
            downloadEngine.enqueue(profileId, url, suggestedName, mime, null)
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
        activeWebView?.let { ProfileEngine.configure(it, profile) }
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

    fun profileSettings(): ProfileSettings = profile.settings

    // ---------- QR ----------

    fun onQrResult(text: String) {
        val (_, url) = UrlIntelligence.classify(text, profileSettings().searchEngineId)
        if (url.isNotBlank()) loadUrl(url)
    }

    // ---------- Network identity ----------

    fun suppressIpWarning() {
        viewModelScope.launch { networkIdentity.suppressCurrentIp() }
    }

    fun dismissIpWarning() { networkIdentity.dismissWarning() }

    fun recheckNetwork() {
        viewModelScope.launch {
            val profiles = graph.profileRepo.profiles()
            networkIdentity.recheck(httpClient, profile, globalSettings, profiles)
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
        runCatching { activeWebView?.destroy() }
        if (::downloadEngine.isInitialized) downloadEngine.shutdown()
        super.onCleared()
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
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
