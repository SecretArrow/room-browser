package com.roombrowser.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
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
import com.roombrowser.domain.credentials.CredentialDomainMatcher
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.HttpsUpgradeFallbackPolicy
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.Profile
import java.lang.ref.WeakReference

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
        /**
         * Host of the page the FIRING engine is showing — the page host a
         * sub-resource block is judged against. [view] is the engine that
         * fired.
         *
         * WHY IT TAKES THE VIEW: shouldInterceptRequest runs for EVERY
         * WebView, background tabs included. Answering with the ACTIVE tab's
         * URL (what this callback used to do, as `currentUrlHost()`) judged a
         * background tab's sub-resources against whatever page the user
         * happened to be looking at — the cross-site determination was wrong
         * and the blocked-event category recorded for that tab was wrong with
         * it.
         */
        fun pageHostFor(view: WebView): String?
        /** Snapshot of site settings for the given request host (thread-safe). */
        fun siteSettingFor(host: String): SiteSettingEntity?
        /** Record a real blocking event (Room insert, fire-and-forget). */
        fun recordBlockEvent(host: String, category: String)
        fun onBlocked(host: String, category: FilterEngine.FilterCategory)
        fun onHttpsUpgrade(host: String)
        fun onPopupBlocked()
        fun onSuspiciousSite(url: String, signals: List<String>)
        /** [view] is the engine that fired: per-tab state must be routed to
         *  the OWNING tab's row, never to whichever tab happens to be active. */
        fun onPageStarted(view: WebView, url: String)
        fun onPageFinished(view: WebView, url: String, title: String)
        /** Live web-history state — fires on EVERY navigation (including
         *  same-document pushState/replaceState) so the UI's Back / Forward
         *  controls are never stale. */
        fun onHistoryChanged(view: WebView, canGoBack: Boolean, canGoForward: Boolean)
        fun onReceivedError(view: WebView, url: String, errorCode: Int, description: String?)
        /** An HTTP status error on the MAIN frame (4xx/5xx). Separate from
         *  [onReceivedError] on purpose: the engine reports it as a SUCCESSFUL
         *  navigation, so a 404 arrives as a clean onPageFinished with the
         *  right URL and an empty document. Reported for the trace only. */
        fun onReceivedHttpError(view: WebView, url: String, statusCode: Int)
        /** The engine has a first frame to show for [url]. Its absence after
         *  onPageFinished means the document never painted. Trace only. */
        fun onPageCommitVisible(view: WebView, url: String)
        fun onSslError(view: WebView, url: String, error: SslError)
        /**
         * A site asked for HTTP Basic/Digest credentials. The host shows a
         * prompt and answers with exactly ONE of [proceed] or [cancel] — the
         * engine's handler is single-shot, and dropping both leaves the
         * navigation hanging.
         */
        fun onHttpAuthRequest(
            view: WebView,
            host: String,
            realm: String,
            proceed: (String, String) -> Unit,
            cancel: () -> Unit
        )
        fun openInNewTab(url: String, isPrivate: Boolean)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Recorded BEFORE the main-frame early return: this is the earliest
        // main-frame signal there is, and it covers redirect hops that
        // onPageStarted does not report. Background thread — see [mainFrameUrl].
        if (request.isForMainFrame) mainFrameUrl = request.url.toString()
        if (request.isForMainFrame) return null
        val url = request.url
        val host = url.host?.lowercase() ?: return null
        // The OWNING engine's page host, resolved from the firing [view] —
        // never the active tab's (see [Callbacks.pageHostFor]). The resolver
        // is total (pure map/URL lookups, no throw) and falls back to the
        // active tab's URL for an engine that owns no session, so this hot
        // path stays allocation-light and cannot fail a sub-resource load.
        val pageHost = callbacks.pageHostFor(view)

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
                // We are the ones starting this load: it IS the main frame
                // now, and an SSL failure on the way must be attributed to it
                // (see [mainFrameUrl]).
                mainFrameUrl = upgraded.url
                view.post { view.loadUrl(upgraded.url) }
                return true
            }
        }
        // Reaching here means the engine loads this url: it is the main frame.
        mainFrameUrl = url
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        CookieManager.getInstance().flush()
        // Main-frame-only callback, and the authoritative one: whatever the
        // engine is actually navigating to (including a url we never saw in
        // shouldOverrideUrlLoading, such as a cross-host redirect target).
        mainFrameUrl = url
        // Early history feedback: the back/forward buttons light up as soon
        // as a navigation begins, then doUpdateVisitedHistory re-reports the
        // authoritative state when the entry lands.
        callbacks.onHistoryChanged(view, view.canGoBack(), view.canGoForward())
        callbacks.onPageStarted(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        CookieManager.getInstance().flush()
        mainFrameUrl = url
        callbacks.onHistoryChanged(view, view.canGoBack(), view.canGoForward())
        callbacks.onPageFinished(view, url, view.title ?: url)
    }

    /**
     * An HTTP status error is NOT an engine error: no [onReceivedError] fires,
     * [onPageFinished] still arrives with the right URL, and the screen shows
     * an empty document. Forwarded so the trace can tell a 404 apart from a
     * page that rendered — from the device side those two are identical.
     */
    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (request.isForMainFrame) {
            callbacks.onReceivedHttpError(
                view,
                request.url.toString(),
                errorResponse.statusCode
            )
        }
    }

    /** The engine has a first frame to show. Its absence after
     *  [onPageFinished] means the document never painted. */
    override fun onPageCommitVisible(view: WebView, url: String) {
        callbacks.onPageCommitVisible(view, url)
    }

    /**
     * THE reliable back/forward signal: fires for every history commit —
     * including same-document navigations (history.pushState) that skip
     * onPageStarted/onPageFinished entirely. Without this, the navigation
     * buttons stay grey forever on SPA sites.
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        callbacks.onHistoryChanged(view, view.canGoBack(), view.canGoForward())
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
                view,
                failedUrl,
                error.errorCode,
                error.description?.toString()
            )
        }
    }

    /**
     * LEGACY 4-arg error callback — some WebView stacks report main-frame
     * transport failures (ERR_SSL_PROTOCOL_ERROR against plain-http ports,
     * connection resets mid-handshake) ONLY through this deprecated
     * signature during shouldOverrideUrlLoading→loadUrl upgrade flows.
     * The default implementation is a no-op, so the failure dies silently
     * with the old page still shown (CI-proven by BrowserNavigationE2eTest:
     * https attempt started — back/forward lit up — then nothing: no error
     * surface, no fallback, server never saw the retry).
     *
     * Routing: only the upgrade-fallback case is handled here; ordinary
     * error reporting stays with the modern signature above (overriding
     * both for reporting would double-fire on stacks that call both —
     * onPageStarted clears pageError, so a raced overlay self-heals).
     */
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        if (failingUrl != null) {
            val original = upgradeFallbacks.consume(failingUrl)
            if (original != null && HttpsUpgradeFallbackPolicy.isRecoverable(errorCode)) {
                view.post { view.loadUrl(original) }
            }
        }
    }

    /**
     * The most recent MAIN-FRAME url this client has seen.
     *
     * WHY IT EXISTS: [onReceivedSslError] is the one WebView callback that
     * does not say which frame it fired for — a `SslError` carries a url and
     * nothing else. Without this, a single third-party sub-resource with a
     * broken certificate (an ad iframe, a tracker pixel, a CDN with an
     * expired cert) replaced an otherwise perfectly good page with the
     * full-screen "Connection Not Secure" error — the reported symptom, and
     * not something any other browser does: Chrome blocks the one resource
     * and keeps the page.
     *
     * HOW IT IS FILLED: every callback that CAN identify a main frame does.
     * [shouldOverrideUrlLoading] and [shouldInterceptRequest] both receive an
     * `isForMainFrame` flag, and [onPageStarted]/[onPageFinished] are
     * main-frame-only callbacks by definition. Sub-resource loads never reach
     * any of them, which is exactly what makes the comparison meaningful.
     *
     * `@Volatile`: [shouldInterceptRequest] runs on a background thread while
     * the SSL callback runs on the UI thread.
     */
    @Volatile
    private var mainFrameUrl: String? = null

    /**
     * Which frame a failing certificate belongs to.
     *
     * The decision itself lives in [SslFrameMatch], where it can be tested;
     * this only supplies the two urls it compares against. See that object
     * for why the comparison is scheme+host+port and why an unknown failing
     * url errs towards "main frame".
     */
    private fun isMainFrameSslFailure(view: WebView, failingUrl: String?): Boolean =
        SslFrameMatch.isMainFrameFailure(failingUrl, mainFrameUrl, view.url)

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // A certificate that is bad for a SUB-RESOURCE is that resource's
        // problem, not the page's: cancel it and let the page carry on. This
        // is a refusal, never an acceptance — the resource is not loaded, and
        // nothing here ever calls handler.proceed(), so certificate
        // validation is untouched.
        //
        // Checked BEFORE the upgrade fallback on purpose: the fallback entry
        // is the MAIN frame's one http retry, and a sub-resource failure that
        // happened to carry the same url used to spend it — navigating the
        // whole tab down to the plain-http original because an iframe's
        // certificate was bad.
        if (!isMainFrameSslFailure(view, error.url)) {
            handler.cancel()
            return
        }
        // HTTPS-First fallback: an https endpoint without a valid TLS setup
        // behind one of OUR upgrades → retry the original http URL once.
        // The registry key is the FAILING url (error.url — e.g.
        // https://host:port/page), NOT view.url: during an in-page link
        // navigation view.url still reports the LAST COMMITTED page, so the
        // old key could never match and the fallback silently never fired
        // (CI-proven by BrowserNavigationE2eTest).
        val original = upgradeFallbacks.consume(error.url)
        if (original != null) {
            handler.cancel()
            view.post { view.loadUrl(original) }
            return
        }
        // Never proceed automatically — the user decides via the error page.
        // The url reported is the one that FAILED, not view.url: for a
        // main-frame navigation view.url is still the previous page, and
        // telling the user that address is insecure when it is not would be
        // the same class of lie this method just stopped telling.
        handler.cancel()
        callbacks.onSslError(view, error.url ?: view.url ?: "", error)
    }

    /**
     * HTTP Basic/Digest authentication.
     *
     * This used to be a bare `handler.cancel()` — no prompt, no message, no
     * explanation — which made every site behind HTTP auth simply unreachable:
     * the user got a blank 401 with no way to enter the credentials the server
     * was asking for. Unlike the SSL path above, there is nothing unsafe to
     * decide here; the server asked for a username and a password, and only
     * the user has them.
     *
     * The handler is single-shot and `useHttpAuthUsernamePassword` is NOT
     * consulted: this browser deliberately keeps no WebView credential
     * database (the password manager is the vault), so every challenge is
     * answered by the user. A non-main-frame challenge is still refused —
     * a sub-resource must not be able to raise a credential prompt.
     */
    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        var answered = false
        callbacks.onHttpAuthRequest(
            view = view,
            host = host.orEmpty(),
            realm = realm.orEmpty(),
            proceed = { user, password ->
                if (!answered) {
                    answered = true
                    runCatching { handler.proceed(user, password) }
                }
            },
            cancel = {
                if (!answered) {
                    answered = true
                    runCatching { handler.cancel() }
                }
            }
        )
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
}

