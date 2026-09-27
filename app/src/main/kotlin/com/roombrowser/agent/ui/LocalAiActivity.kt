@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.LocalAiController
import com.roombrowser.domain.agent.LocalAiBackup
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.OllamaModelInfo
import com.roombrowser.domain.agent.OllamaModelPreset
import com.roombrowser.domain.agent.OllamaModelPresets
import com.roombrowser.domain.agent.OllamaPresetTier
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Local AI (Ollama) manager — a DEDICATED ACTIVITY (own window, own back
 * stack entry, own IME handling), exactly like the other agent screens:
 *
 *   1. Connection — Ollama server address (Termux on this phone or a PC in
 *      the LAN), connect + live status, collapsible setup guide.
 *   2. Installed models — the server's /api/tags list; "Use in chat" makes
 *      a native-Ollama provider for the model, delete asks first.
 *   3. Downloads — live /api/pull progress with pause / resume / cancel.
 *   4. Model catalog — curated presets that run well on phones, grouped in
 *      tiers and matched against the device's RAM.
 *   5. Performance — GPU layers / CPU threads / context window / keep-alive
 *      (applied to native-Ollama provider chats).
 *   6. Import / Export — a small JSON manifest of the whole setup (SAF).
 *
 * HONEST architecture: Room Browser is the MANAGEMENT CLIENT. The models are
 * downloaded TO the Ollama server (Termux on this phone, or a PC on the LAN)
 * — no multi-GB binaries are ever bundled into the APK, and every byte stays
 * on the local network. Pause stops THIS APP's download view; the server
 * keeps already-fetched layers, and Resume re-attaches to continue from the
 * last completed layer.
 *
 * Runs in the DEFAULT process (no WebView here). Tuning and the model list
 * are persisted by [LocalAiController] and shared with the ':browser'
 * process agent through the app-state store.
 */
class LocalAiActivity : ComponentActivity() {

