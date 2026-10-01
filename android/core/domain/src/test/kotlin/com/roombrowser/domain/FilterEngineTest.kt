package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilterEngineTest {

    private val engine = FilterEngine(
        adHosts = setOf("ads.example.com", "doubleclick.net", "adserv.evil.net"),
        trackerHosts = setOf("google-analytics.com", "stats.g.doubleclick.net", "tracker.example.org"),
        maliciousHosts = setOf("phishing.example.net")
    )

    /**
     * These tests are about the RULES, not about the defaults, so every
     * switch is stated here rather than inherited. The product default is
     * all-off (see ProfileSettings), and FilterEngine's own parameter
     * defaults were changed to match it — a test that silently leaned on
     * the old all-on defaults would then have failed for the wrong reason.
     */
    private fun decide(
        host: String,
        pageHost: String? = "site.com",
        blockAds: Boolean = true,
        blockTrackers: Boolean = true,
        blockCrossSite: Boolean = true,
        blockMalicious: Boolean = true
    ) = engine.decide(host, pageHost, "/", blockAds, blockTrackers, blockCrossSite, blockMalicious)

    @Test
    fun `blocks exact ad host`() {
        val d = decide("ads.example.com")
        assertThat(d).isInstanceOf(FilterEngine.Decision.Blocked::class.java)
        assertThat((d as FilterEngine.Decision.Blocked).category).isEqualTo(FilterEngine.FilterCategory.AD)
    }

    @Test
    fun `blocks subdomain of blocklisted host`() {
        val d = decide("cdn.doubleclick.net")
        assertThat(d).isInstanceOf(FilterEngine.Decision.Blocked::class.java)
    }

    @Test
    fun `blocks tracker with cross-site category when page differs`() {
        val d = decide("google-analytics.com", pageHost = "othersite.org")
        assertThat(d).isInstanceOf(FilterEngine.Decision.Blocked::class.java)
        assertThat((d as FilterEngine.Decision.Blocked).category)
            .isEqualTo(FilterEngine.FilterCategory.CROSS_SITE_TRACKER)
    }

    @Test
    fun `allows tracker host when blockTrackers disabled`() {
        val d = decide("google-analytics.com", blockTrackers = false)
        assertThat(d).isEqualTo(FilterEngine.Decision.Allowed())
    }

    @Test
    fun `allows normal hosts`() {
        assertThat(decide("wikipedia.org")).isEqualTo(FilterEngine.Decision.Allowed())
        assertThat(decide("developer.android.com")).isEqualTo(FilterEngine.Decision.Allowed())
    }

    @Test
    fun `malicious host is blocked with priority`() {
        val d = decide("phishing.example.net")
        assertThat((d as FilterEngine.Decision.Blocked).category).isEqualTo(FilterEngine.FilterCategory.MALICIOUS)
    }

    @Test
    fun `keyword rules match path`() {
        val d = decide("unknown-host.net", path = "/js/ads/banner.js")
        assertThat((d as FilterEngine.Decision.Blocked).category).isEqualTo(FilterEngine.FilterCategory.AD)
    }

    @Test
    fun `cross-site tracking allowed when only cross-site blocking is off`() {
        val d = decide("google-analytics.com", blockCrossSite = false)
        // trackers blocking still on but cross-site only disabled → still blocked as same-site? host differs → allowed
        assertThat(d).isInstanceOf(FilterEngine.Decision.Allowed::class.java)
    }

    @Test
    fun `suspicious signals detect http and ip literal and punycode`() {
        assertThat(engine.suspiciousSignals("http://insecure.com")).isNotEmpty()
        assertThat(engine.suspiciousSignals("http://192.168.1.5/login")).contains("IP address used instead of a domain name")
        assertThat(engine.suspiciousSignals("https://xn--pypal-4ve.com")).isNotEmpty()
        assertThat(engine.suspiciousSignals("https://safe.example.com")).isEmpty()
    }
}
