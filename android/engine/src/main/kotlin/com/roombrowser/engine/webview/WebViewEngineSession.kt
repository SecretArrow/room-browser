package com.roombrowser.engine.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.core.graphics.createBitmap
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.engine.EngineCertificate
import com.roombrowser.engine.EngineDownloadStream
import com.roombrowser.engine.EngineErrorCode
import com.roombrowser.engine.EnginePageScripts
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.EngineSessionListener
import com.roombrowser.engine.EngineState
import com.roombrowser.engine.HttpAuthResponder
import com.roombrowser.engine.NavigationDecision
import com.roombrowser.engine.PageErrorKind
import com.roombrowser.engine.PermissionResponder
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineInspector
import com.roombrowser.engine.devtools.EngineNetworkSignal
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * One `WebView` behind the engine facade.
 *
 * IDENTITY, NOT EQUALITY -- DO NOT ADD `equals`/`hashCode`. The tab manager
 * indexes sessions in an identity map and reads it from background threads.
 * That is only sound while two distinct sessions can never compare equal,
 * which is the property a `WebView` had by inheriting identity equality, and
 * which this class keeps by overriding nothing. An implementation that added
 * value equality would make two live tabs collide in that map, and the failure
 * would appear as one tab's state being applied to another -- far from the
 * cause. The reasoning is carried over verbatim from the WebView edition's tab
 * manager (TabManager.kt:48-67, over `ConcurrentHashMap<WebView, EngineEntry>`),
 * where it was written down the first time.
 *
 * THE URL IS A SECURITY BOUNDARY, NOT A CONVENIENCE. [url] is a `@Volatile`
 * mirror published ONLY from the two callbacks that mean "the main frame
 * COMMITTED a document" -- `doUpdateVisitedHistory` and `onPageFinished` -- and
 * never read back from `WebView.getUrl()` at an arbitrary moment. Those two are
 * what `getUrl()` itself would have returned, which is the value this replaces:
 * `RoomVaultBridge.validatedHost` anchors on `getUrl()` today
 * (WebClients.kt:806-807), and the wallet and vault bridges above decide
 * whether to answer a page from [url], so it has to name the document that is
 * actually live and nothing else. NOTHING is published at navigation start:
 * see `onPageStarted` below for why the start url is deliberately a different
 * signal. `getUrl()` IS still read in exactly one place -- the certificate-frame
 * comparison below -- because that is a comparison of urls the engine itself
 * recorded, not a trust decision.
 *
 * THREADING. `url`, `title`, `progress` and the two history flags are written
 * by engine callbacks (main thread, via the clients below) and read from any
 * thread, hence `@Volatile` on every one of them. Every command is callable
 * from any thread and is marshalled to the main thread by [runOnMain], because
 * EVERY `WebView` method is main-thread only -- unlike GeckoView, which takes
 * its commands from the thread you call them on.
 *
 * WHAT THIS FILE PORTS. `ProfileEngine.configure`/`applyDesktopMode`
 * (ProfileEngine.kt:206-312, 400-427), the `WebViewClient`/`WebChromeClient`
 * surface of `WebClients.kt`, the WebView factory and destroy path of
 * `BrowserViewModel.createWebView`/`destroyWebViewQuiet`
 * (BrowserViewModel.kt:1288-1333, 1398-1419), and
 * `saveEngineStateBeforeDestroy` (BrowserViewModel.kt:1452-1456).
 */