    private lateinit var controller: LocalAiController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        controller = LocalAiController(application)
        controller.start()
        setContent {
            RoomBrowserTheme {
                LocalAiRoot(
                    controller = controller,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(from: Activity, profileId: String?) {
            from.startActivity(Intent(from, LocalAiActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        /** Convenience for callers holding only a context (e.g. Compose). */
        fun launch(context: Context, profileId: String?) {
            val intent = Intent(context, LocalAiActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun LocalAiRoot(
    controller: LocalAiController,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            notice = null
        }
    }

    // Model list refresh hooks — the installed list must follow the server
    // without the user tapping Refresh: once when the connection comes
    // online, and once more after any download finishes (the controller
    // keeps SUCCESS rows in #downloads until they are cleared, so the
    // catalog entry flips to "Installed" right after a completed pull).
    LaunchedEffect(controller.connection) {
        if (controller.connection is LocalAiController.ConnectionState.Online) {
            controller.refreshInstalled()
        }
    }
    val finishedTags = controller.downloads.entries
        .filter { it.value.phase == LocalAiController.Phase.SUCCESS }
        .map { it.key }
        .toSet()
    LaunchedEffect(finishedTags) {
        if (finishedTags.isNotEmpty()) controller.refreshInstalled()
    }

    // SAF export: writes the JSON manifest built by the controller.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(LocalAiBackup.encode(controller.buildExportManifest()).toByteArray())
                    }
                }
                    .onSuccess { notice = "Setup exported" }
                    .onFailure { notice = "Export failed: ${it.message}" }
            }
        }
    }
    // SAF import: reads the picked file and re-applies the setup (queues the
    // same models for download on the server).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text == null) {
                    notice = "Import failed: could not read the file"
                } else {
                    controller.importBackup(text) { queued ->
                        notice = if (queued >= 0) "Import OK — $queued model download(s) queued"
                        else "Not a valid Local AI backup"
                    }
                }
            }
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
                title = { Text("Local AI (Ollama)") },
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
            // ================= Connection =================
            SectionHeader("Connection")
            var hostInput by remember(controller.tuning.host) {
                mutableStateOf(controller.tuning.host)
            }
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("Ollama server address") },
                supportingText = {
                    Text("Termux on this phone: http://localhost:11434 · PC on your LAN: http://192.168.x.x:11434")
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "localai_host_field" }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        // The typed host becomes the persistent tuning host,
                        // then the connection (and model list) is checked.
                        controller.saveTuning(controller.tuning.copy(host = hostInput.trim()))
                        controller.checkConnection()
                    },
                    modifier = Modifier.semantics { contentDescription = "localai_connect" }
                ) { Text("Connect") }
                Spacer(Modifier.width(12.dp))
                ConnectionStatusText(controller.connection, Modifier.weight(1f))
            }
            SetupGuideCard()

            // ================= Installed models =================
            SectionHeader("Installed models")
            TextButton(
                onClick = { controller.refreshInstalled() },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "localai_refresh" }
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "localai_installed_list" }
            ) {
                if (controller.installed.isEmpty()) {
                    EmptyState(
                        title = "No models yet",
                        subtitle = "Install one from the catalog below — or import a previous setup."
                    )
                } else {
                    controller.installed.forEach { model ->
                        InstalledModelRow(
                            model = model,
                            onUseInChat = {
                                controller.useInChat(model.name) { _, message -> notice = message }
                            },
                            onDelete = { confirmDelete = model.name }
                        )
                    }
                }
            }

            // ================= Downloads =================
            if (controller.downloads.isNotEmpty()) {
                SectionHeader("Downloads")
                Text(
                    "Pausing stops this app's download view. The Ollama server keeps layers it " +
                        "already fetched — Resume re-attaches and continues from the last completed layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                controller.downloads.forEach { (tag, state) ->
                    DownloadRow(
                        tag = tag,
                        state = state,
                        onPause = { controller.pause(tag) },
                        onResume = { controller.resume(tag) },
                        onCancel = { controller.cancelDownload(tag) }
                    )
                }
            }

            // ================= Model catalog =================
            SectionHeader("Model catalog — best for phones")
            CatalogSection(controller = controller, onNotice = { notice = it })

            // ================= Performance =================
            SectionHeader("Performance (GPU · CPU)")
            TuningSection(controller = controller, onNotice = { notice = it })

            // ================= Import / Export =================
            SectionHeader("Import / Export")
            Text(
                "The export is a small JSON manifest of your setup — server address, performance " +
                    "tuning and the model list (not the multi-GB model files). Import re-applies the " +
                    "setup and queues the same models for download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("room-browser-local-ai.json") },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "localai_export" }
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export setup")
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "localai_import" }
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import setup")
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    confirmDelete?.let { modelName ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete model?") },
            text = { Text("Removes $modelName from the Ollama server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteModel(modelName) { ok ->
                        notice = if (ok) "Deleted $modelName" else "Could not delete $modelName"
                    }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ===========================================================================
// Connection
// ===========================================================================

@Composable
private fun ConnectionStatusText(
    connection: LocalAiController.ConnectionState,
    modifier: Modifier = Modifier
) {
    val (label, isError) = when (connection) {
        is LocalAiController.ConnectionState.Idle -> "Not connected" to false
        is LocalAiController.ConnectionState.Checking -> "Checking…" to false
        // The controller already prefixes the version ("Ollama 0.5.7").
        is LocalAiController.ConnectionState.Online -> "Connected · ${connection.version}" to false
        is LocalAiController.ConnectionState.Offline -> "Offline: ${connection.reason}" to true
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (connection is LocalAiController.ConnectionState.Checking) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                connection is LocalAiController.ConnectionState.Online -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.semantics { contentDescription = "localai_status" }
        )
    }
}

@Composable
private fun SetupGuideCard() {
    var expanded by remember { mutableStateOf(false) }
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Setup guide",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse setup guide" else "Expand setup guide"
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Install Termux (F-Droid or GitHub releases) on this phone.\n" +
                        "2. Inside Termux: pkg install ollama — or a community Android build.\n" +
                        "3. Run ollama serve and leave it running.\n" +
                        "4. Tap Connect above. Models are downloaded TO the server — " +
                        "Room Browser only manages them.\n\n" +
                        "Ollama on a PC in your LAN? Start it with OLLAMA_HOST=0.0.0.0 (and allow " +
                        "port 11434 in the PC firewall), then connect to http://<pc-ip>:11434.\n" +
                        "Everything stays on your local network — no cloud, no telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ===========================================================================
// Installed models
// ===========================================================================

@Composable
private fun InstalledModelRow(
    model: OllamaModelInfo,
    onUseInChat: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            model.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        val details = listOf(
            formatBytes(model.sizeBytes),
            model.family,
            model.parameterSize,
            model.quantizationLevel
        ).filter { it.isNotBlank() }.joinToString(" · ")
        Text(
            details.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            OutlinedButton(
                onClick = onUseInChat,
                modifier = Modifier.semantics { contentDescription = "localai_use_${model.name}" }
            ) { Text("Use in chat") }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "localai_delete_${model.name}" }
            ) { Icon(Icons.Filled.Delete, contentDescription = "Delete model") }
        }
    }
}

