package com.roombrowser.main

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.domain.profile.ProfileManager
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.export.ProfileBackup
import com.roombrowser.domain.theme.BuiltInThemes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Main-process ViewModel: profile CRUD, first-run state, external-link
 * routing ("Open with profile" — never silently opens the wrong profile).
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
    var firstRunDone by mutableStateOf(true)
        private set
    var pendingExternalUrl by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

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

    /** Observe live tab counts per profile (new coroutine per profile). */
    private fun observeTabCounts(list: List<Profile>) {
        viewModelScope.launch {
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

    fun refreshTabCounts() {
        viewModelScope.launch {
            val counts = mutableMapOf<String, Int>()
            profiles.forEach { p ->
                counts[p.id.value] = graph.browserRepo.openTabs(p.id).size
            }
            tabCounts = counts
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

    fun renameProfile(id: ProfileId, newName: String) {
        viewModelScope.launch {
            runCatching { profileManager.rename(id, newName) }
                .onSuccess { message = "Renamed (storage identity unchanged)" }
                .onFailure { message = it.message ?: "Could not rename profile" }
        }
    }

    fun restyleProfile(id: ProfileId, icon: String?, colorArgb: Long?) {
        viewModelScope.launch {
            runCatching { profileManager.restyle(id, icon, colorArgb) }
                .onFailure { message = it.message ?: "Could not update profile" }
        }
    }

    fun setLocked(id: ProfileId, locked: Boolean) {
        viewModelScope.launch { profileManager.setLocked(id, locked) }
    }

    fun setDefault(id: ProfileId) {
        viewModelScope.launch {
            profileManager.setDefault(id)
            message = "Default profile updated"
        }
    }

    fun deleteProfile(id: ProfileId) {
        viewModelScope.launch {
            runCatching { profileManager.delete(id) }
                .onSuccess { message = "Profile deleted with all its data" }
                .onFailure { message = it.message ?: "Could not delete profile" }
        }
    }

    fun resetProfile(id: ProfileId) {
        viewModelScope.launch {
            profileManager.resetData(id)
            message = "Profile browsing data reset"
        }
    }

    /** Export profile settings (never cookies/sessions/credentials). */
    fun exportProfile(id: ProfileId, includeBookmarks: Boolean, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val profile = profileManager.profiles().first { it.id == id }
                val bookmarks = if (includeBookmarks) {
                    graph.browserRepo.bookmarks(id).map {
                        ProfileBackup.BookmarkExport(it.url, it.title, it.folder, it.position)
                    }
                } else emptyList()
                ProfileBackup.serialize(
                    ProfileBackup.BackupPayload(profile = profile, bookmarks = bookmarks)
                )
            }.onSuccess(onReady)
                .onFailure { message = it.message ?: "Export failed" }
        }
    }

    fun importProfile(json: String) {
        viewModelScope.launch {
            runCatching {
                val payload = ProfileBackup.deserialize(json)
                val created = profileManager.create(
                    name = payload.profile.name,
                    icon = payload.profile.icon,
                    colorArgb = payload.profile.colorArgb,
                    settings = payload.profile.settings
                )
                payload.bookmarks.forEach {
                    graph.browserRepo.addBookmark(created.id, it.url, it.title, it.folder)
                }
                created
            }.onSuccess { message = "Imported profile \"${it.name}\"" }
                .onFailure { message = it.message ?: "Import failed" }
        }
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

    fun clearMessage() { message = null }
}
