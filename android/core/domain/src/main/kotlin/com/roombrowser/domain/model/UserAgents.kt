package com.roombrowser.domain.model

/**
 * User-Agent presets.
 *
 * Every preset is an Android/mobile identity: the browser runs on a phone, so
 * the curated list offers mobile browsers only, and a preset says which *mobile
 * browser* a profile claims to be. A [Device] is the richer sibling — it says
 * which handset the profile claims to be and also decides the platform
 * version, build id, memory, core count and GPU strings the profile reports —
 * so when both are configured the device wins, and the settings screen clears
 * one when the other is chosen.
 *
 * Desktop identities are deliberately NOT presets. Claiming a desktop browser
 * on a phone is a rendering-mode choice, not an identity: it goes with a wide
 * viewport scaled to fit the phone screen (overview mode on, pinch-zoom kept),
 * and it is per site, not per profile. That is what the per-site "Desktop mode"
 * toggle is for, and the UA it applies lives in [UserAgents.desktopModeUserAgent]
 * rather than in [UserAgents.all].
 *
 * What this changes is the identity a site is told, not the machine. Screen
 * size, viewport size, devicePixelRatio and the layout that follows from them
 * always report the real device, because a page laid out for a viewport the
 * phone does not have renders wrong. See SECURITY.md for the full list of
 * what is and is not presented.
 *
 * VERSIONS. A preset is only worth having if it is a string some real browser
 * actually sends: a malformed UA is *more* fingerprintable than the WebView
 * default, which is the opposite of the point. So every token here is a real
 * one — the presets whose identity is Chrome carry a real 4-part
 * Chrome-for-Android build, and no preset invents a vendor token no browser
 * emits. The Chromium generation those Chrome-identity presets claim is the
 * one the device catalogue ships (see [Devices]), so a profile on a preset and
 * a profile on a device do not tell the same site two different stories about
 * the same app. A vendor preset is the deliberate exception: its own release
 * token is kept real, while the Chromium build underneath it follows the
 * catalogue's current stable - the product requires that no preset claim a
 * Chromium generation below 153, refreshed with each catalogue refresh
 * (this one: Chrome 156.0.8078.48, October 2026).
 *
 * CHROMIUM-BASED ONLY (the user mandate that removed the Firefox rows). Every
 * preset in this catalogue is a Chromium identity: the engine IS Chromium, its
 * TLS fingerprint and its client-hint metadata are a Chromium engine's, and a
 * preset claiming anything else asks the UA string to disagree with the rest
 * of the wire — the class of contradiction Google's sign-in refusal reads.
 * A stored profile that still names a removed preset id resolves to null in
 * [effectiveUserAgent] and lands on the neutralised engine UA, which is a
 * Chrome identity.
 */
data class UserAgentPreset(
    val id: String,
    val label: String,
    /**
     * Always false now that the catalogue is Android-only. Kept so settings
     * UIs that tag desktop presets keep compiling; the only desktop UA left
     * is [UserAgents.desktopModeUserAgent], which is not a preset.
     */
    val isDesktop: Boolean,
    val value: String
)

object UserAgents {

