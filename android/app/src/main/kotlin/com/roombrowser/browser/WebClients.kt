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
        /** Live web-history state — fires on EVERY navigation (including
         *  same-document pushState/replaceState) so the UI's Back / Forward
         *  controls are never stale. */
        fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean)
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
        // Early history feedback: the back/forward buttons light up as soon
        // as a navigation begins, then doUpdateVisitedHistory re-reports the
        // authoritative state when the entry lands.
        callbacks.onHistoryChanged(view.canGoBack(), view.canGoForward())
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        CookieManager.getInstance().flush()
        callbacks.onHistoryChanged(view.canGoBack(), view.canGoForward())
        callbacks.onPageFinished(url, view.title ?: url)
    }

    /**
     * THE reliable back/forward signal: fires for every history commit —
     * including same-document navigations (history.pushState) that skip
     * onPageStarted/onPageFinished entirely. Without this, the navigation
     * buttons stay grey forever on SPA sites.
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        callbacks.onHistoryChanged(view.canGoBack(), view.canGoForward())
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

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
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

/**
 * NATIVE half of the password-manager page bridge, exposed to page JS as
 * `window.RoomVault` (see [RoomVaultScript] for the injected half).
 *
 * PROTOCOL (what page JS can call — nothing else is exported):
 *  - `RoomVault.requestCredentials(location.host, location.href)` — the user
 *    focused/tapped a password field. No values are returned to the page; the
 *    native side decides whether to surface an "offer" sheet at all.
 *  - `RoomVault.reportCredential(location.host, username, password)` — a
 *    login form containing a non-empty password just submitted. Observational
 *    only; nothing is stored until the user taps "Save" on the prompt sheet.
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
 *     decision ([com.roombrowser.browser.BrowserViewModel]); a LOCKED vault
 *     answers requests with silence (no biometric prompt on page focus).
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
 * Scope, by design (v1):
 *  - MAIN FRAME ONLY (`window.top === window.self`): embedded third-party
 *    login widgets inside iframes are out of scope — the native host check
 *    would compare an iframe's host against the top page anyway, and filling
 *    across frame boundaries is a phishing vector we simply do not open.
 *  - Detection = focus/click on an `input[type=password]` (offer) and the
 *    DOM `submit` event of forms containing a password input (save prompt).
 *    Forms submitted purely by JavaScript (no submit event) are not detected
 *    in v1.
 *
 * Robustness rules: everything is wrapped in try/catch — a broken page must
 * still load; nothing is ever written to the console (no spam); the script is
 * idempotent under re-injection (a reconfigure replaces the document-start
 * handler, and the install guard makes a double injection a no-op anyway).
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
 * events so framework-driven pages (React et al.) register the values. The
 * element references captured at request time are used when still attached;
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
    var lastRequestAt = 0;
    var lastReportKey = '';
    var lastReportAt = 0;
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

    function report(form) {
      try {
        var pw = null;
        var list = form.querySelectorAll('input');
        for (var i = 0; i < list.length; i++) {
          if (isPassword(list[i])) { pw = list[i]; break; }
        }
        if (!pw || !pw.value) return;
        var uf = bestUsername(pw);
        var username = uf ? String(uf.value || '') : '';
        var password = String(pw.value || '');
        var key = String(location.host || '') + '\n' + username + '\n' + password;
        var now = Date.now();
        if (key === lastReportKey && now - lastReportAt < REPORT_SAME_KEY_MS) return;
        lastReportKey = key;
        lastReportAt = now;
        if (window.RoomVault && typeof window.RoomVault.reportCredential === 'function') {
          window.RoomVault.reportCredential(String(location.host || ''), username, password);
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
        if (isPassword(t)) request(t);
      } catch (e) {}
    }, true);

    document.addEventListener('submit', function (ev) {
      try {
        var f = ev.target;
        if (f && f.tagName === 'FORM') report(f);
      } catch (e) {}
    }, true);
  } catch (e) {
    // A page must still load even when the bridge cannot install.
  }
})();
"""
}
