package com.roombrowser.browser.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.macro.MacroRecordState
import com.roombrowser.browser.macro.MacroRunner
import com.roombrowser.domain.macro.MacroPick
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomMotion
import com.roombrowser.ui.common.rememberRoomHaptics
import com.roombrowser.ui.common.roomEnter
import com.roombrowser.ui.common.roomExit
import kotlinx.coroutines.launch

/**
 * The recording indicator — a compact always-visible bar the browser shows
 * while a macro recording is armed. Recording is a state the page does not
 * own, so the chrome must carry the sign: the user is never left guessing
 * whether their taps are being captured. It now ARRIVES (rises + fades on
 * the shared spring) instead of snapping over the page.
 */
@Composable
fun MacroRecordingBar(
    viewModel: BrowserViewModel,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.macroRecordState
    val recording = state == MacroRecordState.RECORDING
    val extras = LocalRoomExtras.current
    val haptics = rememberRoomHaptics()

    AnimatedVisibility(
        visible = state != MacroRecordState.IDLE,
        enter = roomEnter(extras.reducedMotion),
        exit = roomExit(extras.reducedMotion),
        modifier = modifier
    ) {
        Surface(
            // Recording stays urgent — solid error red. Paused goes calm:
            // a tonal primary wash with an outline, the modern reading of
            // "armed, but not capturing". Theme roles, not hardcodes, so
            // every profile theme recolors this bar like the rest of the UI.
            color = if (recording) MaterialTheme.colorScheme.error
            else extras.primary.copy(alpha = 0.14f),
            border = if (recording) null
            else BorderStroke(1.dp, extras.primary.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(extras.radius.dp),
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics { contentDescription = "macro_recording_bar" }
        ) {
            Row(
                Modifier.padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The classic REC dot: solid reads at any glance speed and
                // stays cheap — a pulsing loop would never let the e2e
                // suites' waitForIdle settle.
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (recording) Color.White else extras.primary,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (recording) "Recording macro — ${viewModel.macroRecordSteps} steps" else "Recording paused",
                        color = if (recording) Color.White else extras.textPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "This tab only • secrets are never stored",
                        color = if (recording) Color.White.copy(alpha = 0.85f) else extras.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = {
                        haptics.tick()
                        viewModel.macroPauseOrResumeRecording()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (recording) "macro_recording_pause" else "macro_recording_resume"
                    }
                ) {
                    Icon(
                        if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (recording) Color.White else extras.primary
                    )
                }
                IconButton(
                    onClick = {
                        haptics.knock()
                        viewModel.macroStopRecording()
                        onOpenEditor()
                    },
                    modifier = Modifier.semantics { contentDescription = "macro_recording_stop" }
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, tint = if (recording) Color.White else extras.primary)
                }
                IconButton(
                    onClick = {
                        haptics.tick()
                        viewModel.macroCancelRecording()
                    },
                    modifier = Modifier.semantics { contentDescription = "macro_recording_cancel" }
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = if (recording) Color.White else extras.primary)
                }
            }
        }
    }
}

/**
 * The playback HUD — the always-visible progress surface while a macro
 * runs. Play/pause, stop, restart; the current step, the iteration and the
 * ETA. Stopping is ALWAYS one tap away, per the macro contract. Arrives and
 * leaves on the shared chrome motion; the step progress fills smoothly
 * instead of jumping.
 */