internal class WebViewEngineSession(
    override val id: String,
    context: Context,
    profile: Profile,
    /**
     * Carried because the facade requires it, and used for one thing: see
     * [clearSessionData]. This port cannot do what the GeckoView
     * implementation does with it -- WebView has no per-session storage
     * context to put a session in, so there is no flag here that could give a
     * private tab a private cookie jar. The flag is therefore recorded rather
     * than acted on, and the erase below is gated on it instead of pretending
     * the scope is narrower than it is.
     */
    private val isPrivate: Boolean
) : EngineSession {

    private val webView: WebView = WebView(context)

    @Volatile
    private var listener: EngineSessionListener? = null

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var currentProgress: Int = 0

    @Volatile
    private var backAvailable: Boolean = false

    @Volatile
    private var forwardAvailable: Boolean = false

    @Volatile
    private var closed: Boolean = false

    /** The most recent state [saveState] captured. See that member for why. */
    @Volatile
    private var lastState: EngineState? = null

    /**
     * The most recent MAIN-FRAME url this client has seen, which exists for
     * ONE purpose: deciding whether a failing certificate belongs to the page
     * or to a sub-resource. Port of the identically named field in
     * WebClients.kt:302-324, including its `@Volatile` (shouldInterceptRequest
     * runs on a background thread while the SSL callback runs on the UI
     * thread).
     *
     * It is NOT the facade's [url] and must not become it: this one is fed by
     * sub-frame-capable callbacks too, which is exactly what makes it a
     * usable discriminator.
     */
    @Volatile
    private var mainFrameUrl: String? = null

    /** The scripts the app last asked for. Replaced wholesale, never stacked. */
    @Volatile
    private var pageScripts: EnginePageScripts? = null

    // The document-start handlers currently installed, one per script, so a
    // reconfigure REPLACES the previous one instead of stacking a second
    // document-start listener. Port of ProfileEngine's three WeakHashMaps
    // (ProfileEngine.kt:79-95), collapsed to three fields because a session
    // owns exactly one engine and the map's only job was the WebView key.
    private var deviceShimHandler: ScriptHandler? = null
    private var vaultHandler: ScriptHandler? = null
    private var walletHandler: ScriptHandler? = null

    /**
     * The DevTools console patch's document-start handle. Installed once from
     * [init] and NEVER touched by [applyPageScripts], which replaces only the
     * app's three policy scripts.
     */
    private var consoleHandler: ScriptHandler? = null

    /**
     * Whether the page-world console patch is currently forwarding. The patch
     * buffers from document start regardless, and this mirrors the inspector's
     * sink so a navigation can re-arm the new document.
     */
    @Volatile
    private var consoleArmed: Boolean = false

    /**
     * The view group this session was last attached to, remembered so the
     * fullscreen surface has somewhere to go. See [onShowCustomView].
     */
    private var host: ViewGroup? = null

    /** The page's fullscreen content, owned by this adapter. See [onShowCustomView]. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override val view: View get() = webView

    override val url: String? get() = currentUrl

    override val title: String? get() = currentTitle

    override val progress: Int get() = currentProgress

    /**
     * A `WebView` has no session handle that can be closed independently of
     * the view, so "open" means exactly "not destroyed by us". The GeckoView
     * edition has two things to ask (`!closed && session.isOpen`); this one
     * has one, and inventing a second would be a distinction with no
     * difference.
     */
    override val isOpen: Boolean get() = !closed

    override val canGoBack: Boolean get() = backAvailable

    override val canGoForward: Boolean get() = forwardAvailable

    override fun setListener(listener: EngineSessionListener?) {
        this.listener = listener
    }

    override fun loadUri(uri: String) = runOnMain {
        if (!closed) runCatching { webView.loadUrl(uri) }
    }

    /**
     * A null base URL, on purpose: WebView then resolves the document against
     * `about:blank`, so `getUrl()` carries no host and the privileged bridges
     * (which read the host out of the session URL) decline. Passing a base URL
     * here would hand the loaded markup a real origin -- see the interface.
     *
     * `historyUrl` is null as well, so the entry lands as `about:blank` rather
     * than as a synthesised address the user could be offered to share.
     */
    override fun loadHtml(html: String) = runOnMain {
        if (!closed) {
            runCatching {
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }

    override fun reload() = runOnMain { if (!closed) runCatching { webView.reload() } }

    override fun stop() = runOnMain { if (!closed) runCatching { webView.stopLoading() } }

    override fun goBack() = runOnMain { if (!closed) runCatching { webView.goBack() } }

    override fun goForward() = runOnMain { if (!closed) runCatching { webView.goForward() } }

    /**
     * DELIBERATELY EMPTY. `android.webkit` exposes no compositor-parking API:
     * `WebView.onPause`/`onResume` are for the long-removed plugin model, are
     * documented as not pausing JavaScript, and calling them on the view the
     * user is looking at is a known way to produce a blank surface. The
     * WebView edition's answer to "a background tab costs too much" is the
     * app's live-engine LRU budget -- destroy the least recently used engine
     * and restore it from its saved state -- which lives above the facade and
     * is untouched by this member. So there is nothing here for a Boolean to
     * do, and doing nothing is the honest implementation rather than a stubbed
     * one.
     */
    override fun setActive(active: Boolean) {
        // Intentionally empty -- see the KDoc above. Not a TODO.
    }

    override fun attachTo(host: ViewGroup) {
        this.host = host
        if (webView.parent !== host) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            host.addView(webView)
        }
        // A page that entered fullscreen before this session had a host has
        // been waiting for one; give it its surface now.
        mountCustomView()
    }

    override fun detach() {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    // ---- settings ---------------------------------------------------------

    /**
     * Apply [profile]'s settings to this live session. Port of
     * `ProfileEngine.configure` (ProfileEngine.kt:206-312).
     *
     * EVERY SETTING IS ASSIGNED EXPLICITLY, INCLUDING THE ONES THAT MATCH THE
     * PLATFORM DEFAULT, because this runs again on every settings change. The
     * load-bearing example is the user agent: a null result from
     * `UserAgents.effectiveUserAgent` means "use the engine's own UA" and it
     * must be APPLIED, not skipped -- a `?.let` alone would leave the previous
     * identity installed on every open WebView until the process restarted,
     * which is the bug the app's own comment records. The mobile branch of
     * [applyDesktopMode] follows the same rule.
     *
     * THE PAGE SCRIPTS ARE NOT INSTALLED HERE. They arrive through
     * [setPageScripts], because the script TEXT comes from above the facade
     * (the device shim is built from the app's device catalogue, the other two
     * are app constants). What this does do is re-apply whichever set is
     * stored, matching the GeckoView edition's `configure`, which re-sends the
     * scripts it holds.
     *
     * `@SuppressLint("RestrictedApi")`: the X-Requested-With opt-out below
     * is guarded the way webkit's own `@RequiresFeature` prescribes -- check
     * `WebViewFeature.isFeatureSupported` for the feature, then call -- but
     * the CONSTANT naming it is `@RestrictTo(LIBRARY_GROUP)` in webkit 1.12.1,
     * so the guard pattern their annotation demands is one lint refuses at
     * the reference. The value the constant names is the Chromium
     * support-library boundary's own feature string, matched across the
     * process boundary by the WebView provider itself -- a wire contract,
     * not an implementation detail -- so the suppression names a stable
     * value while keeping the guard's shape honest. The repo's precedent is
     * `BrowserActivity.dispatchKeyEvent`, which documents the same class of
     * androidx-internal contradiction the same way.
     */
    @SuppressLint("RestrictedApi")
    internal fun configure(profile: Profile) {
        runOnMain {
            if (closed) return@runOnMain
            val s: WebSettings = webView.settings
            val settings = profile.settings

            // POLICY (user mandate): JavaScript is NEVER disabled by default.
            // The platform default is false -- this is always applied
            // explicitly from ProfileSettings, whose own default is true. Only
            // an explicit per-profile toggle or per-site override may turn it
            // off, never a default path.
            s.javaScriptEnabled = settings.javascriptEnabled
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.allowFileAccess = false
            s.allowContentAccess = false
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.loadWithOverviewMode = true
            s.useWideViewPort = true
            s.setSupportMultipleWindows(true) // required for popup control
            // Autoplay is an explicit constant, NOT derived from
            // `settings.blockMalicious`: that was a miswire -- blocking
            // malicious sites has nothing to do with whether a page may start
            // playing media, so toggling that one shield silently changed an
            // unrelated behaviour. It is FALSE (autoplay permitted) because
            // that is what the app has actually shipped for every fresh
            // install and every profile that never touched the shield, and
            // because compatibility-first is what this mode means everywhere
            // else in the app.
            s.mediaPlaybackRequiresUserGesture = MEDIA_PLAYBACK_REQUIRES_USER_GESTURE
            // TRUE routes every `window.open` through the app's popup gate
            // (onNewWindowRequest) instead of letting the engine swallow the
            // gesture-less ones at the JS level before a decision is made.
            // The gate blocks exactly those anyway -- `blockPopups ||
            // !hasUserGesture`, WebClients -- so the popup POLICY is
            // unchanged; what changes is that it decides in one place, which
            // is also how Chrome treats the same call: the window is offered
            // and the popup blocker answers, rather than the offer never
            // being made.
            s.javaScriptCanOpenWindowsAutomatically = true
            // Mixed content: compatibility mode by default. NEVER_ALLOW blanked
            // real-world sites that still load http sub-resources (legacy CDNs,
            // older image hosts); a user who wants strictness enables "Block
            // mixed content" in settings.
            s.mixedContentMode = if (settings.blockMixedContent) {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            // See the note above: the null branch is a RESET and has to be
            // applied. DEFAULT no longer means "the engine's UA verbatim" --
            // the stock string announces a WebView, and video sites in
            // particular serve a degraded player to that identity, so the two
            // WebView markers come off first (UserAgents.webViewNeutralUserAgent).
            // Resolved ONCE into a local before it is installed: the client
            // hints below must describe the same identity, and reading the UA
            // back out of the engine after assigning it would make the two a
            // copy of each other only by accident.
            // The second elvis is a real case, not belt-and-braces: the
            // neutralizer answers null for a null/blank engine string, and
            // WebSettings can hand back "" before it has settled. Assigning
            // null is not an option (the setter takes a String), and
            // assigning "" would send an empty User-Agent, so the raw stock
            // string is the honest last resort.
            val resolvedUserAgent = UserAgents.effectiveUserAgent(settings)
                ?: UserAgents.webViewNeutralUserAgent(WebViewStockUserAgent.of(s))
                ?: WebViewStockUserAgent.of(s)
            s.userAgentString = resolvedUserAgent
            applyClientHints(s, settings, resolvedUserAgent)
            // Desktop mode is the LAST word on identity, and it belongs here as
            // well as in [applyDesktopMode]: `configure` runs again on every
            // settings change, including the one the app makes immediately
            // after a session is created, so a profile whose default is desktop
            // mode would otherwise be handed its mobile UA back by that pass.
            if (settings.desktopModeDefault) {
                s.userAgentString = UserAgents.desktopModeUserAgent
                applyClientHints(s, settings, UserAgents.desktopModeUserAgent)
                s.useWideViewPort = true
                s.loadWithOverviewMode = false
            }
            // The profile's text scale normally travels as the shared
            // document-start shim, which a WebView too old for
            // DOCUMENT_START_SCRIPT never receives -- so the engine's own text
            // scaler carries it there instead, and is reset to 100 where the
            // shim owns the property, so the two paths cannot drift.
            s.textZoom = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                100
            } else {
                (settings.fontScale * 100).roundToInt().coerceIn(50, 200)
            }

            // Cookies are process-wide on WebView (one CookieManager for the
            // whole process), scoped on disk by the data-directory suffix
            // [WebViewEngineHost.bind] installed.
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)

            // The stock WebView stamps every request with
            // `X-Requested-With: <applicationId>`. Sites read that as "an app
            // is embedding a WebView, not a browser" -- Google sign-in's
            // "This browser or app may not be secure" is the visible refusal
            // -- and it hands every site the app's package name as a
            // fingerprint for free, the same leak the UA work above closes.
            //
            // WHAT THIS CALL CAN AND CANNOT DO, in the two cases that exist.
            //
            // WHERE THE PROVIDER LISTS THE FEATURE, the call is a no-op anyway:
            // androidx 1.12.1's `setRequestedWithHeaderOriginAllowList` is an
            // EMPTY METHOD BODY whose javadoc says the origin trial "has
            // ended, so this API no longer does anything". So the lever does
            // nothing on a provider new enough to offer it.
            //
            // WHERE IT DOES NOT LIST IT, there is no lever at all -- and that
            // is the measured case: on the CI emulator, WebView 83 reports
            // `isFeatureSupported(REQUESTED_WITH_HEADER_ALLOW_LIST) = false`
            // and the request still arrived at the server carrying
            // `com.roombrowser.debug` (logcat tag `RoomIdentity`). androidx's
            // contract language agrees it is the provider's call -- an origin
            // absent from the list "may not receive the header, depending on
            // the current installed WebView provider".
            //
            // THE CALL STAYS because it is the only control the platform
            // offers and it costs one guarded line, and the screen does not
            // trust it: `RequestedWithProbe` reports what actually went out
            // rather than what this asked for. A comment here once claimed an
            // empty list meant the header was "never sent", which was false
            // and sent people to update a WebView that was already current.
            val optOutSupported =
                WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
            if (optOutSupported) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet<String>())
            }
            logIdentityOnce(optOutSupported)

            // Android Autofill integration: AUTO participates in the system
            // autofill framework; NO opts the WebView out when the profile
            // disables autofill.
            webView.importantForAutofill =
                if (settings.autofillEnabled) View.IMPORTANT_FOR_AUTOFILL_AUTO
                else View.IMPORTANT_FOR_AUTOFILL_NO
        }
        applyPageScripts()
    }

    /**
     * Install the `Sec-CH-UA*` identity that matches [resolvedUserAgent].
     *
     * The UA STRING is only half of what a server reads. The client-hint
     * headers are built by the WebView provider from its own version, so a
     * profile claiming Chrome 156 over an older provider sent a UA saying 156
     * beside headers saying something else — and, on providers that still do
     * it, the brand `"Android WebView"`. Google's sign-in refusal is the
     * visible consequence; the general one is that every site gets two
     * contradictory descriptions of the same client. Setting the metadata from
     * the same string the session installed is what makes them agree.
     *
     * Guarded twice on purpose. The feature belongs to the installed provider,
     * not to this APK, so an old provider keeps whatever it had (there is no
     * app-side way to send a header the engine does not) — and a provider that
     * advertises the feature and then refuses the call is a provider bug, not
     * a reason to fail a page load.
     */
    private fun applyClientHints(s: WebSettings, settings: ProfileSettings, resolvedUserAgent: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val metadata = WebViewClientHints.forUserAgent(settings, resolvedUserAgent) ?: return
        runCatching { WebSettingsCompat.setUserAgentMetadata(s, metadata) }
    }

    /**
     * Desktop-site toggle for this session. Port of
     * `ProfileEngine.applyDesktopMode` (ProfileEngine.kt:400-427).
     *
     * The device shim half of the original is not repeated here: the shim is
     * an [EnginePageScripts] value the app owns, so the app supplies the
     * desktop-mode variant through [setPageScripts]. What IS reproduced is the
     * coupling -- a desktop-mode change makes the installed scripts stale, so
     * they are re-applied exactly as the GeckoView edition does
     * (`GeckoEngineSession.applyDesktopMode`), and as the app did by calling
     * `applyDeviceShim` from both paths.
     */
    internal fun applyDesktopMode(profile: Profile, desktop: Boolean) {
        runOnMain {
            if (closed) return@runOnMain
            val s = webView.settings
            if (desktop) {
                s.userAgentString = UserAgents.desktopModeUserAgent
                applyClientHints(s, profile.settings, UserAgents.desktopModeUserAgent)
                s.useWideViewPort = true
                s.loadWithOverviewMode = false
            } else {
                s.useWideViewPort = true
                s.loadWithOverviewMode = true
                // The same two-step the mobile branch of [configure] uses: a
                // null override means "the engine's own UA", which still
                // announces a WebView, so the stock string is read back and its
                // two WebView markers stripped before it is installed.
                val resolvedUserAgent = UserAgents.effectiveUserAgent(profile.settings)
                    ?: UserAgents.webViewNeutralUserAgent(WebViewStockUserAgent.of(s))
                    ?: WebViewStockUserAgent.of(s)
                s.userAgentString = resolvedUserAgent
                applyClientHints(s, profile.settings, resolvedUserAgent)
            }
        }
        applyPageScripts()
    }

    // ---- page scripts -----------------------------------------------------

    /**
     * Install the page-world scripts. Port of `ProfileEngine.applyDeviceShim`
     * + `applyVaultScript` + `applyWalletScript`
     * (ProfileEngine.kt:338-398), including the ORDER and the replace-don't-
     * stack rule.
     *
     * WHY DOCUMENT_START AND NOT SOMETHING ELSE: these scripts define globals
     * the page calls and a device shim whose entire purpose is to be installed
     * before the page measures anything. A shim injected after the page's first
     * script is a claim that script already read. `WebViewCompat
     * .addDocumentStartJavaScript` is the only API that gives that, which is
     * why this module depends on `androidx.webkit` at all.
     *
     * HONEST LIMIT, carried over from the app: on a WebView too old to support
     * the feature, the scripts are silently absent -- the native entry points
     * are still exposed but nothing calls them. The guard is the app's, and it
     * sits AFTER the removal of the previous handler so that a device which
     * loses the feature does not keep running a stale script.
     */
    override fun setPageScripts(scripts: EnginePageScripts) {
        pageScripts = scripts
        applyPageScripts()
    }

    private fun applyPageScripts() {
        val scripts = pageScripts ?: return
        runOnMain {
            if (closed) return@runOnMain

            // The DEVICE SHIM goes first, so that anything the later scripts
            // read from the environment is already the claimed one -- the same
            // ordering requirement the GeckoView edition's page-world bridge
            // records.
            deviceShimHandler?.let { previous -> runCatching { previous.remove() } }
            deviceShimHandler = null
            // A null shim is NOT "an empty script": it is the app's own answer
            // for a profile with no device, no screen claim and the default
            // WebRTC policy. The removal above has already happened, so a
            // profile that switches back to no-device LOSES its shim rather
            // than keeping the stale one.
            scripts.deviceShim?.let { shim ->
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    runCatching {
                        deviceShimHandler =
                            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf("*"))
                    }
                }
            }

            vaultHandler?.let { previous -> runCatching { previous.remove() } }
            vaultHandler = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    vaultHandler = WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        scripts.vaultBridge,
                        setOf("*")
                    )
                }
            }

            walletHandler?.let { previous -> runCatching { previous.remove() } }
            walletHandler = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    walletHandler = WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        scripts.walletProvider,
                        setOf("*")
                    )
                }
            }
        }
    }

    /**
     * Install the DevTools console patch at document start, once for the life of
     * the session.
     *
     * SEPARATE FROM [applyPageScripts] ON PURPOSE. That method replaces the
     * app's three policy scripts on every reconfigure; this one is engine-owned
     * plumbing a DevTools panel needs, and folding it into the app bundle would
     * push an inspection script through `configure()` on every page load of every
     * profile. The guard is the app's own, for the same reason: on a WebView too
     * old for document-start scripts the patch is silently absent, which is a
     * missing panel rather than a broken page.
     */
    private fun installConsolePatch() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching { consoleHandler?.remove() }
        consoleHandler = null
        runCatching {
            consoleHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                WebViewDevTools.consolePatch(consoleArmed),
                setOf("*")
            )
        }
    }

    // ---- scripting --------------------------------------------------------

    /**
     * The direct replacement for `WebView.evaluateJavascript`.
     *
     * THE CALLBACK RECEIVES THE JSON-ENCODED RESULT, EXACTLY AS THE APP'S
     * CALLERS ALREADY EXPECT: `evaluateJavascript` hands back a string
     * containing JSON, so a JS `null` arrives as the four-character string
     * `"null"` and a JS string arrives quoted. That is NOT the same as this
     * edition's Kotlin null, which means one thing only -- the script could not
     * be delivered at all (the session is closed, or the call threw). The
     * GeckoView edition cannot reproduce the JSON encoding and documents the
     * difference on its side; this edition must not "fix" it, because the
     * app-side callers that unquote a result (`unescapeJson`, the reader-mode
     * and vault paths) depend on it.
     */
    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        runOnMain {
            if (closed) {
                callback?.invoke(null)
                return@runOnMain
            }
            runCatching {
                webView.evaluateJavascript(script, ValueCallback<String> { value ->
                    callback?.invoke(value)
                })
            }.onFailure {
                callback?.invoke(null)
            }
        }
    }

    // ---- capture and state ------------------------------------------------

    /**
     * Capture the session's rendering, cropped to its own bounds. Port of
     * `BrowserViewModel.captureThumbnail` (BrowserViewModel.kt:1481-1532),
     * including the reason it is not `view.draw(Canvas)`.
     *
     * NEVER DRAW A WEBVIEW SYNCHRONOUSLY ON THE MAIN THREAD. The software-draw
     * path forces a synchronous rasterization round-trip through the renderer
     * and DEADLOCKS on WebView-83-class stacks when the compositor has not
     * produced a frame for the view yet: CI proved the whole sequence --
     * onPageFinished -> captureThumbnail -> view.draw() -> the browser process
     * froze forever. `PixelCopy` is the asynchronous surface copy: it delivers
     * the frame, or an error, through the callback, and the main thread is
     * never blocked. The View-source overload of `PixelCopy.request` is API 34
     * and this project's compile surface offers the API-26 Window overload, so
     * the copy runs against the Activity window and is CROPPED to the engine's
     * bounds.
     *
     * The app simply returned when there was nothing to capture; the facade
     * requires an answer, so every one of those early exits reports null. A
     * failed thumbnail is purely cosmetic and never worth a crash or a hang.
     */
    override fun capturePixels(onResult: (Bitmap?) -> Unit) {
        runOnMain {
            val captured = webView
            if (closed) {
                onResult(null)
                return@runOnMain
            }
            if (captured.width == 0 || captured.height == 0) {
                onResult(null)
                return@runOnMain
            }
            val root = captured.rootView
            val window = (root.context as? Activity)?.window
            if (window == null || root.width == 0 || root.height == 0) {
                onResult(null)
                return@runOnMain
            }
            try {
                // The KTX form, whose default config is the same ARGB_8888:
                // written this way lint's UseKtx quickfix has nothing to
                // rewrite, so it cannot land the call without this import.
                val full = createBitmap(root.width, root.height)
                PixelCopy.request(window, full, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        onResult(
                            runCatching {
                                val loc = IntArray(2)
                                captured.getLocationInWindow(loc)
                                val x = loc[0].coerceIn(0, full.width)
                                val y = loc[1].coerceIn(0, full.height)
                                val cropW = captured.width.coerceAtMost(full.width - x)
                                val cropH = captured.height.coerceAtMost(full.height - y)
                                if (cropW > 0 && cropH > 0) {
                                    Bitmap.createBitmap(full, x, y, cropW, cropH)
                                } else {
                                    null
                                }
                            }.getOrNull()
                        )
                    } else {
                        onResult(null)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: Exception) {
                // Not attached to a window yet / surface unavailable.
                onResult(null)
            }
        }
    }

    /** No PDF writer here; [printAdapter] is the route that does work. */
    override fun saveAsPdf(onResult: (java.io.InputStream?) -> Unit) {
        onResult(null)
    }

    override fun pdfUnsupportedReason(): String =
        "WebView, the engine this edition runs on, has no PDF writer of its own, so no tool can " +
            "turn a page into a PDF here. The browser can still do it for the user: the " +
            "\"Save as PDF\" row in the page-actions sheet opens the system print dialog, where " +
            "Save as PDF is one of the destinations. Say that instead of calling this again."

    /**
     * WebView's own print route: the platform print sheet renders the document
     * and offers "Save as PDF" among its destinations. Main-thread only, which
     * is where the browser UI calls it from.
     */
    override fun printAdapter(jobName: String): android.print.PrintDocumentAdapter =
        webView.createPrintDocumentAdapter(jobName)

    /**
     * Read a `blob:` / `data:` download out of this session's page.
     *
     * WebView's download listener reports those URLs like any other, but the
     * URL names an object in the renderer's memory rather than a location a
     * request can be sent to — see [WebViewBlobDownloads]. Only this WebView
     * holds the document that can resolve it, so the read is delegated there
     * and never attempted over HTTP.
     *
     * Main-thread only, both because `evaluateJavascript` is and because the
     * callback is promised on the main thread; [runOnMain] supplies the
     * dispatch either way.
     */
    override fun openDownloadStream(url: String, onResult: (EngineDownloadStream?) -> Unit) {
        runOnMain {
            if (closed) {
                onResult(null)
                return@runOnMain
            }
            blobDownloads.read(webView, url) { stream, name ->
                onResult(stream?.let { EngineDownloadStream(it, name) })
            }
        }
    }

    /**
     * Capture the engine's back/forward state under an opaque token. Port of
     * `saveEngineStateBeforeDestroy` (BrowserViewModel.kt:1452-1456).
     *
     * WHY IT RETURNS THE LAST CAPTURE RATHER THAN BLOCKING. `WebView.saveState`
     * is main-thread only and synchronous, but this member is callable from any
     * thread. Called ON the main thread -- which is where both of its callers
     * live, the tab manager's pre-destroy capture among them -- it runs inline
     * and returns the state it just took. Called from anywhere else it posts
     * the capture and returns the previous one, which is the same shape the
     * GeckoView edition has (it can only ever return what Gecko flushed last).
     * A caller that keeps the most recent token rather than demanding one on
     * the spot -- which the tab manager already does -- cannot tell the
     * difference.
     *
     * An empty bundle means the engine had nothing to save, and the app
     * deliberately stored nothing in that case rather than a token that would
     * restore to a blank page.
     */
    override fun saveState(): EngineState? {
        runOnMain {
            if (closed) return@runOnMain
            val bundle = Bundle()
            runCatching { webView.saveState(bundle) }
            if (!bundle.isEmpty) lastState = WebViewState(bundle)
        }
        return lastState
    }

    override fun restoreState(state: EngineState): Boolean {
        val bundle = (state as? WebViewState)?.bundle ?: return false
        var restored = false
        runOnMain {
            if (closed) return@runOnMain
            runCatching { webView.restoreState(bundle) }
            // Asked HERE, on the same thread and the same turn as the restore,
            // because that is the only point where the answer is meaningful.
            // `restoreState` populates the back/forward list as part of that
            // call, so the list is already correct on the next line -- this is
            // exactly what the app asked before the facade existed
            // (`copyBackForwardList().size > 0`). The session's own
            // `canGoBack` is NOT a substitute: its backing field is written by
            // the back/forward callbacks, none of which have run yet, so it
            // answers false even for a restore that worked perfectly -- and
            // the caller reads false as "nothing was restored, reload the URL"
            // and throws the restored history away.
            restored = runCatching {
                webView.copyBackForwardList().size > 0
            }.getOrDefault(false)
        }
        return restored
    }

    override fun findInPage(query: String) {
        // `findAllAsync("")` highlights nothing and drops the previous
        // matches, which is exactly what the find bar wants when the field is
        // emptied on a keystroke.
        runOnMain { if (!closed) runCatching { webView.findAllAsync(query) } }
    }

    override fun clearFindMatches() {
        runOnMain { if (!closed) runCatching { webView.clearMatches() } }
    }

    /**
     * Session-scoped erase: session cookies and form data.
     *
     * NOT THE PROFILE WIPE. [EngineHost.clearBrowsingData] erases everything
     * for the whole profile -- every tab, every site -- and it is the wrong
     * instrument for "this private session is over": using it here would sign
     * the user out of every site they are logged into, to close one tab. This
     * is the narrower operation the private-tab promise needs, and it is the
     * exact body the app ran before the facade existed
     * (`ProfileEngine.clearSessionArtifacts`, ProfileEngine.kt:492-497) --
     * removeSessionCookies + flush + clearFormData.
     *
     * A REAL ERASE, not a formality. The private-tab surface tells the user
     * session cookies are gone when the last private tab closes, and the
     * per-site "clear data" action promises the same for one site. An empty
     * implementation makes both of those texts false.
     *
     * Deliberately NOT gated on `closed`. The cookie jar is process-wide and
     * this is precisely what a session being torn down needs; refusing it
     * because the view is already gone would break the promise in the one case
     * it exists for.
     */
    override fun clearSessionData() {
        // Gated on privacy for the same reason the GeckoView implementation
        // is: only a private session's artifacts are about THAT session rather
        // than about the profile. WebView cannot narrow the scope any further
        // -- `removeSessionCookies` is process-wide -- so calling this for a
        // normal session would drop every session cookie the user holds, which
        // is the facade's named failure mode rather than a smaller version of
        // the same operation.
        if (!isPrivate) return
        runOnMain {
            runCatching {
                val cookies = CookieManager.getInstance()
                cookies.removeSessionCookies(null)
                cookies.flush()
            }
            runCatching { WebViewDatabase.getInstance(webView.context).clearFormData() }
        }
    }

    /**
     * This session's inspection handle. Per session, because a console sink
     * belongs to the panel that opened it; the capability set it reads is the
     * one shared [WebViewDevTools.CAPABILITIES], so a session can never
     * describe a capability the engine does not serve.
     */
    private val devToolsInspector: WebViewInspector by lazy { WebViewDevTools.inspector(this) }

    override fun inspector(): EngineInspector = devToolsInspector

    /**
     * Tells the page-world console patch to forward (or stop forwarding).
     *
     * While disarmed the patch buffers in the page, so crossing the bridge is
     * paid for only when someone is watching; arming is also what flushes that
     * buffer, which is why a page that logged before the panel opened still shows
     * those lines once it does.
     */
    internal fun armPageConsole(armed: Boolean) {
        consoleArmed = armed
        // Two documents have to be told, because only one of them is reachable by
        // script: the one already running answers to the patch's own switch, and
        // every document after it is served by reinstalling the patch with the
        // state baked in. The page-world flag alone did not survive a navigation.
        installConsolePatch()
        evaluateJs(if (armed) ARM_PAGE_CONSOLE else DISARM_PAGE_CONSOLE, null)
    }

    /**
     * Decodes one console entry the page patch stringified, or null for a
     * payload that is not one -- so a malformed call is dropped rather than
     * thrown back across the bridge.
     */
    private fun decodeConsoleEntry(payload: String): EngineConsoleMessage? =
        runCatching {
            val json = JSONObject(payload)
            EngineConsoleMessage(
                level = json.optString("level", "log"),
                text = json.optString("text", ""),
                source = if (json.isNull("source")) null else json.optString("source").takeIf { it.isNotEmpty() },
                line = json.optInt("line", 0),
                timestampMs = json.optLong("ts", System.currentTimeMillis()),
                fromEngine = false
            )
        }.getOrNull()

    override fun close() {
        if (closed) return
        closed = true
        // Before the WebView is destroyed: an in-flight blob read is waiting on
        // this view's document, and its callback would otherwise never arrive.
        blobDownloads.cancelAll()
        runOnMain {
            // The order is the app's (BrowserViewModel.destroyWebViewQuiet,
            // BrowserViewModel.kt:1398-1419): stop the load, take the view out
            // of the hierarchy, and only then destroy it. Destroying an
            // attached WebView is what the platform means by misuse.
            runCatching { webView.stopLoading() }
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView.destroy() }
        }
        // Everything below releases the session's own references. They are
        // dropped rather than `remove()`d because the WebView is already gone
        // and a document-start handle for a destroyed view has nothing to
        // unregister from.
        deviceShimHandler = null
        vaultHandler = null
        walletHandler = null
        consoleHandler = null
        consoleArmed = false
        customView = null
        customViewCallback = null
        listener = null
    }

    // ---- the WebViewClient ------------------------------------------------

    private val webViewClient = object : WebViewClient() {

        /**
         * Port of `RoomWebViewClient.shouldInterceptRequest`
         * (WebClients.kt:113-149) and of the main-frame record that precedes
         * its early return (WebClients.kt:120).
         *
         * Runs on a WebView BACKGROUND thread for every sub-resource of every
         * engine, which is why [mainFrameUrl] is volatile and why the facade
         * requires every listener method to be safe off the main thread.
         *
         * The app returned early for the main frame, so this member never
         * blocked the page itself -- the malicious-site rule lives in the
         * navigation callback instead, where it can also stop a navigation
         * rather than a document. The facade passes `isForMainFrame` up rather
         * than making that decision here, so the caller can keep that split.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) mainFrameUrl = url
            // What the engine is about to put on the wire, recorded so the
            // identity self-check can report the header's real state instead of
            // inferring it from a provider feature flag that stopped meaning
            // what it used to (see RequestedWithProbe). Side-effect only and
            // allocation-light: this runs for every sub-resource of every tab.
            RequestedWithProbe.record(request.requestHeaders)
            val blocked = listener?.onResourceRequest(
                this@WebViewEngineSession,
                url,
                request.isForMainFrame
            ) ?: false
            // Side-effect only, and wrapped so it can never change the return
            // value below: a report happens whether or not the request was
            // blocked, because a blocked request still "started". The signal is
            // still built with no panel armed -- the inspector's backlog is what
            // replays the document to a Network panel opened after the page --
            // but the marshalling is gated on the inspector's armed sink, so
            // this per-sub-resource call costs no main-thread post while
            // Developer Tools is closed.
            runCatching {
                devToolsInspector.onNetwork(
                    EngineNetworkSignal(
                        kind = EngineNetworkSignal.Kind.REQUEST,
                        url = url,
                        method = request.method,
                        status = null,
                        requestHeaders = request.requestHeaders,
                        responseHeaders = emptyMap(),
                        isForMainFrame = request.isForMainFrame,
                        resourceType = null,
                        timestampMs = System.currentTimeMillis()
                    )
                )
            }
            return if (blocked) blockedResponse() else null
        }

        /**
         * Where the app's navigation policy ran. Port of
         * `RoomWebViewClient.shouldOverrideUrlLoading` (WebClients.kt:151-191),
         * expressed as the facade's [NavigationDecision].
         *
         * The malicious-site refusal is the BLOCK answer; the HTTPS upgrade is
         * the LoadDifferent answer, and the app's `view.post { loadUrl(...) }`
         * is kept exactly -- posting keeps the substitute load out of the
         * callback's own stack, which is how the app avoided re-entering the
         * engine's navigation accounting. The failed-upgrade RETRY is not
         * here: the facade puts that above, where the error report reaches it.
         *
         * The frame url is recorded WITHOUT asking which frame this is, exactly
         * as the app records it (WebClients.kt:183 and :189). The app's own
         * comment here says "Reaching here means the engine loads this url: it
         * is the main frame" -- this callback is not expected to fire for a
         * sub-frame at all -- so the unguarded assignment is intentional and is
         * carried over rather than tightened. The request's frame flag is still
         * passed to the listener, which needs it for the navigation policy.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            val decision = listener?.onNavigationRequest(
                this@WebViewEngineSession,
                url,
                request.isForMainFrame,
                request.hasGesture()
            ) ?: NavigationDecision.Allow
            return when (decision) {
                NavigationDecision.Allow -> {
                    mainFrameUrl = url
                    false
                }
                NavigationDecision.Block -> true
                is NavigationDecision.LoadDifferent -> {
                    mainFrameUrl = decision.url
                    view.post { runCatching { view.loadUrl(decision.url) } }
                    true
                }
            }
        }

        /**
         * A main-frame load began. Port of `RoomWebViewClient.onPageStarted`
         * (WebClients.kt:193-204).
         *
         * WHY IT RECORDS `mainFrameUrl` BUT NOT [url]. This is the app's
         * authoritative main-frame anchor for the certificate-frame comparison
         * (WebClients.kt:198), so it feeds `mainFrameUrl` -- but it is NOT a
         * commit, and the facade's [url] has to be the document that is
         * actually live, because the wallet and vault bridges above decide
         * whether to answer a page from it. `WebView.getUrl()` -- the value
         * this replaces, and the value `RoomVaultBridge.validatedHost` still
         * anchors on (WebClients.kt:806-807) -- names the COMMITTED document,
         * and the facade records that as this edition's known difference
         * against GeckoView's navigation-start report. Publishing here would
         * put [url] one hop ahead of the live document, which is the failure
         * the facade's security note is about.
         *
         * The start URL is still reported, through [EngineSessionListener
         * .onPageStarted] -- which is also what the app's own page-state update
         * uses to move the omnibox at navigation start
         * (BrowserViewModel.kt:381). The two signals are distinct above the
         * facade and stay distinct here.
         *
         * `hasUserGesture` is reported false because this callback carries no
         * such flag. The facade says an engine that cannot supply it for a
         * given navigation reports false, which is the conservative answer --
         * and the navigation POLICY callback, which DOES receive the gesture
         * from the request, is where a caller must make that decision anyway.
         */
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            CookieManager.getInstance().flush()
            mainFrameUrl = url
            currentProgress = 0
            // Early history feedback: the back/forward buttons light up as soon
            // as a navigation begins; doUpdateVisitedHistory re-reports the
            // authoritative state when the entry lands.
            publishHistory(view)
            listener?.onPageStarted(this@WebViewEngineSession, url)
        }

        /**
         * Port of `RoomWebViewClient.onPageFinished` (WebClients.kt:206-211).
         *
         * THE SUCCESS FLAG IS ALWAYS TRUE, and that is not optimism: this
         * callback carries no outcome, and a main-frame failure arrives
         * through [onReceivedError]/[onReceivedSslError] instead, which is
         * where the app has always read failures from. "The load finished and
         * no error was reported" is the only thing this signal can mean.
         *
         * The url is published here as well as on the commit, which the app
         * also did for its own frame record (WebClients.kt:208). It is the
         * backstop for the loads that finish without a history update -- the
         * one case where publishing only on the commit would leave the anchor
         * naming the previous document after a finished load. Publishing the
         * same url twice is idempotent.
         */
        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            mainFrameUrl = url
            currentUrl = url
            currentProgress = 100
            listener?.onUrlChanged(this@WebViewEngineSession, url, true, false)
            publishHistory(view)
            listener?.onPageFinished(this@WebViewEngineSession, url, true)
        }

        /**
         * THE reliable history signal. Port of
         * `RoomWebViewClient.doUpdateVisitedHistory` (WebClients.kt:245-247).
         *
         * It fires for every history commit -- including the same-document
         * navigations (`history.pushState`) that skip onPageStarted and
         * onPageFinished entirely. Without it the navigation buttons stay grey
         * forever on SPA sites, and the facade's [url] would keep naming the
         * document the SPA was on when it first loaded.
         *
         * This is the PRIMARY publication point for [url]: a history commit is
         * precisely the moment a document becomes the live one, which is the
         * value `getUrl()` would return and the value the bridges above anchor
         * a trust decision on. `onPageFinished` repeats it as a backstop.
         */
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) {
                currentUrl = url
                listener?.onUrlChanged(this@WebViewEngineSession, url, true, false)
            }
            publishHistory(view)
        }

        /**
         * A main-frame load failed, in the modern signature. Port of
         * `RoomWebViewClient.onReceivedError(WebView, WebResourceRequest,
         * WebResourceError)` (WebClients.kt:249-270), minus the upgrade
         * fallback, which the facade places above and answers through
         * [onPageError]'s url.
         *
         * WebView's own return type here is void, so the listener's "I handled
         * it" answer cannot be honoured and is deliberately not consulted: the
         * platform's error surface stands, exactly as it does today, and the
         * app's own error UI is what covers it.
         */
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val failedUrl = request.url.toString()
            listener?.onPageError(
                this@WebViewEngineSession,
                failedUrl,
                kindOf(error.errorCode),
                true,
                // Passed through untouched, and that is not laziness: this
                // edition's codes ARE the space the app's policy is written
                // against (EngineErrorCode), so a mapping here would be the
                // identity function with a chance of being wrong.
                error.errorCode,
                error.description?.toString()
            )
        }

        /**
         * THE LEGACY 4-ARG ERROR CALLBACK, and why it is not redundant.
         *
         * Some WebView stacks report main-frame transport failures -- a
         * TLS handshake reset mid-flight, a plain-http port answering an https
         * attempt -- ONLY through this deprecated signature. The default
         * implementation is a no-op, so dropping it loses the failure
         * entirely: CI proved exactly that for the upgrade path
         * (BrowserNavigationE2eTest: the https attempt started, the back/forward
         * controls lit up, and then nothing -- no error surface, no recovery).
         *
         * The app's own override existed ONLY to consume its upgrade-fallback
         * registry and deliberately did not report, because that registry lived
         * in this class. The registry now lives above the facade, where it is
         * fed by [onPageError], so this callback has to report or the fallback
         * it used to trigger is unreachable on those stacks. A stack that calls
         * BOTH signatures reports the failure twice; the caller's registry is
         * consume-based (it removes the entry it matches), so the second report
         * matches nothing and changes nothing, and the app's own error state is
         * a plain assignment. The alternative -- reporting from one signature
         * only -- silently loses the failure on half the devices.
         *
         * IS IT TOP LEVEL? This signature carries no frame flag, and an
         * earlier version of this method answered `true` unconditionally --
         * which is a claim it cannot support. The app paints a full-screen
         * error surface for a top-level failure, so that answer let a
         * SUB-RESOURCE failure (a blocked image, a font that never loaded)
         * paint a full-screen error over a perfectly healthy page. The
         * comparison below is the honest answer available here: the failure is
         * top-level exactly when it names the main frame being tracked, and
         * anything else -- a different URL, or no URL at all -- is not, which
         * errs toward leaving a good page alone.
         */
        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            listener?.onPageError(
                this@WebViewEngineSession,
                failingUrl,
                kindOf(errorCode),
                failingUrl != null && failingUrl == mainFrameUrl,
                errorCode,
                description
            )
        }

        /**
         * Port of `RoomWebViewClient.onReceivedSslError`
         * (WebClients.kt:337-367), minus the upgrade fallback (above the
         * facade now).
         *
         * NEVER PROCEED. There is no `handler.proceed()` anywhere in this file
         * and there must never be one: the user decides, and the facade's
         * [PageErrorKind.CERTIFICATE] is what tells the shared policy to render
         * "this connection is not secure" rather than "the site is
         * unreachable".
         *
         * A certificate that is bad for a SUB-RESOURCE is that resource's
         * problem, not the page's: the handler is cancelled, the resource is
         * not loaded, and nothing is reported -- exactly what the app did, and
         * what stops one expired ad-server certificate from replacing a good
         * page with a full-screen error.
         *
         * The url reported is the one that FAILED (`error.url`), never the
         * committed url: for a main-frame navigation `getUrl()` still names the
         * previous page, and telling the user that address is insecure when it
         * is not is the same class of lie the app's comment records.
         *
         * The certificate the connection presented goes along with the report:
         * it is the evidence behind the refusal, since a network intercepting
         * TLS presents one no public CA signed.
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            handler.cancel()
            if (!WebViewSslFrames.isMainFrameFailure(error.url, mainFrameUrl, view.url)) return
            listener?.onPageError(
                this@WebViewEngineSession,
                error.url ?: view.url ?: "",
                PageErrorKind.CERTIFICATE,
                true,
                error.primaryError,
                null,
                engineCertificate(error)
            )
        }

        /**
         * The renderer behind this WebView died -- it crashed, or the system
         * reclaimed it for memory.
         *
         * NOT OVERRIDING THIS IS NOT NEUTRAL. The default answer is `false`,
         * which tells the platform the app did NOT handle the death, and the
         * platform's documented response is to kill or crash the whole app --
         * a "the browser suddenly died" report that points nowhere near here.
         * Returning true takes the recovery on ourselves, and the smallest
         * honest recovery is the one the app already has: report the failure
         * as a page error, which draws the retry surface the app knows how to
         * draw and drops it for a background session (the app's error gate
         * ignores non-active sessions), while a fresh navigation -- what the
         * retry button, the omnibox and every other way back into this tab
         * produce -- is what restarts a renderer.
         *
         * The platform's own fuller recipe -- destroy this WebView and build
         * a new one -- would mean tearing the bridges, devtools and view
         * wiring off the dead view and re-attaching them to the new one;
         * that is session-lifecycle work that belongs above the facade (see
         * the facade's own `onClosed`, still unwired), not this callback.
         * Until that exists, this keeps the process alive and hands the user
         * a retry instead of a frozen tab or a killed app.
         *
         * The report carries the page's own url ([mainFrameUrl]) and the
         * OTHER kind -- nothing about the SITE is wrong -- with didCrash()
         * only inside the free-form description, which the diagnostics
         * screens show and nothing parses.
         */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            listener?.onPageError(
                this@WebViewEngineSession,
                mainFrameUrl,
                PageErrorKind.OTHER,
                true,
                EngineErrorCode.UNKNOWN,
                "renderer process gone (crashed=" + detail.didCrash() + ")"
            )
            return true
        }

        /**
         * Port of `RoomWebViewClient.onReceivedHttpAuthRequest`
         * (WebClients.kt:385-409).
         *
         * An HTTP status error (`onReceivedHttpError`) and the first-frame
         * signal (`onPageCommitVisible`) are deliberately absent: the app used
         * both for its trace only, and the facade has no member for either.
         * They are gaps in the facade, not in this port.
         *
         * The handler is single-shot and the app's `answered` guard is
         * reproduced inside the responder. `useHttpAuthUsernamePassword` is
         * not consulted, exactly as in the app: this browser keeps no WebView
         * credential database, so every challenge is answered by the user.
         */
        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String?,
            realm: String?
        ) {
            listener?.onHttpAuthRequest(
                this@WebViewEngineSession,
                host.orEmpty(),
                realm.orEmpty(),
                WebViewHttpAuthResponder(handler)
            )
        }
    }

    // ---- the WebChromeClient ----------------------------------------------

    private val webChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            currentProgress = newProgress
            listener?.onProgress(this@WebViewEngineSession, newProgress)
        }

        /**
         * Every console message the engine reports, marked engine-origin.
         *
         * This callback is NOT the page patch's complement: WebView reports the
         * page's own `console.log` calls here as well, so one line reaches the
         * console sink twice. The inspector is what tells the two apart -- it
         * drops the copy of a line the patch has already reported and keeps the
         * ones the patch never saw, such as a CSP violation the engine raised.
         *
         * RETURNS FALSE so the engine's own handling is unchanged -- the default
         * still writes the message to logcat exactly as it did before this
         * member existed.
         */
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            devToolsInspector.onConsole(
                EngineConsoleMessage(
                    // WebView's own levels, not the page patch's vocabulary:
                    // there is no `assert` here, and TIP is informational.
                    level = when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.TIP -> "info"
                        ConsoleMessage.MessageLevel.LOG -> "log"
                        ConsoleMessage.MessageLevel.WARNING -> "warn"
                        ConsoleMessage.MessageLevel.ERROR -> "error"
                        ConsoleMessage.MessageLevel.DEBUG -> "debug"
                        else -> "log"
                    },
                    text = message.message(),
                    source = message.sourceId()?.takeIf { it.isNotEmpty() },
                    line = message.lineNumber(),
                    timestampMs = System.currentTimeMillis(),
                    fromEngine = true
                )
            )
            return false
        }

        /**
         * Port of `RoomWebChromeClient.onReceivedTitle` (WebClients.kt:535-537):
         * `title?.let { ... }` -- a NULL TITLE IS DROPPED, neither stored nor
         * reported, so the session keeps the last title it had. That is the
         * app's behaviour and is reproduced rather than "fixed": the facade
         * documents [title] as nullable ("or null when unknown"), and the
         * GeckoView edition does report null, so this is a real difference
         * between the editions rather than a detail. It is left as it is
         * because changing it changes what the shared code above is handed,
         * which is exactly the kind of change that must not slip through
         * unreviewed.
         *
         * NOTE, and it is the app's known limitation rather than a new one:
         * this callback is not guaranteed to be main-frame only, so an iframe
         * title can arrive here. There is no frame flag to check -- the
         * platform does not supply one.
         */
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title == null) return
            currentTitle = title
            listener?.onTitleChanged(this@WebViewEngineSession, title)
        }

        /**
         * Window creation. Port of `RoomWebChromeClient.onCreateWindow`
         * (WebClients.kt:540-599).
         *
         * THE LISTENER IS ASKED TWICE FOR ONE POPUP, and it has to be, because
         * of how WebView reports a popup: `onCreateWindow` must answer
         * synchronously and is given NO URL, while the URL only exists once
         * the transport WebView it hands back navigates. So the first call
         * carries a null url and is the GATE (the profile's popup policy and
         * the "not the active tab" refusal both live above the facade and
         * cannot be decided here); the second carries the resolved target and
         * is the ACTION. Answering false to the gate refuses the popup and the
         * page simply sees `window.open()` fail, which is what the app's
         * refusal paths did.
         *
         * The gesture flag is carried from the gate into the action so the
         * caller does not have to remember it, and the transport is reaped
         * after [TRANSPORT_REAP_MS] whether or not it ever navigated: the
         * transport only dies when it NAVIGATES, and a `window.open()` with no
         * URL, or one the page keeps as a handle, would otherwise hold a
         * renderer alive for the life of the process.
         *
         * THE SECOND CALL'S RETURN VALUE IS NOT CONSULTED. By the time the
         * target is known the transport has already been handed to the engine
         * and is navigating; there is no longer a way to un-answer the first
         * call, and `shouldOverrideUrlLoading` returning true suppresses the
         * load, so a refusal lands as a popup that never fills in and is reaped
         * a moment later. The app's own refusal for this case was identical --
         * it stopped the load and destroyed the transport.
         *
         * The gate is asked BEFORE the transport exists, which preserves the
         * app's ordering guarantee: "a popup from a background engine is
         * refused outright, BEFORE the transport is built" (WebClients.kt:551-553),
         * so a background page cannot even cost a renderer for its popup.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val accepted = listener?.onNewWindowRequest(
                this@WebViewEngineSession,
                isUserGesture
            ) ?: false
            if (!accepted) return false

            val temp = WebView(view.context)
            // Single-shot destroy, shared by the navigation callback and the
            // timeout below. Main-thread only (onCreateWindow, the client
            // callback and postDelayed all run there), so a plain Boolean is
            // the right guard -- destroy() on an already-destroyed WebView
            // throws.
            var reaped = false
            val reap = {
                if (!reaped) {
                    reaped = true
                    runCatching { temp.stopLoading() }
                    runCatching { temp.destroy() }
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
                    // The gate above already said yes; this is the second half
                    // of the protocol, and it is the ONLY call that carries a
                    // URL. Reporting it as a fresh request -- which is what an
                    // earlier form of this file did, by passing the target back
                    // to onNewWindowRequest -- would ask the gate a question it
                    // has already answered, and a gate that refuses a null URL
                    // reads the answer as "nothing to open".
                    listener?.onNewWindowResolved(
                        this@WebViewEngineSession,
                        target,
                        isUserGesture
                    )
                    return true
                }
            }
            (resultMsg?.obj as? WebView.WebViewTransport)?.webView = temp
            resultMsg?.sendToTarget()
            temp.postDelayed({ reap() }, TRANSPORT_REAP_MS)
            return true
        }

        /**
         * FULLSCREEN: THE ADAPTER OWNS THE SURFACE.
         *
         * The facade says so in as many words -- the WebView edition hands out
         * a separate view to render, GeckoView renders fullscreen inside its
         * own view, and rather than put a view in the facade that one edition
         * has nothing to put in it, the engine adapter keeps the view and the
         * app only reacts to the state. So this class holds the custom view,
         * mounts it over the session's own host (the `ViewGroup` [attachTo]
         * was given) and unmounts it again, and the listener hears only
         * "entered" / "left".
         *
         * `onCustomViewHidden()` MUST be called or WebView keeps rendering
         * fullscreen content into a view nobody is showing, which is a browser
         * with no visible chrome -- the failure the facade's own comment
         * warns about. It is called on the way OUT here, because the app's
         * chrome callback (`RoomWebChromeClient.onHideCustomView`,
         * WebClients.kt:571-575) was the one that used to do it and that
         * callback is now this code.
         */
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) {
                // One at a time: WebView treats the pair as single-shot, and a
                // second entry would strand the first callback with no way to
                // acknowledge it.
                runCatching { callback.onCustomViewHidden() }
                return
            }
            customView = view
            customViewCallback = callback
            mountCustomView()
            listener?.onFullScreen(this@WebViewEngineSession, true)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        /**
         * Port of `RoomWebChromeClient.onPermissionRequest`
         * (WebClients.kt:609-650).
         *
         * PROTECTED MEDIA IS ANSWERED ON THE SPOT, exactly as the app answers
         * it: the resource is not a window onto anything the user owns, it is
         * a request to decode content the page is already delivering with keys
         * it obtained from its own licence server, so there is nothing for a
         * prompt to protect -- and denying it is why video stalls on
         * "initializing" on the sites that use it. MIDI sysex, the remaining
         * kind-less resource, is refused: the app has no MIDI device story.
         *
         * A MIXED REQUEST (camera or microphone AND protected media) stays
         * with the sheet, because `PermissionRequest` is single-shot and a
         * partial grant would leave the other half hanging -- which is also
         * why the responder grants the WHOLE resource array, as the app's
         * stored-decision path does (BrowserViewModel.kt:703).
         *
         * An unanswered request hangs the page for the life of its document,
         * so a session with no listener denies rather than passing it on.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val resources = request.resources
            val wantsVideo = PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
            val wantsAudio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources
            val target = listener
            if (!wantsVideo && !wantsAudio) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                    runCatching {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    }
                } else {
                    runCatching { request.deny() }
                }
                return
            }
            if (target == null) {
                runCatching { request.deny() }
                return
            }
            target.onMediaPermissionRequest(
                this@WebViewEngineSession,
                request.origin?.toString() ?: "",
                wantsVideo,
                wantsAudio,
                WebViewPermissionResponder(request)
            )
        }

        /**
         * Port of `RoomWebChromeClient.onGeolocationPermissionsShowPrompt`
         * (WebClients.kt:652-657) and the app's answer
         * (BrowserViewModel.kt:598-628).
         *
         * `retain` is always FALSE, as the app always passes it: the WebView's
         * own per-origin store is not where a decision the user can neither
         * see nor revoke belongs.
         */
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback
        ) {
            val target = listener
            if (target == null) {
                runCatching { callback.invoke(origin, false, false) }
                return
            }
            target.onGeolocationRequest(
                this@WebViewEngineSession,
                origin,
                WebViewGeolocationResponder(origin, callback)
            )
        }

        /**
         * A `<input type=file>` request. The engine builds the picker Intent
         * here so the app never has to know which engine asked, then hands the
         * chosen documents back through `onReceiveValue`.
         *
         * `true` is returned so the platform's own picker never appears: the
         * app raises the picker, and a second one from the system would sit on
         * top of a page the user is not necessarily looking at.
         *
         * The Intent comes from `createIntent()` rather than being assembled
         * here, because that is what maps the request's mode -- single,
         * multiple, folder, save -- onto the right picker. The mode constants
         * themselves are not usable: `MODE_OPEN_FOLDER` is absent from the
         * public SDK, so a hand-built Intent cannot even name it.
         */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            val target = listener
            val intent = fileChooserParams?.createIntent()
            if (target == null || intent == null) {
                runCatching { filePathCallback?.onReceiveValue(null) }
                return true
            }
            target.onFileChooserRequest(intent) { uris ->
                runCatching { filePathCallback?.onReceiveValue(uris) }
            }
            return true
        }
    }

    /**
     * The page bridges. Each is registered under its own name so that the
     * page sees exactly the methods it saw before -- see
     * [WebViewPageChannels] for why one object installed twice would not do.
     *
     * They are declared HERE, immediately above the `init` block that installs
     * them, and that placement is load-bearing rather than tidy: Kotlin
     * initialises properties and `init` blocks in the order they appear, so a
     * property declared below the `init` that reads it is still null at that
     * moment -- `addJavascriptInterface` would be handed a null object and the
     * two globals would simply never exist, with nothing in the log to say so.
     *
     * The lambda captures this session, which the WebView already holds, so no
     * new reference is created and no extra weak indirection is needed (the
     * app's bridges used a `WeakReference` to the WebView because THEY were
     * held by a WebView they did not otherwise belong to).
     *
     * DELIVERY THREAD: `@JavascriptInterface` methods arrive on WebView's
     * JavaBridge thread and are forwarded from there, unchanged. The facade
     * requires every listener method to be safe off the main thread, and the
     * bridges above hop for themselves -- which is what the app's own bridges
     * already did (`RoomVaultBridge.main`, WebClients.kt:742).
     */
    private val vaultPageBridge = VaultPageBridge { channel, payload ->
        listener?.onPageMessage(this@WebViewEngineSession, channel, payload)
    }

    private val walletPageBridge = WalletPageBridge { channel, payload ->
        listener?.onPageMessage(this@WebViewEngineSession, channel, payload)
    }

    /**
     * `window.RoomConsole`: the DevTools console patch's entry point.
     *
     * The parse and the route live HERE rather than in [ConsolePageBridge], so
     * the bridge stays a pure transport exactly like the two above. This
     * lambda runs on WebView's JavaBridge thread; the inspector posts to main.
     */
    private val consolePageBridge = ConsolePageBridge { payload ->
        decodeConsoleEntry(payload)?.let { devToolsInspector.onConsole(it) }
    }

    /**
     * Reads `blob:` / `data:` downloads out of the page — the one place those
     * URLs mean anything. Registered as `window.RoomBlob` in the `init` block
     * below, for the same ordering reason the three bridges above are declared
     * here: an interface handed to `addJavascriptInterface` before its field is
     * assigned would be null, and the global would simply not exist.
     *
     * One instance per session rather than one per process, because a read is
     * answered by the WebView that asked, and a session's WebView is the only
     * one holding the document whose blob it is.
     */
    private val blobDownloads = WebViewBlobDownloads(webView.context)

    /**
     * Wiring, deliberately LAST in the file.
     *
     * Kotlin runs property initialisers and `init` blocks in the order they
     * appear, so this has to sit after the client properties and the
     * page-bridge properties it installs. Placed at the top -- where it reads
     * better -- it would pass not-yet-initialised values to the WebView, which
     * accepts a null client happily and would then silently deliver no
     * callbacks at all: no URL, no title, no progress, no error surface, and
     * nothing in the log to say why.
     *
     * The ORDER of the statements is `ProfileEngine.createWebView` followed by
     * `BrowserViewModel.createWebView` (ProfileEngine.kt:195-204,
     * BrowserViewModel.kt:1288-1333), which is the order the app has always
     * built an engine in: configured before anything is attached to it, then
     * the clients, then the native page bridges, then the download
     * listener.
     */
    init {
        webView.isFocusable = true
        configure(profile)
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.addJavascriptInterface(vaultPageBridge, WebViewPageChannels.VAULT_INTERFACE)
        webView.addJavascriptInterface(walletPageBridge, WebViewPageChannels.WALLET_INTERFACE)
        webView.addJavascriptInterface(consolePageBridge, WebViewPageChannels.CONSOLE_INTERFACE)
        // Same reason as the three above: it has to be reachable before a page
        // can ask for one of its own downloads to be read.
        webView.addJavascriptInterface(blobDownloads.bridge, WebViewBlobDownloads.INTERFACE)
        // After the clients and the console bridge, so the patch's target global
        // exists by the time any document runs it.
        installConsolePatch()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            listener?.onDownloadRequest(
                this@WebViewEngineSession,
                url,
                userAgent,
                contentDisposition,
                mimeType,
                contentLength
            )
        }
    }

    /** Reported when a sub-resource load is refused. Port of
     *  `RoomWebViewClient.blockedResponse` (WebClients.kt:411-412). */
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Reports the engine's history state, which is reported as STATE rather
     *  than as a reaction to a command -- the two engines disagree about when
     *  it becomes true, so the facade treats it as state and both are correct.
     *  Called from every callback the app called `onHistoryChanged` from
     *  (WebClients.kt:202, 209, 246). */
    private fun publishHistory(view: WebView) {
        val canBack = view.canGoBack()
        val canForward = view.canGoForward()
        backAvailable = canBack
        forwardAvailable = canForward
        listener?.onNavigationStateChanged(this@WebViewEngineSession, canBack, canForward)
    }

    /**
     * Leave fullscreen. The app's Back handler owns this decision, and this is
     * the only lever that can carry it out.
     *
     * WHY THIS HAS TO BE A FACADE MEMBER AT ALL.
     * [EngineSessionListener.onFullScreen] only REPORTS what the engine is
     * doing -- it is not a way to ask for anything. Without this member the
     * app could set its own fullscreen flag to false and nothing else would
     * happen: the page's view stays mounted over the web area, the page still
     * believes it is fullscreen, and the chrome that just came back is hidden
     * behind it. The user is left in a state no control can undo.
     *
     * Idempotent, and a no-op for a session that is not in fullscreen.
     */
    override fun exitFullScreen() {
        runOnMain { hideCustomView() }
    }

    /**
     * Takes the page's fullscreen content back down and tells WebView it is
     * over. Shared by WebView's own exit (the chrome-client callback) and by
     * [exitFullScreen], which is why it is not inline in that callback: doing
     * only half of it is the trap. Unmounting without `onCustomViewHidden()`
     * leaves WebView rendering fullscreen content into a view nobody is
     * showing; calling it without unmounting leaves the page's view on top of
     * the web area with nothing left that can remove it.
     *
     * Silent when there is nothing to hide, so a redundant call cannot report
     * a fullscreen exit that never happened.
     */
    private fun hideCustomView() {
        val view = customView
        val callback = customViewCallback
        if (view == null && callback == null) return
        customView = null
        customViewCallback = null
        if (view != null) runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { callback?.onCustomViewHidden() }
        listener?.onFullScreen(this@WebViewEngineSession, false)
    }

    /** Puts the page's fullscreen content over this session's own view, once
     *  there is a host to put it in. See [onShowCustomView]. */
    private fun mountCustomView() {
        val view = customView ?: return
        if (view.parent != null) return
        val container = host ?: return
        runCatching {
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /**
     * Marshals [block] to the thread every `WebView` method requires.
     *
     * Running inline when already there is not an optimisation: several
     * members have to observe their effect before returning (`saveState`), and
     * a `post` would make that impossible.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            // A lambda literal, not the function value: a Kotlin `() -> Unit`
            // does not convert to a Java SAM interface on its own.
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    /**
     * Single-shot wrapper for a media permission request. The facade requires
     * implementations to ignore a second answer, and the engine agrees with
     * it: `PermissionRequest` is a single-use handle, so a second `grant` is a
     * double release rather than a harmless mistake.
     *
     * The hop to the main thread is not defensive politeness -- a
     * `PermissionRequest` settled off the main thread is ignored by WebView
     * and the page waits for the life of its document, and the facade requires
     * every command to be dispatchable to the engine's own thread internally.
     */
    private inner class WebViewPermissionResponder(
        private val request: PermissionRequest
    ) : PermissionResponder {

        private val answered = AtomicBoolean(false)

        override fun grant() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { request.grant(request.resources) } }
        }

        override fun deny() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { request.deny() } }
        }
    }

    /** The geolocation equivalent of [WebViewPermissionResponder]. `retain` is
     *  false on both answers, always. */
    private inner class WebViewGeolocationResponder(
        private val origin: String?,
        private val callback: GeolocationPermissions.Callback
    ) : PermissionResponder {

        private val answered = AtomicBoolean(false)

        override fun grant() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { callback.invoke(origin, true, false) } }
        }

        override fun deny() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { callback.invoke(origin, false, false) } }
        }
    }

    /** The HTTP authentication equivalent, reproducing the app's `answered`
     *  guard (WebClients.kt:391-408) -- one answer for the pair, not one
     *  each. */
    private inner class WebViewHttpAuthResponder(
        private val handler: HttpAuthHandler
    ) : HttpAuthResponder {

        private val answered = AtomicBoolean(false)

        override fun proceed(username: String, password: String) {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { handler.proceed(username, password) } }
        }

        override fun cancel() {
            if (!answered.compareAndSet(false, true)) return
            runOnMain { runCatching { handler.cancel() } }
        }
    }

    private companion object {

        /**
         * Whether a page must wait for a user gesture before it may start
         * playing media. Port of `ProfileEngine.MEDIA_PLAYBACK_REQUIRES_USER_GESTURE`
         * (ProfileEngine.kt:41-46, 249): it is FALSE -- autoplay permitted --
         * because that is what the app has actually shipped, and because the
         * value it used to be derived from (`blockMalicious`) was a miswire
         * where toggling the malicious-site shield silently changed an
         * unrelated behaviour.
         */
        const val MEDIA_PLAYBACK_REQUIRES_USER_GESTURE = false

        /**
         * How long a popup transport WebView may live without navigating.
         * Port of `RoomWebChromeClient.TRANSPORT_REAP_MS`
         * (WebClients.kt:681-691): long enough that a real popup is never cut
         * off on a cold emulator, short enough that an abandoned one is not
         * holding a renderer while the user browses on.
         */
        const val TRANSPORT_REAP_MS = 10_000L

        /**
         * The arm/disarm calls for the page-world console patch. Written as a
         * guard so a document that has not yet run the patch is a no-op rather
         * than a thrown `ReferenceError`.
         */
        const val ARM_PAGE_CONSOLE = "window.__rbConsole && window.__rbConsole.arm();"
        const val DISARM_PAGE_CONSOLE = "window.__rbConsole && window.__rbConsole.disarm();"

        /** Where the identity questions are answered, in the device's own log. */
        const val IDENTITY_TAG = "RoomIdentity"

        /**
         * Whether the provider line has been written already.
         *
         * `configure` re-runs on every settings change -- every theme toggle,
         * every per-site override -- and the answer cannot change within a
         * process, so the line is written once and the rest of the run is
         * silent. A logcat full of the same line is a logcat nobody reads when
         * a sign-in finally fails.
         */
        @Volatile
        private var identityLogged: Boolean = false
    }

    /**
     * Records what the app was able to ask for, and which provider answered.
     *
     * WHY BOTH. Whether the opt-out EXISTS and whether it WORKS are different
     * facts, and only the second one reaches a server. A provider that does not
     * list the feature is one where this app has no lever at all -- the CI
     * emulator's WebView 83, which reports false here and still sends the
     * header. A provider that lists it is one where the lever is called and
     * does nothing, because androidx's own implementation of the call is an
     * empty method. One number without the other cannot be read, which is why
     * they are written on one line.
     */
    private fun logIdentityOnce(optOutSupported: Boolean) {
        if (identityLogged) return
        identityLogged = true
        Log.i(
            IDENTITY_TAG,
            "identity: x-requested-with opt-out supported=$optOutSupported " +
                "webview=${currentWebViewIdentity()}"
        )
    }

    /**
     * The WebView provider this session runs on, as `package version`.
     */
    private fun currentWebViewIdentity(): String = runCatching {
        val info = WebViewCompat.getCurrentWebViewPackage(webView.context)
        "${info?.packageName ?: "unknown"} ${info?.versionName ?: "?"}"
    }.getOrDefault("unreadable")
}

