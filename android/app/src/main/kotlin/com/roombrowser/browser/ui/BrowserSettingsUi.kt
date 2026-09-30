package com.roombrowser.browser.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.BuildConfig
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.NetworkRetention
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.ScreenSizeMode
import com.roombrowser.domain.model.SearchEngines
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.model.WarningBehavior
import com.roombrowser.domain.model.ConflictSeverity
import com.roombrowser.domain.model.TabLayout
import com.roombrowser.domain.model.WebRtcPolicy
import com.roombrowser.domain.model.withCustomUserAgent
import com.roombrowser.domain.model.withUserAgentPreset
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingSwitchRow
import com.roombrowser.ui.common.SettingsGroup
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings screens (global browser settings, per-profile settings, About) —
 * section header + grouped card rhythm. Insets are already applied by the
 * host BrowserScreen; the top bars below set explicit zero window insets so
 * nothing double-applies.
 */

/** Shared top bar: themed container, back affordance, zero extra insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(title: String, onClose: () -> Unit) {
    val extras = LocalRoomExtras.current
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = extras.background,
            scrolledContainerColor = extras.background,
            navigationIconContentColor = extras.textPrimary,
            titleContentColor = extras.textPrimary,
            actionIconContentColor = extras.icon
        )
    )
}

/** Quiet explanatory note: tinted rounded box under a settings group. */
@Composable
private fun InfoNote(text: String) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape((extras.radius * 0.6f).dp))
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = extras.primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
    }
}

