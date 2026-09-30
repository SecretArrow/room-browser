package com.roombrowser.domain.model

/**
 * User-Agent presets.
 *
 * Every preset is an Android/mobile identity: the browser runs on a phone, so
 * the curated list offers mobile browsers only, and a preset says which *mobile
 * browser* a profile claims to be. A [Device] is the richer sibling — it says
 * which handset the profile claims to be and also decides the platform
 * version, build id, memory, core count and GPU strings the profile reports —
 * so when both are configured the device wins, and the settings screen clears
 * one when the other is chosen.
 *
 * Desktop identities are deliberately NOT presets. Claiming a desktop browser
 * on a phone is a rendering-mode choice, not an identity: it goes with a wide
 * viewport and no overview zoom, and it is per site, not per profile. That is
 * what the per-site "Desktop mode" toggle is for, and the UA it applies lives
 * in [UserAgents.desktopModeUserAgent] rather than in [UserAgents.all].
 *
 * What this changes is the identity a site is told, not the machine. Screen
 * size, viewport size, devicePixelRatio and the layout that follows from them
 * always report the real device, because a page laid out for a viewport the
 * phone does not have renders wrong. See SECURITY.md for the full list of
 * what is and is not presented.
 */
data class UserAgentPreset(
    val id: String,
    val label: String,
    /**
     * Always false now that the catalogue is Android-only. Kept so settings
     * UIs that tag desktop presets keep compiling; the only desktop UA left
     * is [UserAgents.desktopModeUserAgent], which is not a preset.
     */
    val isDesktop: Boolean,
    val value: String
)

object UserAgents {

    private val CHROME_ANDROID = UserAgentPreset(
        id = "chrome_android",
        label = "Chrome Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    private val CHROME_ANDROID_TABLET = UserAgentPreset(
        id = "chrome_android_tablet",
        label = "Chrome Android Tablet",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    private val CHROME_BETA_ANDROID = UserAgentPreset(
        id = "chrome_beta_android",
        label = "Chrome Beta Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 ChromeBetA/124.0.0.0"
    )
    private val FIREFOX_ANDROID = UserAgentPreset(
        id = "firefox_android",
        label = "Firefox Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Android 14; Mobile; rv:127.0) Gecko/127.0 Gecko/20100101 Firefox/127.0"
    )
    private val FIREFOX_ANDROID_TABLET = UserAgentPreset(
        id = "firefox_android_tablet",
        label = "Firefox Android Tablet",
        isDesktop = false,
        value = "Mozilla/5.0 (Android 14; Tablet; rv:127.0) Gecko/127.0 Gecko/20100101 Firefox/127.0"
    )
    private val EDGE_ANDROID = UserAgentPreset(
        id = "edge_android",
        label = "Edge Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 EdgA/124.0.0.0"
    )
    private val SAMSUNG_ANDROID = UserAgentPreset(
        id = "samsung_android",
        label = "Samsung Internet",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36"
    )
    private val OPERA_ANDROID = UserAgentPreset(
        id = "opera_android",
        label = "Opera Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 OPR/79.4.4195.76198"
    )
    private val BRAVE_ANDROID = UserAgentPreset(
        id = "brave_android",
        label = "Brave Android",
        isDesktop = false,
        // Brave's mobile browser is Chromium-based and intentionally reports
        // the plain Chrome mobile UA — an extra token would let sites
        // fingerprint it, so there is nothing to add.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    private val DUCKDUCKGO_ANDROID = UserAgentPreset(
        id = "duckduckgo_android",
        label = "DuckDuckGo Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 DuckDuckGo/5"
    )
    private val VIVALDI_ANDROID = UserAgentPreset(
        id = "vivaldi_android",
        label = "Vivaldi Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 Vivaldi/6.8"
    )
    private val WEBVIEW = UserAgentPreset(
        id = "webview",
        label = "Android WebView (default)",
        isDesktop = false,
        value = ""
    )

    /** The curated preset catalogue — Android/mobile identities only. */
    val all: List<UserAgentPreset> = listOf(
        CHROME_ANDROID, CHROME_ANDROID_TABLET, CHROME_BETA_ANDROID,
        FIREFOX_ANDROID, FIREFOX_ANDROID_TABLET, EDGE_ANDROID,
        SAMSUNG_ANDROID, OPERA_ANDROID, BRAVE_ANDROID,
        DUCKDUCKGO_ANDROID, VIVALDI_ANDROID, WEBVIEW
    )

    /**
     * The mobile subset of [all]. Every preset is mobile now, so this is the
     * whole catalogue; the property stays because settings UIs ask for the
     * mobile subset explicitly and should not silently grow desktop entries
     * if presets are ever widened again.
     */
    val androidPresets: List<UserAgentPreset> = all

    /**
     * The UA the per-site "Desktop mode" toggle applies.
     *
     * Not a preset and never part of [all]: desktop mode is a rendering-mode
     * feature (wide viewport, no overview zoom) toggled per site, not a
     * profile identity to choose in settings. Chrome on Windows is the least
     * remarkable desktop UA a site sees, which is the point.
     */
    const val desktopModeUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun byId(id: String): UserAgentPreset? = all.firstOrNull { it.id == id }

    /**
     * The device this profile presents itself as, or null when it was never
     * assigned one (a profile created before devices existed, or one whose
     * settings were imported).
     */
    fun device(settings: ProfileSettings): Device? = Devices.find(settings.deviceId)

    /**
     * Resolve the effective UA for a profile. Null means "use WebView default".
     *
     * A device, when one is assigned, is the single control: it supplies the
     * UA, so the preset and custom fields are not consulted. In PRESET mode an
     * unknown preset id — e.g. a stored profile that once picked a preset that
     * has since been removed from the catalogue — also resolves to null, so
     * the WebView default is used rather than a stale identity.
     */
    fun effectiveUserAgent(settings: ProfileSettings): String? {
        device(settings)?.let { return it.userAgent }
        return when (settings.uaMode) {
            UaMode.DEFAULT -> null
            UaMode.PRESET -> settings.uaPresetId?.let { byId(it)?.value?.ifEmpty { null } }
            UaMode.CUSTOM -> settings.customUserAgent?.takeIf { it.isNotBlank() }
        }
    }
}
