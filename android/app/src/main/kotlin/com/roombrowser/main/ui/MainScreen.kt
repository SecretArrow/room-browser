package com.roombrowser.main.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.main.MainActivity
import com.roombrowser.main.MainViewModel
import com.roombrowser.main.PassphrasePrompt
import com.roombrowser.main.PendingExport
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.ProfileAvatar
import com.roombrowser.ui.common.RoomBottomSheetShape
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

    // ---- Backup v2 delivery / intake (SAF + share) ----
    val context = LocalContext.current
    // Save an export where the user picks (Delivery dialog → "Save as file").
    val saveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.writeExportTo(uri)
        } else {
            // Closing the picker without a location is a cancel, not a failure.
            viewModel.discardExport()
        }
    }
    // Pick an export file to import — the PRIMARY import path; paste stays
    // as the secondary one.
    val pickImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.readImportFile(uri)
            showImport = false
        }
    }

    // The vault's biometric gate is UI-owned: when the ViewModel needs it
    // (reading/writing saved passwords), run it and report the outcome.
    LaunchedEffect(viewModel.vaultGateRequest) {
        viewModel.vaultGateRequest?.let {
            activity.gateVault(
                onSuccess = { viewModel.onVaultGateResult(true) },
                onFailure = { viewModel.onVaultGateResult(false) }
            )
        }
    }

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
            if (profiles.isEmpty() && !firstRunDone) {
                // TRUE first run: the welcome block carries the copy AND the
                // single create affordance directly beneath it. The profile-
                // list chrome (header + description + empty state) is
                // deliberately omitted here: stacked under the welcome copy
                // it pushed the primary CTA below the fold on small screens
                // (320x640dp — e.g. the CI emulator's default profile), and
                // first-run onboarding whose only action needs scrolling is
                // poor UX. Exactly ONE create affordance per state, as ever:
                // the CreateProfileDialog confirm is the only other place
                // the label exists.
                WelcomeSection(onSkip = { viewModel.setFirstRunDone() })
                Spacer(Modifier.height(4.dp))
                CreateProfileButton(addAnother = false, onCreate = { showCreate = true })
            } else {
                if (profiles.isEmpty()) {
                    // Welcome skipped on an empty list (the "Later" path, or
                    // the last profile was deleted): the list chrome and the
                    // empty state take the welcome's place.
                    ProfileListHeader(extras = extras)
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyState(title = "No profiles yet", subtitle = "Create one to start isolated browsing")
                        Spacer(Modifier.height(12.dp))
                        // Primary CTA of the single empty-state block — the only
                        // create button composed while no profile exists.
                        CreateProfileButton(addAnother = false, onCreate = { showCreate = true })
                    }
                } else {
                    ProfileListHeader(extras = extras)

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

                    // "Add another" affordance below the cards — the SAME
                    // single create entry point, in outlined chrome. It lives in
                    // this branch so it can never stack with the empty-state CTA.
                    CreateProfileButton(addAnother = true, onCreate = { showCreate = true })
                }
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
                exportTarget = null
                // Saved passwords always ride along — the vault gate,
                // passphrase step and delivery are driven from the ViewModel
                // (gate via viewModel.vaultGateRequest below).
                viewModel.startExport(target, includeBookmarks)
            }
        )
    }

    if (showImport) {
        ImportProfileDialog(
            onPickFile = { pickImportLauncher.launch(arrayOf("application/json", "*/*")) },
            onDismiss = { showImport = false },
            onImport = { json ->
                viewModel.importProfile(json)
                showImport = false
            }
        )
    }

    // A finished export, waiting for its delivery action.
    viewModel.pendingExport?.let { export ->
        ExportDeliveryDialog(
            export = export,
            onSave = { saveExportLauncher.launch(export.fileName) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, export.fileName)
                    putExtra(Intent.EXTRA_TEXT, export.json)
                }
                context.startActivity(Intent.createChooser(send, "Share \"${export.fileName}\""))
                viewModel.consumePendingExport()
            },
            onCancel = { viewModel.cancelExport() }
        )
    }

    // The passphrase step of an export (set a new one) or import (enter the
    // file's one).
    viewModel.passphrasePrompt?.let { prompt ->
        VaultPassphraseDialog(
            prompt = prompt,
            onConfirm = { passphrase ->
                if (prompt.forExport) {
                    viewModel.confirmExportPassphrase(passphrase)
                } else {
                    viewModel.confirmImportPassphrase(passphrase)
                }
            },
            onDismiss = { viewModel.cancelPassphrasePrompt() }
        )
    }

    // Import rejections — shown ONLY when nothing was written.
    viewModel.importError?.let { error ->
        ErrorDialog(
            title = "Import failed",
            text = error,
            onDismiss = { viewModel.dismissImportError() }
        )
    }
}

/**
 * First-run welcome copy. Deliberately carries NO create button: the screen
 * composes exactly ONE "Create Profile" affordance ([CreateProfileButton]).
 * This section's own button used to be one of three create buttons rendered
 * simultaneously on first open.
 */
@Composable
private fun WelcomeSection(onSkip: () -> Unit) {
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
        OutlinedButton(onClick = onSkip) { Text("Later") }
    }
}

/**
 * The profile-list chrome: section title + one-line explainer. Shared by the
 * non-empty list and the skipped-welcome empty state so the two stay
 * typographically identical (the true-first-run branch intentionally shows
 * neither — the welcome copy speaks there).
 */
