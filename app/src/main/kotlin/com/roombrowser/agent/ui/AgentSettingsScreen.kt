@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.BrowserAgentController
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingSwitchRow
import kotlinx.coroutines.launch

/** Well-known OpenAI-compatible provider presets (editable after selection). */
val PROVIDER_PRESETS: List<Pair<String, String>> = listOf(
    "Z.ai" to "https://api.z.ai/api/paas/v4",
    "OpenAI" to "https://api.openai.com/v1",
    "OpenRouter" to "https://openrouter.ai/api/v1",
    "Groq" to "https://api.groq.com/openai/v1",
    "DeepSeek" to "https://api.deepseek.com/v1",
    "Mistral" to "https://api.mistral.ai/v1",
    "Together" to "https://api.together.xyz/v1",
    "Ollama (this device)" to "http://localhost:11434/v1",
    "LM Studio (this device)" to "http://localhost:1234/v1",
    "Custom" to ""
)

/**
 * AI Agent settings (the "Settings menu" for the agent):
 *  1. Provider management — add/edit/delete providers with MANUAL input
 *     (name, base URL, API key) + presets; the model list is FETCHED from
 *     the provider's /models endpoint (manual entry always available).
 *  2. Default model selection.
 *  3. Agent behavior — confirm-actions, page context, temperature, step
 *     budget, system prompt.
 *  4. Data & privacy — clear sessions / all agent data.
 */
@Composable
fun AgentSettingsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val agent = viewModel.agent
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AgentProviderEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("AI Agent Settings") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
            }
        )

        // ================= Provider management =================
        SectionHeader("AI Provider (OpenAI-compatible)")
        if (agent.providers.isEmpty()) {
            EmptyState(
                title = "No provider configured",
                subtitle = "Add Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio or any custom endpoint."
            )
        }
        agent.providers.forEach { provider ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = provider }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = agent.settings.defaultProviderId == provider.id,
                    onClick = { agent.setDefault(provider, provider.defaultModel) }
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${provider.baseUrl} · ${provider.defaultModel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { editing = provider },
                    modifier = Modifier.semantics { contentDescription = "edit_provider_${provider.id}" }
                ) { Icon(Icons.Filled.Edit, contentDescription = null) }
                IconButton(
                    onClick = { scope.launch { agent.deleteProvider(provider.id) } },
                    modifier = Modifier.semantics { contentDescription = "delete_provider_${provider.id}" }
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete provider") }
            }
        }
        Button(
            onClick = { adding = true },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "add_provider" }
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add provider")
        }

        // ================= Default model =================
        SectionHeader("Default model")
        val defaultProvider = agent.activeProvider
        SettingActionRow(
            title = "Provider · model",
            subtitle = if (defaultProvider == null) "Not set" else "${defaultProvider.name} · ${agent.activeModel ?: defaultProvider.defaultModel}",
            onClick = { }
        )
        Text(
            "Tip: open the AI Agent panel and tap the provider · model line to pick a model fetched from the provider's /models list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // ================= Behavior =================
        SectionHeader("Agent behavior")
        SettingSwitchRow(
            title = "Confirm actions",
            subtitle = "Ask for Allow/Deny before the agent clicks, types or submits",
            checked = agent.settings.confirmActions,
            onCheckedChange = { agent.updateSettings { it.copy(confirmActions = it) } }
        )
        SettingSwitchRow(
            title = "Include current page by default",
            subtitle = "Attach a page snapshot to the first message of each turn",
            checked = agent.settings.includePageContext,
            onCheckedChange = { agent.updateSettings { it.copy(includePageContext = it) } }
        )

        SliderRow(
            label = "Temperature",
            value = agent.settings.temperature.toFloat(),
            valueLabel = agent.settings.temperature.toString(),
            valueRange = 0f..1f,
            steps = 9,
            onCommit = { agent.updateSettings { s -> s.copy(temperature = (it * 10).toInt() / 10.0) } }
        )
        SliderRow(
            label = "Max steps per turn",
            value = agent.settings.maxSteps.toFloat(),
            valueLabel = agent.settings.maxSteps.toString(),
            valueRange = 5f..50f,
            steps = 8,
            onCommit = { agent.updateSettings { s -> s.copy(maxSteps = it.toInt()) } }
        )

        SystemPromptSection(agent)

        // ================= Data & privacy =================
        SectionHeader("Data & privacy")
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                "Agent requests go DIRECTLY from this device to the provider you configured — " +
                    "Room Browser adds no proxy and no telemetry. API keys are encrypted with " +
                    "AndroidKeyStore and never leave the device except as the provider's own " +
                    "Authorization header. Chat sessions are stored per profile and stay local.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        var confirmClearSessions by remember { mutableStateOf(false) }
        SettingActionRow(
            title = "Delete all agent sessions",
            subtitle = "Removes the chat history of every profile (providers are kept)",
            onClick = { confirmClearSessions = true }
        )
        if (confirmClearSessions) {
            AlertDialog(
                onDismissRequest = { confirmClearSessions = false },
                title = { Text("Delete agent sessions?") },
                text = { Text("This permanently deletes all agent chats on this device.") },
                confirmButton = {
                    TextButton(onClick = {
                        agent.clearAllSessions()
                        confirmClearSessions = false
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmClearSessions = false }) { Text("Cancel") } }
            )
        }

        Spacer(Modifier.height(48.dp))
    }

    if (adding || editing != null) {
        ProviderEditorScreen(
            agent = agent,
            editing = editing,
            onClose = { adding = false; editing = null }
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueLabel: String,
    valueRange: androidx.compose.ui.unit.ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun SystemPromptSection(agent: BrowserAgentController) {
    var prompt by remember(agent.settings.systemPromptOverride) {
        mutableStateOf(agent.settings.systemPromptOverride ?: "")
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("System prompt (optional override)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Leave empty to use the built-in browsing-agent prompt.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "agent_system_prompt" },
            placeholder = { Text("Built-in prompt (browsing rules, ref usage, sources…)") },
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                agent.updateSettings { it.copy(systemPromptOverride = prompt.trim().ifBlank { null }) }
            }) { Text("Apply") }
            TextButton(onClick = {
                prompt = ""
                agent.updateSettings { it.copy(systemPromptOverride = null) }
            }) { Text("Reset to built-in") }
        }
    }
}

