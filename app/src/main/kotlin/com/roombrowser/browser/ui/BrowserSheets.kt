package com.roombrowser.browser.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SafetyCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.engine.NetworkIdentity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.ui.common.ProfileAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Floating browser bottom toolbar. */
@Composable
fun BrowserBottomBar(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onShowPageActions: () -> Unit,
    onShowQuickSwitcher: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenTabs,
            modifier = Modifier.semantics { contentDescription = "Open tab grid" }
        ) {
            Icon(Icons.Filled.Tab, contentDescription = null)
        }
        IconButton(onClick = onOpenBookmarks) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.Star,
                contentDescription = "Bookmarks"
            )
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
            }
        ) {
            Icon(Icons.Filled.Share, contentDescription = "Share page")
        }
        IconButton(
            onClick = onShowQuickSwitcher,
            modifier = Modifier.semantics { contentDescription = "Switch profile" }
        ) {
            ProfileAvatar(
                icon = viewModel.profile.icon.ifBlank { "\uD83D\uDC64" },
                colorArgb = viewModel.profile.colorArgb,
                size = 32
            )
        }
        IconButton(
            onClick = onShowPageActions,
            modifier = Modifier.semantics { contentDescription = "Page actions and settings" }
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
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
    onOpenAgentSessions: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Page Actions", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            SheetAction(Icons.Filled.ArrowBack, "Back") { viewModel.goBack(); onDismiss() }
            SheetAction(Icons.Filled.Close, "Forward") { viewModel.goForward(); onDismiss() }
            SheetAction(Icons.Filled.Add, "New tab") { viewModel.loadUrl("about:home", newTab = true); onDismiss() }
            SheetAction(Icons.Filled.Lock, "New private tab") { viewModel.startPrivateTab(); onDismiss() }
            SheetAction(Icons.Filled.SafetyCheck, "Shields") { onDismiss(); viewModel.setSiteSetting { it } }
            SheetAction(Icons.Filled.FindInPage, "Find in page") { onShowFindBar() }
            SheetAction(Icons.Filled.Language, "Translate") { onTranslate() }
            SheetAction(Icons.Filled.DesktopWindows, if (viewModel.pageState.desktopMode) "Desktop site: ON" else "Desktop site: OFF") {
                viewModel.toggleDesktopMode(); onDismiss()
            }
            SheetAction(Icons.Filled.MenuBook, "Reader mode") { viewModel.enterReaderMode(); onDismiss() }
            SheetAction(
                androidx.compose.material.icons.Icons.Filled.Star,
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
            SheetAction(Icons.Filled.AutoAwesome, "AI Agent (autonomous browsing)") { onOpenAgent() }
            SheetAction(Icons.Filled.SmartToy, "AI Agent settings (providers & models)") { onOpenAgentSettings() }
            SheetAction(Icons.Filled.History, "AI Agent chats") { onOpenAgentSessions() }
            SheetAction(Icons.Filled.Settings, "Browser settings") { onOpenSettings() }
            SheetAction(Icons.Filled.Settings, "Profile settings") { onOpenProfileSettings() }
            SheetAction(Icons.Filled.Settings, "About Room Browser") { onOpenAbout() }
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
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Addressable + announced as one action (TalkBack reads the
            // label instead of raw child texts; UI tests target the row
            // itself, which carries the click action).
            .semantics { contentDescription = label }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Switch Profile", style = MaterialTheme.typography.titleLarge)
            Text(
                "Switching closes the current browsing context completely before opening the next profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            viewModel.allProfiles.forEach { profile ->
                val current = profile.id == viewModel.profileId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !current) { onSwitch(profile.id) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(profile.icon, profile.colorArgb, size = 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name + if (current) "  (current)" else "",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "${viewModel.tabs.size} tabs in current profile",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Site privacy panel (spec section 11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldsSheet(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val shields = viewModel.shieldsState
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(shields.host.ifBlank { "Privacy Shield" }, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("Ads blocked: ${shields.adsBlocked}", style = MaterialTheme.typography.bodyLarge)
            Text("Trackers blocked: ${shields.trackersBlocked}", style = MaterialTheme.typography.bodyLarge)
            Text("HTTPS upgrades: ${shields.httpsUpgrades}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { viewModel.toggleShieldsForSite(!shields.shieldsDisabled) }) {
                Text(if (shields.shieldsDisabled) "Enable protection for this site" else "Disable protection for this site")
            }
            TextButton(onClick = { viewModel.clearSiteDataForCurrentSite() }) {
                Text("Clear site data")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "JavaScript: ${if (viewModel.profileSettings().javascriptEnabled) "allowed by profile settings" else "blocked by profile settings"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

/** Find-in-page bar. */
@Composable
fun FindInPageBar(
    onFind: (String) -> Unit,
    onNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            // Rendered at window top level (over the browser shell): stay
            // below the status bar and beside display cutouts.
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onFind(it)
            },
            placeholder = { Text("Find in page") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onPrevious(query) }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Previous match") }
        IconButton(onClick = { onNext(query) }) { Icon(Icons.Filled.Close, contentDescription = "Next match") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close find bar") }
    }
}

/** Translate dialog — opens the Google Translate wrapper in a new tab. */
@Composable
fun TranslateDialog(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(viewModel.profileSettings().translateTargetLanguage) }
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
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val url = viewModel.pageState.url
                if (url != "about:home") {
                    val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                    viewModel.loadUrl(
                        "https://translate.google.com/translate?sl=auto&tl=$target&u=$encoded",
                        newTab = true
                    )
                }
                onDismiss()
            }) { Text("Translate") }
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
    Box(
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
                .padding(12.dp),
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

/** Profile Network Warning dialog (spec sections 6 / 74). */
@Composable
fun IpWarningDialog(
    conflict: NetworkIdentity.NetState.Conflict,
    showProfileName: Boolean,
    showLastSeen: Boolean,
    onContinue: () -> Unit,
    onSwitchProfile: () -> Unit,
    onNetworkSettings: () -> Unit,
    onDontWarnAgain: () -> Unit,
    onRecheck: () -> Unit
) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("\u26A0 Profile Network Warning") },
        text = {
            Column {
                Text(
                    "This profile is being opened from a public IP previously associated with " +
                        (if (showProfileName) "the \"${conflict.previousProfileName}\" profile" else "another profile") + "."
                )
                Spacer(Modifier.height(8.dp))
                Text("Current IP: ${conflict.currentIp}")
                if (showLastSeen) {
                    Text("Last seen: ${timeFormat.format(Date(conflict.lastSeenAt))}")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A shared public IP does not prove that profiles belong to the same person. This is an informational warning only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onContinue) { Text("Continue Anyway") }
                TextButton(onClick = onSwitchProfile) { Text("Switch Profile") }
                TextButton(onClick = onNetworkSettings) { Text("Network Settings") }
                TextButton(onClick = onRecheck) { Text("Re-check Network") }
                TextButton(onClick = onDontWarnAgain) { Text("Don't Warn Again for this IP") }
            }
        }
    )
}
