@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingSwitchRow
import kotlinx.coroutines.launch

/**
 * AI Agent settings — a DEDICATED ACTIVITY (own window, own back stack entry,
 * proper IME handling) instead of an in-browser route:
 *
 *   1. Provider management — add/edit providers in [AgentProviderEditorActivity]
 *      (another activity), select the default, delete.
 *   2. Agent behavior — floating-button visibility (hidden by default),
 *      confirm-actions, page context, temperature, step budget, system prompt.
 *   3. Data & privacy — clear all agent chats.
 *
 * Runs in the DEFAULT process (no WebView here). Every write lands in the
 * shared Room database, so the live agent in the ':browser' process updates
 * instantly via multi-instance invalidation.
 */
class AgentSettingsActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        controller = AgentSettingsController(application, intent.getStringExtra(EXTRA_PROFILE_ID))
        controller.start()
        setContent {
            RoomBrowserTheme {
                AgentSettingsRoot(
                    controller = controller,
                    onAddProvider = { launchEditor(null) },
                    onEditProvider = { provider -> launchEditor(provider.id) },
                    onClose = { finish() }
                )
            }
        }
    }

    private fun launchEditor(id: Long?) {
        val intent = Intent(this, AgentProviderEditorActivity::class.java)
        if (id != null) intent.putExtra(AgentProviderEditorActivity.EXTRA_PROVIDER_ID, id)
        startActivity(intent)
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(from: Activity, profileId: String?) {
            from.startActivity(Intent(from, AgentSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        /** Convenience for callers holding only a context (e.g. Compose). */
        fun launch(context: Context, profileId: String?) {
            val intent = Intent(context, AgentSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun AgentSettingsRoot(
    controller: AgentSettingsController,
    onAddProvider: () -> Unit,
    onEditProvider: (AgentProviderEntity) -> Unit,
    onClose: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmClearSessions by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            notice = null
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics).
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic, nothing overlaps the nav buttons.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("AI Agent Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Above the navigation bar (3-button Back/Home/Recents or
                // gesture hint) and beside display cutouts in landscape.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .verticalScroll(rememberScrollState())
        ) {
            // ================= Provider management =================
            SectionHeader("AI Provider (OpenAI-compatible)")
            if (controller.providers.isEmpty()) {
                EmptyState(
                    title = "No provider configured",
                    subtitle = "Add Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio or any custom endpoint."
                )
            }
            controller.providers.forEach { provider ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEditProvider(provider) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = controller.settings.defaultProviderId == provider.id,
                        onClick = {
                            controller.setDefault(provider, provider.defaultModel)
                            notice = "Default: ${provider.name}"
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                            if (provider.protocol == AgentProviderEntity.PROTOCOL_OPENCODE) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "OpenCode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            "${provider.baseUrl} · ${provider.defaultModel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { onEditProvider(provider) },
                        modifier = Modifier.semantics { contentDescription = "edit_provider_${provider.id}" }
                    ) { Icon(Icons.Filled.Edit, contentDescription = null) }
                    IconButton(
                        onClick = {
                            scope.launch {
                                controller.deleteProvider(provider.id)
                                notice = "Removed ${provider.name}"
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "delete_provider_${provider.id}" }
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Delete provider") }
                }
            }
            Button(
                onClick = onAddProvider,
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
            val defaultProvider = controller.activeProvider
            SettingActionRow(
                title = "Provider · model",
                subtitle = if (defaultProvider == null) "Not set" else "${defaultProvider.name} · ${controller.activeModel ?: defaultProvider.defaultModel}",
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
                title = "Show AI Agent button",
                subtitle = "The floating agent button on the browser screen — hidden by default; the agent stays reachable from the page menu",
                checked = controller.settings.showAgentButton,
                onCheckedChange = { checked ->
                    controller.updateSettings { s -> s.copy(showAgentButton = checked) }
                }
            )
            SettingSwitchRow(
                title = "Confirm actions",
                subtitle = "Ask for Allow/Deny before the agent clicks, types or submits",
                checked = controller.settings.confirmActions,
                onCheckedChange = { checked -> controller.updateSettings { s -> s.copy(confirmActions = checked) } }
            )
            SettingSwitchRow(
                title = "Include current page by default",
                subtitle = "Attach a page snapshot to the first message of each turn",
                checked = controller.settings.includePageContext,
                onCheckedChange = { checked -> controller.updateSettings { s -> s.copy(includePageContext = checked) } }
            )

            SliderRow(
                label = "Temperature",
                value = controller.settings.temperature.toFloat(),
                valueLabel = controller.settings.temperature.toString(),
                rangeStart = 0f,
                rangeEnd = 1f,
                steps = 9,
                onCommit = { controller.updateSettings { s -> s.copy(temperature = (it * 10).toInt() / 10.0) } }
            )
            SliderRow(
                label = "Max steps per turn",
                value = controller.settings.maxSteps.toFloat(),
                valueLabel = controller.settings.maxSteps.toString(),
                rangeStart = 5f,
                rangeEnd = 50f,
                steps = 8,
                onCommit = { controller.updateSettings { s -> s.copy(maxSteps = it.toInt()) } }
            )

            SystemPromptSection(controller)

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
            SettingActionRow(
                title = "Delete all agent chats",
                subtitle = "Removes the chat history of every profile (providers are kept)",
                onClick = { confirmClearSessions = true }
            )
            Spacer(Modifier.height(48.dp))
        }
    }

    if (confirmClearSessions) {
        AlertDialog(
            onDismissRequest = { confirmClearSessions = false },
            title = { Text("Delete agent chats?") },
            text = { Text("This permanently deletes all agent chats on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearAllSessions()
                    confirmClearSessions = false
                    notice = "Agent chats deleted"
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClearSessions = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueLabel: String,
    rangeStart: Float,
    rangeEnd: Float,
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
            valueRange = rangeStart..rangeEnd,
            steps = steps
        )
    }
}

@Composable
private fun SystemPromptSection(controller: AgentSettingsController) {
    var prompt by remember(controller.settings.systemPromptOverride) {
        mutableStateOf(controller.settings.systemPromptOverride ?: "")
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("System prompt (optional override)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Leave empty to use the built-in browsing-agent prompt.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "agent_system_prompt" },
            placeholder = { Text("Built-in prompt (browsing rules, ref usage, sources…)") },
            minLines = 3
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                controller.updateSettings { it.copy(systemPromptOverride = prompt.trim().ifBlank { null }) }
            }) { Text("Apply") }
            TextButton(onClick = {
                prompt = ""
                controller.updateSettings { it.copy(systemPromptOverride = null) }
            }) { Text("Reset to built-in") }
        }
    }
}
