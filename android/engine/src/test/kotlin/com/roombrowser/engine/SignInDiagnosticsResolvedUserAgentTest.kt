package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import org.junit.Test

/**
 * The resolution the sign-in probe and the check screen use to reproduce the
 * engine's identity — pinned against the session's own `configure` order so
 * the probe can never grade a request the engine would not make.
 *
 * This is the contract the probe fix stands on: until it existed the probe set
 * no user agent at all and asked Google with the STOCK `; wv` string, so its
 * BLOCKED verdicts said nothing about the identity this app actually sends
 * and its notes pointed at the wrong cause.
 */
class SignInDiagnosticsResolvedUserAgentTest {

    /** A stock WebView UA as a real Android 15 device reports it. */
    private val engineStock =
        "Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP3A.241105.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.48 " +
            "Mobile Safari/537.36"

    private val device = Devices.all.first { it.id == "samsung-sm-s918b" }

    @Test
    fun a_device_profile_is_the_engines_stock_with_the_model_slot_swapped() {
        val resolved = SignInDiagnostics.resolvedEngineUserAgent(
            ProfileSettings(deviceId = device.id), engineStock
        )

        // The engine's own platform and Chrome version, the claimed handset's
        // model code, and no WebView marker anywhere.
        assertThat(resolved).contains("Android 15")
        assertThat(resolved).contains(device.code)
        assertThat(resolved).contains("Chrome/156.0.8078.48")
        assertThat(resolved).doesNotContain("wv")
        assertThat(resolved).doesNotContain("Version/4.0")
        assertThat(resolved).doesNotContain("Pixel 8")
    }

    @Test
    fun default_mode_is_the_neutralised_stock_with_the_engines_version_kept() {
        val resolved = SignInDiagnostics.resolvedEngineUserAgent(
            ProfileSettings(), engineStock
        )

        assertThat(resolved).contains("Android 15")
        assertThat(resolved).contains("Chrome/156.0.8078.48")
        assertThat(resolved).doesNotContain("wv")
        assertThat(resolved).doesNotContain("Version/4.0")
        // The engine's real model stays: no device is claimed, so the honest
        // string is the phone the engine actually runs on.
        assertThat(resolved).contains("Pixel 8")
    }

    @Test
    fun a_presets_chrome_token_is_tuned_to_the_engines_real_version() {
        // A preset claims a specific build; the engine cannot honour a version
        // it is not, so the token is rewritten to the engine's real one —
        // the same tuning configure() applies.
        val resolved = SignInDiagnostics.resolvedEngineUserAgent(
            ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "chrome_android"),
            engineStock
        )

        assertThat(resolved).contains("Chrome/156.0.8078.48")
        assertThat(resolved).doesNotContain("Chrome/157")
        assertThat(resolved).doesNotContain("wv")
    }

    @Test
    fun a_desktop_mode_profile_gets_the_desktop_identity() {
        val resolved = SignInDiagnostics.resolvedEngineUserAgent(
            ProfileSettings(desktopModeDefault = true), engineStock
        )

        assertThat(resolved).contains("Windows NT 10.0")
        assertThat(resolved).doesNotContain("Linux; Android")
    }

    @Test
    fun a_missing_stock_still_answers_rather_than_throwing() {
        // getDefaultUserAgent can refuse on odd providers; the resolution must
        // degrade to whatever the stock offers, not crash the probe before it
        // can report anything.
        val resolved = SignInDiagnostics.resolvedEngineUserAgent(
            ProfileSettings(deviceId = device.id), null
        )
        assertThat(resolved).isNotNull()
    }
}
