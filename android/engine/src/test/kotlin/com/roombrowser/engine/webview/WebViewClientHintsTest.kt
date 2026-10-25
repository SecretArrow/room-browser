package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import org.junit.Test

/**
 * The client-hint identity must agree with the UA string it was derived from.
 *
 * This is the network-side half of the web-compatibility contract the device
 * shim already keeps in JavaScript: `navigator.userAgentData` reports
 * Chromium/Google Chrome brands, and the `Sec-CH-UA` header must say the same
 * thing, or a site sees one browser described two ways. Google's "This browser
 * or app may not be secure" refusal is the visible cost of that disagreement.
 *
 * A JVM test can hold this because `UserAgentMetadata` is a plain value class
 * — the provider is what serializes it, not this code. What is asserted here
 * is the mapping from a resolved UA to the hints, which is the part that can
 * silently drift.
 */
class WebViewClientHintsTest {

    private val deviceId = "samsung-sm-s918b"

    private fun deviceSettings() = ProfileSettings(deviceId = deviceId)

    @Test
    fun a_device_profile_reports_that_device_and_its_chrome_build() {
        val device = UserAgents.device(deviceSettings())!!
        val ua = UserAgents.effectiveUserAgent(deviceSettings())!!

        val hints = WebViewClientHints.forUserAgent(deviceSettings(), ua)!!

        assertThat(hints.fullVersion).isEqualTo(device.chromeVersion)
        assertThat(hints.platform).isEqualTo("Android")
        assertThat(hints.platformVersion).isEqualTo("${device.androidVersion}.0.0")
        assertThat(hints.model).isEqualTo(device.code)
        assertThat(hints.isMobile).isEqualTo(device.formFactor != "tablet")
    }

    @Test
    fun the_brand_list_names_chromium_and_google_chrome_never_android_webview() {
        val ua = UserAgents.effectiveUserAgent(deviceSettings())!!

        val hints = WebViewClientHints.forUserAgent(deviceSettings(), ua)!!
        val brands = hints.brandVersionList.map { it.brand }
        val major = ua.substringAfter("Chrome/").substringBefore('.')

        assertThat(brands).contains("Chromium")
        assertThat(brands).contains("Google Chrome")
        // The embedded-browser tell this whole change exists to remove.
        assertThat(brands).doesNotContain("Android WebView")
        assertThat(hints.brandVersionList.first { it.brand == "Chromium" }.majorVersion)
            .isEqualTo(major)
    }

    @Test
    fun a_tablet_device_reports_not_mobile() {
        val tablet = Devices.all.first { it.formFactor == "tablet" }
        val settings = ProfileSettings(deviceId = tablet.id)
        val ua = UserAgents.effectiveUserAgent(settings)!!

        val hints = WebViewClientHints.forUserAgent(settings, ua)!!

        assertThat(hints.isMobile).isFalse()
        assertThat(ua).doesNotContain(" Mobile")
    }

    @Test
    fun a_preset_without_a_device_still_names_android_and_never_invents_a_model() {
        val settings = ProfileSettings(
            uaMode = UaMode.PRESET,
            uaPresetId = "chrome_android"
        )
        val ua = UserAgents.effectiveUserAgent(settings)!!

        val hints = WebViewClientHints.forUserAgent(settings, ua)!!

        assertThat(hints.platform).isEqualTo("Android")
        // `(Linux; Android 14)` carries no model slot, so the honest answer is
        // empty rather than the real phone's code.
        assertThat(hints.model).isEmpty()
        assertThat(hints.platformVersion).isEqualTo("14.0.0")
    }

    @Test
    fun desktop_mode_reports_windows_and_no_handset_model() {
        val ua = UserAgents.desktopModeUserAgent

        val hints = WebViewClientHints.forUserAgent(deviceSettings(), ua)!!

        assertThat(hints.platform).isEqualTo("Windows")
        assertThat(hints.isMobile).isFalse()
        assertThat(hints.model).isEmpty()
        assertThat(hints.platformVersion).isEqualTo("10.0.0")
    }

    @Test
    fun a_firefox_identity_sends_no_chromium_client_hints() {
        val settings = ProfileSettings(
            uaMode = UaMode.PRESET,
            uaPresetId = "firefox_android"
        )
        val ua = UserAgents.effectiveUserAgent(settings)!!

        // Firefox sends no Sec-CH-UA; answering with Chromium brands would be
        // the same contradiction in the other direction.
        assertThat(WebViewClientHints.forUserAgent(settings, ua)).isNull()
    }

    @Test
    fun a_string_with_no_chrome_token_yields_nothing_rather_than_a_guess() {
        assertThat(
            WebViewClientHints.forUserAgent(deviceSettings(), "Mozilla/5.0 (Linux; Android 14)")
        ).isNull()
    }

    @Test
    fun the_engine_default_string_after_neutralisation_still_gets_hints() {
        // The stock WebView UA, as `configure` sees it in DEFAULT mode.
        val stock = "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 " +
            "Mobile Safari/537.36"
        val neutral = UserAgents.webViewNeutralUserAgent(stock)!!

        val hints = WebViewClientHints.forUserAgent(ProfileSettings(), neutral)!!

        // The engine's REAL version is the one it reports — the point is that
        // the brand stops saying "Android WebView", not that the version is
        // replaced with a claim the engine cannot honour.
        assertThat(hints.fullVersion).isEqualTo("131.0.6778.135")
        assertThat(hints.brandVersionList.map { it.brand }).doesNotContain("Android WebView")
    }

    /**
     * EVERY SHIPPED CHROME IDENTITY, not just the one a test happened to name.
     *
     * A single coherent preset proves the mapping works; this proves the
     * catalogue cannot drift out from under it. A preset added later with a
     * hand-typed version, a stale major, or a `wv` token would fail here
     * rather than on a phone at the Google sign-in screen.
     */
    @Test
    fun every_chrome_preset_describes_one_identity_with_no_embedded_browser_tell() {
        val chromePresets = UserAgents.all.filter { it.value.contains("Chrome/") }
        assertThat(chromePresets).isNotEmpty()

        for (preset in chromePresets) {
            val settings = ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = preset.id)
            val ua = UserAgents.effectiveUserAgent(settings)
                ?: throw AssertionError("preset ${preset.id} resolved to no UA")

            // The two tokens Google reads to decide a browser is an embedded
            // WebView rather than a browser.
            assertThat(ua).doesNotContain("; wv")
            assertThat(ua).doesNotContain("Version/4.0")

            val hints = WebViewClientHints.forUserAgent(settings, ua)
                ?: throw AssertionError("preset ${preset.id} produced no client hints")

            val major = ua.substringAfter("Chrome/").substringBefore('.')
            assertThat(hints.fullVersion).isEqualTo(ua.substringAfter("Chrome/").substringBefore(' '))
            assertThat(hints.brandVersionList.map { it.brand }).contains("Chromium")
            assertThat(hints.brandVersionList.map { it.brand }).doesNotContain("Android WebView")
            // One major version across the string and every REAL brand: this is
            // the exact equality a sign-in check compares. The grease brand is
            // excluded because its whole job is to carry a version nothing else
            // has — Chrome emits it that way on purpose.
            val realBrands = hints.brandVersionList.filterNot { it.brand.startsWith("Not") }
            assertThat(realBrands).hasSize(2)
            assertThat(realBrands.map { it.majorVersion }.toSet()).containsExactly(major)
        }
    }
}
