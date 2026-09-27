package com.roombrowser.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.roombrowser.data.db.SiteSettingEntity
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.HttpsUpgradeFallbackPolicy
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.Profile

/**
 * Privacy WebView client — request interception (ad/tracker/malicious
 * blocking), HTTPS upgrades, popup/redirect control and honest error
 * reporting. Blocking statistics come exclusively from REAL events.
 *
 * Site-settings lookups use a thread-safe SNAPSHOT provided by the host
 * (shouldInterceptRequest runs on a background thread; Room cannot be
 * queried synchronously there).
 */
class RoomWebViewClient(
    private val profile: Profile,
    private val filterEngine: FilterEngine,
    private val callbacks: Callbacks
) : WebViewClient() {

    /**
     * HTTPS-First fallback bookkeeping: upgraded navigation -> original http
     * URL. When the https version fails (connect/timeout/SSL) the original
     * URL is retried ONCE automatically — upgrades never produce dead error
     * pages on http-only sites.
     */
    private val upgradeFallbacks = HttpsUpgradeFallbackPolicy.Registry()

    interface Callbacks {
        /** Host of the currently displayed page (or null). */
        fun currentUrlHost(): String?
        /** Snapshot of site settings for the current page's host (thread-safe). */
        fun siteSettingFor(host: String): SiteSettingEntity?
        /** Record a real blocking event (Room insert, fire-and-forget). */
        fun recordBlockEvent(host: String, category: String)
        fun onBlocked(host: String, category: FilterEngine.FilterCategory)
        fun onHttpsUpgrade(host: String)
        fun onPopupBlocked()
        fun onSuspiciousSite(url: String, signals: List<String>)
        fun onPageStarted(url: String)
        fun onPageFinished(url: String, title: String)
        fun onReceivedError(url: String, errorCode: Int, description: String?)
        fun onSslError(url: String, error: SslError)
        fun openInNewTab(url: String, isPrivate: Boolean)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url
        val host = url.host?.lowercase() ?: return null
        val pageHost = callbacks.currentUrlHost()

        val siteOverride = callbacks.siteSettingFor(host)
        val shieldsDisabled = siteOverride?.shieldsDisabled == true
        val s = profile.settings
        val decision = filterEngine.decide(
            requestHost = host,
            pageHost = pageHost,
            path = url.path ?: "/",
            blockAds = s.blockAds && !shieldsDisabled,
            blockTrackers = s.blockTrackers && !shieldsDisabled,
            blockCrossSite = s.blockCrossSiteTrackers && !shieldsDisabled,
            blockMalicious = s.blockMalicious
        )
        if (decision is FilterEngine.Decision.Blocked) {
            callbacks.onBlocked(host, decision.category)
            callbacks.recordBlockEvent(host, StatCategories.from(decision.category))
            return blockedResponse()
        }
        return null
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val url = request.url.toString()
        val host = request.url.host?.lowercase() ?: ""

        // Malicious-site protection for main-frame navigations
        if (profile.settings.blockMalicious && host.isNotBlank()) {
            val category = filterEngine.blockedCategory(host)
            if (category == FilterEngine.FilterCategory.MALICIOUS) {
                callbacks.onBlocked(host, category)
                callbacks.recordBlockEvent(host, StatCategories.from(category))
                return true
            }
            val signals = filterEngine.suspiciousSignals(url)
            if (signals.isNotEmpty()) {
                callbacks.onSuspiciousSite(url, signals)
            }
        }

        // HTTPS upgrade for main-frame http navigations (HTTPS-First with
        // automatic http fallback — see upgradeFallbacks).
        if (profile.settings.httpsUpgrade && url.startsWith("http://") && host.isNotBlank()) {
            val upgraded = UrlIntelligence.upgrade(url)
            if (upgraded.upgradedToHttps) {
                upgradeFallbacks.register(upgraded.url, url)
                callbacks.onHttpsUpgrade(host)
                callbacks.recordBlockEvent(host, StatCategories.HTTPS_UPGRADE)
                view.post { view.loadUrl(upgraded.url) }
                return true
            }
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        CookieManager.getInstance().flush()
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        CookieManager.getInstance().flush()
        callbacks.onPageFinished(url, view.title ?: url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            val failedUrl = request.url.toString()
            // HTTPS-First fallback: our own upgrade failed → retry the
            // original http URL once, silently (no error page flash).
            val original = upgradeFallbacks.consume(failedUrl)
            if (original != null && HttpsUpgradeFallbackPolicy.isRecoverable(error.errorCode)) {
                view.post { view.loadUrl(original) }
                return
            }
            callbacks.onReceivedError(
                failedUrl,
                error.errorCode,
                error.description?.toString()
            )
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // HTTPS-First fallback: an https endpoint without a valid TLS setup
        // behind one of OUR upgrades → retry the original http URL once.
        val original = upgradeFallbacks.consume(view.url ?: "")
        if (original != null) {
            handler.cancel()
            view.post { view.loadUrl(original) }
            return
        }
        // Never proceed automatically — the user decides via the error page.
        handler.cancel()
        callbacks.onSslError(view.url ?: "", error)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        handler.cancel()
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
}

/** Category names persisted for stats (kept in one place to avoid typos). */
object StatCategories {
    const val AD = "AD"
    const val TRACKER = "TRACKER"
    const val CROSS_SITE_TRACKER = "CROSS_SITE_TRACKER"
    const val MALICIOUS = "MALICIOUS"
    const val POPUP = "POPUP"
    const val HTTPS_UPGRADE = "HTTPS_UPGRADE"
    const val COOKIE_BLOCKED = "COOKIE_BLOCKED"
    const val SCRIPT_BLOCKED = "SCRIPT_BLOCKED"

    fun from(category: FilterEngine.FilterCategory): String = when (category) {
        FilterEngine.FilterCategory.AD -> AD
        FilterEngine.FilterCategory.TRACKER -> TRACKER
        FilterEngine.FilterCategory.CROSS_SITE_TRACKER -> CROSS_SITE_TRACKER
        FilterEngine.FilterCategory.MALICIOUS -> MALICIOUS
        FilterEngine.FilterCategory.POPUP -> POPUP
    }
}

/**
 * Chrome client: windows (popups), permissions, fullscreen, file chooser.
 */
class RoomWebChromeClient(
    private val profile: Profile,
    private val callbacks: ChromeCallbacks
) : WebChromeClient() {

    interface ChromeCallbacks {
        fun onProgress(progress: Int)
        fun onTitleChanged(title: String)
        fun onShowCustomView(view: View, callback: CustomViewCallback)
        fun onHideCustomView()
        fun onPermissionRequest(
            request: PermissionRequest,
            kinds: Set<PermissionKind>,
            originUrl: String
        )
        fun onGeolocationPermissions(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback
        )
        fun onFileChooserIntent(intent: Intent, callback: FileChooserResult)
        fun openNewWindow(url: String)
        fun onPopupBlocked()
        fun currentUrl(): String?
    }

    interface FileChooserResult {
        fun onResult(values: Array<out Uri>?)
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        title?.let { callbacks.onTitleChanged(it) }
    }

    /** Popup blocking: new windows are refused while popups are blocked. */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val blocked = profile.settings.blockPopups || !isUserGesture
        if (blocked) {
            callbacks.onPopupBlocked()
            return false
        }
        // Popups allowed → transport WebView forwards the target URL to a new tab.
        val temp = WebView(view.context)
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                tempView: WebView,
                request: WebResourceRequest
            ): Boolean {
                val target = request.url.toString()
                tempView.stopLoading()
                tempView.post { tempView.destroy() }
                callbacks.openNewWindow(target)
                return true
            }
        }
        (resultMsg?.obj as? WebView.WebViewTransport)?.webView = temp
        resultMsg?.sendToTarget()
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        callbacks.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        callbacks.onHideCustomView()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources
        val kinds = mutableSetOf<PermissionKind>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) kinds += PermissionKind.CAMERA
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) kinds += PermissionKind.MICROPHONE
        callbacks.onPermissionRequest(request, kinds, callbacks.currentUrl() ?: "")
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback
    ) {
        callbacks.onGeolocationPermissions(origin, callback)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val intent = fileChooserParams?.createIntent() ?: return false
        callbacks.onFileChooserIntent(
            intent,
            object : FileChooserResult {
                override fun onResult(values: Array<out Uri>?) {
                    filePathCallback?.onReceiveValue(values?.let { arrayOf(*it) })
                }
            }
        )
        return true
    }
}