    private val CHROME_ANDROID = UserAgentPreset(
        id = "chrome_android",
        label = "Chrome Android",
        isDesktop = false,
        // A real Chrome-for-Android build, not the `<major>.0.0.0` placeholder:
        // the three-zero form is a reduced-UA artefact, and a preset pinned to
        // it while every device in the catalogue reports a full build is a
        // mismatch between two profiles of the same app. 156.0.8078.48 is the
        // current stable build, the one build the refreshed catalogue ships.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
    )
    private val CHROME_ANDROID_TABLET = UserAgentPreset(
        id = "chrome_android_tablet",
        label = "Chrome Android Tablet",
        isDesktop = false,
        // Chrome puts a real device MODEL CODE in the platform slot — the word
        // "Tablet" there is another browser's convention, never Chrome's.
        // SM-X710 is a Galaxy Tab S9, a model
        // Google Play really ships to. Form factor is expressed the way Chrome
        // expresses it: a tablet UA carries no " Mobile" token, the same rule
        // [Devices] records on every handset it lists.
        value = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36"
    )
    private val CHROME_BETA_ANDROID = UserAgentPreset(
        id = "chrome_beta_android",
        label = "Chrome Beta Android",
        isDesktop = false,
        // Chrome Beta for Android sends the ORDINARY Chrome mobile UA: the
        // channel is a client hint (full version list), never a UA token, so
        // there is nothing extra to append. The only honest way this row
        // differs from CHROME_ANDROID is the version, and it must be a real
        // one - the current Beta-channel Chrome-for-Android build (157.0.8103.14,
        // October 2026), a later major than the stable presets carry, in the
        // same full 4-part form. Devices run stable, so this build is the one
        // preset token that is deliberately not in the catalogue.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.14 Mobile Safari/537.36"
    )
    private val EDGE_ANDROID = UserAgentPreset(
        id = "edge_android",
        label = "Edge Android",
        isDesktop = false,
        // Edge for Android sends a real build of its own (EdgA never uses the
        // `<major>.0.0.0` placeholder) on top of the Chromium build it bundles,
        // so the Chrome token tracks the same Chromium generation as the other
        // presets while EdgA states the Edge release: 156.0.4314.42 is the current
        // Edge 156 branch build, exactly the pair a current Edge for Android
        // really sends (the EdgA token carries Edge's own branch, not Chrome's).
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36 EdgA/156.0.4314.42"
    )
    private val SAMSUNG_ANDROID = UserAgentPreset(
        id = "samsung_android",
        label = "Samsung Internet",
        isDesktop = false,
        // SamsungBrowser/29.0 is the vendor's current release; its Chromium
        // token is the reduced three-zero form Samsung really sends, pinned to
        // the catalogue's current stable so no preset in this file claims a
        // Chromium generation below 153.
        value = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/29.0 Chrome/156.0.0.0 Mobile Safari/537.36"
    )
    private val OPERA_ANDROID = UserAgentPreset(
        id = "opera_android",
        label = "Opera Android",
        isDesktop = false,
        // Opera states its own release on top of the Chromium build it ships;
        // OPR/99.6.2254.2363 is the newest Opera-for-Android build family observed in
        // the wild (October 2026), and its Chromium token follows the
        // catalogue's current stable under the same 153 floor as the rest.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36 OPR/99.6.2254.2363"
    )
    private val BRAVE_ANDROID = UserAgentPreset(
        id = "brave_android",
        label = "Brave Android",
        isDesktop = false,
        // Brave's mobile browser is Chromium-based and intentionally reports
        // the plain Chrome mobile UA — an extra token would let sites
        // fingerprint it, so there is nothing to add.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"
    )
    private val DUCKDUCKGO_ANDROID = UserAgentPreset(
        id = "duckduckgo_android",
        label = "DuckDuckGo Android",
        isDesktop = false,
        // DuckDuckGo splices its application component in immediately after
        // the " Mobile" token and BEFORE Safari/537.36 — that is the order its
        // own UA builder emits (app component, then the browser's Safari
        // component). Appending it after Safari is a string DuckDuckGo has
        // never sent, and a wrong token order is itself a fingerprint.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile DuckDuckGo/5 Safari/537.36"
    )
    private val VIVALDI_ANDROID = UserAgentPreset(
        id = "vivaldi_android",
        label = "Vivaldi Android",
        isDesktop = false,
        // Vivaldi states its own release on top of the Chromium build it ships:
        // Vivaldi/8.2 is the current Android release. Its upstream base
        // tracks Chromium's ESR rather than stable, so the Chromium token
        // follows the catalogue's current stable under the 153 floor instead
        // of the ESR number.
        value = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36 Vivaldi/8.2"
    )
    private val WEBVIEW = UserAgentPreset(
        id = "webview",
        label = "Android WebView (default)",
        isDesktop = false,
        value = ""
    )

    /** The curated preset catalogue — Android Chromium identities only. */
    val all: List<UserAgentPreset> = listOf(
        CHROME_ANDROID, CHROME_ANDROID_TABLET, CHROME_BETA_ANDROID,
        EDGE_ANDROID,
        SAMSUNG_ANDROID, OPERA_ANDROID, BRAVE_ANDROID,
        DUCKDUCKGO_ANDROID, VIVALDI_ANDROID, WEBVIEW
    )

    /**
     * The mobile subset of [all]. Every preset is mobile now, so this is the
     * whole catalogue; the property stays because settings UIs ask for the
     * mobile subset explicitly and should not silently grow desktop entries
     * if presets are ever widened again.
     */
    val androidPresets: List<UserAgentPreset> = all

