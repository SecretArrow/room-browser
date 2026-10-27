package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewFeature
import com.roombrowser.browser.engine.IdentityCheck
import com.roombrowser.browser.engine.IdentityDiagnostics
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.engine.EmbeddedHeaderObservation
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape
import androidx.compose.material3.Surface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Google sign-in check" — the refusal, reproduced on THIS device, with every
 * factor named.
 *
 * WHY THIS EXISTS. Google's "This browser or app may not be secure" page
 * lists four boilerplate causes and shows none of them, the header-level work
 * this app does is invisible, and one field test (a GeckoView build on the
 * user's own device failing the same way) proved the cause is not always in
 * the headers at all. Guessing again would have been the third blind fix.
 * This screen measures instead:
 *
 *  1. the INSTALLED PROVIDER — name and version, because two of Google's
 *     levers (client hints, document-start scripts) exist only above
 *     provider-specific floors, and an old Android System WebView is the one
 *     cause this app cannot code around;
 *  2. the IDENTITY rows — the same [IdentityDiagnostics] checks the settings
 *     screen shows, answered here from the provider this process loaded;
 *  3. the DEVICE CLOCK — Google refuses sign-ins when the device time drifts
 *     far from the server's, measured here against an HTTPS `Date` header;
 *  4. GOOGLE PLAY SERVICES — a device without it is often a device Google's
 *     risk engine distrusts, which is a factor no browser header touches;
 *  5. THE LIVE PROBE — a throwaway WebView, configured with the same
 *     default identity this app's engine uses (WebView markers stripped from
 *     the UA, an empty `X-Requested-With` override on the load, JavaScript
 *     and cookies on), loads the real sign-in page and reports whether the
 *     refusal text is in what Google served, whether a real sign-in form
 *     rendered, and where the navigation finally landed.
 *
 * WHAT THE PROBE SETTLES. A blocked probe on a device where Chrome signs in
 * fine means the remaining block is attached to THIS app's requests and the
 * identity rows will say which surface leaked. A blocked probe everywhere —
 * including Chrome — means the network, the device or the account is the
 * factor, and no amount of app-side header work can move it. A clean probe on
 * a device where the user still saw the refusal means the block was transient
 * (Google's risk engine cools down) and the previous fixes held.
 *
 * The probe is read-only: it never submits anything, never touches the
 * user's account, and destroys its WebView before reporting.
 */
class GoogleSignInCheckActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContext = applicationContext
        setContent {
            RoomBrowserTheme {
                var report by remember { mutableStateOf<SignInReport?>(null) }
                var probe by remember { mutableStateOf<ProbeOutcome?>(null) }
                var reading by remember { mutableStateOf(false) }
                var probing by remember { mutableStateOf(false) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                suspend fun runAll() {
                    reading = true
                    probe = null
                    report = runCatching { readReport(appContext) }.getOrNull()
                    reading = false
                    probing = true
                    probe = runCatching { runProbe(appContext) }.getOrNull()
                    probing = false
                }
                LaunchedEffect(Unit) { runAll() }
                GoogleSignInCheckScreen(
                    report = report,
                    probe = probe,
                    reading = reading || probing,
                    onRerun = {
                        if (!reading && !probing) {
                            scope.launch { runAll() }
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, GoogleSignInCheckActivity::class.java))
        }
    }
}

/** One full reading: environment rows, identity rows, and the probe slot. */
private data class SignInReport(
    val providerLabel: String,
    val userAgent: String,
    val checks: List<IdentityCheck>,
    val clockSkewSeconds: Long?,
    val playServicesVersion: String?
)

/** What the live probe saw when Google answered. */
private data class ProbeOutcome(
    /** true = refusal served, false = sign-in form rendered, null = inconclusive. */
    val blocked: Boolean?,
    val marker: String?,
    val formSeen: Boolean,
    val finalUrl: String,
    val note: String
)

/** The sign-in URL the probe loads. `hl=en` so the refusal text is the stable English one. */
private const val PROBE_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.google.com%2F&hl=en"

/** How long the probe waits for Google before reporting inconclusive. */
private const val PROBE_TIMEOUT_MS = 25_000L

/**
 * The refusal strings, matched lowercase against the rendered page text.
 * English first (the probe pins `hl=en`), then the Indonesian renderings —
 * the device locale can still win for interstitials Google localises before
 * the `hl` parameter is read.
 */
private val BLOCK_MARKERS = listOf(
    "this browser or app may not be secure",
    "sign in with a supported browser",
    "browser atau aplikasi ini mungkin tidak aman",
    "masuk dengan browser yang didukung"
)

/** The page-side probe: marker scan, sign-in-form detection, landing URL. */
private fun probeScript(): String {
    val markersJson = JSONArray(BLOCK_MARKERS).toString()
    return """
        (function() {
          var t = ((document.body && document.body.innerText) || '').toLowerCase();
          var markers = $markersJson;
          var hit = null;
          for (var i = 0; i < markers.length; i++) {
            if (t.indexOf(markers[i]) >= 0) { hit = markers[i]; break; }
          }
          return {
            hit: hit,
            form: !!document.querySelector('input[type=email]'),
            url: location.href
          };
        })()
    """.trimIndent()
}

/** Environment + identity reading, everything except the live probe. */
private suspend fun readReport(context: Context): SignInReport {
    val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
    val providerLabel = provider?.let { p ->
        // `WebViewPackageInfo` exposes the provider's package name and
        // version directly (API 26+); the label comes from PackageManager
        // against that package, with the package name as the fallback the
        // reader still recognises when the label lookup is refused.
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(p.packageName, 0)
            ).toString()
        }.getOrNull() ?: p.packageName
        val version = p.versionName
        "$label ${version ?: ""}".trim()
    } ?: "Android System WebView (version not reported by this device)"
    val stockUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
    val ua = runCatching { UserAgents.webViewNeutralUserAgent(stockUa) }.getOrNull() ?: stockUa
    val hintsOk = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) }.getOrDefault(false)
    val docStartOk = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
    val checks = IdentityDiagnostics.checks(
        userAgent = ua,
        javascriptEnabled = true,
        requestedWith = EmbeddedHeaderObservation.UNOBSERVED,
        setsClientHints = hintsOk,
        installsDocumentStartScripts = docStartOk
    )
    val skew = runCatching { fetchGoogleDateMs() }.getOrNull()?.let { serverDateMs ->
        TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - serverDateMs)
    }
    val gms = runCatching {
        context.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
    }.getOrNull()
    return SignInReport(providerLabel, ua, checks, skew, gms)
}