/** Global browser settings (spec section 32 — "Browser" tree). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var global by remember { mutableStateOf(viewModel.globalSettings) }
    LaunchedEffect(viewModel.globalSettings) { global = viewModel.globalSettings }
    val context = androidx.compose.ui.platform.LocalContext.current
    val extras = LocalRoomExtras.current

    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Browser Settings", onClose = onClose)

        SectionHeader("Privacy & Network")
        SettingsGroup {
            SettingSwitchRow(
                title = "Profile Network Protection",
                subtitle = "Warn when a profile is opened from an IP previously used by another profile",
                checked = global.networkProtectionEnabled,
                onCheckedChange = {
                    global = global.copy(networkProtectionEnabled = it)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
            SettingSwitchRow(
                title = "Show previous profile name",
                checked = global.showPreviousProfileName,
                onCheckedChange = {
                    global = global.copy(showPreviousProfileName = it)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
            SettingSwitchRow(
                title = "Offer network change options",
                checked = global.offerNetworkChangeOptions,
                onCheckedChange = {
                    global = global.copy(offerNetworkChangeOptions = it)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
            SettingSwitchRow(
                title = "Offer Airplane Mode shortcut",
                subtitle = "Android forbids apps toggling Airplane Mode directly — the shortcut opens system settings",
                checked = global.offerAirplaneModeShortcut,
                onCheckedChange = {
                    global = global.copy(offerAirplaneModeShortcut = it)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
        }

        SectionHeader("IP History Retention")
        SettingsGroup {
            DropdownRow(
                label = "Retention",
                options = NetworkRetention.entries.map { retention ->
                    retention.name to retention.name.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                },
                selected = global.retention.name,
                onSelect = { value ->
                    val retention = NetworkRetention.entries.first { it.name == value }
                    global = global.copy(retention = retention)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
        }

        SectionHeader("Warning Behavior")
        SettingsGroup {
            DropdownRow(
                label = "Behavior",
                options = WarningBehavior.entries.map { behavior ->
                    behavior.name to behavior.name.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                },
                selected = global.warningBehavior.name,
                onSelect = { value ->
                    val behavior = WarningBehavior.entries.first { it.name == value }
                    global = global.copy(warningBehavior = behavior)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
        }

        SectionHeader("Conflict Severity")
        SettingsGroup {
            ConflictSeverity.entries.forEach { severity ->
                RadioRow(
                    label = severity.name.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() },
                    selected = global.conflictSeverity == severity,
                    onSelect = {
                        global = global.copy(conflictSeverity = severity)
                        scope.launch { viewModel.updateGlobalSettings(global) }
                    }
                )
            }
        }

        SectionHeader("Global DNS")
        SettingsGroup {
            DropdownRow(
                label = "DNS mode",
                options = DnsMode.entries.map { mode ->
                    mode.name to when (mode) {
                        DnsMode.SYSTEM -> "System default"
                        DnsMode.AUTO -> "Automatic"
                        DnsMode.DOH -> "DNS-over-HTTPS (app connections)"
                        DnsMode.DOT -> "DNS-over-TLS (validate + use OS Private DNS)"
                    }
                },
                selected = global.dnsMode.name,
                onSelect = { value ->
                    val mode = DnsMode.entries.first { it.name == value }
                    global = global.copy(dnsMode = mode)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
            if (global.dnsMode == DnsMode.DOH) {
                DnsUrlField(
                    initial = global.dohUrl ?: "",
                    onCommit = {
                        global = global.copy(dohUrl = it)
                        scope.launch { viewModel.updateGlobalSettings(global) }
                    }
                )
            }
            if (global.dnsMode == DnsMode.DOT) {
                DotHostField(
                    initial = global.dotHostname ?: "",
                    onCommit = {
                        global = global.copy(dotHostname = it)
                        scope.launch { viewModel.updateGlobalSettings(global) }
                    }
                )
            }
        }
        InfoNote("Browser-level DNS cannot override every DNS request made by the Android OS or WebView page loads — see PRIVACY.md.")

        SectionHeader("Diagnostics")
        SettingsGroup {
            SettingSwitchRow(
                title = "Enable network diagnostics",
                subtitle = "Allows the About/Diagnostics screens to show the observed public IP",
                checked = global.diagnosticsEnabled,
                onCheckedChange = {
                    global = global.copy(diagnosticsEnabled = it)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
            val engines = remember { ProfileEngine.installedWebViewEngines(context) }
            // Honest display-only row: Android decides the active WebView
            // provider, so a picker here could never take effect (see the
            // InfoNote below). We simply show the current engine as text.
            val currentEngine = engines.firstOrNull { it.isCurrent } ?: engines.firstOrNull()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Engine",
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    currentEngine?.let { "${it.packageName} ${it.versionName}" }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.5f)
                )
            }
        }
        InfoNote("Read-only diagnostics — Android decides the active WebView provider; Room Browser cannot switch it.")

        // ================= AI Agent =================
        // The floating agent button is hidden by default; this is the global
        // opt-in/out (also available in AI Agent settings).
        SectionHeader("AI Agent")
        SettingsGroup {
            SettingSwitchRow(
                title = "Show AI Agent button",
                subtitle = "Floating button for the agent chat on the browser screen — hidden by default; the agent stays reachable from the page menu",
                checked = viewModel.agent.settings.showAgentButton,
                onCheckedChange = { checked ->
                    viewModel.agent.updateSettings { s -> s.copy(showAgentButton = checked) }
                }
            )
            SettingActionRow(
                title = "AI Agent settings",
                subtitle = "Providers, models and agent behavior (opens its own screen)",
                onClick = {
                    com.roombrowser.agent.ui.AgentSettingsActivity.launch(
                        context, viewModel.profileId.value
                    )
                }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Per-profile settings (spec section 32 — "Profile" tree + section 68). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(viewModel.profileSettings()) }
    LaunchedEffect(viewModel.profile) { settings = viewModel.profileSettings() }
    val extras = LocalRoomExtras.current

    fun update(new: ProfileSettings) {
        settings = new
        scope.launch { viewModel.updateSettings(new) }
    }

    // Device identity. The catalogue is over a thousand entries, so the
    // picker is a search field over a list rather than a dropdown, and it
    // marks the devices other profiles are already presenting as.
    val currentDevice = UserAgents.device(settings)
    var showDevicePicker by remember { mutableStateOf(false) }
    var inUse by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(showDevicePicker, currentDevice) {
        if (showDevicePicker) inUse = viewModel.devicesInUse()
    }

    // This phone's own screen, in the CSS pixels a page reads. It is what the
    // screen-size row offers as "this phone", and where a manual size starts:
    // a user who does not know the presented handset's screen should be able to
    // see, and keep, the truth rather than guess at a number.
    val realScreen = remember(context) { realScreenCssPx(context) }

    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Profile Settings — ${viewModel.profile.name}", onClose = onClose)

        SectionHeader("Appearance")
        SettingsGroup {
            SettingActionRow(
                title = "Theme studio",
                subtitle = "This profile's theme: 18 presets, colors, gradients, corner radius, transparency, blur — fully independent per profile",
                leadingIcon = Icons.Filled.Palette,
                value = viewModel.themeSpec.name,
                onClick = {
                    com.roombrowser.theme.ui.ThemeStudioActivity.launch(
                        context, viewModel.profileId.value
                    )
                }
            )
            TabLayout.entries.forEach { layout ->
                RadioRow(
                    label = if (layout == TabLayout.GRID) "Tab layout: Grid" else "Tab layout: List",
                    selected = settings.tabLayout == layout,
                    onSelect = { update(settings.copy(tabLayout = layout)) }
                )
            }
        }

        SectionHeader("Search")
        SettingsGroup {
            DropdownRow(
                label = "Search engine",
                options = SearchEngines.all.map { engine -> engine.id to engine.label },
                selected = settings.searchEngineId,
                onSelect = { value -> update(settings.copy(searchEngineId = value)) }
            )
            SettingSwitchRow(
                title = "Search suggestions",
                subtitle = "Sends typed queries to the selected search engine (privacy trade-off)",
                checked = settings.searchSuggestions,
                onCheckedChange = { update(settings.copy(searchSuggestions = it)) }
            )
        }

        SectionHeader("Privacy & Blocking")
        SettingsGroup {
            SettingSwitchRow(title = "Block ads", subtitle = "Blocks known ad hosts (bundled offline list)", checked = settings.blockAds, onCheckedChange = { update(settings.copy(blockAds = it)) })
            SettingSwitchRow(title = "Block trackers", checked = settings.blockTrackers, onCheckedChange = { update(settings.copy(blockTrackers = it)) })
            SettingSwitchRow(title = "Block cross-site trackers", checked = settings.blockCrossSiteTrackers, onCheckedChange = { update(settings.copy(blockCrossSiteTrackers = it)) })
            SettingSwitchRow(title = "Block popups", checked = settings.blockPopups, onCheckedChange = { update(settings.copy(blockPopups = it)) })
            SettingSwitchRow(title = "Block malicious websites", checked = settings.blockMalicious, onCheckedChange = { update(settings.copy(blockMalicious = it)) })
            SettingSwitchRow(
                title = "HTTPS upgrades",
                subtitle = "Upgrades http links to https and automatically falls back to http when the secure version is unreachable",
                checked = settings.httpsUpgrade,
                onCheckedChange = { update(settings.copy(httpsUpgrade = it)) }
            )
            SettingSwitchRow(
                title = "Block third-party cookies",
                subtitle = "Off by default for compatibility — many logins and embeds need it",
                checked = settings.blockThirdPartyCookies,
                onCheckedChange = { update(settings.copy(blockThirdPartyCookies = it)) }
            )
            SettingSwitchRow(
                title = "Block mixed content",
                subtitle = "Off by default: https pages may load http images/media so sites render fully. On = strict blocking",
                checked = settings.blockMixedContent,
                onCheckedChange = { update(settings.copy(blockMixedContent = it)) }
            )
            SettingSwitchRow(title = "JavaScript enabled", checked = settings.javascriptEnabled, onCheckedChange = { update(settings.copy(javascriptEnabled = it)) })
        }

        SectionHeader("WebRTC (informational)")
        SettingsGroup {
            DropdownRow(
                label = "Policy",
                options = WebRtcPolicy.entries.map { policy ->
                    policy.name to when (policy) {
                        WebRtcPolicy.DEFAULT -> "WebRTC: Default"
                        WebRtcPolicy.RESTRICT_LOCAL_IP -> "WebRTC: Restrict local IP exposure"
                        WebRtcPolicy.DISABLED -> "WebRTC: Disabled (may break calls)"
                    }
                },
                selected = settings.webRtcPolicy.name,
                onSelect = { value ->
                    update(settings.copy(webRtcPolicy = WebRtcPolicy.entries.first { it.name == value }))
                }
            )
        }
        InfoNote("Android WebView does not expose full WebRTC IP-handling control to normal apps. Camera/microphone grants stay under permission control; local IP exposure limits are documented in SECURITY.md.")

        SectionHeader("Device")
        SettingsGroup {
            SettingActionRow(
                title = "Device",
                subtitle = "The handset this profile presents itself as. It supplies the " +
                    "User-Agent, platform version and hardware the profile reports.",
                value = currentDevice?.let { "${it.brand} ${it.model}" } ?: "None",
                onClick = { showDevicePicker = true }
            )
            SettingActionRow(
                title = "Use a device no other profile is using",
                subtitle = "Picks at random from the bundled catalogue of real devices",
                onClick = {
                    scope.launch {
                        viewModel.pickFreeDevice()?.let { viewModel.setDevice(it.id) }
                    }
                }
            )
            if (currentDevice != null) {
                SettingActionRow(
                    title = "Stop presenting as a device",
                    subtitle = "Falls back to the User-Agent setting below",
                    onClick = { scope.launch { viewModel.setDevice(null) } }
                )
            }
        }
        if (currentDevice != null) {
            InfoNote(
                "${currentDevice.brand} ${currentDevice.model} (${currentDevice.code}), ${currentDevice.year}\n" +
                    "Android ${currentDevice.androidVersion} — Chrome ${currentDevice.chromeVersion} — " +
                    "${currentDevice.deviceMemoryGb} GB RAM, ${currentDevice.hardwareConcurrency} cores\n" +
                    "${currentDevice.userAgent}"
            )
        }
        if (showDevicePicker) {
            DevicePickerDialog(
                inUse = inUse,
                onDismiss = { showDevicePicker = false },
                onPick = { device ->
                    showDevicePicker = false
                    scope.launch { viewModel.setDevice(device?.id) }
                }
            )
        }

        SectionHeader("Screen size")
        SettingsGroup {
            DropdownRow(
                label = "Reported size",
                options = ScreenSizeMode.entries.map { mode ->
                    mode.name to when (mode) {
                        ScreenSizeMode.REAL ->
                            "This phone's screen (${realScreen.first}x${realScreen.second})"
                        ScreenSizeMode.MANUAL -> "Set manually"
                    }
                },
                selected = settings.screenSizeMode.name,
                onSelect = { value ->
                    val mode = ScreenSizeMode.entries.first { it.name == value }
                    update(
                        if (mode == ScreenSizeMode.MANUAL && settings.screenWidthPx == 0) {
                            // Manual opens on the truth: the fields start at
                            // this phone's own size, so what the user sees first
                            // is what the profile reports today, and the edit is
                            // a departure from it rather than a blank guess.
                            settings.copy(
                                screenSizeMode = mode,
                                screenWidthPx = realScreen.first,
                                screenHeightPx = realScreen.second
                            )
                        } else {
                            settings.copy(screenSizeMode = mode)
                        }
                    )
                }
            )
            if (settings.screenSizeMode == ScreenSizeMode.MANUAL) {
                InfoNote(
                    "The page is told the size below. The layout does not follow it: " +
                        "window.innerWidth/innerHeight stay this phone's real size and " +
                        "devicePixelRatio stays the display's, because the page really is " +
                        "drawn here. A manual size that differs from this phone's screen is " +
                        "therefore a disagreement a script can find — so set it to the size " +
                        "the handset you are presenting actually has, and leave it on the " +
                        "real screen if you do not know that size."
                )
                ScreenSizeFields(
                    width = settings.screenWidthPx,
                    height = settings.screenHeightPx,
                    real = realScreen,
                    onCommit = { w, h ->
                        update(settings.copy(screenWidthPx = w, screenHeightPx = h))
                    }
                )
            } else {
                InfoNote(
                    "This profile reports this phone's own screen, untouched. A profile " +
                        "presenting a device sends that handset's User-Agent while reporting " +
                        "a screen the handset may never have had; setting the size here is " +
                        "how that becomes a choice instead of an accident."
                )
            }
        }

        SectionHeader("User-Agent")
        SettingsGroup {
            DropdownRow(
                label = "User-Agent",
                options = UaMode.entries.map { mode ->
                    mode.name to when (mode) {
                        UaMode.DEFAULT -> "Default (WebView)"
                        UaMode.PRESET -> "Preset"
                        UaMode.CUSTOM -> "Custom"
                    }
                },
                selected = settings.uaMode.name,
                onSelect = { value ->
                    update(settings.copy(uaMode = UaMode.entries.first { it.name == value }))
                }
            )
            InfoNote(
                "A device, when one is assigned, is the single control: it supplies the " +
                    "User-Agent, so the preset and custom fields below are ignored while it is set."
            )
            if (settings.uaMode == UaMode.PRESET) {
                DropdownRow(
                    label = "Preset",
                    options = UserAgents.all.map { preset ->
                        preset.id to preset.label + if (preset.isDesktop) "  (desktop)" else ""
                    },
                    selected = settings.uaPresetId ?: "",
                    onSelect = { value -> update(settings.withUserAgentPreset(value)) }
                )
            }
            if (settings.uaMode == UaMode.CUSTOM) {
                var custom by remember(settings.customUserAgent) { mutableStateOf(settings.customUserAgent ?: "") }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = { Text("Custom User-Agent") },
                        singleLine = true,
                        shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { update(settings.withCustomUserAgent(custom.trim())) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Apply") }
                }
            }
        }
        val effective = UserAgents.effectiveUserAgent(settings)
        InfoNote(
            "Current: ${effective ?: "Android WebView default"}\n" +
                "Changing the User-Agent string does not change other platform/device characteristics (screen, APIs, capabilities)."
        )

        SectionHeader("Profile DNS")
        SettingsGroup {
            DropdownRow(
                label = "DNS mode",
                options = DnsMode.entries.map { mode ->
                    mode.name to when (mode) {
                        DnsMode.SYSTEM -> "System"
                        DnsMode.AUTO -> "Use global browser setting"
                        DnsMode.DOH -> "DNS-over-HTTPS"
                        DnsMode.DOT -> "DNS-over-TLS"
                    }
                },
                selected = settings.dnsMode.name,
                onSelect = { value ->
                    update(settings.copy(dnsMode = DnsMode.entries.first { it.name == value }))
                }
            )
            if (settings.dnsMode == DnsMode.DOH) {
                DnsUrlField(initial = settings.dohUrl ?: "", onCommit = { update(settings.copy(dohUrl = it)) })
            }
            if (settings.dnsMode == DnsMode.DOT) {
                DotHostField(initial = settings.dotHostname ?: "", onCommit = { update(settings.copy(dotHostname = it)) })
            }
        }

        SectionHeader("Network Protection (this profile)")
        SettingsGroup {
            SettingSwitchRow(
                title = "Use global setting",
                checked = settings.networkProtectionUseGlobal,
                onCheckedChange = { update(settings.copy(networkProtectionUseGlobal = it)) }
            )
            if (!settings.networkProtectionUseGlobal) {
                SettingSwitchRow(
                    title = "IP conflict warning enabled",
                    checked = settings.networkProtectionEnabled,
                    onCheckedChange = { update(settings.copy(networkProtectionEnabled = it)) }
                )
            }
        }

        SectionHeader("Homepage & Tabs")
        SettingsGroup {
            SettingSwitchRow(title = "Show homepage", checked = settings.homepageEnabled, onCheckedChange = { update(settings.copy(homepageEnabled = it)) })
            SettingSwitchRow(title = "Show privacy statistics", checked = settings.showPrivacyStats, onCheckedChange = { update(settings.copy(showPrivacyStats = it)) })
            SettingSwitchRow(title = "Show recent sites", checked = settings.showRecentSites, onCheckedChange = { update(settings.copy(showRecentSites = it)) })
            SettingSwitchRow(title = "Show clock", checked = settings.showClock, onCheckedChange = { update(settings.copy(showClock = it)) })
        }

        SectionHeader("Language & Translate")
        SettingsGroup {
            var target by remember(settings.translateTargetLanguage) { mutableStateOf(settings.translateTargetLanguage) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Translate target language (e.g. id)") },
                    singleLine = true,
                    shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { update(settings.copy(translateTargetLanguage = target.trim())) },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Apply") }
            }
        }

        SectionHeader("Autofill")
        SettingsGroup {
            SettingSwitchRow(
                title = "Android Autofill integration",
                subtitle = "Delegates credential storage to the Android system autofill framework — Room Browser never stores passwords itself",
                checked = settings.autofillEnabled,
                onCheckedChange = { update(settings.copy(autofillEnabled = it)) }
            )
        }

        SectionHeader("Desktop Mode")
        SettingsGroup {
            SettingSwitchRow(
                title = "Request desktop site by default",
                checked = settings.desktopModeDefault,
                onCheckedChange = { update(settings.copy(desktopModeDefault = it)) }
            )
        }

        SectionHeader("Danger Zone — Clear Browsing Data")
        SettingsGroup {
            ClearDataSection(viewModel = viewModel)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ClearDataSection(viewModel: BrowserViewModel) {
    var clearHistory by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(true) }
    var clearCache by remember { mutableStateOf(true) }
    var clearSiteData by remember { mutableStateOf(false) }
    var clearDownloads by remember { mutableStateOf(false) }
    var clearPermissions by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        com.roombrowser.main.ui.LabeledCheckboxRow("History", clearHistory) { clearHistory = it }
        com.roombrowser.main.ui.LabeledCheckboxRow("Cookies", clearCookies) { clearCookies = it }
        com.roombrowser.main.ui.LabeledCheckboxRow("Cache", clearCache) { clearCache = it }
        com.roombrowser.main.ui.LabeledCheckboxRow("Site data", clearSiteData) { clearSiteData = it }
        com.roombrowser.main.ui.LabeledCheckboxRow("Downloads", clearDownloads) { clearDownloads = it }
        com.roombrowser.main.ui.LabeledCheckboxRow("Permissions", clearPermissions) { clearPermissions = it }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) { Text("Clear Now") }
    }

    if (confirm) {
        com.roombrowser.main.ui.ConfirmDialog(
            title = "Clear Browsing Data",
            text = "This clears the selected data for THIS profile only (${viewModel.profile.name}).",
            confirmLabel = "Clear",
            onDismiss = { confirm = false },
            onConfirm = {
                viewModel.clearBrowsingData(
                    clearHistory, clearCookies, clearCache, clearSiteData, clearDownloads, clearPermissions,
                    since = 0
                )
                confirm = false
            }
        )
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .then(
                if (selected) Modifier.background(extras.primary.copy(alpha = 0.08f))
                else Modifier
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Single touch target: the whole row toggles; the radio itself is
        // display-only (onClick = null) so there is no nested clickable.
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = extras.textPrimary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * Compact dropdown row: leading label + read-only select field, matching the
 * settings-card rhythm. [options] maps raw value -> display text; [selected]
 * is the raw value to show. Opens on tap; picking an item calls [onSelect]
 * and closes the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownRow(
    label: String,
    options: List<Pair<String, String>>, // value -> display
    selected: String,
    onSelect: (String) -> Unit
) {
    val extras = LocalRoomExtras.current
    var expanded by remember { mutableStateOf(false) }
    val selectedDisplay = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = extras.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1.5f)
        ) {
            OutlinedTextField(
                value = selectedDisplay,
                onValueChange = {},
                readOnly = true,
                singleLine = false,
                maxLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = extras.textPrimary),
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "$label dropdown",
                        tint = extras.icon
                    )
                },
                shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                display,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (value == selected) extras.textPrimary else extras.textSecondary
                            )
                        },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Search-and-pick over the whole device catalogue. A profile per handset is
 * the point of the catalogue, so the ones other profiles already present as
 * are marked rather than hidden — the user can still choose one, they are
 * just told it is a repeat.
 */
