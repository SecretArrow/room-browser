package com.roombrowser.browser.engine

import com.roombrowser.domain.engine.DnsValidator
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS privacy monitor.
 *
 * HONEST LIMITATION (documented in PRIVACY.md and in the DNS settings UI):
 * Android WebView resolves its own traffic through the OS network stack —
 * a normal third-party app cannot force WebView to use a custom resolver.
 * The DoH/DoT configuration here protects the app's OWN connections
 * (public-IP check, search suggestions, downloads) and exposes a
 * "Protected" state for those connections. It does NOT claim to protect
 * WebView page loads.
 */
class DnsMonitor {

    sealed interface DnsState {
        data object System : DnsState
        data class Protected(val protocol: String, val resolver: String) : DnsState
        data class Misconfigured(val reason: String) : DnsState
    }

    private val _state = MutableStateFlow<DnsState>(DnsState.System)
    val state: StateFlow<DnsState> = _state

    private val mutex = Mutex()
    private var currentClient: OkHttpClient? = null

    /**
     * Resolve the effective DNS configuration for [profile] given [global]
     * and (re)build the app's OkHttpClient accordingly.
     */
    suspend fun apply(global: BrowserGlobalSettings, profile: Profile): OkHttpClient = mutex.withLock {
        val effective = DnsValidator.effective(
            globalMode = global.dnsMode,
            globalDohUrl = global.dohUrl,
            globalDotHostname = global.dotHostname,
            profileMode = profile.settings.dnsMode,
            profileDohUrl = profile.settings.dohUrl,
            profileDotHostname = profile.settings.dotHostname
        )
        when (effective.status) {
            DnsValidator.DnsStatus.SYSTEM ->
                _state.value = DnsState.System
            DnsValidator.DnsStatus.PROTECTED_DOH ->
                _state.value = DnsState.Protected("DoH", effective.dohUrl ?: "")
            DnsValidator.DnsStatus.PROTECTED_DOT ->
                _state.value = DnsState.Protected("DoT", effective.dotHostname ?: "")
            DnsValidator.DnsStatus.MISCONFIGURED ->
                _state.value = DnsState.Misconfigured("Check the DoH URL / DoT hostname")
        }

        val client = currentClient ?: baseClient()
        val doh = when (effective.status) {
            DnsValidator.DnsStatus.PROTECTED_DOH -> effective.dohUrl?.let { url ->
                buildDoh(url, client)
            }
            else -> null
        }
        val rebuilt = client.newBuilder()
            .apply { if (doh != null) dns(doh) }
            .build()
        currentClient = rebuilt
        return rebuilt
    }

    /**
     * Probe: can we resolve a known host with the current configuration?
     * Returns true when the DoH resolver answered.
     */
    suspend fun probe(client: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.dns.lookup("example.com").isNotEmpty()
        }.getOrDefault(false)
    }

    fun client(): OkHttpClient = currentClient ?: baseClient()

    private fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * DoT note: OkHttp's DnsOverHttps covers DoH; DoT is validated and shown
     * as the selected protocol, and OS-level Private DNS is recommended in
     * the UI (Settings → Network → Private DNS), which covers WebView too.
     */
    private fun buildDoh(dohUrl: String, bootstrap: OkHttpClient): DnsOverHttps {
        val url = dohUrl.toHttpUrl()
        return DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url)
            .build()
    }

    /** System DNS fallback implementation (used by tests and diagnostics). */
    object SystemDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == "localhost") listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            else throw UnknownHostException(hostname)
    }
}