/**
 * The serialised form of a WebView session state, opaque above the facade.
 *
 * A `Bundle` is the only thing `WebView.saveState` produces, and it is the
 * counterpart of the GeckoView edition's serialised `SessionState`: the app
 * stores one per tab and hands it back on restore without ever inspecting it,
 * which is the property that keeps an engine type off the app's classpath.
 *
 * NOT PERSISTABLE ACROSS PROCESS DEATH in either edition, which is why the
 * tab manager keeps these in memory only.
 */
internal class WebViewState(val bundle: Bundle) : EngineState

/**
 * The engine's own stock user agent, captured the first time a session is
 * configured.
 *
 * CAPTURED, NOT RE-READ, and this is the whole reason the object exists:
 * `WebSettings` has no "what would you have sent" getter, so once a UA has
 * been assigned, `userAgentString` returns the assignment. The DEFAULT mode
 * has to be able to clear an identity a previous configure installed (a
 * profile switching from CUSTOM or a preset back to DEFAULT), and reading the
 * property back at that moment would return the custom UA and leave it in
 * place -- exactly the bug the surrounding comment warns about.
 *
 * Process-wide, like the app's field in `ProfileEngine` (ProfileEngine.kt:48-73),
 * because the value belongs to the PROCESS's engine and not to any session.
 * The first configure of a process always runs against a freshly constructed
 * WebView, so that first read is the engine's default.
 */
