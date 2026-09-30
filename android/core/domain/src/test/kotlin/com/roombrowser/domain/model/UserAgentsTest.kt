package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the Android-only shape of the preset catalogue after the desktop
 * presets were removed (desktop identity now lives solely in
 * [UserAgents.desktopModeUserAgent] for the per-site Desktop-mode toggle).
 */
class UserAgentsTest {

    private val removedDesktopIds = listOf(
        "chrome_windows", "chrome_macos", "chrome_linux",
        "firefox_windows", "firefox_macos", "firefox_linux",
        "edge_windows", "safari_macos"
    )

    @Test
    fun `every preset is an Android or mobile identity`() {
        for (p in UserAgents.all) {
            // The webview preset is the empty "let WebView decide" default;
            // every other preset claims an Android platform.
            if (p.id == "webview") {
                assertThat(p.value).isEmpty()
            } else {
                assertThat(p.value).startsWith("Mozilla/5.0 (")
                assertThat(p.value).contains("Android")
            }
            // No desktop/other-platform token may appear in any preset.
            for (token in listOf("Windows NT", "Macintosh", "X11;", "iPhone", "iPad")) {
                assertThat(p.value).doesNotContain(token)
            }
            // No preset is a desktop preset anymore, by construction.
            assertThat(p.isDesktop).isFalse()
        }
    }

    @Test
    fun `preset ids are unique and the catalogue is the shipped one`() {
        val ids = UserAgents.all.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(ids).containsExactly(
            "chrome_android",
            "chrome_android_tablet",
            "chrome_beta_android",
            "firefox_android",
            "firefox_android_tablet",
            "edge_android",
            "samsung_android",
            "opera_android",
            "brave_android",
            "duckduckgo_android",
            "vivaldi_android",
            "webview"
        )
    }

    @Test
    fun `vendor tokens identify the new presets`() {
        assertThat(UserAgents.byId("chrome_android_tablet")!!.value).contains("Tablet")
        assertThat(UserAgents.byId("chrome_beta_android")!!.value).contains("ChromeBetA/124")
        assertThat(UserAgents.byId("firefox_android_tablet")!!.value).contains("Tablet")
        assertThat(UserAgents.byId("opera_android")!!.value).contains("OPR/79")
        // Brave's mobile browser is Chromium-based and ships the plain Chrome
        // mobile UA on purpose, so the two values are identical.
        assertThat(UserAgents.byId("brave_android")!!.value)
            .isEqualTo(UserAgents.byId("chrome_android")!!.value)
        assertThat(UserAgents.byId("duckduckgo_android")!!.value).contains("DuckDuckGo/5")
        assertThat(UserAgents.byId("vivaldi_android")!!.value).contains("Vivaldi/6.8")
    }

    @Test
    fun `removed desktop preset ids resolve to null`() {
        for (id in removedDesktopIds) {
            assertThat(UserAgents.byId(id)).isNull()
        }
        assertThat(UserAgents.byId("no-such-preset")).isNull()
        // And an unknown id in PRESET mode falls back to the WebView default.
        assertThat(
            UserAgents.effectiveUserAgent(
                ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "chrome_windows")
            )
        ).isNull()
    }

    @Test
    fun `the desktop mode UA is not a preset`() {
        assertThat(UserAgents.desktopModeUserAgent).contains("Windows NT")
        assertThat(UserAgents.desktopModeUserAgent).contains("Chrome/124")
        // Not offered as a preset by id...
        assertThat(UserAgents.all.map { it.id }).doesNotContain("chrome_windows")
        // ...and its value is not any preset's value.
        assertThat(UserAgents.all.map { it.value }).doesNotContain(UserAgents.desktopModeUserAgent)
        // The mobile subset is the whole catalogue while every preset is Android.
        assertThat(UserAgents.androidPresets).isEqualTo(UserAgents.all)
    }
}
