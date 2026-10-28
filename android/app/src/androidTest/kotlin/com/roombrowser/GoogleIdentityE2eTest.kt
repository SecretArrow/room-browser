package com.roombrowser

import android.content.Context
import android.content.Intent
import android.webkit.WebSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/**
 * THE GOOGLE-SIGN-IN CONTRACT, observed from both sides of one page load.
 *
 * Google answers an embedded WebView with "Couldn't sign you in — This browser
 * or app may not be secure", and it decides that from three tells:
 *
 *   1. `; wv` or `Version/4.0` in the User-Agent string,
 *   2. an `X-Requested-With` header naming the app (an XMLHttpRequest from an
 *      app, not a navigation from a browser),
 *   3. a `navigator.userAgentData` / `Sec-CH-UA` that contradicts the UA string
 *      it arrived with — one browser described two ways.
 *
 * The engine answers all three, and the app's part is deliberately ALL on the
 * engine side. The first and third are the app's own:
 * `WebViewEngineSession.configure` neutralises the stock string and
 * `WebViewClientHints` derives the hints from the same resolved string, so the
 * string and the hints cannot disagree — and because the metadata is what the
 * ENGINE serves to `navigator.userAgentData`, the page half agrees with the
 * header half by construction. The app installs NO identity JavaScript: the
 * old device shim presented the identity with `Object.defineProperty`, and a
 * page can catch that from inside (a spoofed accessor has a `.get` function
 * where the platform ships a native one), which is itself an automation tell.
 * Nothing in the page world is overridden any more, so nothing in the page
 * world can contradict the wire.
 *
 * TWO PHASES ON ONE PROFILE, because the app ships two shapes of identity and
 * both must survive a Google round trip:
 *
 *   A. the profile pointed at a handset from the catalogue — the shape the app
 *      gives every profile it creates. The catalogue contributes the MODEL
 *      claim; the Chromium build on the wire is the ENGINE's real one, because
 *      configure() tunes every UA it installs through
 *      UserAgents.engineTunedUserAgent — a claimed 156 over an 83 engine is
 *      the split identity Google's refusal reads, and the client
 *      hints and the request must all carry the engine's build instead.
 *   B. the same profile with its device taken away, where the honest identity
 *      is the engine's real build with the embedded-browser tokens removed and
 *      no model claimed at all — a string the WebView serves to
 *      navigator.userAgent itself, with no identity override at all.
 *
 * The page reports what it sees back as a GET to the mock server rather than
 * through the UI: a modal sheet, a toast or an accessibility hiccup cannot
 * hide an HTTP request the server recorded, and the assertion then reads the
 * exact values the page read instead of a rendering of them.
 */
