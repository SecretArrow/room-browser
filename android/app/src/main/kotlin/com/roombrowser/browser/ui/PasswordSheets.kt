package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape

/**
 * Password-manager sheets shown over the browsing surface (rendered by
 * BrowserScreen):
 *  - [VaultOfferSheet] — "Saved logins for <domain>": the autofill offer for
 *    the login form the user focused. Shows USERNAMES/TITLES ONLY — never a
 *    password; the picked login is passed straight to the page fill.
 *  - [VaultSaveSheet] — "Save login for <domain>?": the prompt after a login
 *    form submitted. Shows the username and a MASKED password. It may appear
 *    while the vault is locked (first-run users have nothing saved yet) — the
 *    biometric gate happens only when the user taps Save (owned by
 *    BrowserViewModel.savePromptedLogin via a UI-provided gate).
 *
 * Both use the app-wide sheet shape ([RoomBottomSheetShape] — 10dp top
 * corners) and neither ever renders a plaintext password.
 */

/**
 * The app-wide sheet title block. The drag handle itself comes from
 * ModalBottomSheet's built-in centered handle (rendered above the content) —
 * the header only adds the title, so no sheet ever draws two handles.
 */
@Composable
internal fun VaultSheetHeader(title: String) {
    val extras = LocalRoomExtras.current
    Text(title, style = MaterialTheme.typography.titleLarge, color = extras.textPrimary)
    Spacer(Modifier.height(8.dp))
}

/**
 * Autofill offer: pick one of the logins saved for this page's host family.
 * Tapping a row fills BOTH the password field and the detected username field
 * on the page (through the injected script's `window.__roomVaultFill`), then
 * the sheet closes. Dismiss on outside tap simply closes it; the same page
 * will not re-offer until it is reloaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultOfferSheet(
    host: String,
    credentials: List<SavedCredential>,
    onPick: (SavedCredential) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoomBottomSheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VaultSheetHeader("Saved logins for $host")
            Text(
                "Pick a login to fill into this page. Passwords are filled into the page directly and are never shown here.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            credentials.forEach { credential ->
                VaultCredentialOption(
                    username = credential.username,
                    subtitle = credential.title ?: credential.domain,
                    onClick = { onPick(credential) }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One offered login: full-width touch target (≥48dp), username + label. */
@Composable
private fun VaultCredentialOption(
    username: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .semantics { contentDescription = "Fill login $username" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(extras.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                username.ifBlank { "(no username)" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = extras.textPrimary
            )
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
    }
}

/**
 * Save prompt: a login form on <host> submitted. The username is shown; the
 * password is MASKED (never rendered). "Save" persists the login (running
 * the biometric gate first when the vault is locked — wired through
 * BrowserViewModel.savePromptedLogin); "Not now" dismisses and forgets it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSaveSheet(
    host: String,
    username: String,
    onSave: () -> Unit,
    onNotNow: () -> Unit
) {
    val extras = LocalRoomExtras.current
    ModalBottomSheet(
        onDismissRequest = onNotNow,
        shape = RoomBottomSheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VaultSheetHeader("Save login for $host?")
            Text(
                "A login form was submitted on this page.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(extras.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = extras.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        username.ifBlank { "(no username)" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = extras.textPrimary
                    )
                    // Fixed-width mask: the dot count must never hint at the
                    // password's length.
                    Text(
                        "••••••••",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    // Touch target ≥48dp.
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Save") }
                OutlinedButton(
                    onClick = onNotNow,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Not now") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
