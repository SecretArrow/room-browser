package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCardShape

import kotlinx.coroutines.launch

/**
 * Wallet onboarding, shown while the engine's lock state is NO_WALLET.
 *
 * CREATE: choice → intro (name + chain chips + the recovery-phrase warning)
 * → [WalletRevealScreen] (masked 12-word grid until "Reveal") →
 * [WalletConfirmQuiz] (tap the 3 asked words from a shuffled grid; a wrong
 * pick is an inline error, never a crash) → the dashboard appears.
 *
 * IMPORT: choice → [WalletImportForm] (12/24-word field with whitespace
 * auto-normalization, optional name, chain chips) → dashboard.
 *
 * SECURITY: the freshly created recovery phrase lives ONLY in this
 * composable tree's plain `remember` state — never in rememberSaveable
 * (never in saved instance state), never logged, never copied to the
 * clipboard, and it is nulled on quiz completion; leaving onboarding
 * discards it entirely (remember scoping). There is no copy affordance for
 * the whole seed anywhere; word cells render a FIXED mask that never hints
 * at a word's length.
 */

/** The onboarding flow's internal steps (the engine's lock state drives entry/exit). */
private enum class WalletOnboardingStep {
    CHOICE, CREATE_INTRO, REVEAL, CONFIRM_QUIZ, IMPORT_FORM, RESTORE_BACKUP
}

/** Entry point — rendered by WalletRoot while no wallet exists for the profile. */
@Composable
internal fun WalletOnboarding(
    engine: WalletEngineApi,
    profileName: String,
    onMessage: (String) -> Unit,
    onFlowStarted: () -> Unit = {},
    onWalletReady: () -> Unit
) {
    var step by remember { mutableStateOf(WalletOnboardingStep.CHOICE) }
    // Transient ONLY: the fresh mnemonic never leaves composition state.
    var mnemonic by remember { mutableStateOf<String?>(null) }
    // The name the user typed, kept here rather than read back off
    // engine.wallet: that flow trails the create by one Room invalidation, so
    // a backup named from it could briefly call the wallet "Wallet".
    var createdLabel by remember { mutableStateOf("") }

    when (step) {
        WalletOnboardingStep.CHOICE -> WalletOnboardingChoice(
            onCreate = {
                onFlowStarted()
                step = WalletOnboardingStep.CREATE_INTRO
            },
            onImport = {
                onFlowStarted()
                step = WalletOnboardingStep.IMPORT_FORM
            },
            onRestore = {
                onFlowStarted()
                step = WalletOnboardingStep.RESTORE_BACKUP
            }
        )
        WalletOnboardingStep.CREATE_INTRO -> WalletCreateIntro(
            engine = engine,
            onMessage = onMessage,
            onCreated = { freshMnemonic, typedLabel ->
                mnemonic = freshMnemonic
                createdLabel = typedLabel
                step = WalletOnboardingStep.REVEAL
            },
            onBack = { step = WalletOnboardingStep.CHOICE }
        )
        WalletOnboardingStep.REVEAL -> {
            val words = mnemonic
            if (words != null) {
                WalletRevealScreen(
                    mnemonic = words,
                    engine = engine,
                    walletLabel = createdLabel,
                    profileName = profileName,
                    onMessage = onMessage,
                    onConfirmed = { step = WalletOnboardingStep.CONFIRM_QUIZ }
                )
            } else {
                WalletOnboardingChoice(
                    onCreate = { step = WalletOnboardingStep.CREATE_INTRO },
                    onImport = { step = WalletOnboardingStep.IMPORT_FORM },
                    onRestore = { step = WalletOnboardingStep.RESTORE_BACKUP }
                )
            }
        }
        WalletOnboardingStep.CONFIRM_QUIZ -> {
            val words = mnemonic
            if (words != null) {
                WalletConfirmQuiz(
                    mnemonic = words,
                    onDone = {
                        mnemonic = null
                        onWalletReady()
                    }
                )
            } else {
                WalletOnboardingChoice(
                    onCreate = { step = WalletOnboardingStep.CREATE_INTRO },
                    onImport = { step = WalletOnboardingStep.IMPORT_FORM },
                    onRestore = { step = WalletOnboardingStep.RESTORE_BACKUP }
                )
            }
        }
        WalletOnboardingStep.IMPORT_FORM -> WalletImportForm(
            engine = engine,
            onImported = onWalletReady,
            onBack = { step = WalletOnboardingStep.CHOICE }
        )
        // A backup file carries more than a phrase can: the phrase restores
        // derived accounts, and the file also restores the individually
        // imported private keys that no phrase can bring back. A user who
        // kept a backup should not have to retype anything.
        WalletOnboardingStep.RESTORE_BACKUP -> WalletBackupImportFlow(
            engine = engine,
            onRestored = onWalletReady,
            onCancel = { step = WalletOnboardingStep.CHOICE }
        )
    }
}

