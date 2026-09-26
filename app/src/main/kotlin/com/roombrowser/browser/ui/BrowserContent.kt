package com.roombrowser.browser.ui

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.PageError
import com.roombrowser.browser.StatCategories
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.ui.common.LoadingBar
import com.roombrowser.ui.common.StatTile
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main browser content: omnibox + toolbar, WebView host, homepage and
 * error pages.
 */
@Composable
fun BrowserContent(
    viewModel: BrowserViewModel,
    onShowShields: () -> Unit,
    onOpenPrivacyDashboard: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onQrScan: () -> Unit,
    onVoiceInput: () -> Unit
) {
    val page = viewModel.pageState
    var omniInput by remember(page.url) { mutableStateOf(if (page.isHomepage) "" else UrlIntelligence.displayUrl(page.url)) }
    var omniFocused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // ---------- Omnibox ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!page.isHomepage) {
                IconButton(onClick = { viewModel.goBack() }, enabled = true) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go back"
                    )
                }
                IconButton(onClick = { viewModel.goForward() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go forward"
                    )
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onShowShields)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Address bar: ${if (page.isHomepage) "search or type URL" else page.url}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (page.isPrivate) Icons.Filled.Close else if (page.secure) Icons.Filled.Lock else Icons.Filled.Security,
                    contentDescription = if (page.secure) "Secure connection" else "Connection status",
                    tint = if (page.isPrivate) MaterialTheme.colorScheme.primary else if (page.secure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = omniInput,
                    onValueChange = { input ->
                        omniInput = input
                    },
                    placeholder = { Text(if (page.isPrivate) "Private tab — search or type URL" else "Search or type URL") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            viewModel.onOmniBoxInput(omniInput)
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(0.dp),
                    shape = RoundedCornerShape(20.dp)
                )
                if (!page.isHomepage) {
                    IconButton(
                        onClick = { viewModel.toggleBookmark() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            if (viewModel.bookmarks.any { it.url == page.url }) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Bookmark this page"
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
        }

        LoadingBar(visible = page.loading, progress = page.progress)

        // ---------- Page / homepage / error ----------
        Box(Modifier.weight(1f)) {
            val error = viewModel.pageError
            when {
                error != null -> ErrorPage(
                    error = error,
                    onRetry = { viewModel.reload() }
                )
                page.isHomepage -> Homepage(
                    viewModel = viewModel,
                    onOpenPrivacyDashboard = onOpenPrivacyDashboard,
                    onOmniSubmit = { viewModel.onOmniBoxInput(it) }
                )
                else -> WebViewHost(viewModel = viewModel)
            }
        }
    }
}

/** Error pages (spec section 52): No Internet / SSL / DNS / generic. */
@Composable
private fun ErrorPage(error: PageError, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val (title, message) = when (error) {
            is PageError.NoInternet -> "No Internet" to "Unable to connect. Check your network and try again."
            is PageError.Ssl -> "Connection Not Secure" to (error.message + " Room Browser never bypasses certificate errors automatically.")
            is PageError.DnsFailure -> "DNS Resolution Failed" to "The site's address could not be resolved. Your DNS configuration may block this domain."
            is PageError.Generic -> "Page Problem" to (error.message ?: "The page could not be loaded.")
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try Again") }
        OutlinedButton(onClick = {
            runCatching { context.openWirelessSettings() }
        }) { Text("Network Settings") }
    }
}

private fun android.content.Context.openWirelessSettings() {
    startActivity(
        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun WebViewHost(viewModel: BrowserViewModel) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { frame ->
            viewModel.activeWebView?.let { webView ->
                if (webView.parent != frame) {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    frame.removeAllViews()
                    frame.addView(
                        webView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        },
        onRelease = { frame -> frame.removeAllViews() },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun Homepage(
    viewModel: BrowserViewModel,
    onOpenPrivacyDashboard: () -> Unit,
    onOmniSubmit: (String) -> Unit
) {
    val settings = viewModel.profileSettings()
    val greeting = remember {
        val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toInt()
        when {
            hour < 5 -> "Good night"
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (settings.showClock) {
            Text(greeting, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
        }
        if (viewModel.pageState.isPrivate) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Private tab", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No browsing history will be saved for this tab. Session cookies are cleared when private tabs close (subject to platform limitations — see PRIVACY.md).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (settings.showPrivacyStats) {
            val stats = viewModel.privacyStats
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    value = (stats[StatCategories.AD] ?: 0).toString(),
                    label = "ads blocked",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = ((stats[StatCategories.TRACKER] ?: 0) + (stats[StatCategories.CROSS_SITE_TRACKER] ?: 0)).toString(),
                    label = "trackers blocked",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = (stats[StatCategories.HTTPS_UPGRADE] ?: 0).toString(),
                    label = "HTTPS upgrades",
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(onClick = onOpenPrivacyDashboard) { Text("Privacy Dashboard") }
            Spacer(Modifier.height(16.dp))
        }

        if (settings.homepageShortcuts.isNotEmpty()) {
            Text(
                "Quick Access",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                settings.homepageShortcuts.take(4).forEach { url ->
                    val host = UrlIntelligence.hostOf(url) ?: url
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.loadUrl(url) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                host.substringBefore('.').take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                host.substringBefore('.'),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (settings.showRecentSites && viewModel.recentHistory.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Recent",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
            viewModel.recentHistory.take(5).forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.loadUrl(item.url) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.title.ifBlank { item.url },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
