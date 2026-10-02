package com.roombrowser.browser.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SafetyCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.domain.model.LanguagePresets
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.ui.common.GlassBar
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.ProfileAvatar
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader
import kotlinx.coroutines.launch

/**
 * Floating browser bottom toolbar (glass bar, themed navBar color,
 * tab-count badge) + the redesigned action sheets. All colors follow the
 * per-profile theme.
 *
 * Brave-style navigation bar: Back / Forward / Refresh / Tabs / Share /
 * More — 6 × 48dp touch targets (288dp) + 2×10dp outer + 2×6dp inner
 * padding = exactly 320dp, the smallest common screen width; 360dp
 * screens get comfortable ~8dp gaps between buttons. Bookmarks and
 * profile switching moved into the Page Actions sheet so the bar stays
 * lean while the omnibox above reclaims the width the nav arrows used
 * to eat.
 *
 * The refresh slot doubles as a STOP control while a page is loading
 * (the standard browser pattern), and greys out on the start page where
 * there is nothing to reload.
 */
@Composable
fun BrowserBottomBar(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onShowPageActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extras = LocalRoomExtras.current
    val page = viewModel.pageState
    GlassBar(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Web-history back — mirrors the system Back gesture.
            IconButton(
                onClick = { viewModel.goBack() },
                enabled = page.canGoBack,
                modifier = Modifier.semantics { contentDescription = "Go back" }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = if (page.canGoBack) extras.icon else extras.icon.copy(alpha = 0.35f)
                )
            }
            // Web-history forward.
            IconButton(
                onClick = { viewModel.goForward() },
                enabled = page.canGoForward,
                modifier = Modifier.semantics { contentDescription = "Go forward" }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (page.canGoForward) extras.icon else extras.icon.copy(alpha = 0.35f)
                )
            }
            // Refresh — becomes Stop while a page is loading; disabled on
            // the start page (nothing to reload there).
            IconButton(
                onClick = { if (page.loading) viewModel.stopLoading() else viewModel.reload() },
                enabled = !page.isHomepage,
                modifier = Modifier.semantics {
                    contentDescription = if (page.loading) "Stop loading" else "Reload page"
                }
            ) {
                Icon(
                    if (page.loading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (page.isHomepage) extras.icon.copy(alpha = 0.35f) else extras.icon
                )
            }
            // Tabs with live count badge
            Box {
                IconButton(
                    onClick = onOpenTabs,
                    modifier = Modifier.semantics { contentDescription = "Open tab grid" }
                ) {
                    Icon(Icons.Filled.Tab, contentDescription = null, tint = extras.icon)
                }
                if (viewModel.tabs.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 4.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(extras.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            viewModel.tabs.size.coerceAtMost(99).toString(),
                            color = extras.onButton,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    val url = viewModel.pageState.url
                    if (url != "about:home") {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(share, "Share link"))
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Share page" }
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = extras.icon)
            }
            IconButton(
                onClick = onShowPageActions,
                modifier = Modifier.semantics { contentDescription = "Page actions and settings" }
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = extras.icon)
            }
        }
    }
}

