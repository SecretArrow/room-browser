package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.engine.DnsValidator
import org.junit.Test

class DnsPresetsTest {

    private fun isIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }

    private fun isIpv6(s: String): Boolean =
        s.isNotEmpty() && s.contains(":") && !s.contains(".") &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }

    @Test
    fun `every preset carries well-formed addresses and an https doh url`() {
        assertThat(DnsPresets.all).isNotEmpty()
        for (p in DnsPresets.all) {
            assertThat(isIpv4(p.primaryIpv4)).isTrue()
            assertThat(isIpv4(p.secondaryIpv4)).isTrue()
            assertThat(isIpv6(p.primaryIpv6)).isTrue()
            assertThat(isIpv6(p.secondaryIpv6)).isTrue()
            // The endpoint must clear the same gate the app applies to any
            // DoH URL before using it.
            assertThat(p.dohUrl).startsWith("https://")
            assertThat(DnsValidator.validateDohUrl(p.dohUrl)).isTrue()
            assertThat(p.label).isNotEmpty()
        }
    }

    @Test
    fun `preset ids are unique and lookups resolve`() {
        val ids = DnsPresets.all.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(DnsPresets.all.map { it.dohUrl }.toSet()).hasSize(ids.size)
        for (id in ids) {
            assertThat(DnsPresets.byId(id)!!.id).isEqualTo(id)
        }
        assertThat(DnsPresets.byId("no-such-dns")).isNull()
    }

    @Test
    fun `the shipped resolvers are Cloudflare, Google and OpenDNS`() {
        assertThat(DnsPresets.all.map { it.id }).containsExactly("cloudflare", "google", "opendns").inOrder()

        val cloudflare = DnsPresets.byId("cloudflare")!!
        assertThat(cloudflare.label).isEqualTo("Cloudflare DNS")
        assertThat(cloudflare.primaryIpv4).isEqualTo("1.1.1.1")
        assertThat(cloudflare.secondaryIpv4).isEqualTo("1.0.0.1")
        assertThat(cloudflare.primaryIpv6).isEqualTo("2606:4700:4700::1111")
        assertThat(cloudflare.secondaryIpv6).isEqualTo("2606:4700:4700::1001")
        assertThat(cloudflare.dohUrl).isEqualTo("https://cloudflare-dns.com/dns-query")

        val google = DnsPresets.byId("google")!!
        assertThat(google.primaryIpv4).isEqualTo("8.8.8.8")
        assertThat(google.secondaryIpv4).isEqualTo("8.8.4.4")
        assertThat(google.primaryIpv6).isEqualTo("2001:4860:4860::8888")
        assertThat(google.secondaryIpv6).isEqualTo("2001:4860:4860::8844")
        assertThat(google.dohUrl).isEqualTo("https://dns.google/dns-query")

        val opendns = DnsPresets.byId("opendns")!!
        assertThat(opendns.primaryIpv4).isEqualTo("208.67.222.222")
        assertThat(opendns.secondaryIpv4).isEqualTo("208.67.220.220")
        assertThat(opendns.primaryIpv6).isEqualTo("2620:119:35::35")
        assertThat(opendns.secondaryIpv6).isEqualTo("2620:119:53::53")
        assertThat(opendns.dohUrl).isEqualTo("https://doh.opendns.com/dns-query")
    }
}