internal object WebViewStockUserAgent {

    @Volatile
    private var captured: String? = null

    fun of(settings: WebSettings): String {
        captured?.let { return it }
        val value = settings.userAgentString.orEmpty()
        captured = value
        return value
    }
}

/**
 * The error-code classification the app's `onReceivedError` applied
 * (BrowserViewModel.kt:503-508), moved to where the engine reports errors.
 *
 * The app branched on three codes explicitly -- `ERROR_HOST_LOOKUP` to a DNS
 * failure, `ERROR_CONNECT`/`ERROR_TIMEOUT` to "no internet", everything else
 * to a generic error -- and the facade's [PageErrorKind] is the engine-neutral
 * spelling of exactly that split. Codes the app did not classify stay
 * [PageErrorKind.OTHER] rather than being guessed into TRANSPORT: the
 * distinction drives which screen the user sees, and a guess there is a wrong
 * screen.
 */
private fun kindOf(errorCode: Int): PageErrorKind = when (errorCode) {
    // The NAME did not resolve. Kept apart from a host that resolved and then
    // did not answer, because the app's advice differs -- see
    // PageErrorKind.DNS. Folding it into TRANSPORT is what made the app's
    // "DNS Resolution Failed" surface unreachable dead code.
    WebViewClient.ERROR_HOST_LOOKUP -> PageErrorKind.DNS

    WebViewClient.ERROR_CONNECT,
    WebViewClient.ERROR_TIMEOUT -> PageErrorKind.TRANSPORT

    else -> PageErrorKind.OTHER
}

