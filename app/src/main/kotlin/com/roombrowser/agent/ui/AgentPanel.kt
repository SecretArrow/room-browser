@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentEntry
import com.roombrowser.agent.BrowserAgentController
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentTools

/**
 * The floating AI Agent panel — the agent-mode chat experience layered over
 * the live browser:
 *
 *  - COLLAPSED: a status pill (bottom-right) that streams live progress
 *    ("clicking [12] Sign in…") while the page stays visible behind it
 *  - EXPANDED: a 72%-height panel with the conversation, tool-step cards,
 *    streaming answers, approvals and the composer
 *
 * The user keeps seeing the browser being driven autonomously — the same
 * interaction model as chat.z.ai's agent mode, adapted to a phone browser.
 */
@Composable
fun AgentPanelHost(
    viewModel: BrowserViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit
) {
    val agent = viewModel.agent

    // Defensive cross-process re-sync: a provider configured in the settings
    // activities (default process) must be visible here immediately, even if
    // Room's multi-instance invalidation ping was lost on a slow filesystem.
    // Fires BOTH when the panel expands AND when the browser resumes while
    // the panel is already open (returning from the settings activities).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumeCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(expanded, resumeCount) {
        if (expanded) agent.refreshProviders()
    }

    Box(Modifier.fillMaxSize()) {
        if (expanded) {
            BackHandler { onExpandedChange(false) }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    AgentPanelHeader(
                        agent = agent,
                        onCollapse = { onExpandedChange(false) },
                        onNewSession = { agent.newSession() },
                        onOpenSessions = onOpenSessions,
                        onOpenSettings = onOpenSettings
                    )
                    AgentConversation(
                        agent = agent,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    AgentComposer(
                        agent = agent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                    )
                }
            }
        } else if (agent.settings.showAgentButton || agent.running) {
            // The floating button is OPT-IN (hidden by default — see
            // "Show AI Agent button" in Browser/AI settings). While a task
            // is actively running the pill always shows, so live progress
            // stays visible; it hides again when the turn finishes.
            AgentStatusPill(
                agent = agent,
                onClick = { onExpandedChange(true) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 10.dp)
            )
        }
    }
}

// ------------------------------------------------------------------- pill

@Composable
private fun AgentStatusPill(
    agent: BrowserAgentController,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.semantics { contentDescription = "AI Agent" }
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            val status = when {
                agent.running -> agent.statusLine ?: "Working…"
                agent.activeProvider == null -> "Set up Agent"
                else -> "Agent"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            if (agent.running) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ------------------------------------------------------------------- header

@Composable
private fun AgentPanelHeader(
    agent: BrowserAgentController,
    onCollapse: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showModelPicker by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Room Agent", style = MaterialTheme.typography.titleMedium)
            val provider = agent.activeProvider
            val modelLine = when {
                provider == null -> "No provider configured"
                else -> "${provider.name} · ${agent.activeModel ?: provider.defaultModel}"
            }
            Text(
                modelLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clickable(enabled = provider != null) { showModelPicker = true }
                    .semantics { contentDescription = "agent_model" }
            )
        }
        IconButton(onClick = onNewSession, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New agent chat")
        }
        IconButton(onClick = onOpenSessions, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.History, contentDescription = "Agent chat history")
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = "Agent settings")
        }
        IconButton(onClick = onCollapse, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize agent panel")
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(agent = agent, onDismiss = { showModelPicker = false })
    }
}

// ------------------------------------------------------------------- conversation

@Composable
private fun AgentConversation(
    agent: BrowserAgentController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val entries = agent.entries

    // Live auto-scroll: instant while streaming, animated for new entries.
    LaunchedEffect(entries.size, entries.lastOrNull()) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (agent.activeProvider == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No AI provider configured", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add an OpenAI-compatible provider (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio or a custom endpoint). " +
                            "The model list is fetched automatically from the provider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = "agent_configure" }
                    ) { Text("Configure providers") }
                }
            } else {
                Text(
                    "Ask the agent to browse autonomously — e.g.\n\"Open example.com and summarize it\", \"Search for Kotlin coroutines and open the top result\",\nor \"Like the visible posts about AI on this feed\", \"Reply to this thread: thanks!\", \"Post: hello world\".\nTasks keep running in the background (see the notification) when you leave the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(entries, key = { index, _ -> index }) { _, entry ->
            when (entry) {
                is AgentEntry.User -> UserBubble(entry)
                is AgentEntry.Assistant -> AssistantMessage(entry)
                is AgentEntry.Tool -> ToolCard(entry)
                is AgentEntry.Notice -> NoticeLine(entry)
            }
        }
        agent.approval?.let { approval -> item { ApprovalCard(approval, agent) } }
    }
}