// ===========================================================================
// Downloads
// ===========================================================================

@Composable
private fun DownloadRow(
    tag: String,
    state: LocalAiController.DownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            tag,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            state.statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        val knownTotal = state.totalBytes
        val determinate = knownTotal != null && knownTotal > 0 && state.completedBytes > 0 &&
            state.phase != LocalAiController.Phase.STARTING &&
            state.phase != LocalAiController.Phase.VERIFYING
        if (determinate && knownTotal != null) {
            LinearProgressIndicator(
                progress = { (state.completedBytes.toFloat() / knownTotal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.receivedBytes > 0 && knownTotal != null && knownTotal > 0) {
            Text(
                "${formatBytes(state.receivedBytes)} / ${formatBytes(knownTotal)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        state.error?.let {
            Text(
                "Error: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            when (state.phase) {
                LocalAiController.Phase.STARTING,
                LocalAiController.Phase.DOWNLOADING,
                LocalAiController.Phase.VERIFYING ->
                    FilledTonalButton(
                        onClick = onPause,
                        modifier = Modifier.semantics { contentDescription = "localai_pause_$tag" }
                    ) { Text("Pause") }
                LocalAiController.Phase.PAUSED,
                LocalAiController.Phase.FAILED ->
                    Button(
                        onClick = onResume,
                        modifier = Modifier.semantics { contentDescription = "localai_resume_$tag" }
                    ) { Text("Resume") }
                LocalAiController.Phase.SUCCESS ->
                    // cancelDownload also removes FINISHED rows — hence
                    // "Clear" on a SUCCESS entry calls the same method.
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics { contentDescription = "localai_clear_$tag" }
                    ) { Text("Clear") }
            }
            if (state.phase != LocalAiController.Phase.SUCCESS) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "localai_cancel_$tag" }
                ) { Text("Cancel") }
            }
        }
    }
}

// ===========================================================================
// Model catalog
// ===========================================================================

