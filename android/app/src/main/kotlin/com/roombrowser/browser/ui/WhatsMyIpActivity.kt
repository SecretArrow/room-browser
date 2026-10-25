package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.engine.IdentityCheck
import com.roombrowser.browser.engine.IdentityDiagnostics
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.net.ConnectionFacts
import com.roombrowser.data.net.DeviceFacts
import com.roombrowser.data.net.SystemFacts
import com.roombrowser.data.net.fetchExitIp
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.net.ExitIp
import com.roombrowser.domain.net.IpAddress
import com.roombrowser.domain.net.NetFacts
import com.roombrowser.engine.EngineRuntime
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * "What's My IP" — what this profile looks like from the far end.
 *
 * Everything here is reported, nothing is configured, and the two halves are
 * kept apart on purpose:
 *
 *  - the ADDRESS is measured DIRECTLY, over a bare client that carries none of
 *    this profile's configuration, because the whole point is the address a
 *    site would actually see.
 *  - the IDENTITY (user agent, DNS) is read from the profile's own settings
 *    through the same resolvers the engine is configured from, so a row cannot
 *    claim something the engine does not send.
 *
 * A refresh re-measures both: the address may have changed, and so may the
 * transport it came over.
 */
class WhatsMyIpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            // Nothing to report on — the launcher always names a profile.
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val appContext = applicationContext
        setContent {
            RoomBrowserTheme {
                var facts by remember { mutableStateOf<IpFacts?>(null) }
                var reading by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(profileId) {
                    facts = loadFacts(appContext, profileId)
                }
                WhatsMyIpScreen(
                    facts = facts,
                    reading = reading,
                    onRefresh = {
                        if (!reading) {
                            reading = true
                            scope.launch {
                                facts = loadFacts(appContext, profileId)
                                reading = false
                            }
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: Context, profileId: String) {
            context.startActivity(
                Intent(context, WhatsMyIpActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
            )
        }
    }
}

/** What the profile sends, and where each part of it comes from. */
private data class IdentityFacts(
    val userAgent: String,
    val sourceLabel: String,
    val engineName: String,
    val engineStockUserAgent: String,
    /**
     * The per-surface self-check, computed in the same reading as everything
     * else on this screen.
     *
     * IT LIVES HERE RATHER THAN ONLY IN SETTINGS because this screen is where
     * a person already goes to ask "what does the far end see me as", and
     * because the question that sends them looking — "why does Google call
     * this an app" — arrives while they are looking at a page, not while they
     * are scrolling through settings. The rows are the same object either
     * place; only the door is different.
     */
    val checks: List<IdentityCheck>
)

/** One reading of the screen, so a refresh replaces the whole picture at once. */
private data class IpFacts(
    val ipv4: String?,
    val ipv6: String?,
    val identity: IdentityFacts,
    val dnsSummary: String,
    val connection: ConnectionFacts,
    val device: DeviceFacts
)

/** Bounded so a network that never answers fails the reading instead of hanging it. */
private const val PROBE_TIMEOUT_MS = 8_000L

private const val UNKNOWN = "unknown"

/**
 * The engine's own answers, taken in one pass so the reading cannot mix two
 * different providers.
 */
private data class EngineFacts(
    val name: String,
    val stockUserAgent: String,
    val checks: List<IdentityCheck>
)

private suspend fun loadFacts(context: Context, profileId: ProfileId): IpFacts {
    val graph = (context.applicationContext as RoomBrowserApp).graph
    val profile = withContext(Dispatchers.IO) {
        runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()
    }
    val settings = profile?.settings ?: ProfileSettings()
    val source = NetFacts.uaSource(settings)
    val chosen = UserAgents.effectiveUserAgent(settings)

    // EVERYTHING THE ENGINE IS ASKED HAPPENS ON THE MAIN THREAD, and that is
    // what makes the answers true rather than merely plausible.
    //
    // Each answer below comes from the installed WebView provider -- its name,
    // its stock user agent, and whether it supports each opt-out -- and asking
    // from a worker made the provider load on a thread with no Looper. A load
    // that does not complete there answers exactly like a provider that
    // predates the feature: `WebViewFeature.isFeatureSupported` reports false
    // either way, so this screen told people their Android System WebView was
    // too old on devices whose WebView was current. The provider's own calls
    // are main-thread-only for the same reason, so this is where they belong
    // rather than a workaround for one of them.
    val engine = withContext(Dispatchers.Main) {
        val host = runCatching { EngineRuntime.host() }.getOrNull()
        // Asked only when the answer is the one on screen: the engine may not
        // be able to say yet, and it is asked for nothing else here.
        val stock = if (profile != null && chosen == null) {
            runCatching { host?.defaultUserAgent(context) }.getOrNull().orEmpty()
        } else {
            ""
        }
        EngineFacts(
            name = runCatching { host?.engineName(context) }.getOrNull().orEmpty(),
            stockUserAgent = stock,
            checks = identityChecks(settings, chosen, stock)
        )
    }

    return withContext(Dispatchers.IO) {
        // A fresh client, never the browser's: the browser's carries this
        // profile's DNS choice, and a reading taken through that resolver is
        // the wrong answer to "what address does the internet see ME from".
        val client = OkHttpClient()
        val ipv4 = runCatching {
            fetchExitIp(client, PROBE_TIMEOUT_MS, IpAddress::looksLike)
        }.getOrNull()
        val ipv6 = runCatching {
            fetchExitIp(
                client,
                PROBE_TIMEOUT_MS,
                IpAddress::looksLike,
                ExitIp.IPV6_ENDPOINTS
            )
        }.getOrNull()

        IpFacts(
            ipv4 = ipv4,
            ipv6 = ipv6,
            identity = IdentityFacts(
                userAgent = when {
                    profile == null -> UNKNOWN
                    chosen != null -> chosen
                    engine.stockUserAgent.isNotEmpty() -> engine.stockUserAgent
                    else -> UNKNOWN
                },
                sourceLabel = if (profile == null) {
                    "This profile could not be read"
                } else {
                    NetFacts.uaSourceLabel(source)
                },
                engineName = engine.name,
                engineStockUserAgent = engine.stockUserAgent,
                checks = engine.checks
            ),
            dnsSummary = dnsSummary(settings),
            connection = SystemFacts.connection(context),
            device = SystemFacts.device(context)
        )
    }
}

/**
 * The self-check, for the same five surfaces Settings reports.
 *
 * [chosen] is the profile's own answer and [engineStock] the engine's, and
 * only one of them is ever on the wire: a profile that picks an identity
 * replaces the engine's outright rather than adding to it.
 */
private fun identityChecks(
    settings: ProfileSettings,
    chosen: String?,
    engineStock: String
): List<IdentityCheck> = IdentityDiagnostics.checks(
    // The string the session actually installs: the profile's own choice when
    // it has one, otherwise the engine's, with the WebView markers already off
    // it (`EngineHost.defaultUserAgent` returns the neutral form).
    userAgent = chosen ?: engineStock.takeIf { it.isNotBlank() },
    javascriptEnabled = settings.javascriptEnabled,
    // Every one of these is asked of the INSTALLED PROVIDER, and each is
    // guarded inside the engine, so a device with no working provider answers
    // false rather than taking this screen down. That is the honest answer
    // there: nothing on this device can suppress the header or set the hints.
    // Guarded again here because this screen must render its network facts even
    // when the engine is unreachable — a spinner that never resolves would hide
    // the one screen that could have explained the problem.
    suppressesRequestedWith = runCatching {
        ProfileEngine.suppressesEmbeddedBrowserHeaders()
    }.getOrDefault(false),
    setsClientHints = runCatching {
        ProfileEngine.supportsUserAgentClientHints()
    }.getOrDefault(false),
    installsDocumentStartScripts = runCatching {
        ProfileEngine.supportsDocumentStartScripts()
    }.getOrDefault(false)
)

/**
 * The profile's own DNS choice. A mode with no endpoint stored falls back to
 * the system resolver, so the row says that rather than naming a setting the
 * engine cannot act on.
 */
private fun dnsSummary(settings: ProfileSettings): String = when (settings.dnsMode) {
    DnsMode.SYSTEM -> "System resolver"
    DnsMode.AUTO -> "Automatic - secure where the network offers it"
    DnsMode.DOH -> settings.dohUrl?.takeIf { it.isNotBlank() }
        ?.let { "DNS over HTTPS - $it" }
        ?: "DNS over HTTPS, but no URL is saved - using the system resolver"
    DnsMode.DOT -> settings.dotHostname?.takeIf { it.isNotBlank() }
        ?.let { "DNS over TLS - $it" }
        ?: "DNS over TLS, but no hostname is saved - using the system resolver"
}

@Composable
private fun WhatsMyIpScreen(
    facts: IpFacts?,
    reading: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close" }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                "What's My IP",
                style = MaterialTheme.typography.titleLarge,
                color = extras.textPrimary
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onRefresh,
                enabled = !reading,
                modifier = Modifier.semantics { contentDescription = "whatsmyip_refresh" }
            ) {
                if (reading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = extras.primary
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(if (reading) "Reading" else "Refresh")
            }
        }

        val shown = facts
        if (shown == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = extras.primary)
                Spacer(Modifier.height(16.dp))
                Text("Reading this device and its network", color = extras.textSecondary)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Section("Network") {
                    FactRow(
                        label = "Public address (IPv4)",
                        value = shown.ipv4 ?: "No reading - no IP service answered",
                        desc = "whatsmyip_ipv4"
                    )
                    FactRow(
                        label = "Public address (IPv6)",
                        value = shown.ipv6
                            ?: "None - this network gave the probe no IPv6 route",
                        desc = "whatsmyip_ipv6"
                    )
                    Text(
                        "Measured directly, over a bare connection that carries none of " +
                            "this profile's settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Section("What this profile says it is") {
                    FactRow(
                        label = "User agent sent",
                        value = shown.identity.userAgent,
                        desc = "whatsmyip_user_agent"
                    )
                    FactRow(label = "Coming from", value = shown.identity.sourceLabel)
                    FactRow(
                        label = "Engine",
                        value = shown.identity.engineName.ifBlank { UNKNOWN }
                    )
                    FactRow(
                        label = "Engine's own user agent",
                        value = shown.identity.engineStockUserAgent.ifBlank {
                            "Not reported - this profile supplies its own"
                        }
                    )
                    // The self-check, right under the string it is about. A
                    // sign-in page that answers "this browser or app may not be
                    // secure" is objecting to one of these five, and this is
                    // where a person is already standing when they wonder which.
                    Text(
                        "Read by a sign-in page, surface by surface",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.textSecondary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    for (check in shown.identity.checks) {
                        IdentitySurfaceRow(check)
                    }
                    Text(
                        "A warning here is what makes a site answer \"this browser " +
                            "or app may not be secure\". A row naming Android System " +
                            "WebView also names the version that offers that " +
                            "surface; the JavaScript row is fixed in this profile's " +
                            "settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Section("Route") {
                    FactRow(label = "DNS", value = shown.dnsSummary)
                }

                Section("This connection") {
                    FactRow(label = "Transport", value = shown.connection.transport)
                    FactRow(
                        label = "Metered",
                        value = if (shown.connection.metered) "Yes - a metered network" else "No"
                    )
                    FactRow(label = "VPN", value = if (shown.connection.vpn) "Yes" else "No")
                    FactRow(
                        label = "Local addresses",
                        value = shown.connection.localAddresses.ifEmpty { listOf("None reported") }
                            .joinToString("\n")
                    )
                    FactRow(
                        label = "System DNS servers",
                        value = shown.connection.systemDns.ifEmpty { listOf("None reported") }
                            .joinToString("\n")
                    )
                    FactRow(
                        label = "Private DNS",
                        value = shown.connection.privateDns ?: UNKNOWN
                    )
                    FactRow(
                        label = "Network operator",
                        value = shown.connection.operatorName ?: "Not reported"
                    )
                }

                Section("This device") {
                    FactRow(
                        label = "App",
                        value = "${shown.device.appVersion} (${shown.device.appVersionCode}) - " +
                            shown.device.packageName
                    )
                    FactRow(
                        label = "Android",
                        value = "${shown.device.androidRelease} (API ${shown.device.apiLevel})"
                    )
                    FactRow(
                        label = "Device",
                        value = "${shown.device.manufacturer} ${shown.device.model}"
                    )
                    FactRow(label = "Primary ABI", value = shown.device.primaryAbi)
                    FactRow(label = "Locale", value = shown.device.locale)
                    FactRow(label = "Time zone", value = shown.device.timeZone)
                    FactRow(
                        label = "Screen",
                        value = "${shown.device.screenPixels}, ${shown.device.density}"
                    )
                }

                Text(
                    "None of this leaves the device. The two address readings are the " +
                        "only requests this screen makes, and they go to the public " +
                        "IP-echo services the network check already uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val extras = LocalRoomExtras.current
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = extras.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoomCardShape)
            .background(extras.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        content = content
    )
}

/**
 * One surface of the self-check: a tick or a warning, the surface's name, and
 * what a site is actually told about it.
 */
@Composable
private fun IdentitySurfaceRow(check: IdentityCheck) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (check.ok) "OK" else "Problem",
            tint = if (check.ok) extras.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                check.label,
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textPrimary
            )
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, desc: String? = null) {
    val extras = LocalRoomExtras.current
    val description = desc
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textPrimary,
            modifier = if (description == null) {
                Modifier
            } else {
                Modifier.semantics { contentDescription = description }
            }
        )
    }
}
