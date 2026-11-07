package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.BrowserActivity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.domain.macro.MacroCodec
import com.roombrowser.domain.macro.MacroDefinition
import com.roombrowser.domain.macro.MacroPick
import com.roombrowser.domain.macro.MacroPlayback
import com.roombrowser.domain.macro.MacroStep
import com.roombrowser.domain.macro.MacroVariable
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The Macro Editor — one macro, its steps, its variables and its playback
 * knobs. Its own activity in the DEFAULT process (same pattern as the note
 * editor: the keyboard resizes a writing surface), reading the row back
 * from the repository rather than trusting anything the intent carried.
 *
 * Steps live in a SnapshotStateList: add, delete, edit, move and duplicate
 * are list operations the UI already understands. Delay, timeout, retries,
 * value and note are per-step dialogs; speed, repeat and the error policy
 * are the playback section at the bottom.
 */
class MacroEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val macroId = intent.getStringExtra(EXTRA_MACRO_ID)
        val seedJson = intent.getStringExtra(EXTRA_SEED_JSON)
        val graph = (application as RoomBrowserApp).graph

        setContent {
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                MacroEditorRoot(
                    profileId = profileId,
                    macroId = macroId,
                    seed = seedJson?.let { MacroCodec.decode(it) },
                    repo = graph.browserRepo,
                    onSaved = { message ->
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(EXTRA_RESULT_MESSAGE, message)
                        )
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_MACRO_ID = "macro_id"
        const val EXTRA_RESULT_MESSAGE = "result_message"
        const val EXTRA_SEED_JSON = "seed_json"

        /** Edit an existing macro (id != null) or create one (id == null). */
        fun launch(context: Context, profileId: String, macroId: String?) {
            context.startActivity(
                Intent(context, MacroEditorActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_MACRO_ID, macroId)
            )
        }

        /**
         * Opens the editor on a JUST-RECORDED macro (the recorder's Stop).
         * The seed rides as JSON — the editor reads it back from the intent
         * because the recorder lives in the browser process and the editor
         * runs in the default one.
         */
        fun launchWithSeed(context: Context, profileId: String, seed: com.roombrowser.domain.macro.MacroDefinition) {
            context.startActivity(
                Intent(context, MacroEditorActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_SEED_JSON, MacroCodec.encode(seed))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroEditorRoot(
    profileId: ProfileId,
    macroId: String?,
    seed: MacroDefinition?,
    repo: BrowserRepository,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val extras = LocalRoomExtras.current

    var loaded by remember { mutableStateOf(macroId == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf(seed?.name ?: "") }
    var description by remember { mutableStateOf(seed?.description ?: "") }
    var category by remember { mutableStateOf(seed?.category ?: "General") }
    var icon by remember { mutableStateOf(seed?.icon ?: "⚡") }
    val steps = remember { mutableStateListOf<MacroStep>() }
    val variables = remember { mutableStateListOf<MacroVariable>() }
    var playback by remember { mutableStateOf(seed?.playback ?: MacroPlayback()) }
    var createdAt by remember { mutableStateOf(0L) }

    // A just-recorded seed opens pre-filled (its steps arrive with it);
    // an id opens the stored row below.
    LaunchedEffect(seed) {
        if (seed != null) {
            steps.clear()
            steps.addAll(seed.steps)
            variables.clear()
            variables.addAll(seed.variables)
            createdAt = seed.createdAt
        }
    }

    var editIndex by remember { mutableStateOf<Int?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var varsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    // ---- pick-on-page handoff (default process ← :browser process) ----
    // A pick request survives the process boundary as a one-shot FILE keyed
    // by token (see MacroPick) — SharedPreferences would go stale across
    // processes. The editor generates the token, sends the browser to the
    // front in pick mode, and consumes the answer file when it next resumes.
    val context = LocalContext.current
    var pendingPick by remember { mutableStateOf<PendingPick?>(null) }

    fun consumePickResult() {
        val pending = pendingPick ?: return
        val file = File(context.filesDir, "macro_pick_${pending.token}.json")
        val pick = runCatching { MacroPick.decode(file.readText()) }.getOrNull()
        file.delete()
        pendingPick = null
        if (pick == null) return
        val index = pending.index
        if (index >= steps.size) return
        val old = steps[index]
        steps[index] = when (pick.kind) {
            MacroPick.KIND_DRAG_END -> old.copy(endX = pick.x, endY = pick.y)
            else -> old.copy(
                x = pick.x,
                y = pick.y,
                selector = pick.selector ?: old.selector,
                selectorKind = if (pick.selector != null) pick.selectorKind else old.selectorKind,
                text = pick.text ?: old.text
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) consumePickResult()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Load the row (edit mode). A missing row is an honest error, not a blank
    // editor that would silently create a NEW macro on save.
    LaunchedEffect(macroId) {
        if (macroId == null) return@LaunchedEffect
        val def = repo.macro(profileId, macroId)
        if (def == null) {
            error = "That macro could not be opened"
            return@LaunchedEffect
        }
        name = def.name
        description = def.description
        category = def.category
        icon = def.icon
        steps.clear()
        steps.addAll(def.steps)
        variables.clear()
        variables.addAll(def.variables)
        playback = def.playback
        createdAt = def.createdAt
        loaded = true
    }

    fun save() {
        if (name.isBlank()) {
            error = "Give the macro a name"
            return
        }
        busy = true
        scope.launch {
            runCatching {
                repo.saveMacro(
                    profileId,
                    MacroDefinition(
                        name = name.trim(),
                        description = description.trim(),
                        category = category.trim().ifBlank { "General" },
                        icon = icon.trim().ifBlank { "⚡" }.take(2),
                        steps = steps.toList(),
                        variables = variables.toList(),
                        playback = playback,
                        createdAt = createdAt
                    ),
                    macroId
                )
            }.onSuccess { onSaved(if (macroId == null) "Macro created" else "Macro saved") }
                .onFailure {
                    busy = false
                    error = it.message ?: "Could not save"
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (macroId == null) "New macro" else "Edit macro") },
                navigationIcon = {
                    IconButton(
                        onClick = { confirmExit = true },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close editor")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { save() },
                        enabled = loaded && !busy,
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("Save") }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Edge-to-edge: the scaffold places its bottom bar at the
                    // raw window edge, ON TOP of the system navigation area —
                    // without this the Save button sits under the gesture bar.
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { save() },
                    enabled = loaded && !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Save macro") }
            }
        }
    ) { padding ->
        if (!loaded) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onCancel) { Text("Back") }
                } else {
                    Text("Loading…")
                }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = it.take(2) },
                    label = { Text("Icon") },
                    singleLine = true,
                    modifier = Modifier.width(88.dp)
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp)
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
            Row {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = playback.speed.toString(),
                    onValueChange = { text ->
                        text.toFloatOrNull()?.let { playback = playback.copy(speed = it.coerceIn(0.25f, 4f)) }
                    },
                    label = { Text("Speed ×") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { addOpen = true }) { Text("＋ Step") }
                TextButton(onClick = { varsOpen = true }) { Text("＋ Variable") }
                TextButton(onClick = { settingsOpen = true }) { Text("Playback…") }
            }
            Spacer(Modifier.height(6.dp))

            if (steps.isEmpty()) {
                Text(
                    "No steps. Record from the browser's Macro menu, or add steps by hand.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(steps, key = { _, s -> steps.indexOf(s) }) { index, step ->
                        StepRow(
                            step = step,
                            index = index,
                            count = steps.size,
                            onEdit = { editIndex = index },
                            onMoveUp = {
                                if (index > 0) {
                                    steps.add(index - 1, steps.removeAt(index))
                                }
                            },
                            onMoveDown = {
                                if (index < steps.size - 1) {
                                    steps.add(index + 1, steps.removeAt(index))
                                }
                            },
                            onDuplicate = { steps.add(index + 1, step) },
                            onDelete = { steps.removeAt(index) }
                        )
                    }
                }
            }
        }
    }

    // ---- add step ----
    if (addOpen) {
        StepTypeDialog(
            onDismiss = { addOpen = false },
            onPick = { action ->
                addOpen = false
                steps.add(
                    when (action) {
                        MacroStep.NAVIGATE -> MacroStep(action = action, url = "https://", delayMs = 300)
                        MacroStep.TAB_SWITCH -> MacroStep(action = action, value = "Tab title")
                        MacroStep.WAIT -> MacroStep(action = action, timeoutMs = 1_000)
                        MacroStep.WAIT_ELEMENT -> MacroStep(action = action, selector = "#id", timeoutMs = 5_000)
                        MacroStep.SCREENSHOT -> MacroStep(action = action, note = "checkpoint")
                        else -> MacroStep(action = action)
                    }
                )
                editIndex = steps.size - 1
            }
        )
    }

    // ---- edit step ----
    editIndex?.let { index ->
        if (index < steps.size) {
            StepEditDialog(
                step = steps[index],
                onDismiss = { editIndex = null },
                onPick = { phase ->
                    // Hand the tap capture to the engine, then come back and
                    // read the answer file on resume (consumePickResult).
                    val token = UUID.randomUUID().toString().replace("-", "").take(20)
                    pendingPick = PendingPick(index = index, token = token)
                    BrowserActivity.requestPick(
                        context,
                        profileId.value,
                        phase,
                        token
                    )
                },
                onSave = { updated ->
                    steps[index] = updated
                    editIndex = null
                }
            )
        } else {
            editIndex = null
        }
    }

    // ---- variables ----
    if (varsOpen) {
        VariablesDialog(
            variables = variables,
            onDismiss = { varsOpen = false }
        )
    }

    // ---- playback settings ----
    if (settingsOpen) {
        PlaybackDialog(
            playback = playback,
            onDismiss = { settingsOpen = false },
            onSave = {
                playback = it
                settingsOpen = false
            }
        )
    }

    // ---- unsaved-work guard ----
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this macro have not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmExit = false; onCancel() }
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Keep editing") }
            }
        )
    }
}

