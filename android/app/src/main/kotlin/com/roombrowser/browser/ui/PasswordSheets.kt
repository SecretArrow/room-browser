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
import androidx.compose.material.icons.filled.Lock
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
import com.roombrowser.ui.common.RoomSheetHeader

/**
 * Password-manager sheets shown over the browsing surface (rendered by
 * BrowserScreen):
 *  - [VaultOfferSheet] — "Saved logins for <domain>": the autofill offer for
 *    the login form the user focused. Shows USERNAMES/TITLES ONLY — never a
 *    password; the picked login is passed straight to the page fill. It has
 *    a LOCKED variant (see its own doc) shown when a login field is focused
 *    while the vault is locked.
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
 * Autofill offer: pick one of the logins saved for this page's host family.
 * Tapping a row fills BOTH the password field and the detected username field
 * on the page (through the injected script's `window.__roomVaultFill`), then
 * the sheet closes. Dismiss on outside tap simply closes it; the same page
 * will not re-offer until it is reloaded.
 *
 * LOCKED VARIANT ([locked] = true): the user focused a login field while the
 * vault was still locked for this process (which is how every process
 * starts). Without this the tap would look like it did nothing at all. The
 * locked sheet deliberately shows NO credentials and no counts — it cannot
 * even say whether anything is saved for this site, because that alone would
 * leak vault contents through the page. It offers exactly one user-initiated
 * action, [onUnlock], which the caller wires to the standard biometric /
 * device-credential gate; unlocking is never automatic, and a failed or
 * cancelled gate leaves this sheet in its locked state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultOfferSheet(
    host: String,
    credentials: List<SavedCredential>,
    onPick: (SavedCredential) -> Unit,
    onDismiss: () -> Unit,
    locked: Boolean = false,
    onUnlock: (() -> Unit)? = null
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
            if (locked) {
                VaultLockedOfferContent(host = host, onUnlock = onUnlock)
            } else {
                RoomSheetHeader("Saved logins for $host")
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
}

/**
 * Body of the offer sheet while the vault is locked. Wording rules: never
 * state or imply that a login EXISTS for [host] (that is vault content), and
 * never render a credential or a count — only the page's own host, which the
 * page already knows.
 */
@Composable
private fun VaultLockedOfferContent(host: String, onUnlock: (() -> Unit)?) {
    val extras = LocalRoomExtras.current
    RoomSheetHeader("Unlock to fill")
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
                Icons.Filled.Lock,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            host,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = extras.textPrimary
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "The password vault is locked. Unlock it to fill one of your saved logins into this page.",
        style = MaterialTheme.typography.bodySmall,
        color = extras.textSecondary
    )
    Spacer(Modifier.height(4.dp))
    // Honest about the trade-off: the locked sheet is shown for EVERY login
    // form, so its presence says nothing about what is stored.
    Text(
        "This prompt appears on any login form — it does not mean a login is saved for this site.",
        style = MaterialTheme.typography.bodySmall,
        color = extras.textSecondary
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onUnlock?.invoke() },
        // No gate wired up (non-Fragment host) = nothing to tap.
        enabled = onUnlock != null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) { Text("Unlock") }
    Spacer(Modifier.height(24.dp))
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
            RoomSheetHeader("Save login for $host?")
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
