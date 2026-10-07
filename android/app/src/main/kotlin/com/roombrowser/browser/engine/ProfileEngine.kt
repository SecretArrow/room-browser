package com.roombrowser.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.roombrowser.browser.RoomVaultScript
import com.roombrowser.browser.wallet.dapp.RoomWalletScript
import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.FingerprintProfile
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.model.WebRtcPolicy
import com.roombrowser.domain.model.claimedScreen
import java.io.File

/**
 * Profile engine — configures WebViews for exactly ONE profile per process.
 *
 * ISOLATION MODEL (see PROFILE_ISOLATION.md for the full write-up):
 *  - WebView.setDataDirectorySuffix(<profile-safe-suffix>) is called ONCE,
 *    before the first WebView is created in the ':browser' process. This
 *    gives every profile its own on-disk cookie jar, localStorage,
 *    IndexedDB, service workers, HTTP cache and Web SQL/WebView state.
 *  - The suffix is derived from the immutable profile UUID (never the name).
 *  - Switching profiles destroys the WebView tree and RESTARTS the
 *    ':browser' process (ProfileSwitchExecutor) because the suffix is
 *    process-wide and cannot be changed at runtime.
 */
object ProfileEngine {

    /**
     * Whether a page must wait for a user gesture before it may start
     * playing media. See the note at the assignment in [configure] for why
     * this is a constant rather than a setting, and why it is false.
     */
    private const val MEDIA_PLAYBACK_REQUIRES_USER_GESTURE = false

    @Volatile
    private var boundProfileId: ProfileId? = null

    /**
     * The engine's own stock UA, captured the first time [configure] runs.
     *
     * CAPTURED, not re-read, because WebSettings has no "what would you have
     * sent" getter: once a UA has been assigned, `userAgentString` returns the
     * assignment. This matters for the DEFAULT mode, which must be able to
     * clear an identity a previous configure installed (a profile switching
     * from CUSTOM back to DEFAULT) — reading the property back at that moment
     * would return the custom UA and leave it in place, which is exactly the
     * bug the surrounding comment warns about.
     *
     * The first configure() of a process always runs against a freshly
     * constructed WebView, so that first read is the engine's default.
     */
    @Volatile
    private var stockUserAgentCapture: String? = null

    private fun stockUserAgent(settings: WebSettings): String {
        stockUserAgentCapture?.let { return it }
        val captured = settings.userAgentString.orEmpty()
        stockUserAgentCapture = captured
        return captured
    }

    /**
     * The device shim currently installed per WebView, so reconfiguring a
     * live WebView replaces its script instead of adding another one.
     */
    private val deviceShims = java.util.WeakHashMap<WebView, ScriptHandler>()

    /**
     * The password-manager page bridge script currently installed per
     * WebView — same replace-on-reconfigure pattern as [deviceShims]: the
     * script itself is idempotent (window.__roomVaultInstalled guard), but
     * a reconfigure must never stack a second document-start handler either.
     */
    private val vaultScripts = java.util.WeakHashMap<WebView, ScriptHandler>()

    /**
     * The wallet dApp provider script currently installed per WebView —
     * same replace-on-reconfigure pattern as [vaultScripts]: the script is
     * idempotent (window.__roomWalletInstalled guard) but a reconfigure
     * must never stack a second document-start handler.
     */
    private val walletScripts = java.util.WeakHashMap<WebView, ScriptHandler>()

    /**
     * WebView package name + version for the diagnostics screen.
     *
     * [WebView.getCurrentWebViewPackage] is asked FIRST because it names the
     * provider this process is actually rendering with, which is the only
     * answer a diagnostics screen wants; probing package names can only ever
     * report what is installed. The probe chain remains as a fallback for the
     * pre-provider-selection window and for vendor builds that return null.
     *
     * The old chain was `pm.getPackageInfo(a) ?: pm.getPackageInfo(b)`, which
     * could never reach `b`: `getPackageInfo` signals "not installed" by
     * throwing [android.content.pm.PackageManager.NameNotFoundException], not
     * by returning null, so the elvis was dead and the throw unwound to
     * `getOrDefault`. On an AOSP device carrying only `com.android.webview`
     * the screen therefore showed the bare "Android WebView" placeholder.
     */
    fun engineName(context: Context): String {
        runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()?.let { info ->
            return "${info.packageName} ${info.versionName ?: "?"}"
        }
        val pm = context.packageManager
        listOf("com.google.android.webview", "com.android.webview").forEach { name ->
            runCatching { pm.getPackageInfo(name, 0) }.getOrNull()?.let { info ->
                return "${info.packageName} ${info.versionName ?: "?"}"
            }
        }
        return "Android WebView"
    }

