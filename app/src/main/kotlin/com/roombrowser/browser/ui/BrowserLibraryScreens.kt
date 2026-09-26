package com.roombrowser.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.StatCategories
import com.roombrowser.data.repo.DownloadStatus
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.StatTile

/** Tab grid / list (spec section 12) — per-profile tab collection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabGridScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val tabs = viewModel.tabs
    val grid = viewModel.profileSettings().tabLayout ==
        com.roombrowser.domain.model.TabLayout.GRID
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Tabs (${tabs.size})") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            },
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
            EmptyState("No open tabs", "Tabs are saved per profile and restored after restarts.")
        } else if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
            LazyColumn {
                listItems(tabs, key = { it.id }) { tab ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectTab(tab.id); onClose() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { tab.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                tab.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (tab.isPrivate) {
                            Icon(Icons.Filled.Lock, contentDescription = "Private tab", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { viewModel.closeTab(tab.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close tab")
                        }
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
    Card(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPrivate) {
                    Icon(Icons.Filled.Lock, contentDescription = "Private tab", modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (isPinned) {
                    Icon(Icons.Filled.Lock, contentDescription = "Pinned tab", modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
                Box(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Tab actions")
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
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close tab")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                url,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (group != null) {
                Text(
                    "Group: $group",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Bookmarks screen — profile-specific. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val bookmarks = viewModel.bookmarks
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Bookmarks") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        )
        if (bookmarks.isEmpty()) {
            EmptyState("No bookmarks", "Bookmarks are stored per profile.")
        } else {
            val byFolder = bookmarks.groupBy { it.folder }
            LazyColumn {
                byFolder.forEach { (folder, items) ->
                    if (folder != null) {
                        item(key = "folder-$folder") {
                            Text(
                                folder,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                    }
                    listItems(items, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            title = bookmark.title.ifBlank { bookmark.url },
                            url = bookmark.url,
                            onClick = { viewModel.loadUrl(bookmark.url); onClose() },
                            onDelete = { viewModel.deleteBookmark(bookmark.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(title: String, url: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark")
        }
    }
}

/** History screen — profile-specific, time-ranged clearing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val history = viewModel.recentHistory
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            },
            actions = {
                TextButton(onClick = { viewModel.clearHistory(0) }) { Text("Clear all") }
            }
        )
        if (history.isEmpty()) {
            EmptyState("No history", "History is stored per profile and never shared.")
        } else {
            LazyColumn {
                listItems(history, key = { it.id }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.loadUrl(entry.url); onClose() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifBlank { entry.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entry.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.deleteHistoryItem(entry.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete history item")
                        }
                    }
                }
            }
        }
    }
}

/** Downloads screen with pause/resume/cancel/retry/open/share/delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val downloads = viewModel.downloads
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        )
        if (downloads.isEmpty()) {
            EmptyState("No downloads", "Downloads are stored per profile in Download/RoomBrowser.")
        } else {
            LazyColumn {
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                status == DownloadStatus.RUNNING.name && totalBytes > 0 ->
                    LinearProgressIndicator(
                        progress = { (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                status == DownloadStatus.RUNNING.name -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row {
            when (status) {
                DownloadStatus.RUNNING.name, DownloadStatus.QUEUED.name -> {
                    TextButton(onClick = { viewModel.pauseDownload(id) }) { Text("Pause") }
                    TextButton(onClick = { viewModel.cancelDownload(id) }) { Text("Cancel") }
                }
                DownloadStatus.PAUSED.name -> {
                    TextButton(onClick = { viewModel.resumeDownload(id) }) { Text("Resume") }
                }
                DownloadStatus.FAILED.name, DownloadStatus.CANCELLED.name -> {
                    TextButton(onClick = { viewModel.retryDownload(id) }) { Text("Retry") }
                }
                DownloadStatus.COMPLETED.name -> {
                    TextButton(onClick = { viewModel.openDownload(id) }) { Text("Open") }
                    TextButton(onClick = { viewModel.shareDownload(id) }) { Text("Share") }
                }
            }
            TextButton(onClick = { viewModel.deleteDownload(id) }) { Text("Delete") }
        }
    }
}

/** Privacy Dashboard (spec section 9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val stats = viewModel.privacyStats
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Privacy Dashboard") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Spacer(Modifier.height(8.dp))
            Text("Connection", style = MaterialTheme.typography.titleMedium)
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
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "HTTPS upgrades: ${if (viewModel.profileSettings().httpsUpgrade) "Enabled" else "Disabled"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "All statistics on this dashboard come from real blocking events recorded by this browser — nothing is estimated or faked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
