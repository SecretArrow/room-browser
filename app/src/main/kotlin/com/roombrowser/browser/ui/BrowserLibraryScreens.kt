package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.StatCategories
import com.roombrowser.data.repo.DownloadStatus
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.StatTile

/**
 * Library screens (Tabs / Bookmarks / History / Downloads / Privacy
 * Dashboard) — quiet rounded card lists on the themed background.
 *
 * Insets are already applied by the host BrowserScreen (status bar top +
 * horizontal + bottom bar); the top bars below set explicit zero window
 * insets so nothing is double-applied.
 */

/** Shared top bar: themed container, back affordance, zero extra insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    title: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val extras = LocalRoomExtras.current
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
        },
        actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = extras.background,
            scrolledContainerColor = extras.background,
            navigationIconContentColor = extras.textPrimary,
            titleContentColor = extras.textPrimary,
            actionIconContentColor = extras.icon
        )
    )
}

/** Centered empty state filling the remaining space below the top bar. */
@Composable
private fun CenteredEmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        EmptyState(title, subtitle)
    }
}

/** 40dp touch target that only shows a small glyph — no visual bulk. */
@Composable
private fun QuietIconButton(
    contentDescription: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = LocalRoomExtras.current.icon, modifier = Modifier.size(17.dp))
    }
}