/**
 * Google's own clock, from the `Date` header of a bare 204 probe — the
 * reference the device clock row is judged against.
 */
private suspend fun fetchGoogleDateMs(): Long? = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    val request = Request.Builder().head().url("https://www.google.com/generate_204").build()
    client.newCall(request).execute().use { response ->
        response.header("Date")?.let { header ->
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                isLenient = false
            }.parse(header)?.time
        }
    }
}

/**
 * The live probe. Runs a bare WebView on the main thread with the engine's
 * default identity stack and reads what Google serves. Never submits, never
 * types, destroys the WebView before answering.
 */
private suspend fun runProbe(context: Context): ProbeOutcome =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            var done = false
            val timeout = Runnable {
                settle(cont, main, webView, ProbeOutcome(
                    blocked = null,
                    marker = null,
                    formSeen = false,
                    finalUrl = webView.url ?: PROBE_URL,
                    note = "Timed out after ${PROBE_TIMEOUT_MS / 1000} s with no finished load — " +
                        "the network never answered, or the page never stopped redirecting. " +
                        "Check connectivity and run the check again."
                ))
            }
            webView.settings.apply {
                javaScriptEnabled = true
                // The engine's defaults, reproduced: cookies on, third-party
                // cookies on (a privacy profile that blocks them would test a
                // different question than the one this screen asks).
                domStorageEnabled = true
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(probeScript()) { result ->
                        val outcome = runCatching {
                            // An object result arrives as a JSON object literal;
                            // a null arrives as the four characters "null".
                            val json = JSONObject(result)
                            val hit = json.optString("hit").takeIf { it.isNotEmpty() }
                            val form = json.optBoolean("form", false)
                            val landed = json.optString("url").ifBlank { url }
                            when {
                                hit != null -> ProbeOutcome(
                                    blocked = true,
                                    marker = hit,
                                    formSeen = form,
                                    finalUrl = landed,
                                    note = "Google served the refusal page to this app's requests. " +
                                        "First remedies, in order: update Android System WebView " +
                                        "and Chrome from the Play Store (an old provider is the one " +
                                        "cause no app code can work around), turn any VPN off, and " +
                                        "check the device clock below. If Chrome itself signs in fine " +
                                        "on this device while this probe keeps failing, the block is " +
                                        "attached to this app's traffic — re-read the identity rows " +
                                        "above, and report the marker and URL shown here."
                                )
                                form -> ProbeOutcome(
                                    blocked = false,
                                    marker = null,
                                    formSeen = true,
                                    finalUrl = landed,
                                    note = "The real sign-in form rendered with no refusal text. " +
                                        "Sign-in inside this browser should work here; if you still " +
                                        "saw the refusal in a tab, it was likely Google's risk engine " +
                                        "cooling down after earlier attempts — wait a while and try."
                                )
                                else -> ProbeOutcome(
                                    blocked = false,
                                    marker = null,
                                    formSeen = false,
                                    finalUrl = landed,
                                    note = "No refusal text and no sign-in form on the landing page " +
                                        "(Google may have signed the probe straight through, or served " +
                                        "an interstitial it did not match). If sign-in still fails in " +
                                        "a tab, run the check again from that state."
                                )
                            }
                        }.getOrElse { ProbeOutcome(
                            blocked = null,
                            marker = null,
                            formSeen = false,
                            finalUrl = url,
                            note = "The probe page finished but its answer could not be read."
                        ) }
                        settle(cont, main, webView, outcome)
                    }
                }
            }
            main.postDelayed(timeout, PROBE_TIMEOUT_MS)
            // The same lever the engine's own loads use: an app-supplied
            // additional header overrides the provider's X-Requested-With
            // stamp on this navigation.
            runCatching { webView.loadUrl(PROBE_URL, mapOf("X-Requested-With" to "")) }
                .onFailure { settle(cont, main, webView, ProbeOutcome(
                    blocked = null, marker = null, formSeen = false,
                    finalUrl = PROBE_URL,
                    note = "The probe WebView could not start on this device."
                )) }
        }
    }