// ===========================================================================
// Provider editor (add / edit) — manual input + presets + model discovery
// ===========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderEditorScreen(
    agent: BrowserAgentController,
    editing: AgentProviderEntity?,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var baseUrl by remember(editing) { mutableStateOf(editing?.baseUrl ?: "") }
    var apiKey by remember(editing) { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var models by remember(editing) { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var model by remember(editing) { mutableStateOf(editing?.defaultModel ?: "") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var hasKey by remember(editing) { mutableStateOf(editing != null && editing.apiKeyEnc.isNotBlank()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text(if (editing == null) "Add AI provider" else "Edit provider") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
            }
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            // ---- Presets ----
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROVIDER_PRESETS.forEach { (label, presetUrl) ->
                    FilterChip(
                        selected = presetUrl.isNotBlank() && presetUrl == baseUrl,
                        onClick = {
                            if (presetUrl.isNotBlank()) {
                                baseUrl = presetUrl
                                if (name.isBlank()) name = label.substringBefore(" (")
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- Manual fields ----
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Provider name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_name_field" }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; models = emptyList(); fetchError = null },
                label = { Text("Base URL (OpenAI-compatible, e.g. https://api.z.ai/api/paas/v4)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_url_field" }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; hasKey = it.isNotBlank() },
                label = {
                    Text(
                        when {
                            editing != null && hasKey && apiKey.isBlank() -> "API key (kept)"
                            apiKey.isBlank() -> "API key (optional — local servers need none)"
                            else -> "API key"
                        }
                    )
                },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (keyVisible) "Hide API key" else "Show API key"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_key_field" }
            )
            Spacer(Modifier.height(12.dp))

            // ---- Fetch models ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            fetching = true
                            fetchError = null
                            try {
                                models = agent.fetchModels(baseUrl, apiKey)
                                if (models.isNotEmpty() && model !in models) model = models.first()
                            } catch (t: Throwable) {
                                fetchError = t.message ?: "fetch failed"
                            } finally {
                                fetching = false
                            }
                        }
                    },
                    enabled = baseUrl.isNotBlank() && !fetching
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fetch models")
                }
                Spacer(Modifier.width(12.dp))
                if (fetching) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Contacting provider…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            fetchError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Could not fetch models: $it\nYou can still type the model id manually below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Models returned by the provider:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.semantics { contentDescription = "provider_models_list" }
                ) {
                    models.forEach { candidate ->
                        FilterChip(
                            selected = candidate == model,
                            onClick = { model = candidate },
                            label = { Text(candidate, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model id (required)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_model_field" }
            )

            saveError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val result = agent.saveProvider(
                                id = editing?.id,
                                name = name,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                defaultModel = model
                            )
                            result.fold(
                                onSuccess = { provider ->
                                    // Make the freshly saved provider the default.
                                    agent.setDefault(provider, provider.defaultModel)
                                    onClose()
                                },
                                onFailure = { saveError = it.message ?: "could not save" }
                            )
                        }
                    },
                    enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
                ) { Text(if (editing == null) "Save provider" else "Save changes") }
                OutlinedButton(onClick = onClose) { Text("Cancel") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
