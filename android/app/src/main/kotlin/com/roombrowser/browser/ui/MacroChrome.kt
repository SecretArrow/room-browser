package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.macro.MacroRecordState
import com.roombrowser.browser.macro.MacroRunner
import com.roombrowser.ui.common.LocalRoomExtras

/**
 * The recording indicator — a compact always-visible bar the browser shows
 * while a macro recording is armed. Recording is a state the page does not
 * own, so the chrome must carry the sign: the user is never left guessing
 * whether their taps are being captured.
 */
@Composable
fun MacroRecordingBar(
    viewModel: BrowserViewModel,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.macroRecordState
    if (state == MacroRecordState.IDLE) return
    val recording = state == MacroRecordState.RECORDING
    val extras = LocalRoomExtras.current

    Surface(
        color = if (recording) Color(0xFFB3261E) else Color(0xFF6750A4),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = "macro_recording_bar" }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The classic REC dot: pulsing would need an animation scope; a
            // solid dot reads at any glance speed and stays cheap.
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(10.dp)
                    .background(Color.White, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (recording) "Recording macro — ${viewModel.macroRecordSteps} steps" else "Recording paused",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "This tab only • secrets are never stored",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            TextButton(
                onClick = { viewModel.macroPauseOrResumeRecording() },
                modifier = Modifier.semantics {
                    contentDescription = if (recording) "macro_recording_pause" else "macro_recording_resume"
                }
            ) {
                Icon(
                    if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            TextButton(
                onClick = {
                    viewModel.macroStopRecording()
                    onOpenEditor()
                },
                modifier = Modifier.semantics { contentDescription = "macro_recording_stop" }
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White)
            }
            TextButton(
                onClick = { viewModel.macroCancelRecording() },
                modifier = Modifier.semantics { contentDescription = "macro_recording_cancel" }
            ) {
                Text("✕", color = Color.White)
            }
        }
    }
}

/**
 * The playback HUD — the always-visible progress surface while a macro
 * runs. Play/pause, stop, restart; the current step, the iteration and the
 * ETA. Stopping is ALWAYS one tap away, per the macro contract.
 */
@Composable
fun MacroRunHud(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val progress = viewModel.macroRunProgress
    if (progress.state == MacroRunner.State.IDLE) return
    val extras = LocalRoomExtras.current
    val running = progress.state == MacroRunner.State.RUNNING
    val pct = if (progress.stepCount > 0 && progress.stepIndex >= 0) {
        (progress.stepIndex + 1).toFloat() / progress.stepCount
    } else 0f

    Surface(
        color = extras.background,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extras.primary.copy(alpha = 0.4f)),
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = "macro_run_hud" }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = extras.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${progress.macroName} — step ${progress.stepIndex + 1}/${progress.stepCount} " +
                            if (progress.totalIterations > 1 || progress.totalIterations == 0) {
                                "(run ${progress.iteration}${if (progress.totalIterations > 0) "/${progress.totalIterations}" else ""})"
                            } else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = extras.textPrimary
                    )
                    Text(
                        buildString {
                            append(progress.currentAction.lowercase().replace('_', ' '))
                            if (progress.etaMs > 1_000) {
                                append(" • ~${progress.etaMs / 1000}s left")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { viewModel.macroPauseOrResumeRun() },
                    modifier = Modifier.semantics {
                        contentDescription = if (running) "macro_run_pause" else "macro_run_resume"
                    }
                ) {
                    Icon(
                        if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = extras.icon
                    )
                }
                IconButton(
                    onClick = { viewModel.macroRunner.restart(emptyMap()) },
                    modifier = Modifier.semantics { contentDescription = "macro_run_restart" }
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = null, tint = extras.icon)
                }
                IconButton(
                    onClick = { viewModel.macroStopRun() },
                    modifier = Modifier.semantics { contentDescription = "macro_run_stop" }
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, tint = extras.icon)
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { pct.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
