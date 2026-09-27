package com.roombrowser.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.data.repo.AgentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Lightweight AI-settings controller for the AGENT SETTINGS ACTIVITIES
 * (AgentSettingsActivity / AgentProviderEditorActivity /
 * AgentSessionsActivity), which run in the DEFAULT process — no WebView,
 * no agent loop, just provider/model/behavior management.
 *
 * All writes go through the shared Room database (multi-instance
 * invalidation), so the live BrowserAgentController in the ':browser'
 * process observes every change instantly and the floating panel updates
 * without any manual refresh.
 */
class AgentSettingsController(
    application: Application,
    private val profileId: String?
) {

    private val graph = (application as RoomBrowserApp).graph
    private val repo: AgentRepository = graph.agentRepo
    private val appState = graph.appState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Plain HTTP stack is fine here: this client only lists /models while
    // the user is editing a provider. The agent's real turns keep using the
    // DoH-configured client inside the ':browser' process.
    private val callFactory: OkHttpClient = OkHttpClient()

    // ------------------------------------------------------------- UI state

    var providers by mutableStateOf<List<AgentProviderEntity>>(emptyList())
        private set
    var sessions by mutableStateOf<List<AgentSessionEntity>>(emptyList())
        private set
    var settings by mutableStateOf(AgentSettings())
        private set
    var activeProvider by mutableStateOf<AgentProviderEntity?>(null)
        private set
    var activeModel by mutableStateOf<String?>(null)
        private set

    /** The provider being edited (null while loading, or in add-mode). */
    var editing by mutableStateOf<AgentProviderEntity?>(null)
        private set
    /** True once the editing target has been resolved (add-mode resolves immediately). */
    var editingLoaded by mutableStateOf(false)
        private set

    // ------------------------------------------------------------- lifecycle

    fun start() {
        scope.launch {
            repo.providers.collect { list ->
                providers = list
                refreshSelection()
            }
        }
        scope.launch {
            appState.agentSettings.collect {
                settings = it
                refreshSelection()
            }
        }
        if (!profileId.isNullOrBlank()) {
            scope.launch {
                repo.observeSessions(profileId).collect { sessions = it }
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun refreshSelection() {
        val list = providers
        val preferred = settings.defaultProviderId?.let { id -> list.firstOrNull { it.id == id } }
        val provider = preferred ?: list.firstOrNull()
        activeProvider = provider
        val model = settings.defaultModel?.takeIf { it.isNotBlank() && provider != null }
            ?: provider?.defaultModel?.takeIf { it.isNotBlank() }
        activeModel = model
    }

    // ------------------------------------------------------------- providers

    /** Resolves the editing target for [id] (0 = add mode). */
    fun loadEditing(id: Long) {
        if (id <= 0L) {
            editing = null
            editingLoaded = true
            return
        }
        scope.launch {
            editing = runCatching { repo.provider(id) }.getOrNull()
            editingLoaded = true
        }
    }

    /** Persists a provider (encrypting the API key); blank key keeps the old one on edit. */
    suspend fun saveProvider(
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI
    ): Result<AgentProviderEntity> = AgentProviderStore.save(repo, id, name, baseUrl, apiKey, defaultModel, protocol)

    fun deleteProvider(id: Long) {
        scope.launch {
            runCatching { repo.deleteProvider(id) }
            if (settings.defaultProviderId == id) {
                saveSettings(settings.copy(defaultProviderId = null, defaultModel = null))
            }
        }
    }

    fun setDefault(provider: AgentProviderEntity, model: String) {
        scope.launch {
            saveSettings(settings.copy(defaultProviderId = provider.id, defaultModel = model))
        }
    }

    /**
     * Suspending variant of [setDefault] for callers that must guarantee the
     * write commits even when their own scope is being cancelled (the editor
     * wraps this in withContext(NonCancellable) so finishing the activity
     * mid-save can never lose the provider or the default selection).
     */
    suspend fun setDefaultNow(provider: AgentProviderEntity, model: String) {
        saveSettings(settings.copy(defaultProviderId = provider.id, defaultModel = model))
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        scope.launch { saveSettings(transform(settings)) }
    }

    private suspend fun saveSettings(new: AgentSettings) {
        runCatching { appState.saveAgentSettings(new) }
        settings = new
    }

    /**
     * Fetches the model list for a provider that is still being EDITED
     * (uses the typed base URL + API key, not stored credentials).
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI
    ): List<String> {
        val gateway = AgentGateways.forProvider(callFactory, baseUrl, apiKey, protocol)
        return gateway.listModels()
    }

    // ------------------------------------------------------------- sessions

    fun deleteSession(id: Long) {
        scope.launch { runCatching { repo.deleteSession(id) } }
    }

    /** Deletes every agent chat across ALL profiles (providers are kept). */
    fun clearAllSessions() {
        scope.launch { runCatching { repo.deleteAllSessions() } }
    }
}
