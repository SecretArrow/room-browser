package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.PageAssistantController
import com.roombrowser.domain.assistant.AskKind
import com.roombrowser.domain.assistant.PageContext
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader

/**
 * "Ask this page" — the grounded assistant sheet. Everything on it is
 * honest: without a provider it says so and offers the two setup doors;
 * while thinking it names what it is doing; a failure prints the provider's
 * own reason instead of a silent blank.
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
    var question by remember { mutableStateOf("") }
    // Every open re-reads providers/settings — a provider configured in the
    // settings screen becomes usable here immediately.
    LaunchedEffect(Unit) { controller.refresh() }

    fun ask(kind: AskKind, q: String) {
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

            when (val state = controller.state) {
                is PageAssistantController.UiState.NoProvider -> {
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
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(16.dp).height(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(state.label, style = MaterialTheme.typography.bodyMedium, color = extras.textPrimary)
                }
                is PageAssistantController.UiState.Answer -> Text(
                    state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(extras.surfaceAlt.copy(alpha = 0.5f))
                        .padding(12.dp)
                )
                is PageAssistantController.UiState.Failed -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                PageAssistantController.UiState.Idle -> {}
            }

            Spacer(Modifier.height(12.dp))

            // Quick asks. Always reachable while a provider exists — they
            // double as the retry surface after a failure.
            if (controller.state !is PageAssistantController.UiState.NoProvider) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(onClick = { ask(AskKind.SUMMARIZE, "") }, label = { Text(AskKind.SUMMARIZE.chipLabel) })
                    AssistChip(onClick = { ask(AskKind.KEY_POINTS, "") }, label = { Text(AskKind.KEY_POINTS.chipLabel) })
                    AssistChip(onClick = { ask(AskKind.EXPLAIN, "") }, label = { Text(AskKind.EXPLAIN.chipLabel) })
                    AssistChip(onClick = { ask(AskKind.TRANSLATE, "") }, label = { Text(AskKind.TRANSLATE.chipLabel) })
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
                TextButton(
                    onClick = { ask(AskKind.ASK, question) },
                    enabled = question.isNotBlank() && page != null,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("Ask") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
