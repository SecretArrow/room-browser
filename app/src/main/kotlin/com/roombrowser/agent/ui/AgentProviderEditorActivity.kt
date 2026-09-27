@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.roombrowser.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.ui.common.RoomBrowserTheme
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
 * Add / edit an AI provider — its OWN activity (own window, own IME
 * handling, own back-stack entry): manual input (name, base URL, API key),
 * presets, and live model discovery from the provider's /models endpoint.
 *
 * EXTRA_PROVIDER_ID > 0 → edit mode (loads the stored provider);
 * absent / 0 → add mode.
 */
class AgentProviderEditorActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — the
        // form never runs under the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, 0L)
        controller = AgentSettingsController(application, profileId = null)
        controller.start()
        controller.loadEditing(providerId)
        setContent {
            RoomBrowserTheme {
                if (controller.editingLoaded) {
                    ProviderEditorRoot(
                        controller = controller,
                        editing = controller.editing,
                        onDone = { finish() }
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
    }
}

// ===========================================================================
// Provider editor (add / edit) — manual input + presets + model discovery
// ===========================================================================

@Composable
private fun ProviderEditorRoot(
    controller: AgentSettingsController,
    editing: AgentProviderEntity?,
    onDone: () -> Unit
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

    Scaffold(
        // adjustResize semantics: fields, chips and the save row all ride
        // above the keyboard.
        modifier = Modifier.imePadding(),
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic on every API level.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "Add AI provider" else "Edit provider") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
                .padding(horizontal = 16.dp)
        ) {
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
                                models = controller.fetchModels(baseUrl, apiKey)
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
                            val result = controller.saveProvider(
                                id = editing?.id,
                                name = name,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                defaultModel = model
                            )
                            result.fold(
                                onSuccess = { provider ->
                                    // Make the freshly saved provider the default.
                                    controller.setDefault(provider, provider.defaultModel)
                                    onDone()
                                },
                                onFailure = { saveError = it.message ?: "could not save" }
                            )
                        }
                    },
                    enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
                ) { Text(if (editing == null) "Save provider" else "Save changes") }
                OutlinedButton(onClick = onDone) { Text("Cancel") }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