/**
 * The page host a sub-resource block is judged against, given the FIRING
 * engine's own page URL and — only as a fallback — the ACTIVE tab's URL.
 *
 * Extracted as a pure function because the fallback is the load-bearing part
 * of the cross-tab fix and the one piece the JVM tests can pin without a
 * WebView (see SubResourcePageHostTest):
 *
 *  - the engine's OWN url wins, so a background tab's sub-resources are
 *    judged against ITS page. Judging them against the active tab's page was
 *    the bug: the cross-site test was decided by whichever tab the user was
 *    looking at, and the category recorded for the block followed it.
 *  - an engine that owns no session (destroyed, mid-teardown, never tracked)
 *    falls back to the active tab's URL — exactly the pre-fix answer. An
 *    untracked engine must not behave WORSE than it did before, and the
 *    fallback is deliberately not "block everything".
 *  - a page that resolves to no host at all (about:home, junk) answers null
 *    for its own engine rather than borrowing the active tab's host.
 *
 * Never throws: [UrlIntelligence.hostOf] is total and returns null on junk.
 */
internal fun subResourcePageHost(enginePageUrl: String?, activePageUrl: String?): String? {
    val url = enginePageUrl ?: activePageUrl ?: return null
    return UrlIntelligence.hostOf(url)
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
 *
 * ONE INSTANCE PER ENGINE (built in the host's createWebView). A few chrome
 * callbacks carry no WebView at all — onPermissionRequest and
 * onGeolocationPermissionsShowPrompt — so without knowing its own engine a
 * client cannot tell a background tab's request from the active tab's, and
 * would raise a sheet over the tab the user is actually looking at. Every
 * other callback here carries the firing view and routes on it.
 */
class RoomWebChromeClient(
    private val profile: Profile,
    private val callbacks: ChromeCallbacks,
    /**
     * The engine this instance is installed on, or null for an engine-less
     * instance. The host builds one per engine (createWebView passes the
     * engine it just made), so null here means the client was built without
     * one — and the host REFUSES such a request rather than attributing it to
     * whichever tab happens to be in front. WEAK: the engine owns its client,
     * and a client must never keep a destroyed engine alive.
     */
    engine: WebView? = null
) : WebChromeClient() {

    private val engineRef = WeakReference(engine)

    /** The engine that fired, or null when this instance is not engine-bound
     *  or its engine has been collected (a collected engine cannot call back;
     *  the host refuses a request it cannot attribute). */
    private fun firingEngine(): WebView? = engineRef.get()

    interface ChromeCallbacks {
        /** [view] is the engine that fired (see the WebViewClient callbacks). */
        fun onProgress(view: WebView, progress: Int)
        fun onTitleChanged(view: WebView, title: String)
        fun onShowCustomView(view: View, callback: CustomViewCallback)
        fun onHideCustomView()
        /** [view] is the engine that fired, or null when it is unknown. The
         *  host must refuse a request from anything but the ACTIVE engine. */
        fun onPermissionRequest(
            view: WebView?,
            request: PermissionRequest,
            kinds: Set<PermissionKind>,
            originUrl: String
        )
        fun onGeolocationPermissions(
            view: WebView?,
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback
        )
        fun onFileChooserIntent(
            view: WebView?,
            intent: Intent,
            callback: FileChooserResult
        )
        fun openNewWindow(view: WebView?, url: String)
        /** [view] is the engine whose page tried to open the window, or null
         *  when it is unknown. */
        fun onPopupBlocked(view: WebView?)
        fun currentUrl(): String?
        /** TRUE when [view] is the ACTIVE tab's engine. Needed because the
         *  popup transport below must be refused BEFORE it is built, and the
         *  chrome client has no other way to know which tab is on screen. */
        fun isActiveEngine(view: WebView): Boolean
    }

    interface FileChooserResult {
        fun onResult(values: Array<out Uri>?)
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgress(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        title?.let { callbacks.onTitleChanged(view, it) }
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
            callbacks.onPopupBlocked(view)
            return false
        }
        // A popup from a BACKGROUND engine is refused outright, BEFORE the
        // transport is built: opening a window on behalf of a page the user
        // is not looking at is exactly the cross-tab surprise this guards.
        // DROPPED rather than queued — a window opened now would be navigated
        // whenever the user finally got to that tab, with no context for why,
        // and the usual background case (no user gesture) was already refused
        // above. The page simply sees window.open() fail.
        if (!callbacks.isActiveEngine(view)) return false
        // Popups allowed → transport WebView forwards the target URL to a new tab.
        val temp = WebView(view.context)
        // Single-shot destroy, shared by the navigation callback and the
        // timeout below. Main-thread only (onCreateWindow, the WebViewClient
        // callback and postDelayed all run there), so a plain Boolean is the
        // right guard — destroy() on an already-destroyed WebView throws.
        var reaped = false
        val reap = {
            if (!reaped) {
                reaped = true
                temp.stopLoading()
                temp.destroy()
            }
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                tempView: WebView,
                request: WebResourceRequest
            ): Boolean {
                val target = request.url.toString()
                tempView.stopLoading()
                tempView.post { reap() }
                // The ORIGIN engine, not the transport: the host must re-check
                // ownership against the tab that actually asked (the active
                // tab can change while the popup's URL is being resolved).
                callbacks.openNewWindow(view, target)
                return true
            }
        }
        (resultMsg?.obj as? WebView.WebViewTransport)?.webView = temp
        resultMsg?.sendToTarget()
        // The transport only dies when it NAVIGATES. A `window.open()` with no
        // URL — or one the page keeps as a handle and never points anywhere —
        // never reaches shouldOverrideUrlLoading, so without this the renderer
        // it owns stays alive for the life of the process, one per popup.
        // Nothing is lost by reaping it: the transport is off-screen and is
        // never attached to a tab, so a popup that has not resolved a URL by
        // now had no way to become one.
        temp.postDelayed({ reap() }, TRANSPORT_REAP_MS)
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

        // PROTECTED MEDIA (EME) — answered here, on the spot, never put to the
        // user. This is the request a video site makes before it will hand the
        // engine a DRM-protected stream, and denying it is why video on the
        // sites that use it failed to start or stalled on "initializing".
        //
        // WHY IT NEEDS NO CONSENT SURFACE, unlike camera and microphone: the
        // resource is not a window onto anything the user owns. It is a request
        // to decode content the page is already delivering, using the device's
        // DRM module and keys the page obtained from its own licence server. No
        // data about the user leaves the device because of it, so there is
        // nothing for a prompt to protect — which is why every mainstream
        // browser, this app's own desktop-mode UA included, answers it without
        // asking. Refusing it was never a privacy decision; it was a limitation
        // of a sheet that could only answer the WHOLE resource array at once.
        //
        // The mixed case stays with the sheet: a request that wants a camera or
        // a microphone AND protected media is answered as one decision, because
        // PermissionRequest is single-shot and a partial grant would leave the
        // camera half of it hanging.
        if (kinds.isEmpty()) {
            // Refused rather than ignored: an unanswered PermissionRequest
            // hangs the page for the life of its document.
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else {
                // RESOURCE_MIDI_SYSEX is the remaining case: it has no kind
                // here either, and refusal is the honest answer — the app has
                // no MIDI device story.
                request.deny()
            }
            return
        }
        // No WebView on this callback — firingEngine() is the only handle on
        // the tab that asked. The host denies anything but the ACTIVE engine.
        callbacks.onPermissionRequest(firingEngine(), request, kinds, callbacks.currentUrl() ?: "")
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback
    ) {
        callbacks.onGeolocationPermissions(firingEngine(), origin, callback)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val intent = fileChooserParams?.createIntent() ?: return false
        // `true` is returned even when the host refuses the chooser: false
        // would hand the request to the platform's OWN picker, which is the
        // very UI the ownership check exists to suppress. The host answers a
        // refused request by completing the callback with a null result.
        callbacks.onFileChooserIntent(
            webView,
            intent,
            object : FileChooserResult {
                override fun onResult(values: Array<out Uri>?) {
                    filePathCallback?.onReceiveValue(values?.let { arrayOf(*it) })
                }
            }
        )
        return true
    }

    private companion object {
        /**
         * How long a popup transport WebView may live without navigating.
         *
         * Long enough that a real popup — which navigates as soon as the
         * engine pumps the transport's first load — is never cut off on a
         * cold, loaded emulator; short enough that an abandoned one is not
         * holding a renderer process while the user browses on.
         */
        const val TRANSPORT_REAP_MS = 10_000L
    }
}

/**
 * NATIVE half of the password-manager page bridge, exposed to page JS as
 * `window.RoomVault` (see [RoomVaultScript] for the injected half).
 *
 * PROTOCOL (what page JS can call — nothing else is exported):
 *  - `RoomVault.requestCredentials(location.host, location.href)` — the user
 *    focused/tapped a password field. No values are returned to the page; the
 *    native side decides whether to surface an "offer" sheet at all.
 *  - `RoomVault.reportCredential(location.host, username, password)` — a
 *    login form containing a non-empty password was submitted: the DOM
 *    submit event, a submit-control click, Enter in the password field, a
 *    scripted form submit(), or a request sent in the window after a vault
 *    fill (see [RoomVaultScript]). Observational only; nothing is stored
 *    until the user taps "Save" on the prompt sheet.
 *
 * SECURITY MODEL — the bridge never trusts the page:
 *  1. Only these two `@JavascriptInterface` methods are reachable from JS.
 *  2. Every call is validated ON THE MAIN THREAD against the WebView's own
 *     current URL: the host the page CLAIMS must match the host family of
 *     what the WebView is actually showing (CredentialDomainMatcher, equal or
 *     parent/child). The host handed to the callbacks is always the
 *     WebView's authoritative one, so a page can never obtain or report
 *     credentials attributed to another site. (The injected script registers
 *     in the MAIN FRAME ONLY, which is the first line of defence; this host
 *     check is the second, and it holds even against a page that calls the
 *     interface directly instead of going through the script.)
 *  3. Nothing here reads the vault or shows UI — that is the ViewModel's
 *     decision ([com.roombrowser.browser.BrowserViewModel]). The bridge is
 *     silent to the page either way, and a LOCKED vault never starts a
 *     biometric prompt on page focus; at most the ViewModel may show the
 *     offer sheet's locked variant, whose only action is the user's own tap.
 *  4. No argument is ever logged.
 *
 * THREADING: `@JavascriptInterface` methods arrive on WebView's internal
 * JavaBridge thread; WebView state (getUrl) is main-thread only, so each call
 * hops to the main thread inside [main] before validation. Throttle fields
 * are therefore confined to the main thread.
 *
 * LIFETIME: the WebView holds the interface object strongly, so the interface
 * holds the WebView only through a [WeakReference] — a destroyed engine
 * releases its bridge.
 */
class RoomVaultBridge(
    webView: WebView,
    private val callbacks: Callbacks
) {

    private val viewRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())

    /** Anti-spam state (a hostile page can call the interface directly,
     *  bypassing the injected script's own cooldowns). Main-thread only. */
    private var lastRequestAt = 0L
    private var lastReportKey: String? = null
    private var lastReportAt = 0L

    interface Callbacks {
        /** The user focused a login form on [webView] (authoritative [host]). */
        fun onCredentialsRequested(webView: WebView, host: String, href: String)

        /** A login form submitted on [webView] (authoritative [host]). */
        fun onCredentialReported(
            webView: WebView,
            host: String,
            username: String,
            password: String
        )
    }

    @JavascriptInterface
    fun requestCredentials(host: String?, href: String?) {
        val claimedHost = host ?: return
        main.post {
            val view = viewRef.get() ?: return@post
            val pageHost = validatedHost(view, claimedHost) ?: return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastRequestAt < REQUEST_COOLDOWN_MS) return@post
            lastRequestAt = now
            callbacks.onCredentialsRequested(view, pageHost, href.orEmpty())
        }
    }

    @JavascriptInterface
    fun reportCredential(host: String?, username: String?, password: String?) {
        val claimedHost = host ?: return
        val reportedUsername = username.orEmpty()
        val reportedPassword = password.orEmpty()
        // The password lives only in this posted lambda and the callback —
        // never in a log, cache or field beyond the prompt state.
        if (reportedPassword.isEmpty()) return
        main.post {
            val view = viewRef.get() ?: return@post
            val pageHost = validatedHost(view, claimedHost) ?: return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastReportAt < REPORT_MIN_GAP_MS) return@post
            val key = pageHost + '\n' + reportedUsername + '\n' + reportedPassword
            if (key == lastReportKey && now - lastReportAt < REPORT_SAME_KEY_COOLDOWN_MS) {
                return@post
            }
            lastReportKey = key
            lastReportAt = now
            callbacks.onCredentialReported(view, pageHost, reportedUsername, reportedPassword)
        }
    }

    /**
     * The host a page claims, accepted only when it names the same site as
     * the WebView's CURRENT URL (equal or parent/child domain). Returns the
     * WebView's own (authoritative) host, or null when the claim fails.
     */
    private fun validatedHost(view: WebView, claimedHost: String): String? {
        val url = view.url ?: return null
        val currentHost = UrlIntelligence.hostOf(url) ?: return null
        val claimed = CredentialDomainMatcher.normalize(claimedHost)
        if (claimed.isEmpty()) return null
        if (!CredentialDomainMatcher.matches(claimed, currentHost)) return null
        return currentHost
    }

    companion object {
        /** JS object name the injected script (and, defensively, pages) see. */
        const val JS_INTERFACE_NAME = "RoomVault"

        /** Focus events on the same page arrive in bursts — coalesce them. */
        private const val REQUEST_COOLDOWN_MS = 500L

        /** A hostile page may call the interface directly — rate-limit hard. */
        private const val REPORT_MIN_GAP_MS = 1_000L

        /** An identical (host, username, password) report is not re-prompted. */
        private const val REPORT_SAME_KEY_COOLDOWN_MS = 30_000L
    }
}

