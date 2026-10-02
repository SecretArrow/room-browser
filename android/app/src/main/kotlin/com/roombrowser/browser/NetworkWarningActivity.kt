package com.roombrowser.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The user's decision on the profile network warning. */
enum class NetworkWarningDecision {
    /** Continue with THIS profile (session-acknowledged). */
    CONTINUE,
    /** Pick a different profile (re-opens the quick switcher). */
    SWITCH,
    /** Never warn again for this IP (persisted suppression). */
    SUPPRESS
}

/**
 * Full-screen Profile Network Warning (spec sections 6 / 74) — replaces the
 * old IpWarningDialog.
 *
 * Runs in the ':browser' process next to BrowserActivity, which launches it
 * FOR RESULT while a pending network decision gates the profile:
 *
 *  - The gate is PERSISTED (app_state `net_decision_pending`), so process
 *    death / activity recreation re-launches this screen — the decision can
 *    never be bypassed, and system Back only finishes with RESULT_CANCELED
 *    (BrowserActivity immediately re-launches while the flag stands).
 *  - Exactly three decisions: Continue, Switch Profile, Don't Warn Again
 *    for This IP. All data arrives via Intent extras (the screen holds no
 *    state of its own).
 *
 * Manifest entry (owner: main agent — reported, not edited here):
 * `process=":browser"`, `exported="false"`, same configChanges set as
 * BrowserActivity, `windowSoftInputMode="adjustResize"`.
 */
class NetworkWarningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        val previousProfileName =
            intent.getStringExtra(EXTRA_PREVIOUS_PROFILE_NAME)?.takeIf { it.isNotBlank() }
                ?: "another profile"
        val lastSeenAt = intent.getLongExtra(EXTRA_LAST_SEEN, 0L)
        setContent {
            RoomBrowserTheme {
                NetworkWarningScreen(
                    ip = ip,
                    previousProfileName = previousProfileName,
                    lastSeenAt = lastSeenAt,
                    onContinue = { finishWith(RESULT_CONTINUE) },
                    onSwitchProfile = { finishWith(RESULT_SWITCH) },
                    onDontWarnAgain = { finishWith(RESULT_SUPPRESS) }
                )
            }
        }
    }

    /** The ONLY exits are the three decisions (Back = RESULT_CANCELED). */
    private fun finishWith(resultCode: Int) {
        setResult(resultCode)
        finish()
    }

    companion object {
        const val EXTRA_IP = "com.roombrowser.extra.NET_WARNING_IP"
        const val EXTRA_PREVIOUS_PROFILE_NAME =
            "com.roombrowser.extra.NET_WARNING_PREVIOUS_PROFILE_NAME"
        const val EXTRA_LAST_SEEN = "com.roombrowser.extra.NET_WARNING_LAST_SEEN"

        /**
         * Decision result codes — allocated from Activity.RESULT_FIRST_USER
         * (= 1) upward, so they can never collide with RESULT_OK (-1) or
         * RESULT_CANCELED (0, which a system-Back finish reports).
         */
        const val RESULT_CONTINUE = 1
        const val RESULT_SWITCH = 2
        const val RESULT_SUPPRESS = 3
    }
}

/** Minimalist, theme-following full-screen decision surface. */
@Composable
private fun NetworkWarningScreen(
    ip: String,
    previousProfileName: String,
    lastSeenAt: Long,
    onContinue: () -> Unit,
    onSwitchProfile: () -> Unit,
    onDontWarnAgain: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            // Edge-to-edge: content stays clear of the status bar, the
            // navigation bar and any display cutout (same pattern as the
            // browser shell).
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
            )
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Profile Network Warning",
            style = MaterialTheme.typography.headlineSmall,
            color = extras.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "This profile is being opened from a public IP previously associated " +
                "with the \"$previousProfileName\" profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape((extras.radius * 0.8f).dp))
                .background(extras.surface)
                .padding(16.dp)
        ) {
            Text(
                "Current IP: $ip",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textPrimary
            )
            if (lastSeenAt > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last seen: ${timeFormat.format(Date(lastSeenAt))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "A shared public IP does not prove that profiles belong to the same " +
                "person. This is an informational warning only.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSwitchProfile, modifier = Modifier.fillMaxWidth()) {
            Text("Switch Profile")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDontWarnAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Don't Warn Again for This IP")
        }
    }
}
