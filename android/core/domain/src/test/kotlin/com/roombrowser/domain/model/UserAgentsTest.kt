package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the Android-only shape of the preset catalogue after the desktop
 * presets were removed (desktop identity now lives solely in
 * [UserAgents.desktopModeUserAgent] for the per-site Desktop-mode toggle),
 * and pins the other half of the contract: every preset is a string a REAL
 * Android browser sends. A malformed UA is more fingerprintable than the
 * WebView default, so a typo here is a privacy bug, not a cosmetic one.
 *
 * The assertions below are deliberately written against the strings the
 * browsers actually emit, so a "clean-up" that invents a token, doubles one,
 * or moves one fails here rather than shipping.
 */
class UserAgentsTest {

    private val removedPresetIds = listOf(
        "chrome_windows", "chrome_macos", "chrome_linux",
        "firefox_windows", "firefox_macos", "firefox_linux",
        "edge_windows", "safari_macos",
        // The Android Firefox rows, removed under the Chromium-only mandate:
        // the engine is Chromium, and a preset claiming Gecko asks the UA
        // string to disagree with the TLS fingerprint and client hints.
        "firefox_android", "firefox_android_tablet"
    )

    private val chromeToken = Regex("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)")

    /** Presets whose identity IS Chrome, so their Chrome token is the identity. */
    private val chromeIdentityIds = listOf(
        "chrome_android", "chrome_android_tablet", "chrome_beta_android",
        "brave_android", "duckduckgo_android", "edge_android"
    )

    private val tabletIds = listOf("chrome_android_tablet")

    private val phoneIds = listOf(
        "chrome_android", "chrome_beta_android", "edge_android", "samsung_android",
        "opera_android", "brave_android", "duckduckgo_android", "vivaldi_android"
    )