    /** A WebView-provider package installed on this device (diagnostics). */
    data class EngineOption(val packageName: String, val versionName: String, val isCurrent: Boolean)

    /**
     * All WebView-provider packages we can detect, current one flagged.
     * Read-only diagnostics — Android manages the active provider; this list
     * only reports what is installed (feeds the Settings → Engine dropdown).
     * Never throws.
     */
    fun installedWebViewEngines(context: Context): List<EngineOption> {
        // API 26+; minSdk is 28 so the direct call is fine — still guarded.
        val currentInfo = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val currentPackage = currentInfo?.packageName
        val pm = context.packageManager
        val candidates = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.android.chrome"
        )
        val found = mutableListOf<EngineOption>()
        candidates.forEach { name ->
            runCatching { pm.getPackageInfo(name, 0) }.getOrNull()?.let { info ->
                if (found.none { it.packageName == info.packageName }) {
                    found.add(EngineOption(info.packageName, info.versionName ?: "?", false))
                }
            }
        }
        // The actually-active provider may be a vendor package outside the
        // candidate list — make sure it is listed too.
        if (currentPackage != null && found.none { it.packageName == currentPackage }) {
            found.add(EngineOption(currentPackage, currentInfo?.versionName ?: "?", false))
        }
        if (found.isEmpty()) {
            // Defensive: nothing detectable at all — always fall back to at
            // least one entry matching engineName()'s source package so the
            // dropdown never renders empty.
            return listOf(EngineOption("com.google.android.webview", "?", true))
        }
        return found
            .map { it.copy(isCurrent = it.packageName == currentPackage) }
            .sortedWith(compareByDescending<EngineOption> { it.isCurrent }.thenBy { it.packageName })
    }

    /**
     * Bind this process to [profile]. MUST be called before the first
     * WebView is instantiated. Returns false when the process is already
     * bound to a DIFFERENT profile (the caller must restart the process).
     */
    fun bindProcessToProfile(profile: ProfileId): Boolean {
        val current = boundProfileId
        if (current == profile) return true
        if (current != null) return false // bound to another profile → restart required
        val suffix = profile.safeSuffix
        require(suffix.length in 1..32 && suffix.all { it.isLetterOrDigit() }) {
            "Invalid WebView data directory suffix"
        }
        WebView.setDataDirectorySuffix(suffix)
        boundProfileId = profile
        return true
    }

    fun boundProfile(): ProfileId? = boundProfileId

    /**
     * Create and configure a WebView for the bound profile.
     * All engine-level settings are profile-driven (UA, JS, cookies...).
     */
    fun createWebView(context: Context, profile: Profile): WebView {
        check(boundProfileId == profile.id) {
            "Process is not bound to profile ${profile.id} — restart required"
        }
        val settings = profile.settings
        val webView = WebView(context)
        webView.isFocusable = true
        configure(webView, profile)
        return webView
    }

    fun configure(webView: WebView, profile: Profile) {
        val s: WebSettings = webView.settings
        val settings: ProfileSettings = profile.settings

        // POLICY (user mandate): JavaScript is NEVER disabled by default.
        // The platform WebView default is javaScriptEnabled == false — we
        // ALWAYS apply it explicitly from ProfileSettings, whose own default
        // is `true` (guarded by the `compatibility defaults` unit test in
        // core:domain). Only an explicit per-profile toggle (Settings) or an
        // explicit per-site override (site settings jsEnabled) may turn it
        // off — never a default path.
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
        // Autoplay used to be decided by `settings.blockMalicious`, which is a
        // miswire: blocking malicious sites has nothing to do with whether a
        // page may start playing media, so toggling that one shield silently
        // changed an unrelated behaviour. It is now an explicit constant.
        //
        // It is FALSE — autoplay permitted — because that is both what the
        // app has actually shipped (blockMalicious defaults to false, so the
        // old expression evaluated to false for every fresh install and every
        // profile that never touched the shield) and what compatibility-first
        // mode means everywhere else in this app. A user who had turned the
        // malicious-site shield on did get gesture-gated media as a side
        // effect; that side effect is the bug being removed, not a feature.
        //
        // A real per-profile "block autoplay" setting would need: a
        // ProfileSettings field + entity column + migration, a toggle in the
        // settings UI, and — for the per-site `autoplay_blocked` column that
        // already exists but has no consumer — a host at configure() time,
        // which does not exist today. Until that lands this constant is the
        // single place the answer lives.
        s.mediaPlaybackRequiresUserGesture = MEDIA_PLAYBACK_REQUIRES_USER_GESTURE
        s.javaScriptCanOpenWindowsAutomatically = false
        // Mixed content: compatibility mode by default (blockMixedContent=false).
        // NEVER_ALLOW blanked real-world sites that still load some http
        // sub-resources (legacy CDNs, older image hosts); users who want the
        // strict behavior can enable "Block mixed content" in settings.
        s.mixedContentMode = if (settings.blockMixedContent) {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // A null result means "use the WebView default", and it must be
        // APPLIED, not skipped: configure() re-runs on every settings change
        // (reconfigureAllWebViews), so switching a profile from a device,
        // preset or custom UA BACK TO DEFAULT has to clear the UA the live
        // WebView is still carrying — `?.let` alone would leave the old
        // identity installed on every open WebView until the process
        // restarted. Same rule the desktop-mode toggle applies below
        // (UaMode.DEFAULT -> null); assigning null is how WebSettings resets
        // to the engine's own UA.
        //
        // DEFAULT no longer means "the engine's UA verbatim": the engine's own
        // string identifies it as a WebView, and video sites in particular
        // serve a degraded player to that identity — the load-forever,
        // never-starts behaviour this mode was reported for. The markers come
        // off; see UserAgents.webViewNeutralUserAgent for why stripping is
        // preferred to substituting a fixed Chrome string.
        val chosen = UserAgents.effectiveUserAgent(settings)
        s.userAgentString = chosen
            ?: UserAgents.webViewNeutralUserAgent(stockUserAgent(s))
        s.textZoom = (settings.fontScale * 100f).toInt().coerceIn(50, 200)

        val device = UserAgents.device(settings)
        applyDeviceShim(
            webView,
            device,
            settings.claimedScreen(),
            settings.webRtcPolicy,
            FingerprintProfile.from(settings.fingerprintSeed, device)
        )

        // Password-manager page bridge: a SEPARATE document-start script
        // from the device shim (installed on every configure, including
        // reconfigures). Page JS then sees window.RoomVault (added by the
        // ViewModel's createWebView) + the detection/fill script.
        applyVaultScript(webView)

        // Wallet dApp providers: ANOTHER separate document-start script
        // (window.ethereum / window.solana / window.aptos / window.suiWallet /
        // window.tronLink). Always installed — a page with no wallet sees
        // nothing happen; the native RoomWallet interface (added by the
        // ViewModel's createWebView) stays silent until a dApp calls it.
        applyWalletScript(webView)

        // Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)

        // Android Autofill integration (spec section 22) — respects system autofill:
        // AUTO participates in the system autofill framework; EXCLUDE opts the
        // WebView out when the profile disables autofill.
        webView.importantForAutofill =
            if (settings.autofillEnabled) android.view.View.IMPORTANT_FOR_AUTOFILL_AUTO
            else android.view.View.IMPORTANT_FOR_AUTOFILL_NO

        applyWebAuthnSupport(webView)
    }

    /**
     * Let the page use passkeys.
     *
     * WebView ships WebAuthn switched OFF and aborts `navigator.credentials`
     * the moment it is called, which a page that only offers passkey sign-in
     * shows as a spinner that never ends. FOR_BROWSER is the mode for a
     * browser: FOR_APP covers only a site the app itself owns through Digital
     * Asset Links, and this browser owns none.
     *
     * The gate is not optional — the call throws on a WebView whose APK
     * predates the API, and the mode then stays at its aborted-by-default
     * value, which is the pre-existing behaviour rather than a new failure.
     *
     * The suppression covers a stale lint model, not a wrong constant:
     * androidx.webkit 1.12.1 annotates the feature name with a value list
     * that predates WEB_AUTHENTICATION, while the AAR this builds against
     * does declare it (verified in WebViewFeature.class).
     */
    @SuppressLint("WrongConstant")
    private fun applyWebAuthnSupport(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) return
        WebSettingsCompat.setWebAuthenticationSupport(
            webView.settings,
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
        )
    }

    /**
     * Install (or clear) the shim for one WebView.
     *
     * All four inputs are profile state, so all four are passed in:
     * [device] is the identity the profile presents, [screen] the size it
     * claims, [webRtc] the policy it applies to peer connections and
     * [fingerprint] the values derived from its seed. Any of them may be
     * absent, and each one is independent of the others.
     * Desktop mode clears the device but keeps the screen claim — a browser
     * window on a screen of a stated size is not a contradiction, while an
     * Android client-hint set under a desktop UA is — and it keeps the
     * WebRTC policy too, which has nothing to do with which identity is
     * being presented.
     *
     * The early return covers all four: a profile with no device, no seed,
     * no screen claim and a non-default WebRTC policy still has something to
     * install. Making that return about the device would silently drop the
     * policy for every profile that never picked one.
     *
     * `configure` runs again every time settings change, so the previous
     * script is removed first — otherwise a long session would stack one copy
     * of the shim per edit. The script is idempotent, but leaking handlers is
     * still a leak. Removal goes through the handler the add returned;
     * WebViewCompat has no free-standing remove call.
     */
    private fun applyDeviceShim(
        webView: WebView,
        device: Device?,
        screen: ClaimedScreen?,
        webRtc: WebRtcPolicy,
        fingerprint: FingerprintProfile
    ) {
        deviceShims.remove(webView)?.let { previous ->
            // The view may already be gone; a failed removal costs nothing.
            runCatching { previous.remove() }
        }
        if (device == null && !fingerprint.isSeeded && screen == null &&
            webRtc == WebRtcPolicy.DEFAULT
        ) {
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            deviceShims[webView] = WebViewCompat.addDocumentStartJavaScript(
                webView, DeviceShim.scriptFor(device, screen, webRtc, fingerprint), setOf("*")
            )
        }
    }

    /**
     * Install the vault bridge script for one WebView. Always installed —
     * the offer/save surfaces decide themselves whether anything is shown
     * (a locked vault stays silent), so there is no per-profile toggle here.
     *
     * HONEST LIMIT: DOCUMENT_START_SCRIPT is the same WebView feature the
     * device shim uses; on a WebView too old to support it the script (and
     * therefore login autofill / save detection) is silently absent — the
     * native RoomVault interface is still exposed but nothing calls it.
     */
    private fun applyVaultScript(webView: WebView) {
        vaultScripts.remove(webView)?.let { previous ->
            runCatching { previous.remove() }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            vaultScripts[webView] = WebViewCompat.addDocumentStartJavaScript(
                webView, RoomVaultScript.SCRIPT, setOf("*")
            )
        }
    }

    /**
     * Install the wallet dApp provider script for one WebView. Always
     * installed — providers stay dormant until a dApp actually calls them,
     * and a locked/absent wallet answers requests with errors, never
     * prompts. Same WebView-feature availability limit as [applyVaultScript].
     */
    private fun applyWalletScript(webView: WebView) {
        walletScripts.remove(webView)?.let { previous ->
            runCatching { previous.remove() }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            walletScripts[webView] = WebViewCompat.addDocumentStartJavaScript(
                webView, RoomWalletScript.SCRIPT, setOf("*")
            )
        }
    }

    /**
     * Desktop-site toggle for a specific WebView (per-tab / per-site).
     */
    fun applyDesktopMode(webView: WebView, profile: Profile, desktop: Boolean) {
        val s = webView.settings
        val screen = profile.settings.claimedScreen()
        if (desktop) {
            s.userAgentString = UserAgents.desktopModeUserAgent
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
            // A desktop UA with an Android client-hint set underneath it is a
            // contradiction, so the device shim comes off while desktop mode
            // is on and goes back when it is turned off. The screen claim is
            // not an Android client hint and stays: a desktop browser window on
            // a screen of a stated size is an ordinary thing.
            applyDeviceShim(
                webView,
                null,
                screen,
                profile.settings.webRtcPolicy,
                // The derived surfaces go with the Android identity: a Windows
                // UA must not carry Android touch points.
                FingerprintProfile.legacy()
            )
        } else {
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            when (profile.settings.uaMode) {
                UaMode.DEFAULT -> s.userAgentString = null
                else -> UserAgents.effectiveUserAgent(profile.settings)?.let { s.userAgentString = it }
            }
            val device = UserAgents.device(profile.settings)
            applyDeviceShim(
                webView,
                device,
                screen,
                profile.settings.webRtcPolicy,
                FingerprintProfile.from(profile.settings.fingerprintSeed, device)
            )
        }
    }

    /**
     * Clear ALL engine storage for the given profile. Must be called from a
     * process bound to that profile (the directories are per-suffix).
     */
    fun clearEngineStorage(context: Context, profileId: ProfileId) {
        check(boundProfileId == profileId) { "Process not bound to ${profileId.value}" }
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        WebViewDatabase.getInstance(context).clearFormData()
        // clearCache(true) is an instance method that wipes the whole
        // per-suffix HTTP cache, so a throwaway WebView is the only way to ask
        // for it here — but it has to be DESTROYED again. Without destroy()
        // every clear-browsing-data and profile-delete run left an undestroyed
        // WebView behind, each holding a renderer binding and this Context
        // until the GC happened to collect it. The line itself has to stay:
        // [wipeProfileStorage] is a SEPARATE entry point that neither caller of
        // this function runs, so nothing else clears the cache on this path.
        runCatching {
            val scratch = WebView(context)
            try {
                scratch.clearCache(true)
            } finally {
                scratch.destroy()
            }
        }
    }

    /**
     * Delete the profile's WebView data directories from disk (belt & braces
     * on top of [clearEngineStorage], whose deletion is asynchronous).
     *
     * Filesystem-only, so unlike [clearEngineStorage] it needs no binding and
     * runs from the process that deletes the profile. It must NOT be called
     * while another process is still bound to [profileId]: a live WebView
     * writes its directories straight back.
     */
    fun wipeProfileStorage(context: Context, profileId: ProfileId) {
        val suffix = profileId.safeSuffix
        val candidates = listOf(
            File(context.applicationInfo.dataDir, "app_webview_$suffix"),
            File(context.cacheDir, "WebView_$suffix"),
            File(context.applicationInfo.dataDir, "app_webview_${suffix}_" + "cache"),
            File(context.cacheDir, "http_cache_$suffix")
        )
        candidates.forEach { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    /**
     * Diagnostic: compute per-profile engine storage footprint. The exact
     * directory layout is an Android implementation detail; we measure the
     * documented layout plus our own profile dirs and report the sum.
     */
    fun profileStorageBytes(context: Context, profileId: ProfileId): Long {
        val suffix = profileId.safeSuffix
        val dirs = listOf(
            File(context.applicationInfo.dataDir, "app_webview_$suffix"),
            File(context.cacheDir, "WebView_$suffix"),
            File(context.filesDir, "profiles/profile_${profileId.value}")
        )
        return dirs.filter { it.exists() }.sumOf { it.dirSize() }
    }

    private fun File.dirSize(): Long = walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }

    /** Session-scoped private-tab cleanup (documented limitation mitigation). */
    fun clearSessionArtifacts(context: Context) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()
        runCatching { WebViewDatabase.getInstance(context).clearFormData() }
    }
}
