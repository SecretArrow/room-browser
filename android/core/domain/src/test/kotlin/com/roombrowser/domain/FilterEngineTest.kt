package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilterEngineTest {

    private val engine = FilterEngine(
        adHosts = setOf("ads.example.com", "doubleclick.net", "adserv.evil.net"),
        trackerHosts = setOf("google-analytics.com", "stats.g.doubleclick.net", "tracker.example.org"),
        maliciousHosts = setOf("phishing.example.net")
    )

    private fun decide(host: String, pageHost: String? = "site.com") =
        engine.decide(host, pageHost)

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
        val d = engine.decide("google-analytics.com", "site.com", "/", blockTrackers = false)
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
        val d = engine.decide("unknown-host.net", "site.com", "/js/ads/banner.js")
        assertThat((d as FilterEngine.Decision.Blocked).category).isEqualTo(FilterEngine.FilterCategory.AD)
    }

    @Test
    fun `cross-site tracking allowed when only cross-site blocking is off`() {
        val d = engine.decide(
            "google-analytics.com", "site.com", "/",
            blockCrossSite = false
        )
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