    private fun ua(id: String): String = UserAgents.byId(id)!!.value

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
        // Chrome's tablet form factor is a real device model code, never the
        // word "Tablet" — that token is other browsers' convention.
        assertThat(ua("chrome_android_tablet")).contains("Android 14; SM-X710)")
        assertThat(ua("chrome_android_tablet")).doesNotContain("Tablet")
        assertThat(ua("opera_android")).contains("OPR/99")
        // Brave's mobile browser is Chromium-based and ships the plain Chrome
        // mobile UA on purpose, so the two values are identical.
        assertThat(ua("brave_android")).isEqualTo(ua("chrome_android"))
        assertThat(ua("duckduckgo_android")).contains("DuckDuckGo/5")
        assertThat(ua("vivaldi_android")).contains("Vivaldi/8.2")
        // No preset invents a vendor token: "ChromeBetA" does not exist in any
        // Chrome channel's UA, and neither does anything else spelled like it.
        for (p in UserAgents.all) {
            assertThat(p.value).doesNotContain("ChromeBetA")
        }
    }

    @Test
    fun `the tablet presets drop Mobile and the phone presets keep it`() {
        // Chrome only announces " Mobile" on handsets; a tablet UA claiming it
        // contradicts the form factor the shim reports. Same rule [Devices]
        // records for every handset it lists.
        for (id in tabletIds) {
            assertThat(ua(id)).doesNotContain("Mobile")
        }
        for (id in phoneIds) {
            assertThat(ua(id)).contains("Mobile")
        }
    }

    @Test
    fun `chrome presets speak the Chrome build the device catalogue ships`() {
        // The point of the version: a profile on a preset and a profile on a
        // device must not tell the same site two different things about the
        // same app. Every stable Chrome-identity preset therefore carries the
        // real build the catalogue's handsets report, in the same full 4-part
        // form - never the `<major>.0.0.0` placeholder, whose zero build and
        // patch are what made presets and devices disagree. The Beta row is
        // the deliberate exception: devices run stable, so its newer real
        // Beta-channel build cannot also be in the catalogue; the beta test
        // below pins it.
        val shipped = Devices.all.map { it.chromeVersion }.toSet()
        for (id in chromeIdentityIds) {
            val match = chromeToken.find(ua(id))
            assertThat(match).isNotNull()
            val (major, minor, build, patch) = match!!.groupValues.drop(1)
            assertThat(minor).isEqualTo("0") // Chrome's own Android version scheme
            assertThat(build).isNotEqualTo("0")
            assertThat(patch).isNotEqualTo("0")
            if (id != "chrome_beta_android") {
                assertThat(shipped).contains("$major.$minor.$build.$patch")
            }
        }
    }

    @Test
    fun `every chromium token claims a current generation and never a stale one`() {
        // The product floor: no User-Agent this app sends may claim a Chromium
        // generation below 153. A preset that goes stale is caught here, at
        // unit time, instead of shipping an identity years behind the web.
        for (p in UserAgents.all) {
            val major = chromeToken.find(p.value)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue // the webview preset carries no Chromium token
            assertThat(major).isAtLeast(153)
        }
    }

    @Test
    fun `chrome beta is the plain chrome UA at a different version`() {
        // No Chrome channel puts a token in the UA — the channel travels in
        // the client hints' full version list — so the Beta row can only earn
        // its place by the version, and it must be a newer one.
        val beta = ua("chrome_beta_android")
        val stable = ua("chrome_android")
        assertThat(beta).doesNotContain("Beta")
        assertThat(beta).isNotEqualTo(stable)
        val betaParts = chromeToken.find(beta)!!.groupValues.drop(1)
        val stableParts = chromeToken.find(stable)!!.groupValues.drop(1)
        assertThat(betaParts[0].toInt()).isGreaterThan(stableParts[0].toInt())
        // Everything except the version is the ordinary Chrome mobile UA.
        assertThat(beta).isEqualTo(stable.replace(stableParts.joinToString("."), betaParts.joinToString(".")))
    }

    @Test
    fun `duckduckgo splices its token before Safari`() {
        // DuckDuckGo's UA builder emits its application component right after
        // " Mobile", before the Safari component. A token appended after
        // Safari is a string DuckDuckGo has never sent.
        val value = ua("duckduckgo_android")
        assertThat(value.indexOf("DuckDuckGo/5")).isLessThan(value.indexOf("Safari/537.36"))
        assertThat(value.indexOf("DuckDuckGo/5")).isGreaterThan(value.indexOf("Mobile"))
    }

    @Test
    fun `removed preset ids resolve to null`() {
        for (id in removedPresetIds) {
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
        assertThat(UserAgents.desktopModeUserAgent).contains("Chrome/156")
        // Not offered as a preset by id...
        assertThat(UserAgents.all.map { it.id }).doesNotContain("chrome_windows")
        // ...and its value is not any preset's value.
        assertThat(UserAgents.all.map { it.value }).doesNotContain(UserAgents.desktopModeUserAgent)
        // The mobile subset is the whole catalogue while every preset is Android.
        assertThat(UserAgents.androidPresets).isEqualTo(UserAgents.all)
    }

    // ----------------------------------------------------- neutralising the engine

    /** A stock WebView UA, in the shape a real device reports it. */
    private val engineUa =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.48 " +
            "Mobile Safari/537.36"

    @Test
    fun `the neutralised engine UA stops announcing itself as a WebView`() {
        val cleaned = UserAgents.webViewNeutralUserAgent(engineUa)!!

        // The two tokens sites branch on: `wv` in the platform comment and the
        // `Version/4.0` product. Either one alone gets a page the substitute
        // player, which is what stalls video sites.
        assertThat(cleaned).doesNotContain("wv")
        assertThat(cleaned).doesNotContain("Version/4.0")
        // No stray double space where the product was removed.
        assertThat(cleaned).doesNotContain("  ")
        assertThat(cleaned).isEqualTo(
            "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 " +
                "Mobile Safari/537.36"
        )
    }

    @Test
    fun `neutralising keeps the engine's real Chrome version`() {
        val cleaned = UserAgents.webViewNeutralUserAgent(engineUa)!!

        // Substituting a fixed "Chrome 156" would go stale within months and
        // would fight the per-profile identity system. Only the two WebView
        // markers go.
        assertThat(cleaned).contains("Chrome/156.0.8078.48")
        assertThat(cleaned).contains("Android 14")
        assertThat(cleaned).contains("SM-S918B")
    }

    @Test
    fun `a UA without WebView markers is returned unchanged`() {
        val alreadyClean = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

        assertThat(UserAgents.webViewNeutralUserAgent(alreadyClean)).isEqualTo(alreadyClean)
        assertThat(UserAgents.webViewNeutralUserAgent(UserAgents.desktopModeUserAgent))
            .isEqualTo(UserAgents.desktopModeUserAgent)
    }

    @Test
    fun `a missing or blank engine UA passes through rather than inventing one`() {
        assertThat(UserAgents.webViewNeutralUserAgent(null)).isNull()
        assertThat(UserAgents.webViewNeutralUserAgent("")).isEmpty()
        assertThat(UserAgents.webViewNeutralUserAgent("   ")).isEqualTo("   ")
    }

    @Test
    fun `a UA that is nothing but WebView markers is left as the engine sent it`() {
        // Degenerate, but the alternative is handing the engine an empty
        // userAgentString, which WebView treats as "send nothing".
        val weird = "wv"
        assertThat(UserAgents.webViewNeutralUserAgent(weird)).isEqualTo(weird)
    }

    @Test
    fun `DEFAULT mode resolves to the neutralised engine string, not the stock one`() {
        val settings = ProfileSettings(uaMode = UaMode.DEFAULT)

        // effectiveUserAgent returning null is the signal "the engine decides";
        // it is the caller (ProfileEngine) that neutralises. This test pins the
        // contract the caller depends on.
        assertThat(UserAgents.effectiveUserAgent(settings)).isNull()
        assertThat(UserAgents.webViewNeutralUserAgent(engineUa)).isNotNull()
    }

    // ----------------------------------------------------- tuning to the engine

    /**
     * A stock WebView UA from a phone whose Android System WebView reports a
     * DIFFERENT Chrome version than the catalogue's pinned 156.0.8078.48 -- the
     * exact case the engine-tuner exists for. 131 is the CI emulator's real
     * engine generation and the most likely mismatch a field device hits.
     */
    private val engineStockLegacy =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 " +
            "Mobile Safari/537.36"

    @Test
    fun `a full-form Chrome preset is tuned to the engine's full Chrome version`() {
        val preset = UserAgents.byId("chrome_android")!!.value

        val tuned = UserAgents.engineTunedUserAgent(preset, engineStockLegacy)

        // The catalogue's pinned 156.0.8078.48 is replaced by the engine's
        // 131.0.6778.135 -- the four-part form is preserved, only the version
        // moves, because the engine's TLS fingerprint is 131 and the UA's claim
        // must agree with it or Google's sign-in refusal reads the mismatch.
        assertThat(tuned).contains("Chrome/131.0.6778.135")
        assertThat(tuned).doesNotContain("Chrome/156")
        // The non-version parts of the preset are the user's identity pick and
        // stay as written: Chrome on Android, Mobile token present, no model
        // slot (the preset never carried one).
        assertThat(tuned).startsWith(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
        )
        assertThat(tuned).contains(" Mobile Safari/537.36")
    }

    @Test
    fun `a reduced-form Chrome preset is tuned to the engine's major in the same reduced form`() {
        // Samsung ships `Chrome/156.0.0.0` on Android, never the full four-part
        // token. Tuning it with the engine's full version (131.0.6778.135) would
        // change the token's SHAPE, and a site that distinguishes browsers by
        // the form of their Chrome token would see a Samsung-style UA suddenly
        // carry the full token -- a fingerprint in itself.
        val samsung = UserAgents.byId("samsung_android")!!.value

        val tuned = UserAgents.engineTunedUserAgent(samsung, engineStockLegacy)

        assertThat(tuned).contains("Chrome/131.0.0.0")
        assertThat(tuned).doesNotContain("Chrome/156")
        // The vendor's own token (SamsungBrowser/29.0) is a vendor claim, not
        // an engine claim, and is left alone.
        assertThat(tuned).contains("SamsungBrowser/29.0")
    }

    @Test
    fun `the desktop mode UA is tuned to the engine's major in reduced form`() {
        // desktopModeUserAgent ships `Chrome/156.0.0.0` (desktop Chrome's own
        // reduced three-zero convention), so the tuner uses the engine's MAJOR
        // followed by .0.0.0 -- not the full version.
        val tuned = UserAgents.engineTunedUserAgent(
            UserAgents.desktopModeUserAgent,
            engineStockLegacy
        )

        assertThat(tuned).contains("Chrome/131.0.0.0")
        assertThat(tuned).contains("Windows NT 10.0")
        assertThat(tuned).doesNotContain("Chrome/156")
    }

    @Test
    fun `an Edge preset tunes its Chrome token and leaves the EdgA token alone`() {
        val edge = UserAgents.byId("edge_android")!!.value

        val tuned = UserAgents.engineTunedUserAgent(edge, engineStockLegacy)

        // The Chrome token is the engine's. The EdgA token is Edge's own
        // release (the engine is not Edge), and is a vendor identity claim the
        // user picked knowing the engine cannot honour it -- untouched.
        assertThat(tuned).contains("Chrome/131.0.6778.135")
        assertThat(tuned).contains("EdgA/156.0.4314.42")
    }

    @Test
    fun `a target with no Chrome token is returned unchanged`() {
        val target = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36"

        assertThat(UserAgents.engineTunedUserAgent(target, engineStockLegacy)).isEqualTo(target)
    }

    @Test
    fun `a null or Chrome-less engine UA leaves the target untouched rather than inventing a version`() {
        val chromePreset = UserAgents.byId("chrome_android")!!.value

        // No engine UA to read from: nothing to tune, the catalogue's pinned
        // 156 is left in place rather than silently replaced with a guess.
        assertThat(UserAgents.engineTunedUserAgent(chromePreset, null))
            .isEqualTo(chromePreset)
        assertThat(UserAgents.engineTunedUserAgent(chromePreset, ""))
            .isEqualTo(chromePreset)
        // An engine UA without a Chrome token (a Firefox-on-desktop engine, or
        // anything unrecognisable) is the same case: no honest replacement.
        val noChrome = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36"
        assertThat(UserAgents.engineTunedUserAgent(chromePreset, noChrome))
            .isEqualTo(chromePreset)
    }

    @Test
    fun `tuning is idempotent -- the engine's own UA is left as the engine reported it`() {
        // The DEFAULT path's neutralised string already carries the engine's
        // real Chrome token; tuning it again is a no-op, which is what the
        // engine layer needs to call site uniformly without a special case.
        val neutral = UserAgents.webViewNeutralUserAgent(engineStockLegacy)!!
        val tuned = UserAgents.engineTunedUserAgent(neutral, engineStockLegacy)

        assertThat(tuned).isEqualTo(neutral)
        assertThat(tuned).contains("Chrome/131.0.6778.135")
    }
}