@Composable
private fun CatalogSection(
    controller: LocalAiController,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val cores = Runtime.getRuntime().availableProcessors()
    val ramGb = remember {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024L * 1024L * 1024L)
    }
    val suggestedTier = OllamaModelPresets.suggestedTierForRam(ramGb)
    Text(
        "Curated presets that run well on phones. Sizes are approximate (default 4-bit tags). " +
            "On this device: ${Build.MODEL} · $cores cores · $ramGb GB — suggested tier: ${suggestedTier.label}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    OllamaPresetTier.values().forEach { tier ->
        Text(
            tier.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp)
        )
        Text(
            tier.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        OllamaModelPresets.byTier(tier).forEach { preset ->
            PresetCard(
                preset = preset,
                installed = controller.installed.any { it.name == preset.tag },
                onInstall = {
                    controller.pull(preset.tag)
                    onNotice("Installing ${preset.label}…")
                }
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: OllamaModelPreset,
    installed: Boolean,
    onInstall: () -> Unit
) {
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (installed) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Installed") },
                        modifier = Modifier.semantics { contentDescription = "localai_installed_${preset.tag}" }
                    )
                } else {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.semantics { contentDescription = "localai_install_${preset.tag}" }
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Install")
                    }
                }
            }
            if (preset.recommended || preset.indonesianFriendly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    if (preset.recommended) {
                        FilterChip(
                            selected = true,
                            enabled = false,
                            onClick = {},
                            label = { Text("Recommended") }
                        )
                    }
                    if (preset.indonesianFriendly) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("ID-friendly") }
                        )
                    }
                }
            }
            Text(
                "${preset.params} · ~${OllamaModelPresets.formatSizeMb(preset.sizeMb)} download · " +
                    "min ${preset.minRamGb} GB RAM · ${preset.contextTokens} ctx",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                preset.strengths,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// ===========================================================================
// Performance tuning (GPU · CPU)
// ===========================================================================

@Composable
private fun TuningSection(
    controller: LocalAiController,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val cores = Runtime.getRuntime().availableProcessors()
    val ramGb = remember {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024L * 1024L * 1024L)
    }
    Text(
        "Device: ${Build.MODEL} · $cores cores · $ramGb GB RAM",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Text(
        "GPU offload (num_gpu) speeds models up when your Ollama build supports the phone's GPU " +
            "(e.g. Termux builds with OpenCL/Adreno). Standard builds ignore it and fall back to CPU " +
            "automatically. Values apply to native-Ollama provider chats.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // Local editable copy — committed by the Apply button below.
    var gpuAuto by remember(controller.tuning) { mutableStateOf(controller.tuning.gpuLayers == null) }
    var gpuLayers by remember(controller.tuning) {
        mutableStateOf((controller.tuning.gpuLayers ?: 0).coerceIn(0, 99))
    }
    var cpuAuto by remember(controller.tuning) { mutableStateOf(controller.tuning.cpuThreads == null) }
    var cpuThreads by remember(controller.tuning) {
        mutableStateOf((controller.tuning.cpuThreads ?: 4).coerceIn(1, 16))
    }
    var contextWindow by remember(controller.tuning) {
        mutableStateOf(controller.tuning.contextWindow.coerceIn(512, 16384).toFloat())
    }
    var keepAlive by remember(controller.tuning) {
        mutableStateOf(controller.tuning.keepAliveMinutes.coerceIn(0, 60).toFloat())
    }

    Text(
        "GPU layers to offload",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    SettingSwitchRow(
        title = "Auto (server decides)",
        checked = gpuAuto,
        onCheckedChange = { gpuAuto = it }
    )
    if (!gpuAuto) {
        TuningSlider(
            label = "Layers",
            value = gpuLayers.toFloat(),
            valueLabel = gpuLayers.toString(),
            rangeStart = 0f,
            rangeEnd = 99f,
            steps = 98,
            onValueChange = { gpuLayers = it.toInt() }
        )
    }

    Text(
        "CPU threads",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    SettingSwitchRow(
        title = "Auto (server decides)",
        checked = cpuAuto,
        onCheckedChange = { cpuAuto = it }
    )
    if (!cpuAuto) {
        TuningSlider(
            label = "Threads",
            value = cpuThreads.toFloat(),
            valueLabel = cpuThreads.toString(),
            rangeStart = 1f,
            rangeEnd = 16f,
            steps = 14,
            onValueChange = { cpuThreads = it.toInt() }
        )
    }

    TuningSlider(
        label = "Context window (num_ctx)",
        value = contextWindow,
        valueLabel = contextWindow.toInt().toString(),
        rangeStart = 512f,
        rangeEnd = 16384f,
        // Snaps to multiples of 512: 512, 1024, … 16384 (32 positions).
        steps = 30,
        onValueChange = { contextWindow = it }
    )
    TuningSlider(
        label = "Keep model in memory",
        value = keepAlive,
        valueLabel = "${keepAlive.toInt()} min",
        rangeStart = 0f,
        rangeEnd = 60f,
        steps = 59,
        onValueChange = { keepAlive = it }
    )

    Button(
        onClick = {
            val newTuning = LocalAiTuning(
                host = controller.tuning.host,
                gpuLayers = if (gpuAuto) null else gpuLayers,
                cpuThreads = if (cpuAuto) null else cpuThreads,
                contextWindow = contextWindow.toInt(),
                keepAliveMinutes = keepAlive.toInt()
            ).clampToSanity()
            controller.saveTuning(newTuning)
            onNotice("Performance settings applied")
        },
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "localai_apply_tuning" }
    ) { Text("Apply") }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    valueLabel: String,
    rangeStart: Float,
    rangeEnd: Float,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = rangeStart..rangeEnd,
            steps = steps
        )
    }
}

// ===========================================================================
// Helpers
// ===========================================================================

/** Human-readable byte size ("812 MB", "1.2 GB"); blank when unknown. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