/** Step 1: create vs. import. */
@Composable
private fun WalletOnboardingChoice(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onRestore: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.AccountBalanceWallet,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Set up your wallet",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "One wallet per profile — keys are generated on this device and stay encrypted on it.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) { Text("Create a new wallet") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onImport,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) { Text("Import with recovery phrase") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) { Text("Restore from a backup file") }
        Spacer(Modifier.height(24.dp))
    }
}

/** Reusable chain-chip row (create + import share the default EVM + Solana). */
@Composable
private fun WalletChainChips(selected: Set<ChainType>, onToggle: (ChainType) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChainType.entries.forEach { chain ->
            FilterChip(
                selected = chain in selected,
                onClick = { onToggle(chain) },
                label = { Text(chain.displayName) }
            )
        }
    }
}

/**
 * Create intro: wallet name (default "Wallet"), chain chips (default EVM +
 * Solana) and the recovery-phrase warning. "Create Wallet" runs
 * [WalletEngineApi.createWallet] and hands the returned mnemonic to the
 * reveal screen.
 */
@Composable
private fun WalletCreateIntro(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onCreated: (mnemonic: String, label: String) -> Unit,
    onBack: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("Wallet") }
    var chains by remember { mutableStateOf(setOf(ChainType.EVM, ChainType.SOLANA)) }
    var creating by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Create a new wallet",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(10.dp))
        WalletInfoNote(
            "Your recovery phrase is the ONLY way to restore this wallet. " +
                "No one — not even Room Browser — can recover it for you."
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Wallet name") },
            singleLine = true,
            shape = RoundedCornerShape((extras.radius * 0.6f).dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Chains to enable",
            style = MaterialTheme.typography.labelMedium,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(6.dp))
        WalletChainChips(
            selected = chains,
            onToggle = { chain ->
                chains = if (chain in chains) chains - chain else chains + chain
            }
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    creating = true
                    scope.launch {
                        val typed = label.trim().ifBlank { "Wallet" }
                        runCatching {
                            engine.createWallet(label = typed, enabledChains = chains.toList())
                        }.onSuccess { freshMnemonic ->
                            creating = false
                            onCreated(freshMnemonic, typed)
                        }.onFailure { e ->
                            creating = false
                            onMessage("Could not create wallet — ${e.message}")
                        }
                    }
                },
                enabled = !creating && label.isNotBlank() && chains.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) { Text(if (creating) "Creating…" else "Create Wallet") }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) { Text("Back") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The one-time reveal: 12 (or 24) numbered word cells, MASKED until the
 * user presses "Reveal" (a fixed-width mask — never a hint at word length).
 * No copy affordance exists on this screen. The mnemonic is present only
 * as this composition's transient state.
 *
 * This is also where the phrase can be written out to an encrypted backup
 * file. It is the only moment the phrase is in the user's hands — the app
 * will not show it again — so an export offered anywhere else would be
 * offered to someone who no longer has anything to compare it against.
 * The backup reads the phrase straight from this composition rather than
 * from the vault, which is what lets it work while the session is still
 * locked (creating a wallet deliberately does not unlock it).
 */
@Composable
private fun WalletRevealScreen(
    mnemonic: String,
    engine: WalletEngineApi,
    walletLabel: String,
    profileName: String,
    onMessage: (String) -> Unit,
    onConfirmed: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val words = remember(mnemonic) { mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    var revealed by remember { mutableStateOf(false) }
    var backupOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Your recovery phrase",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(10.dp))
        WalletInfoNote(
            "Write these ${words.size} words on paper and keep them offline. " +
                "This screen never appears again — Room Browser stores the phrase " +
                "encrypted and will not show it again."
        )
        Spacer(Modifier.height(14.dp))
        words.chunked(3).forEachIndexed { rowIndex, rowWords ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowWords.forEachIndexed { column, word ->
                    val index = rowIndex * 3 + column + 1
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(extras.surfaceAlt.copy(alpha = 0.7f))
                            .padding(10.dp)
                    ) {
                        Text(
                            "$index. ${if (revealed) word else "••••••"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = extras.textPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { revealed = !revealed },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) { Text(if (revealed) "Hide phrase" else "Reveal") }
            Button(
                onClick = onConfirmed,
                enabled = revealed,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) { Text("I wrote it down") }
        }
        Spacer(Modifier.height(10.dp))
        // Gated on `revealed` like the confirmation button beside it: a
        // backup taken before the user has looked at the phrase is a file
        // they cannot check against anything.
        OutlinedButton(
            onClick = { backupOpen = true },
            enabled = revealed,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Export wallet keys" }
        ) {
            Icon(Icons.Filled.FileDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Export keys to an encrypted file")
        }
        Spacer(Modifier.height(24.dp))
    }

    WalletBackupFlow(
        open = backupOpen,
        engine = engine,
        walletLabel = walletLabel,
        profileLabel = profileName,
        mnemonicInHand = mnemonic,
        onMessage = onMessage,
        onDone = { backupOpen = false }
    )
}

/**
 * Confirmation quiz: the user taps the 3 asked words (random indices, asked
 * one at a time) from a SHUFFLED full-word grid to prove the phrase was
 * written down. A wrong tap is an inline error and nothing else; three
 * correct taps unlock "Done".
 */
@Composable
private fun WalletConfirmQuiz(mnemonic: String, onDone: () -> Unit) {
    val extras = LocalRoomExtras.current
    val words = remember(mnemonic) { mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val quizIndices = remember(mnemonic) { words.indices.shuffled().take(3) }
    val shuffledWords = remember(mnemonic) { words.shuffled() }
    var stage by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Confirm your phrase",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(10.dp))
        if (stage >= quizIndices.size) {
            WalletInfoNote("All three words correct. Your wallet is ready to use.")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text("Done") }
        } else {
            val expectedIndex = quizIndices[stage]
            Text(
                "Tap word #${expectedIndex + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = extras.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Prove you wrote the phrase down: tap word ${expectedIndex + 1} " +
                    "of ${words.size} from the grid below.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary
            )
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            shuffledWords.chunked(3).forEach { rowWords ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowWords.forEach { word ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(extras.surfaceAlt.copy(alpha = 0.7f))
                                .clickable {
                                    if (word == words[expectedIndex]) {
                                        stage += 1
                                        error = null
                                    } else {
                                        error = "That is not word ${expectedIndex + 1} — " +
                                            "check your notes and try again."
                                    }
                                }
                                .padding(10.dp)
                        ) {
                            Text(
                                word,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = extras.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Import: a 12/24-word field (whitespace runs collapse to single spaces as
 * you type; the live word count is shown), an optional wallet name, and
 * chain chips. Validation of the phrase itself happens in
 * [WalletEngineApi.importWallet] — its failure is surfaced INLINE so the
 * typed phrase survives a retry.
 */
@Composable
private fun WalletImportForm(
    engine: WalletEngineApi,
    onImported: () -> Unit,
    onBack: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var chains by remember { mutableStateOf(setOf(ChainType.EVM, ChainType.SOLANA)) }
    var attempted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val words = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val wordCount = words.size
    val countValid = wordCount == 12 || wordCount == 24

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Import with recovery phrase",
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(10.dp))
        WalletInfoNote(
            "Enter your existing 12- or 24-word recovery phrase. It is checked " +
                "on this device, encrypted, and never sent anywhere."
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it.replace(Regex("\\s+"), " ") },
            label = { Text("Recovery phrase (12 or 24 words)") },
            minLines = 3,
            isError = attempted && !countValid,
            supportingText = {
                when {
                    attempted && !countValid ->
                        Text("A recovery phrase has 12 or 24 words — this one has $wordCount")
                    else -> Text("$wordCount words")
                }
            },
            shape = RoundedCornerShape((extras.radius * 0.6f).dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Wallet name (optional)") },
            singleLine = true,
            shape = RoundedCornerShape((extras.radius * 0.6f).dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Chains to enable",
            style = MaterialTheme.typography.labelMedium,
            color = extras.textSecondary
        )
        Spacer(Modifier.height(6.dp))
        WalletChainChips(
            selected = chains,
            onToggle = { chain ->
                chains = if (chain in chains) chains - chain else chains + chain
            }
        )
        failure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    attempted = true
                    importing = true
                    scope.launch {
                        runCatching {
                            engine.importWallet(
                                mnemonic = words.joinToString(" "),
                                label = label.trim().ifBlank { "Wallet" },
                                enabledChains = chains.toList()
                            )
                        }.onSuccess {
                            importing = false
                            onImported()
                        }.onFailure { e ->
                            importing = false
                            failure = "Import failed — ${e.message ?: "invalid recovery phrase"}"
                        }
                    }
                },
                enabled = !importing && countValid && chains.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) { Text(if (importing) "Importing…" else "Import wallet") }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) { Text("Back") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