/**
 * The certificate a refused connection presented, or null when WebView handed
 * none over. Never throws: this runs on the error path, and a certificate the
 * platform cannot read must not replace the report with a crash.
 */
private fun engineCertificate(error: SslError): EngineCertificate? = runCatching {
    val certificate = error.certificate ?: return null
    EngineCertificate(
        subject = certificateName(certificate.issuedTo),
        issuer = certificateName(certificate.issuedBy),
        // The Date getters, not `validNotBefore`/`validNotAfter`: those two
        // return a preformatted String.
        validFromMs = certificate.validNotBeforeDate?.time,
        validToMs = certificate.validNotAfterDate?.time,
        fingerprint = certificateFingerprint(certificate)
    )
}.getOrNull()

/** `cName` first, then `oName`, then the full `dName` (API 29). */
private fun certificateName(name: SslCertificate.DName?): String? {
    if (name == null) return null
    val candidates = listOfNotNull(
        name.cName,
        name.oName,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) name.dName else null
    )
    return candidates.firstOrNull { it.isNotBlank() }
}

/** SHA-256 of the DER bytes, uppercase and colon-separated. API 29+ only. */
private fun certificateFingerprint(certificate: SslCertificate): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val der = certificate.x509Certificate?.encoded ?: return null
    return MessageDigest.getInstance("SHA-256").digest(der)
        .joinToString(":") { "%02X".format(it) }
}
