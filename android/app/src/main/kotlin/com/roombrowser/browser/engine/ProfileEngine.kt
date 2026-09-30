package com.roombrowser.browser.engine

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.Device
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

    @Volatile
    private var boundProfileId: ProfileId? = null

    /**
     * The device shim currently installed per WebView, so reconfiguring a
     * live WebView replaces its script instead of adding another one.
     */
    private val deviceShims = java.util.WeakHashMap<WebView, ScriptHandler>()

    /** WebView package name for the diagnostics screen. */
    fun engineName(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo("com.google.android.webview", 0)
            ?: pm.getPackageInfo("com.android.webview", 0)
        "${info.packageName} ${info.versionName}"
    }.getOrDefault("Android WebView")

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
        s.mediaPlaybackRequiresUserGesture = settings.blockMalicious // autoplay policy follows profile
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
        UserAgents.effectiveUserAgent(settings)?.let { s.userAgentString = it }
        s.textZoom = (settings.fontScale * 100f).toInt().coerceIn(50, 200)

        applyDeviceShim(webView, UserAgents.device(settings), settings.claimedScreen())

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
    }

    /**
     * Install (or clear) the shim for one WebView.
     *
     * Both halves are profile state, so both are passed in: [device] is the
     * identity the profile presents and [screen] the size it claims, and either
     * may be absent. Desktop mode clears the device but keeps the screen claim
     * — a browser window on a screen of a stated size is not a contradiction,
     * while an Android client-hint set under a desktop UA is.
     *
     * `configure` runs again every time settings change, so the previous
     * script is removed first — otherwise a long session would stack one copy
     * of the shim per edit. The script is idempotent, but leaking handlers is
     * still a leak. Removal goes through the handler the add returned;
     * WebViewCompat has no free-standing remove call.
     */
    private fun applyDeviceShim(webView: WebView, device: Device?, screen: ClaimedScreen?) {
        deviceShims.remove(webView)?.let { previous ->
            // The view may already be gone; a failed removal costs nothing.
            runCatching { previous.remove() }
        }
        if (device == null && screen == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            deviceShims[webView] = WebViewCompat.addDocumentStartJavaScript(
                webView, DeviceShim.scriptFor(device, screen), setOf("*")
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
            s.userAgentString = UserAgents.all.first { it.id == "chrome_windows" }.value
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
            // A desktop UA with an Android client-hint set underneath it is a
            // contradiction, so the device shim comes off while desktop mode
            // is on and goes back when it is turned off. The screen claim is
            // not an Android client hint and stays: a desktop browser window on
            // a screen of a stated size is an ordinary thing.
            applyDeviceShim(webView, null, screen)
        } else {
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            when (profile.settings.uaMode) {
                UaMode.DEFAULT -> s.userAgentString = null
                else -> UserAgents.effectiveUserAgent(profile.settings)?.let { s.userAgentString = it }
            }
            applyDeviceShim(webView, UserAgents.device(profile.settings), screen)
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
        runCatching { WebView(context).clearCache(true) } // best effort; dirs wiped below too
    }

    /** Wipe the profile's WebView data directories from disk (belt & braces). */
    fun wipeWebViewDirs(context: Context, profileId: ProfileId) {
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
