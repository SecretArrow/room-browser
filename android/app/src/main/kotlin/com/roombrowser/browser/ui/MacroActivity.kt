package com.roombrowser.browser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.roombrowser.browser.BrowserSession
import com.roombrowser.browser.MacroRunRequest
import com.roombrowser.RoomBrowserApp
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.data.db.MacroEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.domain.macro.MacroDefinition
import com.roombrowser.domain.macro.MacroRisk
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.LocalRoomExtras
import kotlinx.coroutines.launch

/**
 * The Macro Library — this profile's recorded automations.
 *
 * Its OWN activity in the DEFAULT process, exactly like the notes screen: no
 * WebView, no engine state, and the Room rows arrive through multi-instance
 * invalidation. "Run" is the one action that leaves the activity: it binds
 * launch variables, warns on a risky-looking macro, then hands the macro to
 * the live browser process (BrowserSession) and finishes, so the user watches
 * the playback HUD over the page it is running on.
 */
class MacroActivity : ComponentActivity() {

    private var editorMessage by mutableStateOf<String?>(null)

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportRequest = uri
            doExport(com.roombrowser.domain.model.ProfileId(profileIdValue!!), graphHolder!!)
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importRequest = uri
            doImport(com.roombrowser.domain.model.ProfileId(profileIdValue!!), graphHolder!!)
        }
    }

    // Late fields so the launchers' callbacks can reach the profile + graph
    // (registerForAdapterFactory runs before onCreate assigns them).
    private var exportRequest by mutableStateOf<Uri?>(null)
    private var importRequest by mutableStateOf<Uri?>(null)
    private var profileIdValue: String? = null
    private var graphHolder: com.roombrowser.RoomBrowserApp? = null

    private fun doExport(profileId: com.roombrowser.domain.model.ProfileId, graph: com.roombrowser.RoomBrowserApp) {
        lifecycleScope.launch {
            val rows = runCatching { graph.graph.browserRepo.macros(profileId) }.getOrDefault(emptyList())
            val uri = exportRequest ?: return@launch
            exportRequest = null
            val exported = MacroExportWriter.writeAll(rows)
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(exported.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            editorMessage = if (ok) "Exported ${rows.size} macro(s)" else "Export failed"
        }
    }

    private fun doImport(profileId: com.roombrowser.domain.model.ProfileId, graph: com.roombrowser.RoomBrowserApp) {
        lifecycleScope.launch {
            val uri = importRequest ?: return@launch
            importRequest = null
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text == null) {
                editorMessage = "Import failed"
                return@launch
            }
            val imported = MacroExportReader.readAll(text)
            if (imported.isEmpty()) {
                editorMessage = "No macros found in that file"
                return@launch
            }
            var saved = 0
            for (def in imported) {
                runCatching {
                    graph.graph.browserRepo.saveMacro(profileId, def.copy(id = ""))
                    saved++
                }
            }
            editorMessage = "Imported $saved macro(s)"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            finish()
            return
        }
        this.profileIdValue = profileIdValue
        this.graphHolder = application as RoomBrowserApp
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val editor = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            editorMessage = result.data?.getStringExtra(MacroEditorActivity.EXTRA_RESULT_MESSAGE)
        }

        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                MacroLibraryRoot(
                    activity = this,
                    profileId = profileId,
                    profileName = profileName,
                    repo = graph.graph.browserRepo,
                    editorMessage = editorMessage,
                    onEditorMessageShown = { editorMessage = null },
                    onAdd = { MacroEditorActivity.launch(this, profileId.value, null) },
                    onEdit = { MacroEditorActivity.launch(this, profileId.value, it.id) },
                    onExport = { exportLauncher.launch("room-macros.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json")) },
                    onClose = { finish() },
                    launchEditor = { editor.launch(it) }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        /** What the library asks the browser to run; the browser answers here. */
        const val EXTRA_RUN_MACRO_ID = "run_macro_id"

        fun launch(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, MacroActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

/** One export file: every macro of one profile, definitions and all. */
object MacroExportWriter {
    fun writeAll(rows: List<MacroEntity>): String {
        val sb = StringBuilder("[\n")
        rows.forEachIndexed { i, row ->
            if (i > 0) sb.append(",\n")
            sb.append(row.definitionJson)
        }
        sb.append("\n]\n")
        return sb.toString()
    }
}

object MacroExportReader {
    /** Parses a JSON array of macro definitions; a bad file answers empty. */
    fun readAll(text: String): List<MacroDefinition> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) {
            // A single exported definition is accepted too.
            return listOfNotNull(MacroCodec.decode(trimmed))
        }
        return runCatching {
            val arr = org.json.JSONArray(trimmed)
            (0 until arr.length()).mapNotNull { i ->
                MacroCodec.decode(arr.getJSONObject(i).toString())
            }
        }.getOrDefault(emptyList())
    }
}