@Composable
private fun ProfileListHeader(extras: com.roombrowser.ui.common.RoomExtras) {
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
}

/**
 * The ONE on-screen "Create Profile" affordance — exactly one instance is
 * composed in any state (the CreateProfileDialog confirm button is the only
 * other place that label exists). [addAnother] selects the chrome: filled
 * and centered in the empty state (primary onboarding CTA) vs outlined,
 * full-width below the profile list ("add another").
 */
@Composable
private fun CreateProfileButton(addAnother: Boolean, onCreate: () -> Unit) {
    if (addAnother) {
        OutlinedButton(
            onClick = onCreate,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            CreateProfileLabel()
        }
    } else {
        Button(onClick = onCreate) { CreateProfileLabel() }
    }
}

/** The on-screen create label defined in exactly one place — it cannot
 *  duplicate even if a second button variant is ever added. */
@Composable
private fun CreateProfileLabel() {
    Text("Create Profile")
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
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(Modifier.padding(horizontal = 16.dp)) {
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
        // Noun title — the "Create Profile" string stays exclusive to the
        // confirm action below and the single on-screen button.
        title = { Text("New Profile") },
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

/** Minimum length of a NEW export passphrase (the file's own passphrase is
 *  only checked by decryption — an importer never re-enforces this). */
private const val MIN_EXPORT_PASSPHRASE = 8

/** Human-readable size for the delivery dialog. */
private fun formatSize(bytes: Int): String =
    if (bytes < 2048) "$bytes B"
    else String.format(Locale.US, "%.1f KB", bytes / 1024.0)

@Composable
private fun ExportProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onExport: (includeBookmarks: Boolean) -> Unit
) {
    var includeBookmarks by remember { mutableStateOf(true) }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export \"${profile.name}\"") },
        text = {
            Column(Modifier.imePadding()) {
                Text("Profile settings, site permissions and site settings are always included.")
                Spacer(Modifier.height(12.dp))
                LabeledCheckboxRow("Include bookmarks", includeBookmarks) { includeBookmarks = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved passwords are included too — if the profile has any, you set an " +
                        "export passphrase for them in the next step. Cookies, sessions and " +
                        "history are never exported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
            }
        },
        confirmButton = { Button(onClick = { onExport(includeBookmarks) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportDeliveryDialog(
    export: PendingExport,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Export ready") },
        text = {
            Column {
                Text("\"${export.fileName}\" · ${formatSize(export.sizeBytes)}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save it somewhere safe or share it directly. The passwords inside stay " +
                        "sealed under your export passphrase; cookies, sessions and history " +
                        "are not in the file.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Save as file") } },
        dismissButton = {
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

/**
 * The passphrase step shared by export (set a NEW passphrase, two fields,
 * min length, must match) and import (enter the FILE's passphrase, one
 * field, inline retry on wrong passphrase). Password fields, imePadding so
 * the keyboard never covers them.
 */
@Composable
private fun VaultPassphraseDialog(
    prompt: PassphrasePrompt,
    onConfirm: (passphrase: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed by the prompt so a retry (error copy) resets both fields.
    var passphrase by remember(prompt) { mutableStateOf("") }
    var confirmation by remember(prompt) { mutableStateOf("") }
    val mismatch = prompt.forExport && confirmation.isNotEmpty() && confirmation != passphrase
    val valid = if (prompt.forExport) {
        passphrase.length >= MIN_EXPORT_PASSPHRASE && passphrase == confirmation
    } else {
        passphrase.isNotEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (prompt.forExport) "Export passphrase" else "Enter file passphrase")
        },
        text = {
            Column(Modifier.imePadding()) {
                Text(
                    if (prompt.forExport) {
                        "${prompt.credentialCount} saved " +
                            (if (prompt.credentialCount == 1) "password" else "passwords") +
                            " of \"${prompt.profileName}\" will be sealed under this passphrase. " +
                            "You will need it on the receiving device — it cannot be recovered."
                    } else {
                        "The export of \"${prompt.profileName}\" carries an encrypted password " +
                            "vault. Enter the passphrase it was exported with."
                    }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = {
                        Text(
                            if (prompt.forExport) "Passphrase (min $MIN_EXPORT_PASSPHRASE chars)"
                            else "Passphrase"
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = prompt.error != null,
                    supportingText = prompt.error?.let { error ->
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (prompt.forExport) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Repeat passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text("Passphrases do not match", color = MaterialTheme.colorScheme.error) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase) }, enabled = valid) {
                Text(if (prompt.forExport) "Seal & export" else "Unlock & import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportProfileDialog(
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onImport: (json: String) -> Unit
) {
    var json by remember { mutableStateOf("") }
    val extras = com.roombrowser.ui.common.LocalRoomExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Profile") },
        text = {
            Column(
                Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Restore a profile from a Room Browser export file — settings, bookmarks, " +
                        "site rules and, with its passphrase, saved passwords."
                )
                Spacer(Modifier.height(12.dp))
                // File picking is the PRIMARY path: a full-width 48dp+ target.
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose file…")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "…or paste the export JSON:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textSecondary
                )
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

/** A one-button message dialog (import rejections — nothing was written). */
@Composable
private fun ErrorDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
    )
}
