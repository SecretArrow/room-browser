package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.Surface
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
import com.roombrowser.engine.SignInDiagnostics
import com.roombrowser.engine.SignInDiagnostics.ProbeOutcome
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

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
 *  5. THE GOOGLE COOKIE JAR — the bytes and names this profile would hand
 *     Google on every request, because the OTHER refusal (the 400 "malformed
 *     request" page) is the front end rejecting the request itself, and the
 *     one thing a WebView app's requests carry that Chrome's do not is this
 *     jar; a corrupted or oversized one is answered 400 no matter what the
 *     headers say, and clearing it is the standard remedy. The screen shows
 *     its size and offers the erase;
 *  6. THE LIVE PROBE — a throwaway WebView, configured with the same
 *     default identity this app's engine uses (WebView markers stripped from
 *     the UA, the identity header the shared policy resolves for a Google
 *     host — the Chrome stamp — on the load, JavaScript and cookies on),
 *     loads the real sign-in page and reports whether the refusal text is in
 *     what Google served, whether a real sign-in form rendered, and where
 *     the navigation finally landed.
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
                    probe = runCatching { SignInDiagnostics.runProbe(appContext) }.getOrNull()
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
                    onClearGoogleCookies = {
                        if (!reading && !probing) {
                            scope.launch {
                                runCatching { SignInDiagnostics.clearGoogleCookies() }
                                runAll()
                            }
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
    val playServicesVersion: String?,
    val cookieFootprint: SignInDiagnostics.GoogleCookieFootprint?,
)

/**
 * The jar size a reading still calls healthy. A fresh profile hands Google
 * a couple of hundred bytes; the 400-malformed complaints start well above
 * this line, so anything under it is green no matter how it got there.
 */
private const val GOOGLE_COOKIE_BYTES_OK = 4_096

/** Environment + identity reading, everything except the live probe. */
private suspend fun readReport(context: Context): SignInReport {
    // The provider identity and the stock UA are engine reads -- the boundary
    // test keeps android.webkit imports out of this module, so both cross the
    // SignInDiagnostics facade.
    val providerLabel = runCatching { SignInDiagnostics.webViewProviderIdentity(context) }
        .getOrNull() ?: "Android System WebView (version not reported by this device)"
    val stockUa = runCatching { SignInDiagnostics.stockUserAgent(context) }.getOrDefault("")
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
    val cookieFootprint = runCatching { SignInDiagnostics.googleCookieFootprint() }.getOrNull()
    return SignInReport(providerLabel, ua, checks, skew, gms, cookieFootprint)
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

@Composable
private fun GoogleSignInCheckScreen(
    report: SignInReport?,
    probe: ProbeOutcome?,
    reading: Boolean,
    onRerun: () -> Unit,
    onClearGoogleCookies: () -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
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
                        CheckRow(
                            "Google cookies",
                            rep.cookieFootprint?.let { it.totalBytes < GOOGLE_COOKIE_BYTES_OK },
                            when (val footprint = rep.cookieFootprint) {
                                null -> "Not measured — the cookie jar could not be read on this device."
                                else -> {
                                    val bytes = footprint.totalBytes
                                    val size = if (bytes >= 1024) "${(bytes + 512) / 1024} KB" else "$bytes bytes"
                                    if (bytes < GOOGLE_COOKIE_BYTES_OK) {
                                        "${footprint.names} cookies, $size attached to Google hosts — the size a " +
                                            "clean profile carries."
                                    } else {
                                        "${footprint.names} cookies, $size attached to Google hosts. Google's front " +
                                            "end answers an oversized or corrupted cookie header with the 400 " +
                                            "\"malformed request\" page — clearing them is the standard remedy " +
                                            "(button below)."
                                    }
                                }
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClearGoogleCookies,
                enabled = !reading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Google cookies")
            }
            Text(
                "Erases every Google-owned host's cookies in THIS profile only — " +
                    "the remedy for the 400 \"malformed request\" page. You will be " +
                    "signed out of Google properties here.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
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