private val MacroCodec
    get() = com.roombrowser.domain.macro.MacroCodec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroLibraryRoot(
    activity: Activity,
    profileId: ProfileId,
    profileName: String,
    repo: BrowserRepository,
    editorMessage: String?,
    onEditorMessageShown: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (MacroEntity) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
    launchEditor: (Intent) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val macros by repo.observeMacros(profileId).collectAsState(initial = emptyList())
    var search by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<MacroEntity?>(null) }
    var duplicateTarget by remember { mutableStateOf<MacroEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MacroEntity?>(null) }
    var runTarget by remember { mutableStateOf<MacroEntity?>(null) }

    LaunchedEffect(editorMessage) {
        val message = editorMessage ?: return@LaunchedEffect
        onEditorMessageShown()
        snackbarHostState.showSnackbar(message)
    }

    fun delete(macro: MacroEntity) {
        scope.launch {
            runCatching { repo.deleteMacroFor(profileId, macro.id) }
                .onSuccess { snackbarHostState.showSnackbar("Macro deleted") }
                .onFailure { snackbarHostState.showSnackbar("Could not delete macro") }
        }
    }

    fun duplicate(macro: MacroEntity) {
        scope.launch {
            val def = repo.macro(profileId, macro.id) ?: return@launch
            runCatching {
                repo.saveMacro(
                    profileId,
                    def.copy(id = "", name = "${def.name} (copy)")
                )
            }.onSuccess { snackbarHostState.showSnackbar("Duplicated as \"${macro.name} (copy)\"") }
                .onFailure { snackbarHostState.showSnackbar("Could not duplicate") }
        }
    }

    val visible = macros.filter { m ->
        search.isBlank() ||
            m.name.contains(search, ignoreCase = true) ||
            m.description.contains(search, ignoreCase = true) ||
            m.category.contains(search, ignoreCase = true)
    }
    val grouped = visible.groupBy { it.category }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { Text("Macro") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close macros" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onImport,
                        modifier = Modifier.semantics { contentDescription = "Import macros" }
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                    }
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.semantics { contentDescription = "Export macros" }
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                    }
                    IconButton(
                        onClick = onAdd,
                        modifier = Modifier.semantics { contentDescription = "New macro" }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search macros") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "macro_search_field" }
            )
            if (visible.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("⚙️", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (macros.isEmpty()) "No macros yet" else "Nothing matches \"$search\"",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (macros.isEmpty()) {
                            "Record a macro from the browser's Macro menu — it runs on the tab " +
                                "you record it on, for \"${profileName.ifBlank { "this profile" }}\"."
                        } else "Try a different search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (macros.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Create manually")
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    grouped.forEach { (category, rows) ->
                        item(key = "cat-$category") {
                            Text(
                                category.ifBlank { "General" },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 20.dp, end = 16.dp, top = 12.dp, bottom = 2.dp
                                )
                            )
                        }
                        items(rows, key = { it.id }) { macro ->
                            MacroRow(
                                macro = macro,
                                onOpen = { onEdit(macro) },
                                onRun = { runTarget = macro },
                                onRename = { renameTarget = macro },
                                onDuplicate = { duplicateTarget = macro },
                                onDelete = { deleteTarget = macro }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- rename ----
    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename macro") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.semantics { contentDescription = "macro_rename_field" }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleaned = name.trim()
                        renameTarget = null
                        if (cleaned.isNotBlank() && cleaned != target.name) {
                            scope.launch {
                                val def = repo.macro(profileId, target.id) ?: return@launch
                                repo.saveMacro(profileId, def.copy(name = cleaned), target.id)
                                snackbarHostState.showSnackbar("Renamed")
                            }
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ---- duplicate ----
    duplicateTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { duplicateTarget = null },
            title = { Text("Duplicate macro?") },
            text = { Text("A copy of \"${target.name}\" will be added to this profile's library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        duplicateTarget = null
                        duplicate(target)
                    }
                ) { Text("Duplicate") }
            },
            dismissButton = {
                TextButton(onClick = { duplicateTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ---- delete ----
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete macro?") },
            text = {
                Text(
                    "Delete \"${target.name.ifBlank { "this macro" }}\"? " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        delete(target)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ---- run (variables + risk warning) ----
    runTarget?.let { target ->
        RunMacroDialog(
            repo = repo,
            profileId = profileId,
            macro = target,
            onDismiss = { runTarget = null },
            onRun = { variables ->
                runTarget = null
                // Hand the run to the LIVE browser process: the browser owns
                // the engine, and the macro must run on the tab the user
                // sees, not inside a library window.
                val browser = BrowserSession.current()
                if (browser == null) {
                    scope.launch { snackbarHostState.showSnackbar("Open the browser first, then run the macro") }
                } else {
                    browser.macroRunPending = MacroRunRequest(
                        macroId = target.id,
                        variables = variables
                    )
                    activity.finish()
                }
            }
        )
    }
}

/**
 * The launch dialog: binds variables (when the macro declares any) and
 * shows the risk warning for macros that look transactional — a macro that
 * can spend, send or delete must be confirmed before it runs.
 */
@Composable
private fun RunMacroDialog(
    repo: BrowserRepository,
    profileId: ProfileId,
    macro: MacroEntity,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit
) {
    var def by remember(macro.id) {
        mutableStateOf<MacroDefinition?>(null)
    }
    LaunchedEffect(macro.id) {
        def = repo.macro(profileId, macro.id)
    }
    val loaded = def ?: run {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(macro.name) },
            text = { Text("Loading…") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
        return
    }
    val values = remember(loaded.id) {
        mutableStateMapOf(*loaded.variables.map { it.name to it.defaultValue }.toTypedArray())
    }
    val risky = remember(loaded.id) { MacroRisk.isRisky(loaded) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (risky) "Run \"${loaded.name}\"?" else loaded.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (loaded.description.isNotBlank()) {
                    Text(loaded.description)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "${loaded.steps.size} steps • category: ${loaded.category.ifBlank { "General" }}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (risky) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "⚠️ This macro looks like it performs an important action " +
                            "(a submit, a payment or a delete). It will act on the CURRENT tab " +
                            "of this profile. Review before running.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (loaded.variables.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Inputs", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    loaded.variables.forEach { v ->
                        OutlinedTextField(
                            value = values[v.name] ?: "",
                            onValueChange = { values[v.name] = it },
                            label = { Text(v.name) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(values.toMap()) },
                modifier = Modifier.semantics { contentDescription = "run_macro_confirm" }
            ) { Text("Run on this tab") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MacroRow(
    macro: MacroEntity,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        withGradient = false
    ) {
        Row(
            Modifier
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(macro.icon.ifBlank { "⚡" }, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    macro.name.ifBlank { "(untitled macro)" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary
                )
                if (macro.description.isNotBlank()) {
                    Text(
                        macro.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    android.text.format.DateUtils.getRelativeTimeSpanString(macro.updatedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary
                )
            }
            IconButton(
                onClick = onRun,
                modifier = Modifier.semantics { contentDescription = "Run macro ${macro.name}" }
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = extras.icon)
            }
            IconButton(
                onClick = onRename,
                modifier = Modifier.semantics { contentDescription = "Rename macro" }
            ) {
                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null, tint = extras.icon)
            }
            IconButton(
                onClick = onDuplicate,
                modifier = Modifier.semantics { contentDescription = "Duplicate macro" }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = extras.icon)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Delete macro" }
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = extras.icon)
            }
        }
    }
}