/** The actions a hand-authored step can start from — friendliest first. */
private val STEP_ACTIONS = listOf(
    MacroStep.CLICK to "Click a spot",
    MacroStep.LONG_PRESS to "Long-press a spot",
    MacroStep.DRAG to "Swipe / drag",
    MacroStep.INPUT to "Type into a field",
    MacroStep.CHANGE to "Set a field's value",
    MacroStep.NAVIGATE to "Navigate to a URL",
    MacroStep.SCROLL to "Scroll the page",
    MacroStep.KEY to "Press a key (Enter/Tab…)",
    MacroStep.BACK to "Browser back",
    MacroStep.FORWARD to "Browser forward",
    MacroStep.RELOAD to "Reload the page",
    MacroStep.TAB_SWITCH to "Switch tab by title",
    MacroStep.NEW_TAB to "Open a new tab",
    MacroStep.WAIT to "Wait (ms)",
    MacroStep.WAIT_ELEMENT to "Wait for an element",
    MacroStep.SCREENSHOT to "Screenshot checkpoint"
)

/** A pick request waiting for its answer file when the editor resumes. */
private data class PendingPick(val index: Int, val token: String)

@Composable
private fun StepTypeDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a step") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                STEP_ACTIONS.forEach { (action, label) ->
                    TextButton(
                        onClick = { onPick(action) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StepEditDialog(
    step: MacroStep,
    onDismiss: () -> Unit,
    onPick: ((String) -> Unit)?,
    onSave: (MacroStep) -> Unit
) {
    var value by remember(step) { mutableStateOf(step.value ?: "") }
    var url by remember(step) { mutableStateOf(step.url ?: "") }
    var selector by remember(step) { mutableStateOf(step.selector ?: "") }
    var text by remember(step) { mutableStateOf(step.text ?: "") }
    var x by remember(step) { mutableStateOf(step.x?.toString() ?: "") }
    var y by remember(step) { mutableStateOf(step.y?.toString() ?: "") }
    var endX by remember(step) { mutableStateOf(step.endX?.toString() ?: "") }
    var endY by remember(step) { mutableStateOf(step.endY?.toString() ?: "") }
    var dx by remember(step) { mutableStateOf(step.dx?.toString() ?: "") }
    var dy by remember(step) { mutableStateOf(step.dy?.toString() ?: "") }
    var delay by remember(step) { mutableStateOf(step.delayMs.toString()) }
    var timeout by remember(step) { mutableStateOf(step.timeoutMs?.toString() ?: "") }
    var retries by remember(step) { mutableStateOf(step.retries?.toString() ?: "") }
    var note by remember(step) { mutableStateOf(step.note ?: "") }

    val usesSelector = step.action in listOf(
        MacroStep.CLICK, MacroStep.DBLCLICK, MacroStep.LONG_PRESS, MacroStep.DRAG,
        MacroStep.INPUT, MacroStep.CHANGE, MacroStep.WAIT_ELEMENT
    )
    val usesValue = step.action in listOf(
        MacroStep.INPUT, MacroStep.CHANGE, MacroStep.KEY, MacroStep.TAB_SWITCH
    )
    val usesUrl = step.action in listOf(MacroStep.NAVIGATE, MacroStep.NEW_TAB)
    val usesXY = step.action in listOf(MacroStep.CLICK, MacroStep.DBLCLICK, MacroStep.LONG_PRESS, MacroStep.DRAG)
    val usesDelta = step.action == MacroStep.SCROLL
    val isDrag = step.action == MacroStep.DRAG
    val canPick = usesXY && onPick != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Step: ${step.action.lowercase().replace('_', ' ')}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (canPick) {
                    Text(
                        if (isDrag) {
                            "Pick both points on the page — no numbers to type."
                        } else {
                            "Tap the position on the page — coordinates fill in by themselves."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(Modifier.padding(vertical = 4.dp)) {
                        if (isDrag) {
                            TextButton(
                                onClick = { onPick?.invoke(MacroPick.KIND_DRAG_START) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Pick START point") }
                            TextButton(
                                onClick = { onPick?.invoke(MacroPick.KIND_DRAG_END) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Pick END point") }
                        } else {
                            TextButton(
                                onClick = { onPick?.invoke(MacroPick.KIND_POINT) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Pick on page") }
                        }
                    }
                }
                if (usesUrl) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                if (usesValue) {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it },
                        label = { Text(if (step.action == MacroStep.KEY) "Key (Enter, Tab…)" else "Value") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
                if (step.action == MacroStep.KEY) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        listOf("Enter", "Tab", "Escape", "Backspace", "ArrowDown", "ArrowUp").forEach { key ->
                            TextButton(onClick = { value = key }) { Text(key) }
                        }
                    }
                }
                if (usesSelector) {
                    OutlinedTextField(value = selector, onValueChange = { selector = it }, label = { Text("CSS selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                if (usesXY) {
                    Row {
                        OutlinedTextField(value = x, onValueChange = { x = it }, label = { Text("x") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = y, onValueChange = { y = it }, label = { Text("y") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                if (isDrag) {
                    Row {
                        OutlinedTextField(value = endX, onValueChange = { endX = it }, label = { Text("end x") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = endY, onValueChange = { endY = it }, label = { Text("end y") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                if (usesDelta) {
                    Text(
                        "How far to scroll, in page pixels:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.padding(vertical = 2.dp)) {
                        TextButton(onClick = { dx = "0"; dy = "300" }) { Text("↓ 300") }
                        TextButton(onClick = { dx = "0"; dy = "-300" }) { Text("↑ 300") }
                        TextButton(onClick = { dx = "0"; dy = "800" }) { Text("↓ 800") }
                        TextButton(onClick = { dx = "300"; dy = "0" }) { Text("→ 300") }
                        TextButton(onClick = { dx = "-300"; dy = "0" }) { Text("← 300") }
                    }
                    Row {
                        OutlinedTextField(value = dx, onValueChange = { dx = it }, label = { Text("dx") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = dy, onValueChange = { dy = it }, label = { Text("dy") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                if (step.action == MacroStep.WAIT) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        TextButton(onClick = { timeout = "500" }) { Text("0.5s") }
                        TextButton(onClick = { timeout = "1000" }) { Text("1s") }
                        TextButton(onClick = { timeout = "2000" }) { Text("2s") }
                        TextButton(onClick = { timeout = "5000" }) { Text("5s") }
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Element text (optional)") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                OutlinedTextField(
                    value = delay, onValueChange = { delay = it },
                    label = { Text("Delay before (ms)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = timeout, onValueChange = { timeout = it },
                    label = { Text(if (step.action == MacroStep.WAIT) "Wait (ms)" else "Timeout (ms, optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = retries, onValueChange = { retries = it },
                    label = { Text("Retries (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        step.copy(
                            value = value.ifBlank { null },
                            url = url.ifBlank { null },
                            selector = selector.ifBlank { null },
                            text = text.ifBlank { null },
                            x = x.toFloatOrNull(),
                            y = y.toFloatOrNull(),
                            endX = endX.toFloatOrNull(),
                            endY = endY.toFloatOrNull(),
                            dx = dx.toFloatOrNull(),
                            dy = dy.toFloatOrNull(),
                            delayMs = delay.toLongOrNull() ?: 0L,
                            timeoutMs = timeout.toLongOrNull(),
                            retries = retries.toIntOrNull(),
                            note = note.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save step") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun VariablesDialog(variables: MutableList<MacroVariable>, onDismiss: () -> Unit) {
    val names = remember { mutableStateListOf<String>() }
    LaunchedEffect(variables.size) {
        names.clear()
        variables.forEach { names.add(it.name) }
    }
    var newName by remember { mutableStateOf("") }
    var newDefault by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Variables") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Reference a variable in any step's value or URL as {{name}}; " +
                        "the launch dialog asks for it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                variables.forEachIndexed { i, v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("{{${v.name}}}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { variables.removeAt(i) }) { Text("Remove") }
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("New variable name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newDefault, onValueChange = { newDefault = it },
                    label = { Text("Default value (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleaned = newName.trim().replace(Regex("[^A-Za-z0-9_ -]"), "")
                    if (cleaned.isNotBlank() && variables.none { it.name == cleaned }) {
                        variables.add(MacroVariable(name = cleaned, defaultValue = newDefault))
                    }
                    newName = ""
                    newDefault = ""
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun PlaybackDialog(
    playback: MacroPlayback,
    onDismiss: () -> Unit,
    onSave: (MacroPlayback) -> Unit
) {
    var speed by remember { mutableStateOf(playback.speed.toString()) }
    var repeat by remember { mutableStateOf(playback.repeat.toString()) }
    var onError by remember { mutableStateOf(playback.onError) }
    var timeout by remember { mutableStateOf(playback.defaultTimeoutMs.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback") },
        text = {
            Column {
                OutlinedTextField(
                    value = speed, onValueChange = { speed = it },
                    label = { Text("Speed multiplier (0.25–4)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = repeat, onValueChange = { repeat = it },
                    label = { Text("Repeat (1 = once, 0 = loop until stopped)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = timeout, onValueChange = { timeout = it },
                    label = { Text("Element wait timeout (ms)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("When a step fails after its retries:", fontWeight = FontWeight.SemiBold)
                Row {
                    TextButton(onClick = { onError = "stop" }) {
                        Text(if (onError == "stop") "● Stop" else "Stop")
                    }
                    TextButton(onClick = { onError = "skip" }) {
                        Text(if (onError == "skip") "● Skip" else "Skip")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        playback.copy(
                            speed = speed.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f,
                            repeat = repeat.toIntOrNull()?.coerceAtLeast(0) ?: 1,
                            onError = if (onError == "skip") "skip" else "stop",
                            defaultTimeoutMs = timeout.toLongOrNull()?.coerceIn(500, 60_000) ?: 8_000L
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StepRow(
    step: MacroStep,
    index: Int,
    count: Int,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        withGradient = false
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${step.action.lowercase().replace('_', ' ')}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit step", tint = extras.icon)
                }
                IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", tint = extras.icon)
                }
                IconButton(onClick = onMoveDown, enabled = index < count - 1, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", tint = extras.icon)
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate step", tint = extras.icon)
                }
                IconButton(onClick = onDelete, modifier = Modifier.heightIn(min = 40.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete step", tint = extras.icon)
                }
            }
            val bits = mutableListOf<String>()
            step.selector?.let { bits.add("⌖ $it") }
            step.value?.let { bits.add("“$it”") }
            step.url?.let { bits.add(it.take(48)) }
            step.x?.let { bits.add("x${it.toInt()} y${(step.y ?: 0).toInt()}") }
            step.dx?.let { bits.add("Δ${it.toInt()},${(step.dy ?: 0).toInt()}") }
            if (step.delayMs > 0) bits.add("delay ${step.delayMs}ms")
            step.note?.let { bits.add(it) }
            if (bits.isNotEmpty()) {
                Text(
                    bits.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary,
                    maxLines = 2
                )
            }
        }
    }
}
