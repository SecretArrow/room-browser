package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.ConflictSeverity
import com.roombrowser.domain.model.NetworkRetention
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.WarningBehavior
import org.junit.Test

class IpConflictDetectorTest {

    private var now = 1_000_000L
    private val detector = IpConflictDetector(clock = { now })
    private val personal = ProfileId("personal")
    private val work = ProfileId("work")
    private val research = ProfileId("research")

    private val defaults = BrowserGlobalSettings()

    @Test
    fun `validates ipv4`() {
        assertThat(detector.isValidIp("8.8.8.8")).isTrue()
        assertThat(detector.isValidIp("255.255.255.0")).isTrue()
        assertThat(detector.isValidIp("256.1.1.1")).isFalse()
        assertThat(detector.isValidIp("1.2.3")).isFalse()
        assertThat(detector.isValidIp("abc")).isFalse()
    }

    @Test
    fun `validates ipv6`() {
        assertThat(detector.isValidIp("2001:db8::1")).isTrue()
        assertThat(detector.isValidIp("fe80::")).isTrue()
        assertThat(detector.isValidIp("::1")).isTrue()
        assertThat(detector.isValidIp("2001:zz::1")).isFalse()
    }

    @Test
    fun `detects conflict with latest association`() {
        val history = listOf(
            IpAssociation(work, "203.0.113.10", now - 50_000, now - 50_000),
            IpAssociation(personal, "198.51.100.7", now - 90_000, now - 10_000)
        )
        val result = detector.check(research, "198.51.100.7", history, defaults)
        assertThat(result.conflict).isNotNull()
        assertThat(result.conflict!!.previousProfileId).isEqualTo(personal)
        assertThat(result.shouldWarn).isTrue()
        assertThat(result.requiresConfirmation).isFalse()
    }

    @Test
    fun `no conflict when own profile used ip`() {
        val history = listOf(IpAssociation(research, "203.0.113.10", now - 5, now))
        val result = detector.check(research, "203.0.113.10", history, defaults)
        assertThat(result.conflict).isNull()
    }

    @Test
    fun `no warning when globally disabled`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        val off = defaults.copy(networkProtectionEnabled = false)
        assertThat(detector.check(research, "1.2.3.4", history, off).shouldWarn).isFalse()
    }

    @Test
    fun `no warning when disabled for this profile`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        assertThat(
            detector.check(research, "1.2.3.4", history, defaults, profileNetworkProtectionEnabled = false).shouldWarn
        ).isFalse()
    }

    @Test
    fun `dont warn behavior suppresses`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        val s = defaults.copy(warningBehavior = WarningBehavior.DONT_WARN)
        assertThat(detector.check(research, "1.2.3.4", history, s).shouldWarn).isFalse()
    }

    @Test
    fun `suppressed ip (dont warn again) suppresses`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        assertThat(
            detector.check(research, "1.2.3.4", history, defaults, suppressedIps = setOf("1.2.3.4")).shouldWarn
        ).isFalse()
    }

    @Test
    fun `session suppressed ip suppresses`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        assertThat(
            detector.check(
                research, "1.2.3.4", history, defaults,
                suppressedIpsThisSession = setOf("1.2.3.4")
            ).shouldWarn
        ).isFalse()
    }

    @Test
    fun `session suppression keys on the ip, not the profile id`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        // The session set holds IP addresses. A profile id in it must NOT
        // suppress — that comparison is the type confusion this guards against.
        assertThat(
            detector.check(
                research, "1.2.3.4", history, defaults,
                suppressedIpsThisSession = setOf("work")
            ).shouldWarn
        ).isTrue()
        // Neither must an unrelated IP.
        assertThat(
            detector.check(
                research, "1.2.3.4", history, defaults,
                suppressedIpsThisSession = setOf("5.6.7.8")
            ).shouldWarn
        ).isTrue()
    }

    @Test
    fun `once per network suppresses second time`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        val s = defaults.copy(warningBehavior = WarningBehavior.ONCE_PER_NETWORK)
        assertThat(detector.check(research, "1.2.3.4", history, s).shouldWarn).isTrue()
        assertThat(
            detector.check(research, "1.2.3.4", history, s, alreadyWarnedNetworks = setOf("1.2.3.4")).shouldWarn
        ).isFalse()
    }

    @Test
    fun `require confirmation severity`() {
        val history = listOf(IpAssociation(work, "1.2.3.4", now, now))
        val s = defaults.copy(conflictSeverity = ConflictSeverity.REQUIRE_CONFIRMATION)
        assertThat(detector.check(research, "1.2.3.4", history, s).requiresConfirmation).isTrue()
    }

    @Test
    fun `expired associations ignored by retention`() {
        now = 100 * 24L * 60 * 60 * 1000
        val oldSeen = now - 40L * 24 * 60 * 60 * 1000 // 40 days ago
        val history = listOf(IpAssociation(work, "1.2.3.4", oldSeen, oldSeen))
        val s = defaults.copy(retention = NetworkRetention.THIRTY_DAYS)
        assertThat(detector.check(research, "1.2.3.4", history, s).conflict).isNull()
        val forever = defaults.copy(retention = NetworkRetention.FOREVER)
        assertThat(detector.check(research, "1.2.3.4", history, forever).conflict).isNotNull()
    }

    @Test
    fun `record updates lastSeen and keeps firstSeen`() {
        val existing = IpAssociation(research, "9.9.9.9", firstSeenAt = 100, lastSeenAt = 100)
        now = 500
        val updated = detector.record(research, "9.9.9.9", existing)
        assertThat(updated.firstSeenAt).isEqualTo(100)
        assertThat(updated.lastSeenAt).isEqualTo(500)
    }

    @Test
    fun `purge removes expired only`() {
        now = 100L * 24 * 60 * 60 * 1000
        val fresh = IpAssociation(personal, "1.1.1.1", now - 1000, now - 1000)
        val stale = IpAssociation(work, "2.2.2.2", now - 90L * 24 * 60 * 60 * 1000, now - 90L * 24 * 60 * 60 * 1000)
        val purged = detector.purgeExpired(listOf(fresh, stale), NetworkRetention.THIRTY_DAYS)
        assertThat(purged).containsExactly(stale)
    }
}
