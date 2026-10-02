package com.roombrowser.main

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.credentials.VaultAuthException
import com.roombrowser.domain.credentials.VaultFormatException
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.export.ProfileBackup
import com.roombrowser.domain.export.ProfileBackupResult
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.domain.profile.ProfileManager
import com.roombrowser.domain.theme.BuiltInThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A fully built export waiting for its delivery action (save file / share). */
class PendingExport(val fileName: String, val json: String, val sizeBytes: Int)

/**
 * The passphrase step of an in-flight export or import.
 *
 * @param forExport true = the user is CHOOSING a passphrase to seal the
 *   export (two fields, min length enforced by the dialog); false = the user
 *   is ENTERING the passphrase an import file was sealed with.
 * @param error inline retry hint (import only), e.g. "Wrong passphrase".
 * @param id identity of THIS prompt instance. Like [VaultGateRequest.id] it
 *   exists so an equal-looking prompt is still a NEW prompt: the dialog keys
 *   its passphrase fields on it. Without it, a second wrong passphrase
 *   produced a byte-identical copy of the first retry's prompt, the remember
 *   key never changed and the typed secret stayed in the field instead of
 *   being cleared. No default — every prompt must claim a fresh id.
 */
data class PassphrasePrompt(
    val forExport: Boolean,
    val profileName: String,
    val credentialCount: Int,
    val error: String? = null,
    val id: Int
)

/**
 * The one-tap follow-up a [MainViewModel.message] can carry. The ViewModel
 * cannot hold the Activity, so the screen maps this to the real navigation —
 * same division of labour as [VaultGateRequest].
 */
enum class MessageAction { OPEN_NOTIFICATION_SETTINGS }

/**
 * Signals MainScreen that the vault's biometric gate must run NOW (a
 * ViewModel cannot hold the Activity). The screen calls BiometricGate and
 * reports back via [MainViewModel.onVaultGateResult]. [id] makes each
 * request a fresh LaunchedEffect key even when they otherwise look equal.
 */
data class VaultGateRequest(val id: Int)