    /**
     * The UA the per-site "Desktop mode" toggle applies.
     *
     * Not a preset and never part of [all]: desktop mode is a rendering-mode
     * feature (wide viewport, overview zoom fit-to-screen) toggled per site, not a
     * profile identity to choose in settings. Chrome on Windows is the least
     * remarkable desktop UA a site sees, which is the point.
     */
    const val desktopModeUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    fun byId(id: String): UserAgentPreset? = all.firstOrNull { it.id == id }

    /**
     * The device this profile presents itself as, or null when it was never
     * assigned one (a profile created before devices existed, or one whose
     * settings were imported).
     */
    fun device(settings: ProfileSettings): Device? = Devices.find(settings.deviceId)

    /**
     * Resolve the effective UA for a profile. Null means "use WebView default".
     *
     * A device, when one is assigned, is the single control: it supplies the
     * UA, so the preset and custom fields are not consulted. In PRESET mode an
     * unknown preset id — e.g. a stored profile that once picked a preset that
     * has since been removed from the catalogue (the desktop rows, then the
     * Firefox rows under the Chromium-only mandate) — also resolves to null, so
     * the WebView default is used rather than a stale identity.
     */
    fun effectiveUserAgent(settings: ProfileSettings): String? {
        device(settings)?.let { return it.userAgent }
        return when (settings.uaMode) {
            UaMode.DEFAULT -> null
            UaMode.PRESET -> settings.uaPresetId?.let { byId(it)?.value?.ifEmpty { null } }
            UaMode.CUSTOM -> settings.customUserAgent?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The engine's OWN user agent with its WebView markers removed — what the
     * DEFAULT mode actually sends.
     *
     * A WebView announces itself twice in its stock UA: the `wv` token and the
     * `Version/4.0` product. Sites read both, and a growing number of them
     * answer the WebView identity with a code path that is not the one a
     * browser gets — most visibly the video sites, where the substitute player
     * is the one that stalls on "initializing", loads slowly or never starts at
     * all. That is not a bug in the page: it is the page taking the engine at
     * its word.
     *
     * WHY STRIP RATHER THAN SUBSTITUTE A FIXED CHROME UA: the rest of this
     * string is the honest answer — the Chrome version the device's engine
     * actually is, and the platform it actually runs on. Replacing it wholesale
     * with a hard-coded "Chrome 131" would make every profile claim a version
     * that will be years stale, and it would fight the per-profile identity
     * system, which is where a chosen UA belongs (PRESET, CUSTOM, and the
     * device list all supply their own and are not touched by this). Removing
     * two tokens leaves a UA that is true about everything it says and silent
     * about the one thing that changes how a site behaves.
     *
     * A string without those tokens — a desktop profile, an already-clean UA,
     * or anything unrecognisable — is returned unchanged.
     */
    fun webViewNeutralUserAgent(engineUserAgent: String?): String? {
        val raw = engineUserAgent?.takeIf { it.isNotBlank() } ?: return engineUserAgent
        val cleaned = raw
            .replace("; wv)", ")")
            .replace(" wv)", ")")
            .replace("Version/4.0 ", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return cleaned.ifBlank { raw }
    }

    /**
     * The engine's OWN user agent — WebView markers stripped, with only the
     * device MODEL slot swapped for the one the profile chose.
     *
     * WHY THIS REPLACES `device.userAgent`. A device in the catalogue carries
     * its own full UA, and that UA carries its own Chrome version — one that
     * goes stale within months and that the installed WebView does not match.
     * A profile claiming Chrome 156 over a device whose installed engine is
     * 120 sends a UA saying 156 next to client hints and a TLS fingerprint
     * that both say 120, and that contradiction is a far stronger
     * automation/embedded-browser signal than the `wv` token ever was. The
     * sign-in refusal reads it without any JavaScript at all.
     *
     * WHAT THIS KEEPS HONEST. The Chrome version is the engine's REAL one —
     * whatever the installed Android System WebView actually reports — and
     * the Android version and platform come straight off the same stock
     * string. Only the MODEL segment inside `(Linux; Android N; <model>
     * Build/...)` is the profile's choice, so per-profile device privacy lives
     * on the one field that can vary without claiming a version the engine
     * is not.
     *
     * A non-Android stock (a desktop claim, or anything the regex does not
     * recognise) is returned with markers stripped and the model untouched,
     * which is the only honest answer — there is no Android model slot to
     * put a phone code into.
     */
    fun deviceTunedUserAgent(engineUserAgent: String?, device: Device?): String? {
        val neutral = webViewNeutralUserAgent(engineUserAgent) ?: return engineUserAgent
        if (device == null) return neutral
        return MODEL_SEGMENT.replace(neutral) { mr ->
            "${mr.groupValues[1]}${device.code}${mr.groupValues[2]}${mr.groupValues[3]}"
        }
    }

    /**
     * Replaces the `Chrome/<version>` token in [target] with the Chrome token
     * the engine actually reports in [engineUserAgent], so a UA a profile picked
     * (a preset, the desktop-mode UA, the device catalogue's full string) does
     * not claim a Chrome version the installed Android System WebView cannot
     * honour — the contradiction Google's "This browser or app may not be
     * secure" refusal reads without any JavaScript at all.
     *
     * FORM IS PRESERVED. A preset that ships the reduced three-zero form
     * (`Chrome/156.0.0.0`, as Samsung, Opera and Vivaldi actually emit, and as
     * the desktop UA does) is rewritten with the engine's MAJOR followed by
     * `.0.0.0`, not the engine's full version: a site that distinguishes a
     * browser by the form of its Chrome token would otherwise see a reduced
     * Samsung-style UA suddenly carry the full four-part token, and that shape
     * change is itself a fingerprint. A preset that ships the full form
     * (`Chrome/156.0.8078.48`) is rewritten with the engine's full version.
     *
     * NO-OP WHEN THERE IS NOTHING TO TUNE. A target with no Chrome token — a
     * Gecko string, or anything that is not a Chromium claim — has no engine
     * version to install and is returned verbatim; so is any string the engine
     * itself reports without a Chrome token (it would be invented, not tuned).
     * The vendor tokens (EdgA, SamsungBrowser, OPR, Vivaldi,
     * DuckDuckGo) are deliberately left alone: they describe a vendor the
     * engine is not, and the user picked that identity knowing it.
     *
     * This is the network-side half of the contract
     * [WebViewClientHints.forUserAgent] already keeps in client hints: both
     * read the Chrome version from the same resolved UA, so the UA string and
     * the `Sec-CH-UA*` headers cannot disagree once the engine's real version
     * is installed.
     */
    fun engineTunedUserAgent(target: String, engineUserAgent: String?): String {
        val engineMatch = CHROME_TOKEN.find(engineUserAgent ?: return target) ?: return target
        val engineVersion = engineMatch.groupValues[1]
        val engineMajor = engineVersion.substringBefore('.')
        return CHROME_TOKEN.replace(target) { mr ->
            val targetVersion = mr.groupValues[1]
            val targetMajor = targetVersion.substringBefore('.')
            // The reduced form (e.g. `120.0.0.0`) is what Samsung, Opera and
            // Vivaldi ship on Android and what the desktop UA ships; rewriting
            // it with the engine's full version would change the token's SHAPE
            // and the form is itself a fingerprint.
            if (targetVersion == "$targetMajor.0.0.0") {
                "Chrome/$engineMajor.0.0.0"
            } else {
                "Chrome/$engineVersion"
            }
        }
    }

    /**
     * A `Chrome/<four-part-version>` token, with the version captured so the
     * tuner can read both the full form and the reduced three-zero form the
     * same way. Anchored on `Chrome/` (not the whole product) so a vendor
     * token such as `EdgA/156.0.4314.42` is not touched -- it is a vendor
     * claim the engine is not making.
     */
    private val CHROME_TOKEN = Regex("""Chrome/(\d+\.\d+\.\d+\.\d+)""")

    /**
     * The `(Linux; Android N; <model> Build/...)` parenthetical — the slot a
     * phone model lives in. Captures the `Android N; ` prefix, the model, an
     * optional ` Build/...`, and the closing `)` so the whole parenthetical
     * can be rebuilt with the model replaced.
     */
    private val MODEL_SEGMENT =
        Regex("""(Android \d+(?:\.\d+)?; )[^);]+?( Build/[^)]*)?(\))""")
}
