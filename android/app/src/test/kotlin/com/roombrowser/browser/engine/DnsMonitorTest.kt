package com.roombrowser.browser.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Test

/**
 * The DNS monitor's real contract: the client it hands out must resolve
 * through exactly the resolver the reported state claims. These tests read
 * `OkHttpClient.dns` — the live resolver — rather than trusting the label.
 */
class DnsMonitorTest {

    private fun profile() = Profile(
        id = ProfileId("test-profile"),
        name = "Test",
        createdAt = 0L,
        // AUTO so the profile follows the global DNS setting under test.
        settings = ProfileSettings(dnsMode = DnsMode.AUTO)
    )

    private val cloudflare = BrowserGlobalSettings(
        dnsMode = DnsMode.DOH,
        dohUrl = "https://cloudflare-dns.com/dns-query"
    )

    @Test
    fun `reverting to system dns actually removes the doh resolver`() {
        runBlocking {
            val monitor = DnsMonitor()

            val dohClient = monitor.apply(cloudflare, profile())
            assertThat(dohClient.dns).isInstanceOf(DnsOverHttps::class.java)
            assertThat(monitor.state.value).isInstanceOf(DnsMonitor.DnsState.Protected::class.java)

            val systemClient = monitor.apply(cloudflare.copy(dnsMode = DnsMode.SYSTEM), profile())

            // The bug: this client used to still resolve through Cloudflare's
            // DoH, so the panel said "System default" while nothing had changed.
            assertThat(systemClient.dns).isSameInstanceAs(Dns.SYSTEM)
            assertThat(monitor.client().dns).isSameInstanceAs(Dns.SYSTEM)
            assertThat(monitor.state.value).isEqualTo(DnsMonitor.DnsState.System)
        }
    }

    @Test
    fun `switching provider then reverting rebuilds from a clean base`() {
        runBlocking {
            val monitor = DnsMonitor()
            val google = cloudflare.copy(dohUrl = "https://dns.google/dns-query")

            assertThat(monitor.apply(cloudflare, profile()).dns).isInstanceOf(DnsOverHttps::class.java)
            assertThat(monitor.apply(google, profile()).dns).isInstanceOf(DnsOverHttps::class.java)
            assertThat(monitor.apply(cloudflare, profile()).dns).isInstanceOf(DnsOverHttps::class.java)
            assertThat(monitor.apply(google.copy(dnsMode = DnsMode.SYSTEM), profile()).dns)
                .isSameInstanceAs(Dns.SYSTEM)
        }
    }

    @Test
    fun `dot is reported as validated but not enforced`() {
        runBlocking {
            val monitor = DnsMonitor()
            val global = BrowserGlobalSettings(dnsMode = DnsMode.DOT, dotHostname = "dns.google")

            val client = monitor.apply(global, profile())

            // No DoT transport exists anywhere in this app, so no DoT resolver
            // can be installed...
            assertThat(client.dns).isSameInstanceAs(Dns.SYSTEM)
            // ...and the state must therefore not claim protection.
            val state = monitor.state.value
            assertThat(state).isInstanceOf(DnsMonitor.DnsState.NotEnforced::class.java)
            val notEnforced = state as DnsMonitor.DnsState.NotEnforced
            assertThat(notEnforced.protocol).isEqualTo("DoT")
            assertThat(notEnforced.detail).isEqualTo("dns.google")
        }
    }

    @Test
    fun `a custom doh url still installs a resolver`() {
        runBlocking {
            val monitor = DnsMonitor()
            val custom = BrowserGlobalSettings(
                dnsMode = DnsMode.DOH,
                dohUrl = "https://doh.example.org/dns-query"
            )
            assertThat(monitor.apply(custom, profile()).dns).isInstanceOf(DnsOverHttps::class.java)
        }
    }
}