@Composable
fun MacroRunHud(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val progress = viewModel.macroRunProgress
    val extras = LocalRoomExtras.current
    val haptics = rememberRoomHaptics()
    val running = progress.state == MacroRunner.State.RUNNING
    val pctTarget = if (progress.stepCount > 0 && progress.stepIndex >= 0) {
        (progress.stepIndex + 1).toFloat() / progress.stepCount
    } else {
        0f
    }
    val pct by animateFloatAsState(
        targetValue = pctTarget.coerceIn(0f, 1f),
        animationSpec = if (extras.reducedMotion) snap() else RoomMotion.smooth(),
        label = "macroRunProgress"
    )

    AnimatedVisibility(
        visible = progress.state != MacroRunner.State.IDLE,
        enter = roomEnter(extras.reducedMotion),
        exit = roomExit(extras.reducedMotion),
        modifier = modifier
    ) {
        Surface(
            color = extras.background,
            shape = RoundedCornerShape(extras.radius.dp),
            border = BorderStroke(1.dp, extras.primary.copy(alpha = 0.4f)),
            shadowElevation = 4.dp,
            modifier = Modifier
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
                        onClick = {
                            haptics.tick()
                            viewModel.macroPauseOrResumeRun()
                        },
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
                        onClick = {
                            haptics.tick()
                            viewModel.macroRunner.restart(emptyMap())
                        },
                        modifier = Modifier.semantics { contentDescription = "macro_run_restart" }
                    ) {
                        Icon(Icons.Filled.Replay, contentDescription = null, tint = extras.icon)
                    }
                    IconButton(
                        onClick = {
                            haptics.knock()
                            viewModel.macroStopRun()
                        },
                        modifier = Modifier.semantics { contentDescription = "macro_run_stop" }
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = extras.icon)
                    }
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * The tap-to-pick overlay — the editor's "Pick on page" request puts a
 * scrim over the engine view and every tap is consumed HERE (the page never
 * sees it) and resolved into a [MacroPick]: coordinates converted to the
 * page's CSS-viewport scale, the probe naming what sits underneath. The
 * answer is delivered to the editor (another process) through the one-shot
 * file the view model writes; this overlay only owns the visuals and the
 * confirmation toast.
 *
 * The overlay ENTERS animated — scrim fades in, banner drops on the arrive
 * spring — and leaves composition the instant the pick resolves (a snappy
 * disappearance; there is nothing to animate out). Both entrance animations
 * are one-shot and finite; reduced motion snaps both.
 */
@Composable
fun MacroPickOverlay(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val kind = viewModel.macroPickKind ?: return
    val context = LocalContext.current
    val extras = LocalRoomExtras.current
    val haptics = rememberRoomHaptics()

    DisposableEffect(Unit) {
        viewModel.onMacroPickResolved = { pick ->
            Toast.makeText(
                context,
                "Picked ${pick.x.toInt()}, ${pick.y.toInt()} — back to the editor",
                Toast.LENGTH_SHORT
            ).show()
        }
        onDispose { viewModel.onMacroPickResolved = null }
    }

    val scrim = remember { Animatable(0f) }
    val bannerDrop = remember { Animatable(-48f) }
    LaunchedEffect(extras.reducedMotion) {
        if (extras.reducedMotion) {
            scrim.snapTo(1f)
            bannerDrop.snapTo(0f)
        } else {
            launch { scrim.animateTo(1f, tween(RoomMotion.FADE_MS)) }
            launch { bannerDrop.animateTo(0f, RoomMotion.arrive()) }
        }
    }

    val instruction = when (kind) {
        MacroPick.KIND_DRAG_START -> "Tap the START point of the swipe"
        MacroPick.KIND_DRAG_END -> "Tap the END point of the swipe"
        else -> "Tap the position to capture"
    }

    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.30f * scrim.value))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    haptics.confirm()
                    viewModel.resolveMacroPickAt(offset.x, offset.y)
                }
            }
            .semantics { contentDescription = "macro_pick_overlay" }
    ) {
        Surface(
            color = extras.background,
            shape = RoundedCornerShape(extras.radius.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = bannerDrop.value
                    alpha = scrim.value
                }
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pick on page",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = extras.textPrimary
                    )
                    Text(
                        instruction,
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.textSecondary
                    )
                }
                TextButton(onClick = {
                    haptics.tick()
                    viewModel.cancelMacroPick()
                }) {
                    Text("Cancel", color = extras.primary)
                }
            }
        }
    }
}