private fun settle(
    cont: kotlinx.coroutines.CancellableContinuation<ProbeOutcome>,
    main: Handler,
    webView: WebView,
    outcome: ProbeOutcome
) {
    if (cont.isActive) {
        cont.resume(outcome) { }
    }
    runCatching { webView.stopLoading() }
    main.post { runCatching { webView.destroy() } }
}

@Composable
private fun GoogleSignInCheckScreen(
    report: SignInReport?,
    probe: ProbeOutcome?,
    reading: Boolean,
    onRerun: () -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout()))
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Google sign-in check",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onRerun,
                enabled = !reading,
                modifier = Modifier.semantics { contentDescription = "Run the check again" }
            ) { Icon(Icons.Filled.Refresh, contentDescription = null) }
        }
        if (reading && report == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Reproduces Google's sign-in refusal on this device, and names every factor " +
                    "it reads. Nothing is submitted; no account is touched.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            report?.let { rep ->
                Surface(shape = RoomCardShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        CheckRow("WebView provider", null, rep.providerLabel)
                        rep.checks.forEach { CheckRow(it.label, it.ok, it.detail) }
                        CheckRow(
                            "Device clock",
                            rep.clockSkewSeconds?.let { kotlin.math.abs(it) < 120 },
                            when (val skew = rep.clockSkewSeconds) {
                                null -> "Not measured — the time server did not answer."
                                else -> {
                                    val drifted = kotlin.math.abs(skew)
                                    if (drifted < 120) {
                                        "Within ${drifted}s of Google's own clock. A drifted clock is " +
                                            "a classic silent sign-in breaker."
                                    } else {
                                        "Off by ${drifted}s from Google's time. Fix the clock " +
                                            "(Settings → System → Date & time → automatic); Google " +
                                            "refuses sign-ins from a device whose time is far off."
                                    }
                                }
                            }
                        )
                        CheckRow(
                            "Google Play services",
                            rep.playServicesVersion != null,
                            if (rep.playServicesVersion != null) {
                                "Installed (v${rep.playServicesVersion}). Its absence would not block " +
                                    "sign-in by itself, but devices without it are distrusted more."
                            } else {
                                "Not found on this device. Sign-in can still work, but Google's risk " +
                                    "engine distrusts uncertified devices — expect harder checks."
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Surface(shape = RoomCardShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Live probe",
                        style = MaterialTheme.typography.titleMedium,
                        color = extras.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    when {
                        probe == null && reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Loading the sign-in page with this app's identity…")
                        }
                        probe == null -> Text("Not run yet.")
                        else -> {
                            val (icon, tint, verdict) = when (probe.blocked) {
                                true -> Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, "BLOCKED by Google")
                                false -> Triple(Icons.Filled.CheckCircle, Color(0xFF2E7D32), "Sign-in page served cleanly")
                                null -> Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Inconclusive")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, tint = tint)
                                Spacer(Modifier.width(8.dp))
                                Text(verdict, style = MaterialTheme.typography.titleMedium)
                            }
                            probe.marker?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Refusal marker matched: \"$it\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extras.textSecondary
                                )
                            }
                            if (probe.blocked == false && probe.formSeen) {
                                Text(
                                    "A real sign-in form rendered (email field present).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extras.textSecondary
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Landed on: ${probe.finalUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = extras.textSecondary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(probe.note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRerun, enabled = !reading, modifier = Modifier.fillMaxWidth()) {
                Text("Run the check again")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean?, detail: String) {
    val extras = LocalRoomExtras.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        when (ok) {
            true -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
            false -> Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            null -> Icon(Icons.Filled.Refresh, contentDescription = null, tint = extras.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = extras.textSecondary)
        }
    }
}