@Composable
private fun UserBubble(entry: AgentEntry.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AssistantMessage(entry: AgentEntry.Assistant) {
    Column(Modifier.fillMaxWidth()) {
        if (entry.thinking.isNotBlank()) {
            ThinkingBlock(entry.thinking)
            Spacer(Modifier.height(6.dp))
        }
        val text = entry.text + if (entry.streaming) " ▍" else ""
        if (text.isNotBlank()) {
            MarkdownText(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String) {
    var open by rememberSaveable(thinking) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "Hide thought process" else "Thought process",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (open) {
            Text(
                thinking,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ToolCard(entry: AgentEntry.Tool) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            val icon = toolIcon(entry.name)
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (entry.running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        if (entry.ok) Icons.Filled.Check else Icons.Filled.ErrorOutline,
                        contentDescription = if (entry.ok) "step done" else "step failed",
                        tint = if (entry.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.summary.isNotBlank() && !entry.running) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun toolIcon(name: String): ImageVector = when (name) {
    AgentTools.NAVIGATE, AgentTools.SEARCH_WEB -> Icons.Filled.Public
    AgentTools.READ_PAGE -> Icons.Filled.Description
    AgentTools.CLICK -> Icons.Filled.TouchApp
    AgentTools.FILL_INPUT -> Icons.Filled.Edit
    AgentTools.PRESS_ENTER -> Icons.Filled.KeyboardReturn
    AgentTools.SCROLL -> Icons.Filled.ArrowDownward
    AgentTools.GO_BACK -> Icons.AutoMirrored.Filled.ArrowBack
    AgentTools.OPEN_NEW_TAB -> Icons.Filled.Add
    AgentTools.LIST_TABS, AgentTools.SWITCH_TAB -> Icons.Filled.Tab
    AgentTools.CLOSE_TAB -> Icons.Filled.Close
    else -> Icons.Filled.Search
}

@Composable
private fun NoticeLine(entry: AgentEntry.Notice) {
    Text(
        entry.text,
        style = MaterialTheme.typography.labelMedium,
        color = if (entry.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun ApprovalCard(approval: com.roombrowser.agent.AgentApproval, agent: BrowserAgentController) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Confirm agent action", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(approval.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { agent.respondApproval(true) }) { Text("Allow") }
                OutlinedButton(onClick = { agent.respondApproval(false) }) { Text("Deny") }
            }
        }
    }
}

// ------------------------------------------------------------------- composer

@Composable
private fun AgentComposer(agent: BrowserAgentController, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    var includePage by rememberSaveable { mutableStateOf(agent.settings.includePageContext) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = includePage,
                    onClick = { includePage = !includePage },
                    label = { Text("Include page") },
                    leadingIcon = {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask the agent to browse…") },
                    maxLines = 3,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            agent.send(input, includePage)
                            input = ""
                        }
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (agent.running) {
                    FilledIconButton(
                        onClick = { agent.stop() },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "agent_stop" }
                    ) { Icon(Icons.Filled.Stop, contentDescription = null) }
                } else {
                    FilledIconButton(
                        onClick = {
                            agent.send(input, includePage)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "agent_send" }
                    ) { Icon(Icons.Filled.Send, contentDescription = null) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- model picker

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(agent: BrowserAgentController, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(agent.activeProvider) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }

    LaunchedEffect(selected) {
        val provider = selected ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            models = agent.modelsFor(provider)
        } catch (t: Throwable) {
            models = emptyList()
            error = t.message ?: "could not fetch models"
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Select model", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Providers are configured in Agent settings; model lists come from the provider's /models endpoint.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (agent.providers.isEmpty()) {
                Text("No providers configured yet.")
            }
            agent.providers.forEach { provider ->
                ProviderPickRow(
                    provider = provider,
                    selected = selected?.id == provider.id,
                    isDefault = agent.settings.defaultProviderId == provider.id,
                    onClick = { selected = provider }
                )
            }

            Spacer(Modifier.height(12.dp))
            when {
                selected == null -> Unit
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetching models from ${selected!!.name}…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    error?.let {
                        Text(
                            "Could not fetch the model list ($it). Type a model id manually:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (models.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            models.forEach { model ->
                                FilterChip(
                                    selected = model == agent.activeModel,
                                    onClick = {
                                        agent.setDefault(selected!!, model)
                                        onDismiss()
                                    },
                                    label = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            label = { Text("Model id (manual)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (manual.isNotBlank() && selected != null) {
                                    agent.setDefault(selected!!, manual.trim())
                                    onDismiss()
                                }
                            }
                        ) { Text("Use") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderPickRow(
    provider: AgentProviderEntity,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                provider.name + if (isDefault) "  (default)" else "",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                provider.baseUrl,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            provider.defaultModel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ------------------------------------------------------------------- markdown-lite

/**
 * Lightweight markdown rendering for agent answers: **bold**, `code`,
 * ### headings and "- " bullets. Enough for chat replies without a
 * full markdown dependency.
 */
@Composable
fun MarkdownText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    val annotated = remember(text) { renderMarkdown(text) }
    androidx.compose.material3.Text(
        text = annotated,
        style = style,
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = androidx.compose.ui.graphics.Color(0x14808080)
    )
    text.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trimStart()
        when {
            line.startsWith("### ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(line.removePrefix("### ").trim())
                pop()
            }
            line.startsWith("## ") || line.startsWith("# ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(line.trimStart('#').trim())
                pop()
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                append("• ")
                appendInlineStyles(line.substring(2), codeStyle)
            }
            else -> appendInlineStyles(line, codeStyle)
        }
        if (index < text.lines().lastIndex) append('\n')
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineStyles(
    line: String,
    codeStyle: SpanStyle
) {
    var i = 0
    while (i < line.length) {
        val boldStart = line.indexOf("**", i)
        val codeStart = line.indexOf('`', i)
        when {
            boldStart >= 0 && (codeStart < 0 || boldStart < codeStart) -> {
                append(line.substring(i, boldStart))
                val end = line.indexOf("**", boldStart + 2)
                if (end < 0) {
                    append(line.substring(boldStart))
                    return
                }
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(line.substring(boldStart + 2, end))
                pop()
                i = end + 2
            }
            codeStart >= 0 -> {
                append(line.substring(i, codeStart))
                val end = line.indexOf('`', codeStart + 1)
                if (end < 0) {
                    append(line.substring(codeStart))
                    return
                }
                pushStyle(codeStyle)
                append(line.substring(codeStart + 1, end))
                pop()
                i = end + 1
            }
            else -> {
                append(line.substring(i))
                return
            }
        }
    }
}