/** Tab grid / list (spec section 12) — per-profile tab collection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabGridScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val tabs = viewModel.tabs
    val extras = LocalRoomExtras.current
    val grid = viewModel.profileSettings().tabLayout ==
        com.roombrowser.domain.model.TabLayout.GRID
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(
            title = "Tabs (${tabs.size})",
            onClose = onClose,
            actions = {
                IconButton(onClick = { viewModel.reopenClosedTab() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Reopen closed tab")
                }
                TextButton(onClick = { viewModel.startPrivateTab() }) { Text("Private") }
                IconButton(onClick = { viewModel.loadUrl("about:home", newTab = true) }) {
                    Icon(Icons.Filled.Add, contentDescription = "New tab")
                }
            }
        )
        if (tabs.isEmpty()) {
            CenteredEmptyState("No open tabs", "Tabs are saved per profile and restored after restarts.", Modifier.weight(1f))
        } else if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(tabs, key = { it.id }) { tab ->
                    TabCard(
                        title = tab.title.ifBlank { tab.url },
                        url = tab.url,
                        isPrivate = tab.isPrivate,
                        isPinned = tab.isPinned,
                        group = tab.groupName,
                        isActive = tab.id == viewModel.activeTabId,
                        onClick = { viewModel.selectTab(tab.id); onClose() },
                        onClose = { viewModel.closeTab(tab.id) },
                        onPin = { viewModel.pinTab(tab.id) },
                        onDuplicate = { viewModel.duplicateTab() },
                        onGroup = { viewModel.groupTab(tab.id, it) }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listItems(tabs, key = { it.id }) { tab ->
                    val rowShape = RoundedCornerShape((extras.radius * 0.9f).dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(extras.surface)
                            .border(
                                if (tab.id == viewModel.activeTabId) 1.2.dp else 0.5.dp,
                                if (tab.id == viewModel.activeTabId) extras.primary else extras.border,
                                rowShape
                            )
                            .clickable { viewModel.selectTab(tab.id); onClose() }
                            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Favicon circle
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(extras.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tab.title.ifBlank { tab.url }.take(1).uppercase().ifBlank { "\u2022" },
                                style = MaterialTheme.typography.titleMedium,
                                color = extras.primary
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { tab.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                color = extras.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                tab.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = extras.textSecondary
                            )
                        }
                        if (tab.isPrivate) {
                            Icon(Icons.Filled.Lock, contentDescription = "Private tab", modifier = Modifier.size(16.dp), tint = extras.primary)
                        }
                        QuietIconButton(
                            contentDescription = "Close tab",
                            icon = Icons.Filled.Close,
                            onClick = { viewModel.closeTab(tab.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    title: String,
    url: String,
    isPrivate: Boolean,
    isPinned: Boolean,
    group: String?,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onGroup: (String?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val extras = LocalRoomExtras.current
    val shape = RoundedCornerShape((extras.radius * 0.9f).dp)
    Column(
        Modifier
            .clip(shape)
            .background(extras.surface)
            .border(
                if (isActive) 1.5.dp else 0.5.dp,
                if (isActive) extras.primary else extras.border,
                shape
            )
            .clickable(onClick = onClick)
    ) {
        // Preview area: soft gradient placeholder + favicon + status chips
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            extras.primary.copy(alpha = if (isPrivate) 0.30f else 0.16f),
                            extras.surfaceAlt
                        )
                    )
                )
        ) {
            // Favicon circle
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(extras.surface.copy(alpha = 0.88f))
                    .border(0.5.dp, extras.border.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title.take(1).uppercase().ifBlank { "\u2022" },
                    style = MaterialTheme.typography.titleLarge,
                    color = extras.textPrimary
                )
            }
            if (isPrivate) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(extras.primary.copy(alpha = 0.85f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(11.dp), tint = extras.onButton)
                    Spacer(Modifier.width(4.dp))
                    Text("Private", style = MaterialTheme.typography.labelSmall, color = extras.onButton)
                }
            }
            if (isPinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(extras.surface.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned tab",
                        tint = extras.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                // 40dp touch target wrapping a small visual chip
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = { menuOpen = true }),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(extras.background.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Tab actions", modifier = Modifier.size(16.dp), tint = extras.icon)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isPinned) "Unpin" else "Pin") },
                        onClick = { menuOpen = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { menuOpen = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = { Text("Group: Shopping") },
                        onClick = { menuOpen = false; onGroup("Shopping") }
                    )
                    DropdownMenuItem(
                        text = { Text("Ungroup") },
                        onClick = { menuOpen = false; onGroup(null) }
                    )
                }
            }
        }
        // Info area
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
            QuietIconButton(
                contentDescription = "Close tab",
                icon = Icons.Filled.Close,
                onClick = onClose
            )
        }
        if (group != null) {
            Text(
                "Group: $group",
                style = MaterialTheme.typography.labelSmall,
                color = extras.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
            )
        }
    }
}

/** Bookmarks screen — profile-specific. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val bookmarks = viewModel.bookmarks
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(title = "Bookmarks", onClose = onClose)
        if (bookmarks.isEmpty()) {
            CenteredEmptyState("No bookmarks", "Bookmarks are stored per profile.", Modifier.weight(1f))
        } else {
            val byFolder = bookmarks.groupBy { it.folder }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                byFolder.forEach { (folder, items) ->
                    if (folder != null) {
                        item(key = "folder-$folder") {
                            SectionHeader(folder)
                        }
                    }
                    listItems(items, key = { it.id }) { bookmark ->
                        LibraryListRow(
                            icon = Icons.Filled.Star,
                            title = bookmark.title.ifBlank { bookmark.url },
                            url = bookmark.url,
                            onClick = { viewModel.loadUrl(bookmark.url); onClose() },
                            onDelete = { viewModel.deleteBookmark(bookmark.id) },
                            deleteContentDescription = "Delete bookmark"
                        )
                    }
                }
            }
        }
    }
}

/** History screen — profile-specific, time-ranged clearing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val history = viewModel.recentHistory
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(
            title = "History",
            onClose = onClose,
            actions = {
                TextButton(onClick = { viewModel.clearHistory(0) }) { Text("Clear all") }
            }
        )
        if (history.isEmpty()) {
            CenteredEmptyState("No history", "History is stored per profile and never shared.", Modifier.weight(1f))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listItems(history, key = { it.id }) { entry ->
                    LibraryListRow(
                        icon = Icons.Filled.History,
                        title = entry.title.ifBlank { entry.url },
                        url = entry.url,
                        onClick = { viewModel.loadUrl(entry.url); onClose() },
                        onDelete = { viewModel.deleteHistoryItem(entry.id) },
                        deleteContentDescription = "Delete history item"
                    )
                }
            }
        }
    }
}

/** Bookmarks / history row: rounded card, tinted icon tile, trailing delete. */
@Composable
private fun LibraryListRow(
    icon: ImageVector,
    title: String,
    url: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteContentDescription: String
) {
    val extras = LocalRoomExtras.current
    RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(extras.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = extras.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
            QuietIconButton(
                contentDescription = deleteContentDescription,
                icon = Icons.Filled.Delete,
                onClick = onDelete
            )
        }
    }
}

