package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProfileModelTest {

    @Test
    fun `profile id safe suffix is 32 hex chars`() {
        val id = ProfileId.new()
        assertThat(id.safeSuffix).hasLength(32)
        assertThat(id.safeSuffix).matches("^[0-9a-f]{32}$")
        assertThat(id.safeSuffix).doesNotContain("-")
    }

    @Test
    fun `renaming does not change storage identity`() {
        val id = ProfileId.new()
        val p1 = Profile(id = id, name = "Personal", createdAt = 0)
        val p2 = p1.copy(name = "Family")
        assertThat(p2.id).isEqualTo(p1.id)
        assertThat(p2.id.safeSuffix).isEqualTo(p1.id.safeSuffix)
    }

    @Test
    fun `blank profile id rejected`() {
        var thrown = false
        try { ProfileId("") } catch (e: IllegalArgumentException) { thrown = true }
        assertThat(thrown).isTrue()
    }

    @Test
    fun `retention cutoff math`() {
        val now = 1_000_000_000L
        assertThat(NetworkRetention.ONE_DAY.cutoff(now)).isEqualTo(now - 86_400_000L)
        assertThat(NetworkRetention.SEVEN_DAYS.cutoff(now)).isEqualTo(now - 7 * 86_400_000L)
        assertThat(NetworkRetention.FOREVER.cutoff(now)).isNull()
    }

    @Test
    fun `effective user agent modes`() {
        assertThat(UserAgents.effectiveUserAgent(ProfileSettings())).isNull()
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "chrome_windows"))
        ).contains("Windows NT")
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.CUSTOM, customUserAgent = "MyUA/1.0"))
        ).isEqualTo("MyUA/1.0")
        assertThat(
            UserAgents.effectiveUserAgent(ProfileSettings(uaMode = UaMode.CUSTOM, customUserAgent = "  "))
        ).isNull()
    }

    @Test
    fun `search url encoding`() {
        val url = SearchEngines.buildSearchUrl("duckduckgo", "a b&c")
        assertThat(url).isEqualTo("https://duckduckgo.com/?q=a+b%26c")
    }

    @Test
    fun `search engine fallback for unknown id`() {
        assertThat(SearchEngines.byId("nope").id).isEqualTo("duckduckgo")
    }
}