/** Page actions sheet (long-press / menu per spec section 37). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageActionsSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    onShowFindBar: () -> Unit,
    onTranslate: () -> Unit,
    onShowQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfileSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenAgentSessions: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onShowQuickSwitcher: () -> Unit,
    onShowShields: () -> Unit = {}
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Page Actions")
            SheetAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { viewModel.goBack(); onDismiss() }
            SheetAction(Icons.AutoMirrored.Filled.ArrowForward, "Forward") { viewModel.goForward(); onDismiss() }
            SheetAction(Icons.Filled.Add, "New tab") { viewModel.loadUrl("about:home", newTab = true); onDismiss() }
            SheetAction(Icons.Filled.Lock, "New private tab") { viewModel.startPrivateTab(); onDismiss() }
            SheetAction(Icons.Filled.SafetyCheck, "Shields") { onDismiss(); onShowShields() }
            SheetAction(Icons.Filled.FindInPage, "Find in page") { onShowFindBar() }
            SheetAction(Icons.Filled.Language, "Translate") { onTranslate() }
            SheetAction(Icons.Filled.DesktopWindows, if (viewModel.pageState.desktopMode) "Desktop site: ON" else "Desktop site: OFF") {
                viewModel.toggleDesktopMode(); onDismiss()
            }
            SheetAction(Icons.Filled.MenuBook, "Reader mode") { viewModel.enterReaderMode(); onDismiss() }
            SheetAction(
                Icons.Filled.Star,
                if (viewModel.bookmarks.any { it.url == viewModel.pageState.url }) "Remove bookmark" else "Add bookmark"
            ) { viewModel.toggleBookmark(); onDismiss() }
            SheetAction(Icons.Filled.PictureAsPdf, "Save page as PDF") {
                // PrintManager → user saves as PDF (real Android print pipeline)
                runCatching {
                    val webView = viewModel.activeWebView
                    if (webView != null) {
                        val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE)
                            as android.print.PrintManager
                        printManager.print(
                            viewModel.pageState.title.ifBlank { "Page" },
                            webView.createPrintDocumentAdapter("RoomBrowser"),
                            android.print.PrintAttributes.Builder().build()
                        )
                    }
                }
                onDismiss()
            }
            SheetAction(Icons.Filled.QrCodeScanner, "QR: share this page as code") { onShowQr() }
            SheetAction(Icons.Filled.Share, "Share") {
                val url = viewModel.pageState.url
                if (url != "about:home") {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(share, "Share link"))
                }
                onDismiss()
            }
            SheetAction(Icons.Filled.Add, "Add to Home screen") {
                addShortcutToHomeScreen(context, viewModel)
                onDismiss()
            }
            SheetAction(Icons.Filled.StarBorder, "Bookmarks") { onOpenBookmarks(); onDismiss() }
            SheetAction(Icons.Filled.Download, "Downloads") { onOpenDownloads(); onDismiss() }
            SheetAction(Icons.Filled.History, "History") { onOpenHistory(); onDismiss() }

            SheetSectionLabel("Appearance")
            SheetAction(Icons.Filled.Palette, "Theme studio") {
                com.roombrowser.theme.ui.ThemeStudioActivity.launch(
                    context, viewModel.profileId.value
                )
                onDismiss()
            }

            SheetSectionLabel("AI Agent")
            SheetAction(Icons.Filled.AutoAwesome, "AI Agents") { onOpenAgent() }
            // The parenthetical "(providers & models)" is gone on purpose:
            // this row sits directly under "AI Agents" in the same section,
            // so the suffix was repeating the section, widening the row and
            // wrapping the label on a narrow screen — for no information.
            SheetAction(Icons.Filled.SmartToy, "AI Agent Settings") { onOpenAgentSettings() }
            SheetAction(Icons.Filled.History, "AI Agent chats") { onOpenAgentSessions() }

            SheetSectionLabel("Settings")
            SheetAction(Icons.Filled.Settings, "Browser settings") { onOpenSettings() }
            SheetAction(Icons.Filled.Person, "Profile settings") { onOpenProfileSettings() }
            SheetAction(Icons.Filled.SwapHoriz, "Switch profile") { onShowQuickSwitcher(); onDismiss() }
            SheetAction(Icons.Filled.Info, "About Room Browser") { onOpenAbout() }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Real pinned-shortcut request via ShortcutManager (API 26+). */
private fun addShortcutToHomeScreen(context: android.content.Context, viewModel: BrowserViewModel) {
    runCatching {
        val url = viewModel.pageState.url
        if (url == "about:home") return
        val shortcutManager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            val intent = Intent(context, com.roombrowser.browser.BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(com.roombrowser.browser.BrowserActivity.EXTRA_PROFILE_ID, viewModel.profileId.value)
                putExtra(com.roombrowser.browser.BrowserActivity.EXTRA_INITIAL_URL, url)
            }
            val info = android.content.pm.ShortcutInfo.Builder(context, "site-${url.hashCode()}")
                .setShortLabel(viewModel.pageState.title.ifBlank { url }.take(10))
                .setIntent(intent)
                .build()
            shortcutManager.requestPinShortcut(info, null)
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    val extras = LocalRoomExtras.current
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = extras.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .clickable(onClick = onClick)
            // Addressable + announced as one action (TalkBack reads the
            // label instead of raw child texts; UI tests target the row
            // itself, which carries the click action).
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(extras.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = extras.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = extras.textPrimary)
    }
}