@Composable
private fun DevicePickerDialog(
    inUse: Set<String>,
    onDismiss: () -> Unit,
    onPick: (Device?) -> Unit
) {
    val extras = LocalRoomExtras.current
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            Devices.all
        } else {
            Devices.all.filter {
                it.brand.lowercase().contains(needle) ||
                    it.model.lowercase().contains(needle) ||
                    it.code.lowercase().contains(needle) ||
                    it.year.toString() == needle
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device (${results.size} of ${Devices.all.size})") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search brand, model, code or year") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        // "No device" is a real choice: the profile then sends
                        // whatever the User-Agent setting below says.
                        TextButton(onClick = { onPick(null) }) {
                            Text("No device (use the User-Agent setting)")
                        }
                    }
                    items(results, key = { it.id }) { device ->
                        val taken = device.id in inUse
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(device) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "${device.brand} ${device.model}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = extras.textPrimary
                            )
                            val marks = buildString {
                                append(device.code)
                                append(" · ")
                                append(device.year)
                                append(" · Android ")
                                append(device.androidVersion)
                                if (device.formFactor == "tablet") append(" · tablet")
                                if (taken) append(" · used by another profile")
                            }
                            Text(
                                marks,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (taken) extras.primary else extras.textSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The width/height pair for a manual screen size, plus the one-tap way back to
 * the truth.
 *
 * Input is digits only and four characters at most, and a commit clamps into
 * [ClaimedScreen]'s stored range, so a typed 9999 is stored as 4320 rather than
 * as a screen no device has — which the page would then be told, because the
 * shim only declines sizes that are outside the range in the *stored* setting.
 * The fields reset to whatever was stored, so what they show is always what the
 * profile is actually claiming.
 */
@Composable
private fun ScreenSizeFields(
    width: Int,
    height: Int,
    real: Pair<Int, Int>,
    onCommit: (Int, Int) -> Unit
) {
    val extras = LocalRoomExtras.current
    var w by remember(width) { mutableStateOf(width.toString()) }
    var h by remember(height) { mutableStateOf(height.toString()) }
    fun commit() {
        val nw = (w.toIntOrNull() ?: 0).coerceIn(ClaimedScreen.MIN_PX, ClaimedScreen.MAX_PX)
        val nh = (h.toIntOrNull() ?: 0).coerceIn(ClaimedScreen.MIN_PX, ClaimedScreen.MAX_PX)
        onCommit(nw, nh)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = w,
                onValueChange = { w = it.filter(Char::isDigit).take(4) },
                label = { Text("Width (CSS px)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = h,
                onValueChange = { h = it.filter(Char::isDigit).take(4) },
                label = { Text("Height (CSS px)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    w = real.first.toString()
                    h = real.second.toString()
                    onCommit(real.first, real.second)
                }
            ) { Text("Use this phone's screen (${real.first}x${real.second})") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { commit() }) { Text("Apply") }
        }
        Text(
            "On Android one CSS pixel is one dp, so a 393x852 claim is a 393x852 dp screen.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun DnsUrlField(initial: String, onCommit: (String) -> Unit) {
    val extras = LocalRoomExtras.current
    var value by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("DoH URL (https://…)") },
            singleLine = true,
            shape = RoundedCornerShape((extras.radius * 0.6f).dp),
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = { onCommit(value.trim()) },
            modifier = Modifier.align(Alignment.End)
        ) { Text("Apply") }
    }
}

@Composable
private fun DotHostField(initial: String, onCommit: (String) -> Unit) {
    val extras = LocalRoomExtras.current
    var value by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("DoT hostname (e.g. dns.google)") },
            singleLine = true,
            shape = RoundedCornerShape((extras.radius * 0.6f).dp),
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = { onCommit(value.trim()) },
            modifier = Modifier.align(Alignment.End)
        ) { Text("Apply") }
    }
}