/**
 * INJECTED half of the password-manager page bridge — a SEPARATE
 * document-start script (installed by ProfileEngine.configure, independent of
 * the device shim) that provides the page-side detection and fill logic the
 * native [RoomVaultBridge] calls back into.
 *
 * Scope, by design:
 *  - MAIN FRAME ONLY (`window.top === window.self`): embedded third-party
 *    login widgets inside iframes are out of scope — the native host check
 *    would compare an iframe's host against the top page anyway, and filling
 *    across frame boundaries is a phishing vector we simply do not open.
 *    Cross-origin iframe logins are therefore NOT detected, by choice.
 *  - OFFER = focus/click on an `input[type=password]`.
 *  - SAVE = a login form being submitted, whatever path the page uses:
 *      1. the DOM `submit` event — native submits and `form.requestSubmit()`;
 *      2. a capture-phase `click` on a submit control (`input[type=submit]`,
 *         `input[type=image]`, `button[type=submit]`, and a `<button>` with
 *         no type attribute, whose default IS submit) inside a form — the
 *         shape of nearly every "Sign in" button, including the ones whose
 *         form handler preventDefaults and posts with fetch;
 *      3. `Enter` in a password field (SPAs routinely consume the key and
 *         post the form themselves, so no DOM event ever reaches us);
 *      4. the form's own `submit()` / `requestSubmit()` (a scripted
 *         `submit()` bypasses the submit event entirely);
 *      5. `fetch` and `XMLHttpRequest.prototype.send` WHILE a password field
 *         we filled is still recent — the SPA login that reads the field and
 *         POSTs JSON, with no DOM signal at all.
 *
 * Why 2/3/5 are deliberately narrow: `click`, `keydown` and `fetch` fire for
 * everything on a page (toggles, search-as-you-type, analytics), so the click
 * path accepts only genuine submit controls, and the network path requires a
 * fill made by us within RECENT_FILL_MS. Paths 1 and 4 are exact signals and
 * are never gated.
 *
 * STILL NOT DETECTED — the honest list, so nobody mistakes this for total
 * coverage: cross-origin iframe logins; canvas/WebGL-drawn login UIs (there
 * are no input elements to observe); a page that collects the password
 * without any of the five signals above (e.g. keeps keystrokes in a variable
 * and posts from a Web Worker or WebSocket); a page that replaces
 * `window.fetch` / `XMLHttpRequest.prototype.send` AFTER this script has run
 * (we wrap once, at document start — re-wrapping on every assignment would be
 * an arms race we lose anyway); and a submit from a password field that was
 * never focused, clicked or filled through anything we can see.
 *
 * Robustness rules: everything is wrapped in try/catch — a broken page must
 * still load; nothing is ever written to the console (no spam); the script is
 * idempotent under re-injection (a reconfigure replaces the document-start
 * handler, and the install guard makes a double injection a no-op anyway —
 * which is also why the prototype/`fetch` wrappers need no second guard).
 *
 * Username heuristics: within the password field's form (falling back to the
 * whole document), the text/email/tel inputs BEFORE the password field are
 * ranked — autocomplete/name/id/placeholder hints like "username", "email",
 * "login", "account", plus a type=email bonus — and the best-scoring one is
 * remembered as the username field for the fill.
 *
 * Fill protocol: `window.__roomVaultFill(payload)` where payload is a JSON
 * string `{"u": username, "p": password}` (JSON-quoted by the native side —
 * values are never naively interpolated into JS). The fill writes through
 * the input prototype's native value setter and dispatches input/change
 * events so framework-driven pages (React et al.) register the values, and it
 * starts the post-fill window that arms detection path 5. The element
 * references captured at request time are used when still attached;
 * otherwise detection re-runs against the live document.
 */
