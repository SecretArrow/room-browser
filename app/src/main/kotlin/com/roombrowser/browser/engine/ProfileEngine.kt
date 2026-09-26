package com.roombrowser.browser.engine

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.WebRtcPolicy
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
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

    /** WebView package name for the diagnostics screen. */
    fun engineName(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo("com.google.android.webview", 0)
            ?: pm.getPackageInfo("com.android.webview", 0)
        "${info.packageName} ${info.versionName}"
    }.getOrDefault("Android WebView")

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
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        UserAgents.effectiveUserAgent(settings)?.let { s.userAgentString = it }
        s.textZoom = (settings.fontScale * 100f).toInt().coerceIn(50, 200)

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
     * Desktop-site toggle for a specific WebView (per-tab / per-site).
     */
    fun applyDesktopMode(webView: WebView, profile: Profile, desktop: Boolean) {
        val s = webView.settings
        if (desktop) {
            s.userAgentString = UserAgents.all.first { it.id == "chrome_windows" }.value
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
        } else {
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            when (profile.settings.uaMode) {
                UaMode.DEFAULT -> s.userAgentString = null
                else -> UserAgents.effectiveUserAgent(profile.settings)?.let { s.userAgentString = it }
            }
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
