package com.roombrowser.browser.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.PageAssistantController
import com.roombrowser.domain.assistant.AskKind
import com.roombrowser.domain.assistant.PageContext
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.RoomLoadingSpinner
import com.roombrowser.ui.common.RoomMotion
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.rememberRoomHaptics

/**
 * "Ask this page" — the grounded assistant sheet. Everything on it is
 * honest: without a provider it says so and offers the two setup doors;
 * while thinking it names what it is doing; a failure prints the provider's
 * own reason and keeps the exact ask one tap away ("Try again").
 *
 * The state area crossfades through [AnimatedContent] with the shared
 * finite fade — no endless spinners anywhere UiAutomator can see, and the
 * profile's reduced-motion setting turns the whole swap into a plain jump.
 * State transitions are keyed by state CLASS, so an answer streaming in
 * (or a longer label swap inside Thinking) does not re-trigger the fade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageAssistantSheet(
    controller: PageAssistantController,
    page: PageContext?,
    onDismiss: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenLocalAi: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val haptics = rememberRoomHaptics()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    // The last thing the user asked, kept so a failure can offer an
    // explicit one-tap retry instead of forcing a hunt through the chips.
    var lastAsk by remember { mutableStateOf<Pair<AskKind, String>?>(null) }
    // Every open re-reads providers/settings — a provider configured in the
    // settings screen becomes usable here immediately.
    LaunchedEffect(Unit) { controller.refresh() }
    // An answer landing is a "something arrived" moment in the shared
    // haptics vocabulary; failures stay quiet so an error does not feel
    // like a success.
    LaunchedEffect(controller.state) {
        if (controller.state is PageAssistantController.UiState.Answer) haptics.confirm()
    }

    fun ask(kind: AskKind, q: String) {
        lastAsk = kind to q
        haptics.tick()
        controller.ask(page, kind, q)
        question = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Ask this page")

            // The grounding chip: what the assistant is about to read.
            if (page != null) {
                Text(
                    page.title.ifBlank { page.url },
                    style = MaterialTheme.typography.titleSmall,
                    color = extras.textPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    page.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            } else {
                Text(
                    "This page has no readable text yet — let it finish loading, then ask.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = controller.state,
                transitionSpec = {
                    if (extras.reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(RoomMotion.FADE_MS)) togetherWith fadeOut(tween(RoomMotion.FADE_MS))
                },
                contentKey = { it::class },
                label = "assistantState"
            ) { state ->
                when (state) {
                    is PageAssistantController.UiState.NoProvider -> Column {
                        Text(
                            "No AI provider is configured yet. Point the Room Agent at any " +
                                "OpenAI-compatible server, a native Ollama server, or install an " +
                                "on-device model in Local AI — this assistant rides the same provider.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = extras.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(onClick = onOpenAgentSettings) { Text("Open AI settings") }
                            OutlinedButton(onClick = onOpenLocalAi) { Text("Open Local AI") }
                        }
                    }
                    is PageAssistantController.UiState.Thinking -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoomLoadingSpinner()
                        Spacer(Modifier.width(10.dp))
                        Text(state.label, style = MaterialTheme.typography.bodyMedium, color = extras.textPrimary)
                    }
                    // Solid surface, not an alpha composite: the answer is the
                    // payload of the sheet and must hold its contrast on every
                    // profile theme, including the translucent ones. One tap
                    // moves it to the clipboard — an AI answer's whole job is
                    // to be reused somewhere else.
                    is PageAssistantController.UiState.Answer -> Column {
                        Text(
                            state.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = extras.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoomCardShape)
                                .background(extras.surfaceAlt)
                                .padding(12.dp)
                        )
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(state.text))
                                haptics.tick()
                                Toast.makeText(context, "Answer copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) { Text("Copy answer") }
                    }
                    is PageAssistantController.UiState.Failed -> Column {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        if (lastAsk != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = { lastAsk?.let { ask(it.first, it.second) } },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) { Text("Try again") }
                        }
                    }
                    // First open: say what the sheet is for instead of
                    // leaving a blank hole between the grounding text and
                    // the quick-ask chips.
                    PageAssistantController.UiState.Idle -> Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(
                            "Summarize, pull the key points, simplify or translate this page — or ask it anything.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = extras.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Every answer is grounded in this page's own text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extras.textSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Quick asks. Always reachable while a provider exists — they
            // double as the retry surface after a failure. The chips sit on
            // a 48dp hit rect (M3's 32dp default fails the app's own touch
            // target rule); the glyph stays chip-sized.
            if (controller.state !is PageAssistantController.UiState.NoProvider) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { ask(AskKind.SUMMARIZE, "") },
                        label = { Text(AskKind.SUMMARIZE.chipLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    AssistChip(
                        onClick = { ask(AskKind.KEY_POINTS, "") },
                        label = { Text(AskKind.KEY_POINTS.chipLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    AssistChip(
                        onClick = { ask(AskKind.EXPLAIN, "") },
                        label = { Text(AskKind.EXPLAIN.chipLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    AssistChip(
                        onClick = { ask(AskKind.TRANSLATE, "") },
                        label = { Text(AskKind.TRANSLATE.chipLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    placeholder = { Text("Ask about this page…") },
                    singleLine = false,
                    maxLines = 3
                )
                // The primary action of the sheet: filled, full-width, and a
                // real 48dp target — a bare text button under the field
                // read as a caption, not as the way to send the question.
                Button(
                    onClick = { ask(AskKind.ASK, question) },
                    enabled = question.isNotBlank() && page != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .heightIn(min = 48.dp)
                ) { Text("Ask") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