object RoomVaultScript {

    // A Kotlin raw string: no "$" may appear anywhere in this script (a
    // dollar would be read as Kotlin interpolation), so plain string
    // concatenation is used throughout, and no JS template literals.
    const val SCRIPT = """
(function () {
  'use strict';
  try {
    if (window.top !== window.self) return;
    if (window.__roomVaultInstalled) return;
    window.__roomVaultInstalled = true;

    var REQUEST_COOLDOWN_MS = 800;
    var REPORT_SAME_KEY_MS = 30000;
    // How long after a vault fill the network wrappers keep watching. Long
    // enough for a round-trip login, short enough that later unrelated
    // requests are not mistaken for one.
    var RECENT_FILL_MS = 15000;
    var lastRequestAt = 0;
    var lastReportKey = '';
    var lastReportAt = 0;
    var lastFillAt = 0;
    var userField = null;
    var passField = null;

    function visible(el) {
      try {
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      } catch (e) {
        return true;
      }
    }

    function isPassword(el) {
      try {
        return !!el && el.tagName === 'INPUT' &&
          String(el.type || '').toLowerCase() === 'password';
      } catch (e) {
        return false;
      }
    }

    function isUsernameCandidate(el) {
      if (!el || el.tagName !== 'INPUT') return false;
      var t = String(el.type || '').toLowerCase();
      if (t !== 'text' && t !== 'email' && t !== 'tel') return false;
      try {
        if (el.disabled || el.readOnly) return false;
      } catch (e) {}
      return true;
    }

    function score(el) {
      var hint = ((el.name || '') + ' ' + (el.id || '') + ' ' +
        (el.autocomplete || '') + ' ' + (el.placeholder || '')).toLowerCase();
      var s = 0;
      if (String(el.type || '').toLowerCase() === 'email') s += 3;
      if (hint.indexOf('username') >= 0) s += 4;
      if (hint.indexOf('email') >= 0) s += 3;
      if (hint.indexOf('user') >= 0) s += 2;
      if (hint.indexOf('login') >= 0) s += 2;
      if (hint.indexOf('account') >= 0) s += 2;
      return s;
    }

    function bestUsername(passEl) {
      var scopes = [];
      try {
        if (passEl.form && passEl.form.querySelectorAll) scopes.push(passEl.form);
      } catch (e) {}
      scopes.push(document);
      for (var s = 0; s < scopes.length; s++) {
        var before = [];
        var after = [];
        var seen = false;
        try {
          var list = scopes[s].querySelectorAll('input');
          for (var i = 0; i < list.length; i++) {
            var el = list[i];
            if (el === passEl) { seen = true; continue; }
            if (!isUsernameCandidate(el) || !visible(el)) continue;
            if (seen) { after.push(el); } else { before.push(el); }
          }
        } catch (e) {}
        // The username is normally ABOVE the password field; fall back to
        // below-only when the form has no leading candidate at all.
        var pool = before.length ? before : after;
        if (!pool.length) continue;
        var best = null;
        var bestScore = -1;
        for (var j = 0; j < pool.length; j++) {
          var sc = score(pool[j]);
          if (sc > bestScore) { best = pool[j]; bestScore = sc; }
        }
        if (best) return best;
      }
      return null;
    }

    function setValue(el, value) {
      try {
        var proto = (el.tagName === 'TEXTAREA')
          ? window.HTMLTextAreaElement.prototype
          : window.HTMLInputElement.prototype;
        var d = Object.getOwnPropertyDescriptor(proto, 'value');
        if (d && d.set) { d.set.call(el, value); } else { el.value = value; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } catch (e) {
        try { el.value = value; } catch (e2) {}
      }
    }

    function anyPassword() {
      try {
        var list = document.querySelectorAll('input');
        for (var i = 0; i < list.length; i++) {
          if (isPassword(list[i]) && visible(list[i])) return list[i];
        }
      } catch (e) {}
      return null;
    }

    function firstPassword(scope) {
      try {
        if (!scope || !scope.querySelectorAll) return null;
        var list = scope.querySelectorAll('input');
        for (var i = 0; i < list.length; i++) {
          if (isPassword(list[i])) return list[i];
        }
      } catch (e) {}
      return null;
    }

    function closestForm(el) {
      try {
        var n = el;
        while (n && n.nodeType === 1) {
          if (n.tagName === 'FORM') return n;
          n = n.parentNode;
        }
      } catch (e) {}
      return null;
    }

    function isSubmitControl(el) {
      try {
        if (!el || (el.tagName !== 'BUTTON' && el.tagName !== 'INPUT')) return false;
        var t = String(el.type || '').toLowerCase();
        if (el.tagName === 'INPUT') return t === 'submit' || t === 'image';
        if (t === 'submit') return true;
        // A BUTTON with no type attribute defaults to type=submit; an
        // explicit type=button is a toggle or other control and is ignored,
        // which is what keeps "show password" buttons from looking like logins.
        return !el.hasAttribute('type');
      } catch (e) {
        return false;
      }
    }

    function recentlyFilled() {
      return lastFillAt > 0 && (Date.now() - lastFillAt) < RECENT_FILL_MS;
    }

    window.__roomVaultFill = function (payload) {
      try {
        var data = (typeof payload === 'string') ? JSON.parse(payload) : payload;
        if (!data) return;
        var pf = (passField && document.contains(passField)) ? passField : anyPassword();
        if (!pf) return;
        passField = pf;
        if (data.p !== undefined && data.p !== null) setValue(pf, String(data.p));
        var uf = (userField && document.contains(userField)) ? userField : bestUsername(pf);
        if (uf && data.u !== undefined && data.u !== null) setValue(uf, String(data.u));
        // Open the post-fill window: on an SPA the next fetch/XHR is very
        // likely the login submit, and it sends no DOM event we could see.
        lastFillAt = Date.now();
      } catch (e) {}
    };

    function detect(passEl) {
      passField = passEl;
      userField = bestUsername(passEl);
    }

    function request(passEl) {
      var now = Date.now();
      if (now - lastRequestAt < REQUEST_COOLDOWN_MS) return;
      lastRequestAt = now;
      detect(passEl);
      try {
        if (window.RoomVault && typeof window.RoomVault.requestCredentials === 'function') {
          window.RoomVault.requestCredentials(
            String(location.host || ''), String(location.href || ''));
        }
      } catch (e) {}
    }

    // Reports ONE password field: reads its own (and its username field's)
    // value, applies the duplicate suppression, and hands the payload to the
    // native bridge — the one place every detection path funnels through.
    // Returns true when something was actually reported.
    function reportElement(pw) {
      try {
        if (!pw || !isPassword(pw) || !pw.value) return false;
        var uf = bestUsername(pw);
        var username = uf ? String(uf.value || '') : '';
        var password = String(pw.value || '');
        var key = String(location.host || '') + '\n' + username + '\n' + password;
        var now = Date.now();
        if (key === lastReportKey && now - lastReportAt < REPORT_SAME_KEY_MS) return false;
        lastReportKey = key;
        lastReportAt = now;
        if (window.RoomVault && typeof window.RoomVault.reportCredential === 'function') {
          window.RoomVault.reportCredential(String(location.host || ''), username, password);
        }
        return true;
      } catch (e) {
        return false;
      }
    }

    function report(scope) {
      try {
        var pw = firstPassword(scope);
        if (pw) reportElement(pw);
      } catch (e) {}
    }

    // The password field we last saw, when it is still in the document and
    // holds something; otherwise the first one on the page. Used by the paths
    // that have no form argument (network wrappers, the fill window).
    function reportBest() {
      try {
        if (passField && document.contains(passField) && isPassword(passField) && passField.value) {
          return reportElement(passField);
        }
        var pf = anyPassword();
        if (pf && pf.value) return reportElement(pf);
      } catch (e) {}
      return false;
    }

    // Armed only by the fetch/XHR wrappers: they see every request on the
    // page, so they must only fire inside the short window after a fill made
    // by the vault. The first hit closes the window, so a page that fires
    // several requests after a login cannot produce several prompts.
    function maybeReportOnSubmit() {
      if (!recentlyFilled()) return;
      if (reportBest()) lastFillAt = 0;
    }

    // Path 4: a scripted form.submit() skips the submit event entirely, and
    // requestSubmit() fires it (reporting twice is harmless — the key
    // suppression above dedupes). Wrapped once, at document start; a page
    // that replaces these later defeats the wrap (documented above).
    function hookFormMethods() {
      try {
        var proto = window.HTMLFormElement && window.HTMLFormElement.prototype;
        if (!proto) return;
        var origSubmit = proto.submit;
        if (typeof origSubmit === 'function') {
          proto.submit = function () {
            try { report(this); } catch (e) {}
            return origSubmit.apply(this, arguments);
          };
        }
        var origRequestSubmit = proto.requestSubmit;
        if (typeof origRequestSubmit === 'function') {
          proto.requestSubmit = function () {
            try { report(this); } catch (e) {}
            return origRequestSubmit.apply(this, arguments);
          };
        }
      } catch (e) {}
    }

    // Path 5: the SPA login that reads the field and POSTs, with no DOM
    // signal at all. Both wrappers report BEFORE the request leaves, so the
    // values are still in the page.
    function hookNetwork() {
      try {
        if (typeof window.fetch === 'function') {
          var origFetch = window.fetch;
          window.fetch = function () {
            try { maybeReportOnSubmit(); } catch (e) {}
            return origFetch.apply(this, arguments);
          };
        }
      } catch (e) {}
      try {
        var xhrProto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
        if (xhrProto && typeof xhrProto.send === 'function') {
          var origSend = xhrProto.send;
          xhrProto.send = function () {
            try { maybeReportOnSubmit(); } catch (e) {}
            return origSend.apply(this, arguments);
          };
        }
      } catch (e) {}
    }

    document.addEventListener('focusin', function (ev) {
      try {
        var t = ev.target;
        if (isPassword(t)) request(t);
      } catch (e) {}
    }, true);

    document.addEventListener('click', function (ev) {
      try {
        var t = ev.target;
        if (isPassword(t)) { request(t); return; }
        // Path 2: walk up from the click target (it is usually a child of
        // the control) to a genuine submit control inside a form.
        var el = t;
        while (el && el.nodeType === 1 && el !== document && !isSubmitControl(el)) {
          el = el.parentNode;
        }
        if (!isSubmitControl(el)) return;
        var form = closestForm(el);
        if (!form && el.form && el.form.tagName === 'FORM') form = el.form;
        if (!form) return;
        var pw = firstPassword(form);
        if (pw && pw.value) reportElement(pw);
      } catch (e) {}
    }, true);

    // Path 3: Enter in a password field. Deferred one tick so a page that
    // consumes the key and posts the form itself has run first; the values
    // are already in the field either way.
    document.addEventListener('keydown', function (ev) {
      try {
        var key = ev.key;
        if (key !== 'Enter' && ev.keyCode !== 13) return;
        var t = ev.target;
        if (!isPassword(t)) return;
        var form = closestForm(t);
        setTimeout(function () {
          try {
            var pw = form ? firstPassword(form) : t;
            if (pw && pw.value) reportElement(pw);
          } catch (e) {}
        }, 0);
      } catch (e) {}
    }, true);

    // Path 1: the DOM submit event (native submits, requestSubmit()).
    document.addEventListener('submit', function (ev) {
      try {
        var f = ev.target;
        if (f && f.tagName === 'FORM') report(f);
      } catch (e) {}
    }, true);

    hookFormMethods();
    hookNetwork();
  } catch (e) {
    // A page must still load even when the bridge cannot install.
  }
})();
"""
}
