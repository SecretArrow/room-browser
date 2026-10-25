package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The engine answers all three (see `WebViewEngineSession.configure`, which
 * neutralises the stock string and empties the `X-Requested-With` allow-list,
 * and `WebViewClientHints`, which derives the hints from the same resolved
 * string). The unit tests cover the derivation in isolation; THIS test is the
 * one that proves the result reaches the wire, because it asserts on the
 * request the WebView actually made and on what the page's own JavaScript
 * read back — not on a mapper's return value.
 *
 * TWO PHASES ON ONE PROFILE, because the app ships two shapes of identity and
 * both must survive a Google round trip:
 *
 *   A. the profile pointed at a handset from the catalogue — the shape the app
 *      gives every profile it creates, and the one whose claimed Chromium
 *      version is the 156 the catalogue promises;
 *   B. the same profile with its device taken away, where the honest identity
 *      is the engine's real build with the embedded-browser tokens removed and
 *      no model claimed at all.
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
     * The page-side half, asserted against what the installed engine can
     * actually install.
     *
     * `navigator.userAgentData` reaches a page in one of two ways: the engine's
     * own User-Agent-Client-Hints implementation, or the app's device shim
     * installed as a document-start script. On a provider that supports neither
     * — the CI emulator ships WebView 83 — the page has no such object, and no
     * app-side code can give it one. What the test must NOT do is quietly accept
     * that on an engine that could have done better, so the branch is keyed on
     * the engine's own capability report rather than on what the page happened
     * to return.
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

        if (!ProfileEngine.supportsDocumentStartScripts()) {
            // The engine said it cannot install the shim, so the absence is the
            // engine's, not this app's — and saying which is the honest outcome.
            println(
                "GoogleIdentityE2e: this engine's provider cannot install a " +
                    "document-start script, so navigator.userAgentData is absent " +
                    "(page reported hasUaData=${url.queryParameter("hasUaData")}). " +
                    "The User-Agent and client-hint halves were still asserted."
            )
            return
        }
        assertTrue(
            "The engine supports document-start scripts, so the shim must install and " +
                "the page must expose navigator.userAgentData — Google's sign-in page " +
                "reads it, and its absence is itself an embedded-browser signal",
            url.queryParameter("hasUaData") == "1"
        )
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
     * The `X-Requested-With` half, asserted against what the installed engine
     * can actually do.
     *
     * The header is added by the engine itself, so only an engine with the
     * opt-out API can stop it; on a provider that predates that API it goes out
     * on every request and nothing in this app can remove it (see
     * `WebViewEngineSession.configure`). The assertion is therefore the honest
     * one — the header may be present ONLY when the engine says it cannot
     * suppress it — which still fails on a modern provider that regressed, and
     * names the engine when it passes for the other reason.
     */
    private fun assertNoAppIdentifyingHeader(hit: RecordedRequest, where: String) {
        val header = hit.getHeader("X-Requested-With")
        val canSuppress = ProfileEngine.suppressesEmbeddedBrowserHeaders()
        if (canSuppress) {
            assertNull(
                "$where: this engine can suppress X-Requested-With, so it must — " +
                    "Google reads the header as 'an app, not a browser' (saw '$header')",
                header
            )
        } else {
            // Not a pass dressed as a skip: the failure this test exists to
            // catch (a modern engine that stopped suppressing) is asserted
            // above, and this branch records WHY the header is tolerated here.
            println(
                "GoogleIdentityE2e: this engine's provider predates the " +
                    "X-Requested-With opt-out, so the header ('$header') cannot be " +
                    "removed from the app side — the UA and client-hint halves are " +
                    "still asserted."
            )
        }
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

        // The newest handset the catalogue ships. Asserting on the catalogue's
        // own best answer (rather than a hard-coded id) means a preset added
        // later cannot quietly fall below the 153 floor.
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
        assertTrue(
            "The request must carry the device's claimed identity (saw '$ua')",
            ua.contains("Chrome/${device.chromeVersion}")
        )
        assertTrue(
            "The claimed Chromium version must be 153 or newer (saw '$ua')",
            (ua.substringAfter("Chrome/", "").substringBefore('.').toIntOrNull() ?: 0) >= 153
        )
        assertNoEmbeddedBrowserTell("request", ua)
        assertNoAppIdentifyingHeader(hit, "handset profile")
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
        assertNoAppIdentifyingHeader(plain, "no-device profile")
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
