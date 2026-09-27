@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent chat history — its OWN activity. Tapping a chat returns the selected
 * session id via setResult() so the browser (':browser' process) reopens the
 * conversation inside the floating agent panel.
 */
class AgentSessionsActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — the
        // list never runs under the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        controller = AgentSettingsController(application, profileId)
        controller.start()
        setContent {
            RoomBrowserTheme {
                AgentSessionsRoot(
                    controller = controller,
                    onPick = { sessionId ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_SESSION_ID, sessionId)
                        )
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SESSION_ID = "session_id"

        fun launchForResult(from: Activity, profileId: String) {
            from.startActivity(Intent(from, AgentSessionsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }
    }
}

@Composable
private fun AgentSessionsRoot(
    controller: AgentSettingsController,
    onPick: (Long) -> Unit,
    onClose: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()) }

    Scaffold(
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic on every API level.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Agent chats") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
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
        ) {
            if (controller.sessions.isEmpty()) {
                EmptyState(
                    title = "No agent chats yet",
                    subtitle = "Conversations with the browsing agent appear here, scoped to this profile."
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(controller.sessions, key = { it.id }) { session ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(session.id) }
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
                        IconButton(onClick = { controller.deleteSession(session.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