/**
 * Main-process ViewModel: profile CRUD, first-run state, external-link
 * routing ("Open with profile" — never silently opens the wrong profile)
 * and the backup v2 export / import flows.
 *
 * Export: gate → unlock → read credentials → (if any) set an export
 * passphrase → seal → deliver (SAF save / share). Import: parse (reject
 * anything unsupported BEFORE writing) → (if vault) file passphrase →
 * decrypt → gate → ONE Room transaction restoring everything.
 *
 * Nothing here logs the passphrase or any credential; the passphrase
 * CharArray is wiped right after each crypto call.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as RoomBrowserApp).graph
    private val profileManager: ProfileManager = graph.profileManager
    private val repo = graph.profileRepo
    val appState: AppStateRepository = graph.appState

    var profiles by mutableStateOf<List<Profile>>(emptyList())
        private set

    /** The picker itself is themed by the DEFAULT profile's theme — a live
     *  preview of the per-profile theme system. */
    var appTheme by mutableStateOf(BuiltInThemes.default())
        private set
    var tabCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Current generation of per-profile tab-count collectors — replaced,
     *  never stacked (see [observeTabCounts]). */
    private var tabCountsJob: Job? = null
    var firstRunDone by mutableStateOf(true)
        private set
    var pendingExternalUrl by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * The follow-up the current [message] offers, or null when the message is
     * just text. Set beside [message] and cleared by [clearMessage] so a
     * stale action can never ride along with the next message.
     */
    var messageAction by mutableStateOf<MessageAction?>(null)
        private set

    // ---------- Backup v2: shared flow state ----------

    /** A finished export, staged until the user saves or shares it. */
    var pendingExport by mutableStateOf<PendingExport?>(null)
        private set

    /** The active passphrase step (export sealing / import unlocking). */
    var passphrasePrompt by mutableStateOf<PassphrasePrompt?>(null)
        private set

    /** Import rejection shown in a dialog — set only when NOTHING was written. */
    var importError by mutableStateOf<String?>(null)
        private set

    /** Non-null = MainScreen must run the vault's biometric gate now. */
    var vaultGateRequest by mutableStateOf<VaultGateRequest?>(null)
        private set

    /** Export-in-progress inputs — intermediate work data, not UI state. */
    private class ExportDraft(val profile: Profile, val includeBookmarks: Boolean)

    private var exportDraft: ExportDraft? = null

    /**
     * Plaintext credentials of the in-flight export. Deliberately NOT Compose
     * state: never rendered, never logged, never persisted — dropped the
     * moment the sealed vault blob exists.
     */
    private var exportCredentials: List<SavedCredential> = emptyList()

    /** A parsed import payload waiting for its file passphrase / gate. */
    private var importPayload: ProfileBackup.BackupPayload? = null

    private var gateSeq = 0
    private var afterGate: (() -> Unit)? = null

    /** Source of [PassphrasePrompt.id] — see that field's doc. */
    private var promptSeq = 0

    /** Flipped by [claimNotificationPrompt]; see it for why once is enough. */
    private var notificationPromptClaimed = false

    /** Credential array codec for the sealed vault blob — the plaintext
     *  array exists only as the transient string between (de)serialization
     *  and the cipher. */
    private val credentialsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        viewModelScope.launch {
            firstRunDone = appState.firstRunDone()
        }
        viewModelScope.launch {
            repo.observeProfiles().collect { list ->
                profiles = list
                appTheme = BuiltInThemes.resolveOrDefault(
                    (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.themeJson ?: ""
                )
                observeTabCounts(list)
            }
        }
        viewModelScope.launch {
            val url = appState.externalUrl()
            if (url != null) pendingExternalUrl = url
        }
    }

    /**
     * Live tab counts, one generation at a time. Each observeProfiles
     * emission REPLACES the previous generation: without cancelling, every
     * emission stacked a fresh set of per-profile collectors on top of the
     * old ones (which kept counting even for deleted profiles).
     */
    private fun observeTabCounts(list: List<Profile>) {
        tabCountsJob?.cancel()
        if (list.isEmpty()) {
            tabCounts = emptyMap()
            tabCountsJob = null
            return
        }
        tabCountsJob = viewModelScope.launch {
            val snapshot = mutableMapOf<String, Int>()
            kotlinx.coroutines.coroutineScope {
                list.forEach { p ->
                    launch {
                        graph.browserRepo.observeTabCount(p.id).collect { count ->
                            snapshot[p.id.value] = count
                            tabCounts = snapshot.toMap()
                        }
                    }
                }
            }
        }
    }

    fun createProfile(
        name: String,
        icon: String,
        colorArgb: Long,
        settings: ProfileSettings = ProfileSettings(),
        onCreated: (Profile) -> Unit
    ) {
        viewModelScope.launch {
            runCatching { profileManager.create(name, icon, colorArgb, settings) }
                .onSuccess {
                    message = "Profile \"${it.name}\" is ready"
                    onCreated(it)
                }
                .onFailure { message = it.message ?: "Could not create profile" }
        }
    }

    fun duplicateProfile(id: ProfileId, options: CopyOptions) {
        viewModelScope.launch {
            runCatching { profileManager.duplicate(id, options) }
                .onSuccess { message = "Duplicated as \"${it.name}\" (new isolated storage)" }
                .onFailure { message = it.message ?: "Could not duplicate profile" }
        }
    }

    /**
     * The "Edit Profile" dialog's single save.
     *
     * It used to be two calls — renameProfile() followed by restyleProfile()
     * — and that was a lost-update race: two coroutines, two independent
     * get → copy → put round trips on the same row, both reading before
     * either wrote. Whichever put landed second discarded the other's field,
     * so the new name OR the new icon/color vanished while the snackbar still
     * said the save had worked. [ProfileManager.update] does it in one read
     * and one write, and both former entry points are gone so the race cannot
     * be reintroduced by calling them in sequence again.
     */
    fun updateProfile(id: ProfileId, name: String, icon: String?, colorArgb: Long?) {
        viewModelScope.launch {
            runCatching { profileManager.update(id, name, icon, colorArgb) }
                .onSuccess { message = "Renamed (storage identity unchanged)" }
                .onFailure { message = it.message ?: "Could not update profile" }
        }
    }

    /**
     * Lock / unlock a profile.
     *
     * The bare launch this used to be let ProfileManager's "Profile not
     * found" escape a coroutine with no handler: tapping the lock entry on a
     * row the ':browser' process had just deleted CRASHED the launcher
     * instead of reporting it. Same runCatching shape as every sibling.
     */
    fun setLocked(id: ProfileId, locked: Boolean) {
        viewModelScope.launch {
            runCatching { profileManager.setLocked(id, locked) }
                .onSuccess { message = if (locked) "Profile locked" else "Profile unlocked" }
                .onFailure { message = it.message ?: "Could not change the profile lock" }
        }
    }

    /** Same unguarded-throw fix as [setLocked]: a vanished row must report,
     *  not crash the launcher. */
    fun setDefault(id: ProfileId) {
        viewModelScope.launch {
            runCatching { profileManager.setDefault(id) }
                .onSuccess { message = "Default profile updated" }
                .onFailure { message = it.message ?: "Could not change the default profile" }
        }
    }

    fun deleteProfile(id: ProfileId) {
        viewModelScope.launch {
            runCatching { profileManager.delete(id) }
                .onSuccess { message = "Profile deleted with all its data" }
                .onFailure { message = it.message ?: "Could not delete profile" }
        }
    }

    /**
     * Same unguarded-throw fix as [setLocked], plus the success message is
     * now actually conditional on success: it was posted unconditionally
     * right after the call, so a reset that threw told the user their
     * browsing data was gone when nothing had been touched.
     */
    fun resetProfile(id: ProfileId) {
        viewModelScope.launch {
            runCatching { profileManager.resetData(id) }
                .onSuccess { message = "Profile browsing data reset" }
                .onFailure { message = it.message ?: "Could not reset profile data" }
        }
    }

    // ---------- Notification permission (API 33+) ----------

    /**
     * Claims this session's single POST_NOTIFICATIONS prompt: true for the
     * first caller, false ever after.
     *
     * The framework answers a twice-denied permission INSTANTLY with no UI,
     * so an onCreate that asks unconditionally re-asks on every Activity
     * recreation — a rotation would re-post the hint below forever. The
     * ViewModel outlives configuration changes, which makes "once per
     * session" the natural scope for both the ask and its explanation.
     */
    fun claimNotificationPrompt(): Boolean {
        if (notificationPromptClaimed) return false
        notificationPromptClaimed = true
        return true
    }

    /**
     * The POST_NOTIFICATIONS prompt came back denied.
     *
     * The launcher used to discard that result entirely, so download
     * completions and the agent's progress notification simply never appeared
     * and nothing said why. The hint rides the normal snackbar channel and
     * carries the only tap that can fix it (an app cannot re-prompt once the
     * user has denied twice — only the system screen can grant it).
     */
    fun onNotificationPermissionDenied() {
        message = "Notifications are off — downloads and agent progress stay silent"
        messageAction = MessageAction.OPEN_NOTIFICATION_SETTINGS
    }

    // ---------- Backup v2: export ----------

    /** True once the vault's session gate has passed (no re-gate needed). */
    private fun vaultUnlocked(): Boolean = graph.credentialRepo.isUnlocked.value

    /**
     * Export step 1 — the dialog confirmed. Saved passwords ALWAYS ride
     * along, so reading them is gated: if the vault is still locked for this
     * session, the UI gate runs first ([vaultGateRequest]); on failure the
     * export aborts with a message and nothing is built.
     */
    fun startExport(profile: Profile, includeBookmarks: Boolean) {
        exportDraft = ExportDraft(profile, includeBookmarks)
        pendingExport = null
        passphrasePrompt = null
        if (vaultUnlocked()) {
            readVaultForExport()
        } else {
            requestVaultGate { readVaultForExport() }
        }
    }

    /** Export step 2 — the gate passed (or the session was already unlocked):
     *  unlock the repo and read the profile's credentials. */
    private fun readVaultForExport() {
        val draft = exportDraft ?: return
        viewModelScope.launch {
            runCatching {
                // The gate just ran (MainScreen); record it for the session.
                graph.credentialRepo.unlock()
                graph.credentialRepo.exportAll(draft.profile.id)
            }.onSuccess { creds ->
                if (creds.isEmpty()) {
                    // No saved logins → nothing to seal; the file has no vault.
                    buildExport(creds, vault = null)
                } else {
                    exportCredentials = creds
                    passphrasePrompt = PassphrasePrompt(
                        forExport = true,
                        profileName = draft.profile.name,
                        credentialCount = creds.size,
                        id = ++promptSeq
                    )
                }
            }.onFailure {
                abortExport(it.message ?: "could not read the password vault")
            }
        }
    }

    /** Export step 3 (only with ≥1 credential) — the passphrase was set:
     *  seal the credential array (plaintext only inside the cipher) and
     *  continue. The passphrase CharArray is wiped immediately after use. */
    fun confirmExportPassphrase(passphrase: String) {
        val creds = exportCredentials
        if (creds.isEmpty()) {
            passphrasePrompt = null
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                val plaintext = credentialsJson.encodeToString(
                    ListSerializer(SavedCredential.serializer()), creds
                )
                val chars = passphrase.toCharArray()
                // PBKDF2 (210k iterations) is real CPU work — off the main
                // thread; wipe happens on the same worker, right after use.
                withContext(Dispatchers.Default) {
                    try {
                        ProfileBackup.VaultBackup.from(PasswordVaultCrypto.encrypt(plaintext, chars))
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            }
            passphrasePrompt = null
            outcome
                .onSuccess { buildExport(creds, it) }
                .onFailure {
                    abortExport(it.message ?: "could not seal the password vault")
                }
        }
    }

    /** Export step 4 — assemble the v2 payload (populated bookmarks /
     *  permissions / settings) and stage it for delivery. */
    private suspend fun buildExport(creds: List<SavedCredential>, vault: ProfileBackup.VaultBackup?) {
        val draft = exportDraft ?: return
        runCatching {
            val id = draft.profile.id
            ProfileBackup.serialize(
                ProfileBackup.BackupPayload(
                    profile = draft.profile,
                    bookmarks = if (draft.includeBookmarks) {
                        graph.browserRepo.bookmarks(id).map {
                            ProfileBackup.BookmarkExport(it.url, it.title, it.folder, it.position)
                        }
                    } else {
                        emptyList()
                    },
                    sitePermissions = graph.browserRepo.permissions(id).map {
                        ProfileBackup.SitePermissionExport(it.host, it.permission, it.decision)
                    },
                    siteSettings = graph.browserRepo.allSiteSettings(id).map {
                        ProfileBackup.SiteSettingExport(
                            host = it.host,
                            shieldsDisabled = it.shieldsDisabled,
                            jsEnabled = it.jsEnabled,
                            cookiesBlocked = it.cookiesBlocked,
                            desktopMode = it.desktopMode,
                            autoplayBlocked = it.autoplayBlocked,
                            popupBlocked = it.popupBlocked
                        )
                    },
                    vault = vault
                )
            )
        }.onSuccess { json ->
            exportCredentials = emptyList() // plaintext list dropped for good
            exportDraft = null
            pendingExport = PendingExport(
                fileName = exportFileName(draft.profile.name),
                json = json,
                sizeBytes = json.toByteArray(Charsets.UTF_8).size
            )
        }.onFailure {
            abortExport(it.message ?: "could not build the export")
        }
    }

    /** SAF save — write the staged export where the user picked. On failure
     *  the export stays staged so Save can be retried or Share used. */
    fun writeExportTo(uri: Uri) {
        val export = pendingExport ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("the selected location is not writable")
                    out.use { it.write(export.json.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                pendingExport = null
                message = "Exported \"${export.fileName}\""
            }.onFailure {
                message = "Export failed — ${it.message ?: "could not write the file"}"
            }
        }
    }

    /** The staged export was delivered another way (Share): drop it. */
    fun consumePendingExport() {
        pendingExport = null
    }

    /** User canceled at a dialog — drop everything quietly but visibly. */
    fun cancelExport() {
        dropExportState()
        message = "Export canceled"
    }

    /** The SAF picker closed without a location — drop the staged export
     *  silently (the user just backed out; that is not a failure). */
    fun discardExport() {
        dropExportState()
    }

    /** A step of the export failed — abort with a clear message; no file
     *  was (partially) built anywhere. */
    private fun abortExport(reason: String) {
        dropExportState()
        message = "Export aborted — $reason"
    }

    private fun dropExportState() {
        exportDraft = null
        exportCredentials = emptyList()
        passphrasePrompt = null
        pendingExport = null
    }

    // ---------- Backup v2: import ----------

    /** SAF import — read the picked file (any JSON picker result), then the
     *  same validate-first path as pasted text. */
    fun readImportFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("the selected file could not be read")
                }
            }.onSuccess { importProfile(it) }
                .onFailure {
                    importError = "Import failed — ${it.message ?: "could not read the file"}"
                }
        }
    }

    /**
     * Import step 1 — validate. Anything unsupported is rejected with a
     * dialog BEFORE a single row is written.
     */
    fun importProfile(text: String) {
        when (val parsed = ProfileBackup.parse(text)) {
            is ProfileBackupResult.Malformed ->
                importError = "Not a valid Room Browser export (${parsed.detail}). Nothing was imported."
            is ProfileBackupResult.InvalidVersion ->
                importError =
                    "The export uses format v${parsed.found}, newer than this app supports " +
                        "(v${parsed.maxSupported}). Update the app and try again. Nothing was imported."
            is ProfileBackupResult.Parsed -> {
                importPayload = parsed.payload
                if (parsed.payload.vault == null) {
                    // No sealed vault → nothing touches the device vault; the
                    // restore needs no gate and no passphrase.
                    finalizeImport(emptyList())
                } else {
                    passphrasePrompt = PassphrasePrompt(
                        forExport = false,
                        profileName = parsed.payload.profile.name,
                        credentialCount = 0,
                        id = ++promptSeq
                    )
                }
            }
        }
    }

    /**
     * Import step 2 (v2 with vault) — the file's passphrase: decrypt the
     * vault or stay in the dialog for a retry. Wrong passphrase NEVER writes
     * anything; a corrupt vault aborts the whole import.
     */
    fun confirmImportPassphrase(passphrase: String) {
        val vault = importPayload?.vault ?: run {
            passphrasePrompt = null
            return
        }
        viewModelScope.launch {
            val chars = passphrase.toCharArray()
            val decrypted = try {
                withContext(Dispatchers.Default) {
                    try {
                        PasswordVaultCrypto.decrypt(vault.toCipherData(), chars)
                    } finally {
                        PasswordVaultCrypto.wipe(chars)
                    }
                }
            } catch (e: VaultAuthException) {
                // Wrong passphrase — let the user retry in the same dialog.
                // A fresh id goes with it: the retry prompt is otherwise
                // identical to the previous one from the SECOND wrong attempt
                // on, and the dialog's remember key would stop changing (the
                // typed secret would survive into the retry).
                passphrasePrompt = passphrasePrompt?.copy(
                    error = "Wrong passphrase — try again",
                    id = ++promptSeq
                )
                return@launch
            } catch (e: VaultFormatException) {
                passphrasePrompt = null
                importPayload = null
                importError = "The export's password vault is corrupt. Nothing was imported."
                return@launch
            }
            val creds = try {
                credentialsJson.decodeFromString(
                    ListSerializer(SavedCredential.serializer()), decrypted
                )
            } catch (e: SerializationException) {
                passphrasePrompt = null
                importPayload = null
                importError = "The export's password vault is unreadable. Nothing was imported."
                return@launch
            }
            passphrasePrompt = null
            if (creds.isNotEmpty() && !vaultUnlocked()) {
                // Writing into the device vault is gated like reading it.
                requestVaultGate { finalizeImport(creds) }
            } else {
                finalizeImport(creds)
            }
        }
    }

    /**
     * Import final step — ONE Room transaction (see
     * ProfileRepositoryImpl.importBackup): profile row + bookmarks + site
     * permissions + site settings + credentials re-encrypted under the new
     * profile's device key. Any failure rolls the whole import back.
     *
     * Duplicate safety: the import NEVER reuses the file's UUID, so an
     * existing profile can never be overwritten — if the file's UUID or its
     * name already exists locally the import lands as a NEW profile named
     * "<name> (imported)" (with numeric disambiguation), mirroring
     * ProfileManager.duplicate's convention.
     */
    private fun finalizeImport(creds: List<SavedCredential>) {
        val payload = importPayload ?: return
        viewModelScope.launch {
            runCatching {
                val existing = profileManager.profiles()
                val name = uniqueImportName(
                    base = payload.profile.name,
                    existingNames = existing.map { it.name },
                    needsSuffix = existing.any { it.id == payload.profile.id }
                )
                val now = System.currentTimeMillis()
                val fresh = payload.profile.copy(
                    id = ProfileId.new(), // fresh identity — never overwrite
                    name = name,
                    isLocked = false, // a lock is a device-local concern
                    isDefault = existing.isEmpty(),
                    createdAt = now,
                    lastActiveAt = now
                    // settings + themeJson are preserved verbatim from the
                    // file (the import path's convention: no device
                    // randomization, no theme loss).
                )
                val summary = repo.importBackup(
                    profile = fresh,
                    bookmarks = payload.bookmarks,
                    sitePermissions = payload.sitePermissions,
                    siteSettings = payload.siteSettings
                ) {
                    // Runs INSIDE the transaction: importAll re-encrypts every
                    // password under THIS device's key for the new profile id
                    // (fresh UUIDs, canonical domains). Skipped entirely when
                    // there is nothing to write, so a vault-less import needs
                    // no unlock.
                    if (creds.isNotEmpty()) {
                        graph.credentialRepo.importAll(fresh.id, creds)
                    }
                }
                Triple(summary.profile.name, summary.bookmarks, creds.size)
            }.onSuccess { (name, bookmarkCount, credentialCount) ->
                importPayload = null
                val details = buildString {
                    append(if (bookmarkCount == 1) "1 bookmark" else "$bookmarkCount bookmarks")
                    if (credentialCount > 0) {
                        append(if (credentialCount == 1) ", 1 password" else ", $credentialCount passwords")
                    }
                }
                message = "Imported \"$name\" ($details)"
            }.onFailure {
                // Room rolled the transaction back — nothing half-imported.
                importPayload = null
                importError =
                    "Import failed — ${it.message ?: "the import was rolled back"}. Nothing was imported."
            }
        }
    }

    /** "<name>" when free, otherwise "<name> (imported)", "… 2", "… 3" —
     *  the same disambiguation ProfileManager.duplicate uses. */
    private fun uniqueImportName(base: String, existingNames: List<String>, needsSuffix: Boolean): String {
        val trimmed = base.trim().ifBlank { "Imported profile" }
        val taken = existingNames.map { it.lowercase() }.toSet()
        if (!needsSuffix && trimmed.lowercase() !in taken) return trimmed
        var candidate = "$trimmed (imported)"
        var i = 2
        while (candidate.lowercase() in taken) {
            candidate = "$trimmed (imported) $i"
            i++
        }
        return candidate
    }

    /** The user backed out of the passphrase step — cancel the whole
     *  action quietly but visibly. */
    fun cancelPassphrasePrompt() {
        val wasExport = passphrasePrompt?.forExport == true
        passphrasePrompt = null
        dropExportState()
        importPayload = null
        message = if (wasExport) "Export canceled" else "Import canceled"
    }

    fun dismissImportError() {
        importError = null
    }

    // ---------- Vault gate plumbing (the screen owns BiometricGate) ----------

    private fun requestVaultGate(andThen: () -> Unit) {
        afterGate = andThen
        vaultGateRequest = VaultGateRequest(++gateSeq)
    }

    /** The screen reports the biometric gate's outcome. A failed gate aborts
     *  whatever asked for it — the vault stays locked, nothing is read,
     *  written or built. */
    fun onVaultGateResult(success: Boolean) {
        val andThen = afterGate
        afterGate = null
        vaultGateRequest = null
        if (!success) {
            dropExportState()
            importPayload = null
            message = "Vault not unlocked — nothing was exported or imported"
            return
        }
        andThen?.invoke()
    }

    // ---------- Export file naming ----------

    /** "room-browser-profile-<sanitized name>.json" — safe for any file
     *  picker: letters/digits/-/_ only, blank names fall back. */
    private fun exportFileName(profileName: String): String {
        val safe = profileName.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").trim('-').take(40)
        return "room-browser-profile-${if (safe.isBlank()) "export" else safe}.json"
    }

    // ---------- External link routing ----------

    fun consumeExternalUrl() {
        viewModelScope.launch {
            appState.setExternalUrl(null)
            pendingExternalUrl = null
        }
    }

    fun submitExternalUrl(url: String?) {
        pendingExternalUrl = url
        viewModelScope.launch { appState.setExternalUrl(url) }
    }

    fun isUrl(text: String): Boolean = UrlIntelligence.looksLikeUrl(text)

    fun setFirstRunDone() {
        viewModelScope.launch { appState.setFirstRunDone() }
    }

    /** Drops the message AND whatever follow-up it carried — the action must
     *  never outlive the message it belonged to. */
    fun clearMessage() {
        message = null
        messageAction = null
    }
}