/** Profile quick switcher (spec section 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileQuickSwitcherSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    onSwitch: (ProfileId) -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Switch Profile")
            Text(
                "Switching closes the current browsing context completely before opening the next profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary
            )
            Spacer(Modifier.height(12.dp))
            viewModel.allProfiles.forEach { profile ->
                val current = profile.id == viewModel.profileId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
                        .clickable(enabled = !current) { onSwitch(profile.id) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(profile.icon, profile.colorArgb, size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name + if (current) "  (current)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) extras.primary else extras.textPrimary
                        )
                        Text(
                            "Switch to this profile",
                            style = MaterialTheme.typography.labelMedium,
                            color = extras.textSecondary
                        )
                    }
                }
            }
            // ---- Create New Profile (always the LAST action) ----------------
            // Create-then-switch: the new profile row exists (and the dialog
            // is closed) BEFORE onSwitch runs the profile-switch executor —
            // the current session is never torn down for a profile that
            // failed to materialize. On failure the dialog stays and a
            // snackbar explains.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
                    .clickable { showCreateDialog = true }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(extras.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = extras.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Create New Profile",
                        style = MaterialTheme.typography.bodyLarge,
                        color = extras.textPrimary
                    )
                    Text(
                        "Add another profile and switch to it",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showCreateDialog) {
        QuickCreateProfileDialog(
            initialName = viewModel.suggestedProfileName(),
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                scope.launch {
                    runCatching { viewModel.createProfileFromSwitcher(name) }
                        .onSuccess { created ->
                            showCreateDialog = false
                            // EXISTING switch path — BrowserActivity.switchProfile
                            // runs the 7-step process-restart protocol.
                            onSwitch(created.id)
                        }
                        .onFailure { failure ->
                            // Snackbar + stay: the dialog remains open, the
                            // current profile/session is untouched.
                            viewModel.postMessage(failure.message ?: "Could not create profile")
                        }
                }
            }
        )
    }
}

/**
 * Minimal create dialog for the quick switcher: a name field only — icon and
 * color are picked automatically (the full editor lives on the main screen).
 */
@Composable
private fun QuickCreateProfileDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Site privacy panel (spec section 11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldsSheet(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val shields = viewModel.shieldsState
    val extras = LocalRoomExtras.current
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(shields.host.ifBlank { "Privacy Shield" })
            com.roombrowser.ui.common.RoomCard {
                Column(Modifier.padding(14.dp)) {
                    ShieldStat("Ads blocked", shields.adsBlocked)
                    ShieldStat("Trackers blocked", shields.trackersBlocked)
                    ShieldStat("HTTPS upgrades", shields.httpsUpgrades)
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { viewModel.toggleShieldsForSite(!shields.shieldsDisabled) }) {
                Text(if (shields.shieldsDisabled) "Enable protection for this site" else "Disable protection for this site")
            }
            TextButton(onClick = { viewModel.clearSiteDataForCurrentSite() }) {
                Text("Clear site data")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "JavaScript: ${if (viewModel.profileSettings().javascriptEnabled) "allowed by profile settings" else "blocked by profile settings"}",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary
            )
            TextButton(onClick = {
                viewModel.setSiteSetting { it.copy(jsEnabled = !(it.jsEnabled ?: viewModel.profileSettings().javascriptEnabled)) }
            }) { Text("Toggle JavaScript for this site") }
            TextButton(onClick = {
                viewModel.setSiteSetting { it.copy(cookiesBlocked = !(it.cookiesBlocked ?: false)) }
            }) { Text("Toggle cookies for this site") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShieldStat(label: String, value: Int) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = extras.textSecondary, modifier = Modifier.weight(1f))
        Text("$value", style = MaterialTheme.typography.titleMedium, color = extras.primary)
    }
}

/** Find-in-page bar. */
@Composable
fun FindInPageBar(
    onFind: (String) -> Unit,
    onNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    var query by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            // Rendered at window top level (over the browser shell): stay
            // below the status bar and beside display cutouts.
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .background(extras.surface.copy(alpha = 0.97f))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onFind(it)
                },
                placeholder = { Text("Find in page") },
                singleLine = true,
                shape = RoundedCornerShape((extras.radius * 0.75f).dp),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onPrevious(query) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous match", tint = extras.icon) }
            IconButton(onClick = { onNext(query) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match", tint = extras.icon) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close find bar", tint = extras.icon) }
        }
    }
}