/** Downloads screen with pause/resume/cancel/retry/open/share/delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val downloads = viewModel.downloads
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(title = "Downloads", onClose = onClose)
        if (downloads.isEmpty()) {
            CenteredEmptyState("No downloads", "Downloads are stored per profile in Download/RoomBrowser.", Modifier.weight(1f))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listItems(downloads, key = { it.id }) { download ->
                    DownloadRow(
                        viewModel = viewModel,
                        id = download.id,
                        fileName = download.fileName,
                        status = download.status,
                        downloadedBytes = download.downloadedBytes,
                        totalBytes = download.totalBytes,
                        error = download.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    viewModel: BrowserViewModel,
    id: Long,
    fileName: String,
    status: String,
    downloadedBytes: Long,
    totalBytes: Long,
    error: String?
) {
    val extras = LocalRoomExtras.current
    RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(extras.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = extras.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            when {
                status == DownloadStatus.RUNNING.name && totalBytes > 0 ->
                    LinearProgressIndicator(
                        progress = { (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp)
                    )
                status == DownloadStatus.RUNNING.name -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                )
            }
            val statusText = when (status) {
                DownloadStatus.QUEUED.name -> "Queued"
                DownloadStatus.RUNNING.name -> "${downloadedBytes / 1024} KB" +
                    (if (totalBytes > 0) " / ${totalBytes / 1024} KB" else "")
                DownloadStatus.PAUSED.name -> "Paused at ${downloadedBytes / 1024} KB"
                DownloadStatus.COMPLETED.name -> "Completed"
                DownloadStatus.FAILED.name -> "Failed${error?.let { ": $it" } ?: ""}"
                DownloadStatus.CANCELLED.name -> "Cancelled"
                else -> status
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (status) {
                    DownloadStatus.RUNNING.name, DownloadStatus.QUEUED.name -> {
                        TextButton(
                            onClick = { viewModel.pauseDownload(id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Pause") }
                        TextButton(
                            onClick = { viewModel.cancelDownload(id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Cancel") }
                    }
                    DownloadStatus.PAUSED.name -> {
                        TextButton(
                            onClick = { viewModel.resumeDownload(id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Resume") }
                    }
                    DownloadStatus.FAILED.name, DownloadStatus.CANCELLED.name -> {
                        TextButton(
                            onClick = { viewModel.retryDownload(id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Retry") }
                    }
                    DownloadStatus.COMPLETED.name -> {
                        TextButton(
                            onClick = { viewModel.openDownload(id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Open") }
                        TextButton(
                            onClick = { viewModel.shareDownload(id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Share") }
                    }
                }
                TextButton(
                    onClick = { viewModel.deleteDownload(id) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Delete") }
            }
        }
    }
}

/** Privacy Dashboard (spec section 9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val stats = viewModel.privacyStats
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            .verticalScroll(rememberScrollState())
    ) {
        LibraryTopBar(title = "Privacy Dashboard", onClose = onClose)
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    value = ((stats[StatCategories.TRACKER] ?: 0) + (stats[StatCategories.CROSS_SITE_TRACKER] ?: 0)).toString(),
                    label = "Trackers blocked",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = (stats[StatCategories.AD] ?: 0).toString(),
                    label = "Ads blocked",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    value = (stats[StatCategories.HTTPS_UPGRADE] ?: 0).toString(),
                    label = "HTTPS upgrades",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = (stats[StatCategories.POPUP] ?: 0).toString(),
                    label = "Popups blocked",
                    modifier = Modifier.weight(1f)
                )
            }
            RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Connection",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.primary
                    )
                    val dns = viewModel.dnsState
                    Text(
                        when (dns) {
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.System ->
                                "DNS: System default (app connections)"
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.Protected ->
                                "DNS: Protected (${dns.protocol}) — ${dns.resolver}"
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.Misconfigured ->
                                "DNS: Check configuration (${dns.reason})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textPrimary
                    )
                    Text(
                        "HTTPS upgrades: ${if (viewModel.profileSettings().httpsUpgrade) "Enabled" else "Disabled"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
            }
            Text(
                "All statistics on this dashboard come from real blocking events recorded by this browser — nothing is estimated or faked.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
