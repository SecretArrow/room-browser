package com.roombrowser.agent.ui

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.ui.common.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Agent chat history for the current profile. */
@Composable
fun AgentSessionsScreen(
    viewModel: BrowserViewModel,
    onClose: () -> Unit,
    onOpenSession: (Long) -> Unit
) {
    val agent = viewModel.agent
    val dateFormat = rememberSimpleDateFormat()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Agent chats") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                }
            }
        )

        if (agent.sessions.isEmpty()) {
            EmptyState(
                title = "No agent chats yet",
                subtitle = "Conversations with the browsing agent appear here, scoped to this profile."
            )
            return
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(agent.sessions, key = { it.id }) { session ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(session.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${session.model} · ${dateFormat.format(Date(session.updatedAt))}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { agent.deleteSession(session.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun rememberSimpleDateFormat(): SimpleDateFormat =
    androidx.compose.runtime.remember {
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
    }
