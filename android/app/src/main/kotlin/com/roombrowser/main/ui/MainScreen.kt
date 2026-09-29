package com.roombrowser.main.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.main.MainViewModel
import com.roombrowser.main.MainActivity
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.ProfileAvatar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PROFILE_COLORS = listOf(
    0xFF6750A4L, 0xFF2196F3L, 0xFF00897BL, 0xFF43A047L,
    0xFFFF7043L, 0xFFF4511EL, 0xFFD81B60L, 0xFF8D6E63L,
    0xFF5C6BC0L, 0xFF3949ABL
)

private val PROFILE_ICONS = listOf(
    "\uD83D\uDC64", "\uD83D\uDC68\u200D\uD83D\uDCBC", "\uD83D\uDD0D", "\uD83D\uDCB0",
    "\uD83E\uDDEA", "\uD83D\uDED2", "\uD83D\uDC7B", "\uD83D\uDCBB",
    "\uD83C\uDFA4", "\uD83C\uDFD4\uFE0F", "\uD83D\uDE80", "\uD83D\uDCDA"
)

/**
 * Main screen: welcome flow (first run), profile selector, CRUD dialogs
 * and the "Open with profile" chooser for external links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    viewModel: MainViewModel,
    onOpenProfile: (profileId: String, url: String?) -> Unit
) {
    val profiles = viewModel.profiles
    val firstRunDone = viewModel.firstRunDone
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message = viewModel.message
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Profile?>(null) }
    var duplicateTarget by remember { mutableStateOf<Profile?>(null) }
    var resetTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var exportTarget by remember { mutableStateOf<Profile?>(null) }
    var showImport by remember { mutableStateOf(false) }

    val pendingUrl = viewModel.pendingExternalUrl

    Scaffold(
        snackbarHost = {
            // Padded above the system navigation bar. contentWindowInsets is
            // zeroed on this Scaffold, so a bare SnackbarHost would draw UNDER
            // the Back/Home/Recents bar.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        // Insets applied explicitly below (TopAppBar handles the status bar
        // itself) — deterministic on every API level, nothing overlaps the
        // system Back / Home / Recents buttons.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Room Browser") },
                actions = {
                    IconButton(
                        onClick = { showImport = true },
                        modifier = Modifier.semantics { contentDescription = "Import profile settings" }
                    ) { Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null) }
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
            if (!firstRunDone) {
                WelcomeSection(
                    onSkip = { viewModel.setFirstRunDone() },
                    onCreate = { showCreate = true }
                )
            }

            Text(
                "Your profiles",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "Each profile is a fully isolated browsing environment — separate cookies, storage, history and settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (profiles.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyState(title = "No profiles yet", subtitle = "Create one to start isolated browsing")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showCreate = true }) { Text("Create Profile") }
                }
            } else {
                profiles.forEach { profile ->
                    ProfileCard(
                        profile = profile,
                        tabCount = viewModel.tabCounts[profile.id.value] ?: 0,
                        onOpen = {
                            if (profile.isLocked) {
                                activity.gateProfile(profile.name) {
                                    onOpenProfile(profile.id.value, pendingUrl)
                                }
                            } else {
                                onOpenProfile(profile.id.value, pendingUrl)
                            }
                        },
                        onEdit = { editTarget = profile },
                        onDuplicate = { duplicateTarget = profile },
                        onReset = { resetTarget = profile },
                        onDelete = { deleteTarget = profile },
                        onExport = { exportTarget = profile },
                        onSetDefault = { viewModel.setDefault(profile.id) },
                        onToggleLock = { viewModel.setLocked(profile.id, !profile.isLocked) }
                    )
                }
            }

            OutlinedButton(
                onClick = { showCreate = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create Profile")
            }
        }
    }

    // ---- External link routing: "Open with profile" (never silent) ----
    if (pendingUrl != null && profiles.isNotEmpty()) {
        OpenWithProfileSheet(
            url = pendingUrl,
            profiles = profiles,
            onChoose = { profile ->
                if (profile.isLocked) {
                    activity.gateProfile(profile.name) {
                        viewModel.consumeExternalUrl()
                        onOpenProfile(profile.id.value, pendingUrl)
                    }
                } else {
                    viewModel.consumeExternalUrl()
                    onOpenProfile(profile.id.value, pendingUrl)
                }
            },
            onDismiss = { viewModel.consumeExternalUrl() }
        )
    }

    if (showCreate) {
        CreateProfileDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, icon, color ->
                viewModel.createProfile(name, icon, color) { created ->
                    viewModel.setFirstRunDone()
                    showCreate = false
                    onOpenProfile(created.id.value, pendingUrl)
                }
            }
        )
    }

    editTarget?.let { target ->
        EditProfileDialog(
            profile = target,
            onDismiss = { editTarget = null },
            onSave = { name, icon, color ->
                viewModel.renameProfile(target.id, name)
                viewModel.restyleProfile(target.id, icon, color)
                editTarget = null
            }
        )
    }

    duplicateTarget?.let { target ->
        DuplicateProfileDialog(
            profile = target,
            onDismiss = { duplicateTarget = null },
            onConfirm = { options ->
                viewModel.duplicateProfile(target.id, options)
                duplicateTarget = null
            }
        )
    }

    resetTarget?.let { target ->
        ConfirmDialog(
            title = "Reset Profile",
            text = "This removes all browsing data of \"${target.name}\" (tabs, history, permissions, site data). Storage identity stays valid.",
            confirmLabel = "Reset",
            onDismiss = { resetTarget = null },
            onConfirm = {
                viewModel.resetProfile(target.id)
                resetTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete Profile",
            text = "This permanently deletes \"${target.name}\" and ALL of its isolated data (cookies, storage, history, downloads).",
            confirmLabel = "Delete",
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteProfile(target.id)
                deleteTarget = null
            }
        )
    }

    exportTarget?.let { target ->
        ExportProfileDialog(
            profile = target,
            onDismiss = { exportTarget = null },
            onExport = { includeBookmarks ->
                viewModel.exportProfile(target.id, includeBookmarks) { }
                exportTarget = null
            }
        )
    }

    if (showImport) {
        ImportProfileDialog(
            onDismiss = { showImport = false },
            onImport = { json ->
                viewModel.importProfile(json)
                showImport = false
            }
        )
    }
}

@Composable
private fun WelcomeSection(onSkip: () -> Unit, onCreate: () -> Unit) {
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(extras.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("\uD83C\uDFE0", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text("Your browser.", style = MaterialTheme.typography.headlineSmall, color = extras.textPrimary)
        Text("Your profiles.", style = MaterialTheme.typography.headlineSmall, color = extras.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your data stays separated. Every profile keeps its own cookies, storage, history, settings and THEME — like separate browser installations.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSkip) { Text("Later") }
            Button(onClick = onCreate) { Text("Create Profile") }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    tabCount: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleLock: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    val cardContext = androidx.compose.ui.platform.LocalContext.current
    val accent = androidx.compose.ui.graphics.Color(profile.colorArgb.toInt())
    com.roombrowser.ui.common.RoomCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            // Accent header strip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(accent, extras.primary)
                        )
                    )
            )
            Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(
                    icon = profile.icon,
                    colorArgb = profile.colorArgb,
                    size = 46
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = extras.textPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile.isLocked) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Profile locked",
                                modifier = Modifier.size(16.dp),
                                tint = extras.primary
                            )
                        }
                        if (profile.isDefault) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Default profile",
                                modifier = Modifier.size(14.dp),
                                tint = extras.secondary
                            )
                        }
                    }
                    Text(
                        "$tabCount tabs · Last active ${timeFormat.format(Date(profile.lastActiveAt))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Profile actions", tint = extras.icon)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            onClick = { menuOpen = false; onOpen() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit / Rename") },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (profile.isLocked) "Remove lock" else "Lock profile") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = { menuOpen = false; onToggleLock() }
                        )
                        DropdownMenuItem(
                            text = { Text("Set as default") },
                            onClick = { menuOpen = false; onSetDefault() }
                        )
                        DropdownMenuItem(
                            text = { Text("Theme studio") },
                            leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                com.roombrowser.theme.ui.ThemeStudioActivity.launch(
                                    cardContext,
                                    profile.id.value
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export settings") },
                            onClick = { menuOpen = false; onExport() }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset profile data") },
                            onClick = { menuOpen = false; onReset() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Open profile ${profile.name}" }
            ) { Text("OPEN") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenWithProfileSheet(
    url: String,
    profiles: List<Profile>,
    onChoose: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Open with profile", style = MaterialTheme.typography.titleLarge)
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(profile) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileAvatar(profile.icon, profile.colorArgb, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, icon: String, colorArgb: Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(PROFILE_ICONS.first()) }
    var color by remember { mutableStateOf(PROFILE_COLORS.first()) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROFILE_ICONS.forEach { candidate ->
                        Text(
                            candidate,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (candidate == icon) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { icon = candidate }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.take(5).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(
                                    2.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.drop(5).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(
                                    2.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Defaults: DuckDuckGo search, recommended privacy shields, system DNS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), icon, color) },
                enabled = name.isNotBlank()
            ) { Text("Create Profile") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String, colorArgb: Long) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var icon by remember { mutableStateOf(profile.icon) }
    var color by remember { mutableStateOf(profile.colorArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Renaming never changes the profile's storage identity (UUID).")
                Spacer(Modifier.height(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROFILE_ICONS.forEach { candidate ->
                        Text(
                            candidate,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (candidate == icon) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { icon = candidate }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_COLORS.take(5).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(
                                    2.dp,
                                    if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), icon, color) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DuplicateProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onConfirm: (CopyOptions) -> Unit
) {
    var copyBookmarks by remember { mutableStateOf(true) }
    var copyHistory by remember { mutableStateOf(false) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicate \"${profile.name}\"") },
        text = {
            Column {
                Text("The duplicate gets a NEW isolated storage namespace (fresh cookies and site data).")
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = extras.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Settings, theme and shields are copied too",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary
                    )
                }
                LabeledCheckboxRow("Bookmarks", copyBookmarks) { copyBookmarks = it }
                LabeledCheckboxRow("History", copyHistory) { copyHistory = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cookies, cache, sessions and site data are never copied between profiles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    CopyOptions(
                        settings = true,
                        bookmarks = copyBookmarks,
                        history = copyHistory
                    )
                )
            }) { Text("Duplicate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun LabeledCheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp)
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onExport: (includeBookmarks: Boolean) -> Unit
) {
    var includeBookmarks by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export \"${profile.name}\" settings") },
        text = {
            Column {
                Text("The export contains profile settings and optionally bookmarks/site settings. It NEVER contains cookies, sessions or credentials.")
                Spacer(Modifier.height(12.dp))
                LabeledCheckboxRow("Include bookmarks", includeBookmarks) { includeBookmarks = it }
            }
        },
        confirmButton = { Button(onClick = { onExport(includeBookmarks) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportProfileDialog(
    onDismiss: () -> Unit,
    onImport: (json: String) -> Unit
) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Profile Settings") },
        text = {
            Column {
                Text("Paste a Room Browser profile export (JSON).")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it },
                    label = { Text("JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (json.isNotBlank()) onImport(json) }, enabled = json.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
