package com.roombrowser.domain.model

/**
 * User-Agent presets.
 *
 * A preset says which browser a profile claims to be; a [Device] says which
 * handset it claims to be, and carries the UA that handset's Chrome sends.
 * The device is the richer of the two — it also decides the platform version,
 * build id, memory, core count and GPU strings the profile reports — so when
 * both are configured the device wins, and the settings screen clears one
 * when the other is chosen.
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
    private val FIREFOX_ANDROID = UserAgentPreset(
        id = "firefox_android",
        label = "Firefox Android",
        isDesktop = false,
        value = "Mozilla/5.0 (Android 14; Mobile; rv:127.0) Gecko/127.0 Gecko/20100101 Firefox/127.0"
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
    private val WEBVIEW = UserAgentPreset(
        id = "webview",
        label = "Android WebView (default)",
        isDesktop = false,
        value = ""
    )
    private val CHROME_WINDOWS = UserAgentPreset(
        id = "chrome_windows",
        label = "Chrome Windows",
        isDesktop = true,
        value = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    private val CHROME_MACOS = UserAgentPreset(
        id = "chrome_macos",
        label = "Chrome macOS",
        isDesktop = true,
        value = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    private val CHROME_LINUX = UserAgentPreset(
        id = "chrome_linux",
        label = "Chrome Linux",
        isDesktop = true,
        value = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    private val FIREFOX_WINDOWS = UserAgentPreset(
        id = "firefox_windows",
        label = "Firefox Windows",
        isDesktop = true,
        value = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"
    )
    private val FIREFOX_MACOS = UserAgentPreset(
        id = "firefox_macos",
        label = "Firefox macOS",
        isDesktop = true,
        value = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0"
    )
    private val FIREFOX_LINUX = UserAgentPreset(
        id = "firefox_linux",
        label = "Firefox Linux",
        isDesktop = true,
        value = "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"
    )
    private val EDGE_WINDOWS = UserAgentPreset(
        id = "edge_windows",
        label = "Edge Windows",
        isDesktop = true,
        value = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
    )
    private val SAFARI_MACOS = UserAgentPreset(
        id = "safari_macos",
        label = "Safari macOS",
        isDesktop = true,
        value = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    )

    val all: List<UserAgentPreset> = listOf(
        CHROME_ANDROID, FIREFOX_ANDROID, EDGE_ANDROID, SAMSUNG_ANDROID, WEBVIEW,
        CHROME_WINDOWS, CHROME_MACOS, CHROME_LINUX,
        FIREFOX_WINDOWS, FIREFOX_MACOS, FIREFOX_LINUX,
        EDGE_WINDOWS, SAFARI_MACOS
    )

    val androidPresets: List<UserAgentPreset> = all.filter { !it.isDesktop }

    val desktopPresets: List<UserAgentPreset> = all.filter { it.isDesktop }

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
     * UA, so the preset and custom fields are not consulted.
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
