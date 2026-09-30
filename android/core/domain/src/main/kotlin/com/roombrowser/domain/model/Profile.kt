package com.roombrowser.domain.model

import kotlinx.serialization.Serializable

/**
 * Immutable identity of a browser profile.
 * Storage identity is the UUID — never the profile name.
 */
@Serializable
data class Profile(
    val id: ProfileId,
    val name: String,
    val icon: String = "\uD83D\uDC64", // 👤 generic person
    val colorArgb: Long = 0xFF6750A4,
    val isLocked: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val lastActiveAt: Long = createdAt,
    val settings: ProfileSettings = ProfileSettings(),
    /** Full per-profile theme snapshot (RoomThemeSpec JSON). Blank = default theme. */
    val themeJson: String = ""
)

@Serializable
data class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProfileId must not be blank" }
    }

    /**
     * Safe directory / WebView data-directory suffix.
     * WebView.setDataDirectorySuffix() accepts alphanumeric, max 32 chars.
     * A UUID without dashes is exactly 32 hex chars.
     */
    val safeSuffix: String get() = value.replace("-", "").lowercase().take(32)

    override fun toString(): String = value

    companion object {
        fun new(): ProfileId = ProfileId(java.util.UUID.randomUUID().toString())
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class TabLayout { GRID, LIST }

@Serializable
enum class PermissionDecision { ASK, ALLOW, BLOCK }

@Serializable
enum class WebRtcPolicy { DEFAULT, RESTRICT_LOCAL_IP, DISABLED }

@Serializable
enum class DnsMode { SYSTEM, AUTO, DOH, DOT }

@Serializable
enum class UaMode { DEFAULT, PRESET, CUSTOM }

@Serializable
enum class WarningBehavior { ASK_EVERY_TIME, ONCE_PER_NETWORK, ONCE_PER_SESSION, DONT_WARN }

@Serializable
enum class ConflictSeverity { INFORMATIONAL, REQUIRE_CONFIRMATION }

/** Retention window for profile network (IP) history. */
@Serializable
enum class NetworkRetention(val days: Int) {
    ONE_DAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    FOREVER(Int.MAX_VALUE);

    fun cutoff(nowMs: Long): Long? =
        if (this == FOREVER) null else nowMs - days * 24L * 60L * 60L * 1000L
}

/**
 * Per-profile configuration. Serialized to JSON in Room.
 * Contains NO secrets and NO browsing data.
 */
@Serializable
data class ProfileSettings(
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accentArgb: Long = 0xFF6750A4,
    val fontScale: Float = 1.0f,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val tabLayout: TabLayout = TabLayout.GRID,
    // Search & homepage
    val searchEngineId: String = "duckduckgo",
    val homepageEnabled: Boolean = true,
    val homepageShortcuts: List<String> = defaultShortcuts,
    val showPrivacyStats: Boolean = true,
    val showRecentSites: Boolean = true,
    val showClock: Boolean = true,
    // User agent
    //
    // The device is the single control for browser identity: when [deviceId]
    // names a device, that handset's UA is the one the profile sends and the
    // two fields below are not consulted. Choosing a preset or a custom UA in
    // settings clears [deviceId] instead, so a profile never carries two
    // contradictory identities.
    val deviceId: String? = null,
    val uaMode: UaMode = UaMode.DEFAULT,
    val uaPresetId: String? = null,
    val customUserAgent: String? = null,
    // DNS
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val dohUrl: String? = null,
    val dotHostname: String? = null,
    // Privacy
    //
    // Compatibility-first defaults (2026-09 revision): the annoyance shields —
    // ad blocking, tracker blocking, cross-site-tracker blocking and popup
    // blocking — are now OFF out of the box so sites render exactly as their
    // authors intended (aggressive blocking broke layouts, login flows and
    // embedded players on many real sites). Users who want the stricter
    // behavior can enable each shield per profile in Settings.
    //
    // Security-grade protections that never break legitimate sites stay ON:
    // blockMalicious below, and httpsUpgrade (which falls back to http when
    // the secure version is unreachable).
    val blockAds: Boolean = false,
    val blockTrackers: Boolean = false,
    val blockCrossSiteTrackers: Boolean = false,
    val blockPopups: Boolean = false,
    val blockMalicious: Boolean = true,
    // HTTPS-First: upgrade http navigations to https, then FALL BACK to the
    // original http URL automatically when the secure version is unreachable
    // (HttpsUpgradeFallbackPolicy) — upgrades no longer break http-only sites.
    val httpsUpgrade: Boolean = true,
    // Compatibility defaults (2026-09): third-party cookies are ALLOWED and
    // mixed content runs in compatibility mode out of the box. The previous
    // strict defaults blanked login flows and media on many real sites;
    // users who want the strict behavior can flip both switches in settings.
    val blockThirdPartyCookies: Boolean = false,
    val blockMixedContent: Boolean = false,
    val javascriptEnabled: Boolean = true,
    val webRtcPolicy: WebRtcPolicy = WebRtcPolicy.RESTRICT_LOCAL_IP,
    val searchSuggestions: Boolean = false,
    // Desktop mode default
    val desktopModeDefault: Boolean = false,
    // Language
    val languageTag: String? = null,
    val translateTargetLanguage: String = "id",
    val neverTranslateSites: List<String> = emptyList(),
    // Network protection (profile IP conflict warning)
    val networkProtectionUseGlobal: Boolean = true,
    val networkProtectionEnabled: Boolean = true,
    // Downloads
    val downloadSubfolder: String = "RoomBrowser",
    // Autofill
    val autofillEnabled: Boolean = true
) {
    companion object {
        val defaultShortcuts = listOf(
            "https://www.youtube.com",
            "https://github.com",
            "https://www.google.com",
            "https://www.reddit.com"
        )
    }
}

/**
 * The identity fields answer one question — what does this profile claim to
 * be — so at most one of them is ever set. These two helpers are how a screen
 * hands that choice over: picking a preset or a custom UA drops the device,
 * because the UA is then the whole answer. The other direction is
 * [ProfileManager.setDevice], which drops the UA fields.
 */
fun ProfileSettings.withUserAgentPreset(presetId: String?): ProfileSettings =
    copy(deviceId = null, uaMode = UaMode.PRESET, uaPresetId = presetId)

fun ProfileSettings.withCustomUserAgent(value: String?): ProfileSettings =
    copy(deviceId = null, uaMode = UaMode.CUSTOM, customUserAgent = value)

/** Global (browser-wide) settings, independent of any profile. */
@Serializable
data class BrowserGlobalSettings(
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val dohUrl: String? = null,
    val dotHostname: String? = null,
    val networkProtectionEnabled: Boolean = true,
    val showPreviousProfileName: Boolean = true,
    val showLastSeenTime: Boolean = true,
    val offerNetworkChangeOptions: Boolean = true,
    val offerAirplaneModeShortcut: Boolean = true,
    val retention: NetworkRetention = NetworkRetention.THIRTY_DAYS,
    val warningBehavior: WarningBehavior = WarningBehavior.ASK_EVERY_TIME,
    val conflictSeverity: ConflictSeverity = ConflictSeverity.INFORMATIONAL,
    val telemetryEnabled: Boolean = false, // OFF by default; no data is collected anyway
    val diagnosticsEnabled: Boolean = false
)
