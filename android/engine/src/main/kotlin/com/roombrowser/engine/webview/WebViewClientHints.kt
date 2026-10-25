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
 * that device rather than with the real phone.
 *
 * `architecture` and `bitness` are answered for the same reason, and they are
 * answered here because the PAGE already answers them: `DeviceShim` reports
 * `arm` / `64` to `navigator.userAgentData.getHighEntropyValues`, so a network
 * half that stayed empty would be the same contradiction in a different place
 * — one browser, two descriptions. A desktop claim gets the x86 answer its
 * Windows UA implies. A profile with NO device is the one case left alone: it
 * presents the engine's real build, that build's architecture is whatever the
 * device running it actually is, and this code cannot see it.
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
        // `Mobile` in the string is the token that makes an identity a phone
        // one: Chrome emits it for handsets and omits it for tablets. The flag
        // therefore follows the STRING, not the device's form factor — a
        // profile that claims a handset but sends a tablet string must report
        // what it actually sends, or `Sec-CH-UA-Mobile` contradicts the UA it
        // arrived with. (This used to be the negation of the test, which
        // announced every phone as a tablet and every tablet as a phone.)
        val mobile = !desktop && resolvedUserAgent.contains(" Mobile")

        // What the page-side shim reports for the same two hints, so the
        // header half and the `getHighEntropyValues` half cannot disagree.
        // Chrome on Android answers "arm"/64 whatever the SoC vendor is;
        // "arm64" is not a value it ever emits. `bitness` is an Int in
        // androidx.webkit (the provider renders it into the header), while the
        // JS API hands the page the string "64" — the same fact, two types.
        // Zero and empty are the "say nothing" values, used when no device is
        // claimed: that profile presents the engine's real build, and this
        // code has no honest answer for what that build was compiled for.
        val architecture: String
        val bitness: Int
        when {
            desktop -> {
                architecture = "x86"
                bitness = 64
            }
            device != null -> {
                architecture = "arm"
                bitness = 64
            }
            else -> {
                architecture = ""
                bitness = 0
            }
        }

        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands(fullVersion))
            .setFullVersion(fullVersion)
            .setPlatform(platform)
            .setPlatformVersion(platformVersion)
            .setModel(model)
            .setMobile(mobile)
            .setArchitecture(architecture)
            .setBitness(bitness)
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