/** About screen (honest capabilities & limitations summary). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onClose: () -> Unit) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "About Room Browser", onClose = onClose)
        RoomCard(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    // The real versionName, not a literal: this said "1.0.0"
                    // for every build ever shipped, so the About screen could
                    // not tell a user which release they had installed.
                    "Room Browser ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = extras.textPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "One Android browser, multiple completely isolated profiles.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Isolation model: each profile binds the browser engine process to its own WebView data directory (cookies, localStorage, IndexedDB, cache, service workers are physically separated). Profile switching restarts the engine process — no state is reused.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
        }
        SectionHeader("Privacy")
        SettingsGroup {
            Text(
                "No telemetry. No analytics. No collection of URLs, history or profile identity. Filter lists ship inside the APK and never phone home. All statistics shown in the app come from real, locally recorded events.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        SectionHeader("Limitations (honesty first)")
        SettingsGroup {
            Text(
                "• WebView page-load DNS uses the OS resolver; DoH/DoT protects app connections.\n• Full WebRTC IP-handling control is not available to normal Android apps.\n• Private tabs share the profile cookie jar while open; session cookies are cleared on close.\n• This browser is NOT an anonymity tool.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * This display's size in CSS pixels — the numbers `screen.width` and
 * `screen.height` report inside a WebView on this phone.
 *
 * On Android one CSS pixel is one dp, and a dp is a physical pixel divided by
 * the display density, so this is the same arithmetic WebView does before it
 * lays a page out. That is what makes it the honest answer for "this phone's
 * screen": it is not an approximation of the viewport, it is the number the
 * page would have read anyway.
 *
 * Window metrics are preferred over the resource metrics because from Android
 * 12 the resources can reflect the app's window rather than the display, and a
 * size the user is about to claim should be the display's own.
 */
private fun realScreenCssPx(context: Context): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    val density = if (dm.density > 0f) dm.density else 1f
    val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(android.view.WindowManager::class.java)
            ?.currentWindowMetrics?.bounds
    } else {
        null
    }
    val wPx = bounds?.width() ?: dm.widthPixels
    val hPx = bounds?.height() ?: dm.heightPixels
    return (wPx / density).roundToInt().coerceAtLeast(1) to
        (hPx / density).roundToInt().coerceAtLeast(1)
}
