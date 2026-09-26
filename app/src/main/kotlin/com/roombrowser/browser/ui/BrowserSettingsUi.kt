package com.roombrowser.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.NetworkRetention
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.SearchEngines
import com.roombrowser.domain.model.ThemeMode
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.model.WarningBehavior
import com.roombrowser.domain.model.ConflictSeverity
import com.roombrowser.domain.model.WebRtcPolicy
import com.roombrowser.domain.model.TabLayout
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingSwitchRow
import com.roombrowser.ui.common.SectionHeader
import kotlinx.coroutines.launch

/** Global browser settings (spec section 32 — "Browser" tree). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var global by remember { mutableStateOf(viewModel.globalSettings) }
    LaunchedEffect(viewModel.globalSettings) { global = viewModel.globalSettings }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Browser Settings") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        )

        SectionHeader("Privacy & Network")
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

        SectionHeader("IP History Retention")
        NetworkRetention.entries.forEach { retention ->
            RadioRow(
                label = retention.name.lowercase().replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
                selected = global.retention == retention,
                onSelect = {
                    global = global.copy(retention = retention)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
        }

        SectionHeader("Warning Behavior")
        WarningBehavior.entries.forEach { behavior ->
            RadioRow(
                label = behavior.name.lowercase().replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
                selected = global.warningBehavior == behavior,
                onSelect = {
                    global = global.copy(warningBehavior = behavior)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
        }

        SectionHeader("Conflict Severity")
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

        SectionHeader("Global DNS")
        DnsMode.entries.forEach { mode ->
            RadioRow(
                label = when (mode) {
                    DnsMode.SYSTEM -> "System default"
                    DnsMode.AUTO -> "Automatic"
                    DnsMode.DOH -> "DNS-over-HTTPS (app connections)"
                    DnsMode.DOT -> "DNS-over-TLS (validate + use OS Private DNS)"
                },
                selected = global.dnsMode == mode,
                onSelect = {
                    global = global.copy(dnsMode = mode)
                    scope.launch { viewModel.updateGlobalSettings(global) }
                }
            )
        }
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
        Text(
            "Browser-level DNS cannot override every DNS request made by the Android OS or WebView page loads — see PRIVACY.md.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )

        SectionHeader("Diagnostics")
        SettingSwitchRow(
            title = "Enable network diagnostics",
            subtitle = "Allows the About/Diagnostics screens to show the observed public IP",
            checked = global.diagnosticsEnabled,
            onCheckedChange = {
                global = global.copy(diagnosticsEnabled = it)
                scope.launch { viewModel.updateGlobalSettings(global) }
            }
        )
        SettingActionRow(
            title = "Engine",
            value = ProfileEngine.engineName(context),
            onClick = { }
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** Per-profile settings (spec section 32 — "Profile" tree + section 68). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(viewModel.profileSettings()) }
    LaunchedEffect(viewModel.profile) { settings = viewModel.profileSettings() }

    fun update(new: ProfileSettings) {
        settings = new
        scope.launch { viewModel.updateSettings(new) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Profile Settings — ${viewModel.profile.name}") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        )

        SectionHeader("Appearance")
        ThemeMode.entries.forEach { mode ->
            RadioRow(
                label = when (mode) {
                    ThemeMode.SYSTEM -> "Theme: System"
                    ThemeMode.LIGHT -> "Theme: Light"
                    ThemeMode.DARK -> "Theme: Dark"
                    ThemeMode.AMOLED -> "Theme: AMOLED black"
                },
                selected = settings.theme == mode,
                onSelect = { update(settings.copy(theme = mode)) }
            )
        }
        SettingSwitchRow(
            title = "High contrast",
            checked = settings.highContrast,
            onCheckedChange = { update(settings.copy(highContrast = it)) }
        )
        SettingSwitchRow(
            title = "Reduced motion",
            checked = settings.reducedMotion,
            onCheckedChange = { update(settings.copy(reducedMotion = it)) }
        )
        TabLayout.entries.forEach { layout ->
            RadioRow(
                label = if (layout == TabLayout.GRID) "Tab layout: Grid" else "Tab layout: List",
                selected = settings.tabLayout == layout,
                onSelect = { update(settings.copy(tabLayout = layout)) }
            )
        }

        SectionHeader("Search")
        SearchEngines.all.forEach { engine ->
            RadioRow(
                label = engine.label,
                selected = settings.searchEngineId == engine.id,
                onSelect = { update(settings.copy(searchEngineId = engine.id)) }
            )
        }
        SettingSwitchRow(
            title = "Search suggestions",
            subtitle = "Sends typed queries to the selected search engine (privacy trade-off)",
            checked = settings.searchSuggestions,
            onCheckedChange = { update(settings.copy(searchSuggestions = it)) }
        )

        SectionHeader("Privacy & Blocking")
        SettingSwitchRow(title = "Block ads", checked = settings.blockAds, onCheckedChange = { update(settings.copy(blockAds = it)) })
        SettingSwitchRow(title = "Block trackers", checked = settings.blockTrackers, onCheckedChange = { update(settings.copy(blockTrackers = it)) })
        SettingSwitchRow(title = "Block cross-site trackers", checked = settings.blockCrossSiteTrackers, onCheckedChange = { update(settings.copy(blockCrossSiteTrackers = it)) })
        SettingSwitchRow(title = "Block popups", checked = settings.blockPopups, onCheckedChange = { update(settings.copy(blockPopups = it)) })
        SettingSwitchRow(title = "Block malicious websites", checked = settings.blockMalicious, onCheckedChange = { update(settings.copy(blockMalicious = it)) })
        SettingSwitchRow(title = "HTTPS upgrades", checked = settings.httpsUpgrade, onCheckedChange = { update(settings.copy(httpsUpgrade = it)) })
        SettingSwitchRow(title = "Block third-party cookies", checked = settings.blockThirdPartyCookies, onCheckedChange = { update(settings.copy(blockThirdPartyCookies = it)) })
        SettingSwitchRow(title = "JavaScript enabled", checked = settings.javascriptEnabled, onCheckedChange = { update(settings.copy(javascriptEnabled = it)) })

        SectionHeader("WebRTC (informational)")
        WebRtcPolicy.entries.forEach { policy ->
            RadioRow(
                label = when (policy) {
                    WebRtcPolicy.DEFAULT -> "WebRTC: Default"
                    WebRtcPolicy.RESTRICT_LOCAL_IP -> "WebRTC: Restrict local IP exposure"
                    WebRtcPolicy.DISABLED -> "WebRTC: Disabled (may break calls)"
                },
                selected = settings.webRtcPolicy == policy,
                onSelect = { update(settings.copy(webRtcPolicy = policy)) }
            )
        }
        Text(
            "Android WebView does not expose full WebRTC IP-handling control to normal apps. Camera/microphone grants stay under permission control; local IP exposure limits are documented in SECURITY.md.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionHeader("User-Agent")
        UaMode.entries.forEach { mode ->
            RadioRow(
                label = when (mode) {
                    UaMode.DEFAULT -> "Default (WebView)"
                    UaMode.PRESET -> "Preset"
                    UaMode.CUSTOM -> "Custom"
                },
                selected = settings.uaMode == mode,
                onSelect = { update(settings.copy(uaMode = mode)) }
            )
        }
        if (settings.uaMode == UaMode.PRESET) {
            UserAgents.all.forEach { preset ->
                RadioRow(
                    label = preset.label + if (preset.isDesktop) "  (desktop)" else "",
                    selected = settings.uaPresetId == preset.id,
                    onSelect = { update(settings.copy(uaPresetId = preset.id)) }
                )
            }
        }
        if (settings.uaMode == UaMode.CUSTOM) {
            var custom by remember(settings.customUserAgent) { mutableStateOf(settings.customUserAgent ?: "") }
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("Custom User-Agent") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            TextButton(
                onClick = { update(settings.copy(customUserAgent = custom.trim())) },
                modifier = Modifier.padding(start = 16.dp)
            ) { Text("Apply") }
        }
        val effective = UserAgents.effectiveUserAgent(settings)
        Text(
            "Current: ${effective ?: "Android WebView default"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        Text(
            "Changing the User-Agent string does not change other platform/device characteristics (screen, APIs, capabilities).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionHeader("Profile DNS")
        DnsMode.entries.forEach { mode ->
            RadioRow(
                label = when (mode) {
                    DnsMode.SYSTEM -> "System"
                    DnsMode.AUTO -> "Use global browser setting"
                    DnsMode.DOH -> "DNS-over-HTTPS"
                    DnsMode.DOT -> "DNS-over-TLS"
                },
                selected = settings.dnsMode == mode,
                onSelect = { update(settings.copy(dnsMode = mode)) }
            )
        }
        if (settings.dnsMode == DnsMode.DOH) {
            DnsUrlField(initial = settings.dohUrl ?: "", onCommit = { update(settings.copy(dohUrl = it)) })
        }
        if (settings.dnsMode == DnsMode.DOT) {
            DotHostField(initial = settings.dotHostname ?: "", onCommit = { update(settings.copy(dotHostname = it)) })
        }

        SectionHeader("Network Protection (this profile)")
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

        SectionHeader("Homepage & Tabs")
        SettingSwitchRow(title = "Show homepage", checked = settings.homepageEnabled, onCheckedChange = { update(settings.copy(homepageEnabled = it)) })
        SettingSwitchRow(title = "Show privacy statistics", checked = settings.showPrivacyStats, onCheckedChange = { update(settings.copy(showPrivacyStats = it)) })
        SettingSwitchRow(title = "Show recent sites", checked = settings.showRecentSites, onCheckedChange = { update(settings.copy(showRecentSites = it)) })
        SettingSwitchRow(title = "Show clock", checked = settings.showClock, onCheckedChange = { update(settings.copy(showClock = it)) })

        SectionHeader("Language & Translate")
        var target by remember(settings.translateTargetLanguage) { mutableStateOf(settings.translateTargetLanguage) }
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Translate target language (e.g. id)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        TextButton(onClick = { update(settings.copy(translateTargetLanguage = target.trim())) }) { Text("Apply") }

        SectionHeader("Autofill")
        SettingSwitchRow(
            title = "Android Autofill integration",
            subtitle = "Delegates credential storage to the Android system autofill framework — Room Browser never stores passwords itself",
            checked = settings.autofillEnabled,
            onCheckedChange = { update(settings.copy(autofillEnabled = it)) }
        )

        SectionHeader("Desktop Mode")
        SettingSwitchRow(
            title = "Request desktop site by default",
            checked = settings.desktopModeDefault,
            onCheckedChange = { update(settings.copy(desktopModeDefault = it)) }
        )

        SectionHeader("Danger Zone — Clear Browsing Data")
        ClearDataSection(viewModel = viewModel)

        Spacer(Modifier.height(48.dp))
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
        androidx.compose.material3.Button(onClick = { confirm = true }) { Text("Clear Now") }
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun DnsUrlField(initial: String, onCommit: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("DoH URL (https://…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { onCommit(value.trim()) }) { Text("Apply") }
    }
}

@Composable
private fun DotHostField(initial: String, onCommit: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("DoT hostname (e.g. dns.google)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { onCommit(value.trim()) }) { Text("Apply") }
    }
}

/** About screen (honest capabilities & limitations summary). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("About Room Browser") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        )
        Column(Modifier.padding(16.dp)) {
            Text("Room Browser 1.0.0", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "One Android browser, multiple completely isolated profiles.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Isolation model: each profile binds the browser engine process to its own WebView data directory (cookies, localStorage, IndexedDB, cache, service workers are physically separated). Profile switching restarts the engine process — no state is reused.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text(
                "No telemetry. No analytics. No collection of URLs, history or profile identity. Filter lists ship inside the APK and never phone home. All statistics shown in the app come from real, locally recorded events.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Text("Limitations (honesty first)", style = MaterialTheme.typography.titleMedium)
            Text(
                "• WebView page-load DNS uses the OS resolver; DoH/DoT protects app connections.\n• Full WebRTC IP-handling control is not available to normal Android apps.\n• Private tabs share the profile cookie jar while open; session cookies are cleared on close.\n• This browser is NOT an anonymity tool.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