/** Translate dialog — opens the Google Translate wrapper in a new tab. */
@Composable
fun TranslateDialog(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(viewModel.profileSettings().translateTargetLanguage) }
    // The field used to be spliced straight into the Translate URL, so an
    // unknown code, an empty one, or anything containing a URL metacharacter
    // went to Google verbatim. LanguagePresets.isSupported is the same
    // validator the settings picker uses: it accepts a curated preset or any
    // well-formed BCP-47-ish tag, and rejects blank and malformed input —
    // its own charset (alphanumerics and hyphens) is what makes the URL safe,
    // and the code is percent-encoded below as well so a future loosening of
    // that regex cannot turn this into parameter injection.
    val code = target.trim()
    val valid = LanguagePresets.isSupported(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translate this page?") },
        text = {
            Column {
                Text("Uses Google Translate's web wrapper. Some sites may not work.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target language code (e.g. id, en, ja)") },
                    singleLine = true,
                    isError = target.isNotEmpty() && !valid,
                    supportingText = {
                        if (target.isNotEmpty() && !valid) {
                            Text("Not a recognised language code.")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val url = viewModel.pageState.url
                    if (url != "about:home") {
                        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                        val tl = java.net.URLEncoder.encode(code, "UTF-8")
                        viewModel.loadUrl(
                            "https://translate.google.com/translate?sl=auto&tl=$tl&u=$encoded",
                            newTab = true
                        )
                    }
                    onDismiss()
                }
            ) { Text("Translate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** QR share dialog — shows a generated QR for the current page. */
@Composable
fun QrShareDialog(content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QR Code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (content != "about:home") {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                setImageBitmap(QrCodeGenerator.generate(content, 512))
                            }
                        },
                        modifier = Modifier.size(240.dp)
                    )
                } else {
                    Text("Open a page first, then share it as a QR code.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Reader mode screen with typography controls. */
@Composable
fun ReaderScreen(
    content: BrowserViewModel.ReaderContent,
    onClose: () -> Unit
) {
    var fontSize by remember { mutableFloatStateOf(16f) }
    var dark by remember { mutableStateOf(false) }
    var lineSpacing by remember { mutableFloatStateOf(1.4f) }
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(if (dark) androidx.compose.ui.graphics.Color(0xFF101014) else androidx.compose.ui.graphics.Color(0xFFFCF8F0))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                )
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                content.title,
                style = MaterialTheme.typography.headlineSmall,
                color = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black
            )
            if (content.byline.isNotBlank()) {
                Text(
                    content.byline,
                    style = MaterialTheme.typography.labelMedium,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
            }
            Spacer(Modifier.height(12.dp))
            // Rendered as pre-formatted text: the extraction strips scripts/styles
            Text(
                text = content.html.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim(),
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineSpacing).sp,
                color = if (dark) androidx.compose.ui.graphics.Color(0xFFDDDDDD) else androidx.compose.ui.graphics.Color(0xFF222222)
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { if (fontSize > 12f) fontSize -= 2f }) { Text("A-") }
            OutlinedButton(onClick = { if (fontSize < 28f) fontSize += 2f }) { Text("A+") }
            OutlinedButton(onClick = { dark = !dark }) { Text(if (dark) "Light" else "Dark") }
            OutlinedButton(onClick = { lineSpacing = if (lineSpacing < 1.8f) 1.8f else 1.4f }) { Text("Spacing") }
            Button(onClick = onClose) { Text("Exit") }
        }
    }
}
