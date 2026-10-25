package com.roombrowser.engine.webview

import androidx.webkit.UserAgentMetadata
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UserAgents

/**
 * The `Sec-CH-UA*` identity a profile sends, derived from the SAME answer that
 * produced its User-Agent string.
 *
 * WHY THIS EXISTS. The UA string and the client hints are two descriptions of
 * one browser, and until this file existed only the first was under the
 * profile's control. `WebSettings.userAgentString` replaces the string, but the
 * `Sec-CH-UA`, `Sec-CH-UA-Mobile` and `Sec-CH-UA-Platform` headers are built by
 * the installed WebView provider from ITS OWN version and platform. On a device
 * whose provider is older than the identity the profile claims, the request
 * therefore carried a UA saying Chrome 156 next to headers saying the engine's
 * real version — and, on the providers that still do it, the brand
 * `"Android WebView"`. A disagreement between two headers describing the same
 * client is a far stronger automation/embedded-browser signal than the `wv`
 * token ever was, and it is the one Google's "This browser or app may not be
 * secure" refusal can read without any JavaScript at all. The device shim
 * (`DeviceShim`) already answers `navigator.userAgentData` with
 * Chromium/Google Chrome brands; this is the network-side half of the same
 * claim, so the two cannot tell a site different stories.
 *
 * WHAT IT DOES NOT DO. It never invents a brand the profile did not claim and
 * never touches the UA string. A Firefox identity has no Chromium client hints
 * to send, so it answers null and the engine keeps whatever it had.
 *
 * HIGH-ENTROPY HINTS. `architecture`, `bitness`, `model` and `fullVersionList`
 * are only sent to an origin that asked for them with `Accept-CH`, so leaving
 * them at their defaults would be safe — but `model` is exactly the hint that
 * names the handset, and a profile presenting a device should answer it with
 * that device rather than with the real phone. The three that a device cannot
 * describe honestly (`architecture`, `bitness`, `wow64`) are left alone.
 */
internal object WebViewClientHints {

    /** `Chrome/156.0.8078.48`, the token every Chrome identity carries. */
    private val CHROME = Regex("""Chrome/(\d+)(?:\.(\d+)\.(\d+)\.(\d+))?""")

    /** The platform version in `(Linux; Android 14; …)`. */
    private val ANDROID = Regex("""Android\s+(\d+(?:\.\d+)?)""")

    /**
     * The model code in `(Linux; Android 14; SM-X710 Build/…)` or
     * `(Linux; Android 14; SM-X710)`. The slot is absent on the Chrome-Android
     * preset, which is why this is optional rather than required.
     */
    private val ANDROID_MODEL =
        Regex("""Android\s+[\d.]+;\s*([^;)]+?)\s*(?:Build/|\))""")

    /**
     * The metadata for [settings] given the UA string the session actually
     * installed, or null when there is nothing truthful to send.
     *
     * [resolvedUserAgent] is passed rather than re-derived so the headers can
     * never describe a different identity than the string: the caller has just
     * written that string into the engine, and the two are computed from one
     * value.
     */
    fun forUserAgent(settings: ProfileSettings, resolvedUserAgent: String): UserAgentMetadata? {
        // A Firefox identity sends no Sec-CH-UA at all; answering with
        // Chromium brands would be a contradiction of its own.
        val chrome = CHROME.find(resolvedUserAgent) ?: return null
        val fullVersion = chrome.value.substringAfter("Chrome/")

        val device = UserAgents.device(settings)
        val desktop = isDesktopUserAgent(resolvedUserAgent)

        val platform = if (desktop) "Windows" else "Android"
        val platformVersion = when {
            desktop -> WINDOWS_PLATFORM_VERSION
            device != null -> "${device.androidVersion}.0.0"
            else -> ANDROID.find(resolvedUserAgent)?.let { "${it.groupValues[1]}.0.0" }.orEmpty()
        }
        // A desktop claim must not carry a handset model, and a profile with no
        // device has no model it can honestly name.
        val model = when {
            desktop -> ""
            device != null -> device.code
            else -> ANDROID_MODEL.find(resolvedUserAgent)?.groupValues?.get(1).orEmpty()
        }
        val mobile = if (desktop) false else !resolvedUserAgent.contains(" Mobile")

        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands(fullVersion))
            .setFullVersion(fullVersion)
            .setPlatform(platform)
            .setPlatformVersion(platformVersion)
            .setModel(model)
            .setMobile(mobile)
            .build()
    }

    /**
     * The brand list `DeviceShim.brands()` reports to `navigator.userAgentData`,
     * in the same order — one identity, described twice.
     *
     * The reduced form (major only) is what `Sec-CH-UA` carries; the full
     * version travels in `fullVersionList`, which is what the `BrandVersion`
     * entries here are. Chromium is always first, and the deliberately
     * malformed grease brand is the one Chrome itself emits.
     */
    private fun brands(fullVersion: String): List<UserAgentMetadata.BrandVersion> {
        val major = fullVersion.substringBefore('.')
        return listOf(
            brand("Chromium", major, fullVersion),
            brand("Google Chrome", major, fullVersion),
            brand(GREASE_BRAND, GREASE_VERSION, "$GREASE_VERSION.0.0.0")
        )
    }

    private fun brand(
        name: String,
        major: String,
        full: String
    ): UserAgentMetadata.BrandVersion = UserAgentMetadata.BrandVersion.Builder()
        .setBrand(name)
        .setMajorVersion(major)
        .setFullVersion(full)
        .build()

    /**
     * Desktop mode is a rendering-mode toggle, and its UA is the only identity
     * in this app that is not Android. Keyed on the UA itself rather than on a
     * flag because the two call sites that install it (the profile default and
     * the per-site toggle) reach this object from different places.
     */
    private fun isDesktopUserAgent(ua: String): Boolean =
        ua.contains("Windows NT") || ua.contains("Macintosh") || ua.contains("X11; Linux")

    /**
     * Windows 10's own client-hint version string. Chrome reports 13.0.0 for
     * Windows 11 and 10.0.0 for Windows 10; the desktop UA this app sends names
     * `Windows NT 10.0`, so 10.0.0 is the value that agrees with it.
     */
    private const val WINDOWS_PLATFORM_VERSION = "10.0.0"

    /** The grease brand Chrome emits; `?` is part of the string, not a typo. */
    private const val GREASE_BRAND = "Not?A_Brand"

    /** The grease major Chrome 156 emits. */
    private const val GREASE_VERSION = "24"
}
