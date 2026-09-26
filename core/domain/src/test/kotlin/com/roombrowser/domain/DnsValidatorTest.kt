package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.DnsMode
import org.junit.Test

class DnsValidatorTest {

    @Test
    fun `doh url requires https and host`() {
        assertThat(DnsValidator.validateDohUrl("https://dns.google/dns-query")).isTrue()
        assertThat(DnsValidator.validateDohUrl("https://cloudflare-dns.com/dns-query")).isTrue()
        assertThat(DnsValidator.validateDohUrl("http://dns.google/dns-query")).isFalse()
        assertThat(DnsValidator.validateDohUrl("https://")).isFalse()
        assertThat(DnsValidator.validateDohUrl("not a url")).isFalse()
    }

    @Test
    fun `dot hostname validation`() {
        assertThat(DnsValidator.validateDotHostname("dns.google")).isTrue()
        assertThat(DnsValidator.validateDotHostname("one.one.one.one:853")).isTrue()
        assertThat(DnsValidator.validateDotHostname("has space.example")).isFalse()
        assertThat(DnsValidator.validateDotHostname("")).isFalse()
        assertThat(DnsValidator.validateDotHostname("https://dns.example")).isFalse()
    }

    @Test
    fun `profile system mode wins`() {
        val eff = DnsValidator.effective(
            globalMode = DnsMode.DOH, globalDohUrl = "https://dns.google/dns-query",
            globalDotHostname = null,
            profileMode = DnsMode.SYSTEM, profileDohUrl = null, profileDotHostname = null
        )
        assertThat(eff.mode).isEqualTo(DnsMode.SYSTEM)
        assertThat(eff.status).isEqualTo(DnsValidator.DnsStatus.SYSTEM)
    }

    @Test
    fun `profile auto uses global`() {
        val eff = DnsValidator.effective(
            globalMode = DnsMode.DOT, globalDohUrl = null, globalDotHostname = "dns.google",
            profileMode = DnsMode.AUTO, profileDohUrl = null, profileDotHostname = null
        )
        assertThat(eff.mode).isEqualTo(DnsMode.DOT)
        assertThat(eff.status).isEqualTo(DnsValidator.DnsStatus.PROTECTED_DOT)
    }

    @Test
    fun `profile doh overrides global`() {
        val eff = DnsValidator.effective(
            globalMode = DnsMode.DOT, globalDohUrl = null, globalDotHostname = "dns.google",
            profileMode = DnsMode.DOH, profileDohUrl = "https://cloudflare-dns.com/dns-query",
            profileDotHostname = null
        )
        assertThat(eff.mode).isEqualTo(DnsMode.DOH)
        assertThat(eff.dohUrl).isEqualTo("https://cloudflare-dns.com/dns-query")
        assertThat(eff.status).isEqualTo(DnsValidator.DnsStatus.PROTECTED_DOH)
    }

    @Test
    fun `misconfigured doh detected`() {
        val eff = DnsValidator.effective(
            globalMode = DnsMode.DOH, globalDohUrl = "http://bad.example",
            globalDotHostname = null,
            profileMode = DnsMode.AUTO, profileDohUrl = null, profileDotHostname = null
        )
        assertThat(eff.status).isEqualTo(DnsValidator.DnsStatus.MISCONFIGURED)
    }
}