@RunWith(AndroidJUnit4::class)
class GoogleIdentityE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val appContext: RoomBrowserApp =
        instrumentation.targetContext.applicationContext as RoomBrowserApp

    private lateinit var server: MockWebServer

    /**
     * Every request the engine actually put on the wire, by path. The page's
     * self-report lands in here too — that is the whole point of the probe.
     */
    private val requests = ConcurrentHashMap<String, RecordedRequest>()

    private val tag = (System.currentTimeMillis() % 100_000).toString()

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                requests[path] = request
                return when {
                    path == "/phase-a" -> page("/probe-a")
                    path == "/phase-b" -> page("/probe-b")
                    path.startsWith("/probe-") -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /**
     * A page that reports its own identity back to the server.
     *
     * `navigator.userAgentData` is read here rather than inferred from the
     * headers: Google's sign-in page runs the same reads in JavaScript, so a
     * fix that only satisfied the network half would still be refused on a
     * page that compares the two.
     */
    private fun page(probePath: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head><body style="font-size:24px; margin:24px;">
            <h1>ROOM-GOOGLE-IDENTITY-E2E</h1>
            <script>
            (function () {
              var q = new URLSearchParams();
              q.set('ua', navigator.userAgent || '');
              var d = navigator.userAgentData;
              q.set('hasUaData', d ? '1' : '0');
              if (d) {
                q.set('brands', (d.brands || []).map(function (b) {
                  return b.brand + '=' + b.version;
                }).join(','));
                q.set('mobile', String(d.mobile));
                q.set('platform', String(d.platform));
              }
              fetch('$probePath?' + q.toString()).catch(function () {});
            })();
            </script>
            </body></html>
            """.trimIndent()
        )

    private fun launchEngineAt(url: String, profileId: ProfileId) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.PROFILE_ID", profileId.value)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
            }
        }
        return condition()
    }

    private fun await(path: String, what: String): RecordedRequest {
        assertTrue(
            "$what — the engine never requested $path (saw ${requests.keys})",
            waitUntil(90_000) { requests.containsKey(path) }
        )
        val hit = requests[path]
        assertNotNull("$what — no recorded request for $path", hit)
        return hit!!
    }

    /**
     * The three string tells Google reads, asserted on the exact bytes that
     * went out. Split out because every phase of this test needs it and a
     * future preset must not be able to skip it.
     */
    private fun assertNoEmbeddedBrowserTell(where: String, ua: String) {
        assertTrue(
            "$where: the User-Agent must not carry the WebView token — Google reads " +
                "it as an embedded browser (saw '$ua')",
            !ua.contains("; wv") && !ua.contains(" wv)")
        )
        assertTrue(
            "$where: the User-Agent must not carry Chromium's 'Version/4.0' tell " +
                "(saw '$ua')",
            !ua.contains("Version/4.0")
        )
        assertTrue(
            "$where: the User-Agent must not name Android WebView (saw '$ua')",
            !ua.contains("Android WebView")
        )
    }

    /**
     * The hint half, asserted only for what was actually sent. A WebView old
     * enough to have no User-Agent-Client-Hints support sends none, and
     * asserting their presence would fail the runner's image rather than the
     * app — but when they ARE sent they must agree with the string, because
     * disagreement is the third tell.
     */
    private fun assertHintsAgreeWithUserAgent(hit: RecordedRequest, ua: String) {
        val secChUa = hit.getHeader("Sec-CH-UA")
        if (secChUa != null) {
            assertTrue(
                "Sec-CH-UA must name Chromium (saw '$secChUa')",
                secChUa.contains("Chromium")
            )
            assertTrue(
                "Sec-CH-UA must never name Android WebView (saw '$secChUa')",
                !secChUa.contains("Android WebView")
            )
            val hintMajor = secChUa.substringAfter("Chromium\";v=\"", "")
                .substringBefore('"')
            val uaMajor = ua.substringAfter("Chrome/", "").substringBefore('.')
            assertTrue(
                "Sec-CH-UA ($hintMajor) and the User-Agent ($uaMajor) must report ONE " +
                    "Chromium version — the split is what Google refuses",
                hintMajor.isNotEmpty() && hintMajor == uaMajor
            )
        }
        val mobile = hit.getHeader("Sec-CH-UA-Mobile")
        if (mobile != null && ua.contains("Android")) {
            val expected = if (ua.contains(" Mobile")) "?1" else "?0"
            assertTrue(
                "Sec-CH-UA-Mobile ($mobile) must match the User-Agent's mobile flag " +
                    "(expected $expected for '$ua')",
                mobile == expected
            )
        }
    }

    /**
     * The page-side half, asserted against what the ENGINE can natively
     * serve.
     *
     * The app installs NO identity JavaScript, so `navigator.userAgentData`
     * exists only where the engine's User-Agent-Client-Hints implementation
     * does — on a provider that has it, it is built from the metadata this app
     * set from the same resolved UA string, and the page cannot contradict the
     * headers; on an older provider the object is absent, exactly as it is on
     * engines that never had the API (Firefox and Safari ship without it too,
     * and Google signs in there). What this asserts is the part that must
     * hold EITHER way: the UA the page read is byte-for-byte the UA the
     * request carried, and wherever a userAgentData IS visible, it agrees
     * with that string — never "Android WebView", one Chromium version, the
     * right mobile flag, the right platform.
     */
    private fun assertPageIdentityIsCoherent(hit: RecordedRequest, ua: String, mobile: Boolean) {
        val recorded = hit.requestUrl
        assertNotNull("The probe must carry its report in the query string", recorded)
        val url = recorded!!
        val pageUa = url.queryParameter("ua").orEmpty()
        assertTrue(
            "navigator.userAgent must be the string the site was sent — page read " +
                "'$pageUa', request carried '$ua'",
            pageUa == ua
        )
        assertNoEmbeddedBrowserTell("page", pageUa)

        if (url.queryParameter("hasUaData") != "1") {
            // The engine's provider has no User-Agent-Client-Hints
            // implementation, so the object is absent — the engine's own
            // honest state, not something app-side code could or should fake.
            println(
                "GoogleIdentityE2e: this provider exposes no navigator.userAgentData " +
                    "(page reported hasUaData=0). The User-Agent half was still " +
                    "asserted byte-for-byte against the wire."
            )
            return
        }
        val brands = url.queryParameter("brands").orEmpty()
        assertTrue(
            "The brand list must name Chromium (saw '$brands')",
            brands.contains("Chromium=")
        )
        assertTrue(
            "The brand list must name Google Chrome (saw '$brands')",
            brands.contains("Google Chrome=")
        )
        assertTrue(
            "The brand list must never name Android WebView (saw '$brands')",
            !brands.contains("Android WebView")
        )
        val brandMajor = brands.split(',')
            .firstOrNull { it.startsWith("Chromium=") }
            ?.substringAfter('=')
            ?.substringBefore('.')
        val uaMajor = ua.substringAfter("Chrome/", "").substringBefore('.')
        assertTrue(
            "navigator.userAgentData says Chromium $brandMajor while navigator.userAgent " +
                "says Chrome $uaMajor — one browser, two descriptions",
            brandMajor != null && brandMajor == uaMajor
        )
        assertTrue(
            "userAgentData.mobile must be $mobile for this identity (saw " +
                "'${url.queryParameter("mobile")}')",
            url.queryParameter("mobile") == mobile.toString()
        )
        assertTrue(
            "userAgentData.platform must be Android for an Android identity (saw " +
                "'${url.queryParameter("platform")}')",
            url.queryParameter("platform") == "Android"
        )
    }

    /**
     * The `X-Requested-With` half: the main frame must never name the app.
     *
     * THE HISTORY OF THIS ASSERTION IS TWO RELAXATIONS AND THIS TIGHTENING.
     * It began conditional on the provider's opt-out flag, which proved
     * nothing (the flag reads false for opposite reasons on old and current
     * providers). It then became an unconditional `assertNull`, which was
     * red for a reason no commit could fix: with no lever at all, the
     * emulator's WebView 83 stamped `com.roombrowser.debug` on every
     * request, so it was weakened to "if present, must name this app" — an
     * assertion that passed while the exact header Google's refusal reads
     * went out with the app's name on it.
     *
     * THE LEVER NOW EXISTS. `loadUri` issues every main-frame navigation
     * with `X-Requested-With: ""` in the additional headers, and an
     * app-supplied additional header wins over the engine's stamp on every
     * provider since 71. The requests this test records ARE main-frame
     * requests (the engine was launched at these URLs), so if the override
     * reaches the wire the header is either gone or empty, and a provider
     * that stamps the package name OVER an app-supplied header is exactly
     * the regression this must catch. Subresource stamps — which a provider
     * can still add and the app still cannot remove — belong to the probe
     * and the diagnostics screen, not to this assertion.
     */
    private fun assertHeaderNeverNamesTheApp(hit: RecordedRequest, where: String) {
        val header = hit.getHeader("X-Requested-With") ?: return
        assertNotEquals(
            "$where: the main-frame request must not carry X-Requested-With: " +
                "<package> — loadUri issues every main-frame navigation with an " +
                "empty override, and Google's sign-in refusal reads exactly this " +
                "request (server received '$header')",
            targetContext.packageName,
            header
        )
    }

    /** The app's own profile creation path: a real handset from the catalogue. */
    private fun createProfile(name: String): ProfileId = runBlocking {
        val profile = appContext.graph.profileManager.create(name, "G", 0xFF6750A4)
        appContext.graph.appState.setActiveProfile(profile.id.value)
        profile.id
    }

    /**
     * ONE PROFILE, TWO LAUNCHES.
     *
     * Both identity shapes are exercised on the SAME profile because a second
     * profile would be a profile SWITCH, and a switch restarts the process (the
     * WebView data-directory suffix is one-shot) — a different test than this
     * one, and one that took the second launch down with it when the two were
     * split. Changing only the device keeps the profile bound and the launch
     * ordinary, so a failure here is about the identity and nothing else.
     */
    @Test
    fun the_identity_sent_to_google_is_one_coherent_browser() {
        val base = server.url("/").toString().trimEnd('/')

        // The newest handset the catalogue ships. The catalogue's own version
        // floor stays a CATALOGUE assertion — the wire carries the engine's
        // build — but keeping the floor high means a preset added later cannot
        // quietly shrink the model catalogue below a modern handset.
        val chromeMajor: (Device) -> Int = {
            it.chromeVersion.substringBefore('.').toIntOrNull() ?: 0
        }
        val device = Devices.all
            .filter { it.formFactor == "phone" }
            .maxByOrNull(chromeMajor)!!
        assertTrue(
            "The catalogue's newest handset must claim Chromium 153 or newer " +
                "(saw ${device.chromeVersion})",
            chromeMajor(device) >= 153
        )

        val profileId = createProfile("E2EGoogle$tag")
        runBlocking { appContext.graph.profileManager.setDevice(profileId, device.id) }

        // ---- A. the shape the app gives every profile it creates ----------
        launchEngineAt("$base/phase-a", profileId)

        val hit = await("/phase-a", "The handset profile must load the page")
        val ua = hit.getHeader("User-Agent").orEmpty()
        // The engine's own stock string, read the way WebViewStockUserAgent
        // captures it before any override lands: this is what configure()
        // tuned, so it is what the request must carry.
        val stockUa = WebSettings.getDefaultUserAgent(targetContext)
        val engineVersion = Regex("""Chrome/(\d+(?:\.\d+){1,3})""").find(stockUa)?.groupValues[1]
        assertNotNull(
            "The engine's stock UA must carry a Chromium token to tune against " +
                "(saw '$stockUa')",
            engineVersion
        )
        assertTrue(
            "The request must carry the claimed handset's model slot — the one " +
                "catalogue field the tuning contract lets a profile choose (saw '$ua')",
            ua.contains(device.code)
        )
        assertTrue(
            "The request must carry the ENGINE's real Chromium build " +
                "(engine token 'Chrome/$engineVersion', saw '$ua') — a claimed " +
                "${device.chromeVersion} over a $engineVersion engine is the " +
                "split identity Google's refusal reads",
            ua.contains("Chrome/$engineVersion")
        )
        assertNoEmbeddedBrowserTell("request", ua)
        assertHeaderNeverNamesTheApp(hit, "handset profile")
        assertHintsAgreeWithUserAgent(hit, ua)

        val probe = await("/probe-a", "The page must report the identity it read")
        assertPageIdentityIsCoherent(probe, ua, mobile = true)

        // ---- B. no device: the engine's own build, packaging removed ------
        runBlocking { appContext.graph.profileManager.setDevice(profileId, null) }
        launchEngineAt("$base/phase-b", profileId)

        val plain = await("/phase-b", "The no-device profile must load the page")
        val plainUa = plain.getHeader("User-Agent").orEmpty()
        assertTrue(
            "The request must carry a Chrome identity even with no device claimed " +
                "(saw '$plainUa')",
            plainUa.contains("Chrome/")
        )
        assertNoEmbeddedBrowserTell("request", plainUa)
        assertHeaderNeverNamesTheApp(plain, "no-device profile")
        assertHintsAgreeWithUserAgent(plain, plainUa)

        val plainProbe = await("/probe-b", "The page must report the identity it read")
        val plainPageUa = plainProbe.requestUrl?.queryParameter("ua").orEmpty()
        assertTrue(
            "navigator.userAgent must be the string the site was sent — page read " +
                "'$plainPageUa', request carried '$plainUa'",
            plainPageUa == plainUa
        )
        assertNoEmbeddedBrowserTell("page", plainPageUa)
    }
}
