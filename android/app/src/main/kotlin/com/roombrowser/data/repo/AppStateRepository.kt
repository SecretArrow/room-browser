package com.roombrowser.data.repo

import com.roombrowser.data.db.AppStateDao
import com.roombrowser.data.db.AppStateEntity
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.model.BrowserGlobalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Cross-process app state keys (Room-backed, multi-instance-safe). */
object AppStateKeys {
    const val GLOBAL_SETTINGS = "global_settings"
    const val ACTIVE_PROFILE_ID = "active_profile_id"
    const val FIRST_RUN_DONE = "first_run_done"
    const val WARNED_NETWORKS = "warned_networks"
    const val SUPPRESSED_IPS = "suppressed_ips"
    const val IP_CACHE = "network_ip_cache"
    const val EXTERNAL_URL = "external_url"
    const val SESSION_ID = "browser_session_id"
    const val AGENT_SETTINGS = "agent_settings"
    const val LOCAL_AI_SETTINGS = "local_ai_settings"
}

/** AI agent behavior settings (app-global, stored as JSON in app_state). */
@Serializable
data class AgentSettings(
    val enabled: Boolean = true,
    /**
     * Visibility of the floating "AI Agent" button on the browser surface.
     * HIDDEN by default — the agent stays reachable from the page-actions
     * menu, and the button (or live status pill) only appears when the
     * user opts in here (or while a task is running).
     */
    val showAgentButton: Boolean = false,
    val defaultProviderId: Long? = null,
    val defaultModel: String? = null,
    val temperature: Double = 0.2,
    val maxSteps: Int = 25,
    val confirmActions: Boolean = false,
    val includePageContext: Boolean = true,
    val systemPromptOverride: String? = null
)

@Serializable
data class IpCache(val ip: String?, val checkedAt: Long)

/**
 * Global settings + app state repository backed by the Room KV table so it
 * can be read from BOTH the main process and the ':browser' process.
 */
class AppStateRepository(private val dao: AppStateDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val globalSettings: Flow<BrowserGlobalSettings> =
        dao.observe(AppStateKeys.GLOBAL_SETTINGS).map { raw ->
            raw?.let { runCatching { json.decodeFromString(BrowserGlobalSettings.serializer(), it) }.getOrNull() }
                ?: BrowserGlobalSettings()
        }

    suspend fun globalSettingsSnapshot(): BrowserGlobalSettings =
        dao.get(AppStateKeys.GLOBAL_SETTINGS)?.let {
            runCatching { json.decodeFromString(BrowserGlobalSettings.serializer(), it) }.getOrNull()
        } ?: BrowserGlobalSettings()

    suspend fun saveGlobalSettings(settings: BrowserGlobalSettings) {
        dao.put(AppStateEntity(AppStateKeys.GLOBAL_SETTINGS, json.encodeToString(BrowserGlobalSettings.serializer(), settings)))
    }

    val activeProfileId: Flow<String?> = dao.observe(AppStateKeys.ACTIVE_PROFILE_ID)

    suspend fun activeProfileIdSnapshot(): String? = dao.get(AppStateKeys.ACTIVE_PROFILE_ID)

    suspend fun setActiveProfile(profileId: String?) {
        if (profileId == null) dao.remove(AppStateKeys.ACTIVE_PROFILE_ID)
        else dao.put(AppStateEntity(AppStateKeys.ACTIVE_PROFILE_ID, profileId))
    }

    suspend fun firstRunDone(): Boolean = dao.get(AppStateKeys.FIRST_RUN_DONE) == "true"

    suspend fun setFirstRunDone() {
        dao.put(AppStateEntity(AppStateKeys.FIRST_RUN_DONE, "true"))
    }

    suspend fun warnedNetworks(): Set<String> =
        deserializeSet(dao.get(AppStateKeys.WARNED_NETWORKS))

    suspend fun addWarnedNetwork(ip: String) {
        dao.put(AppStateEntity(AppStateKeys.WARNED_NETWORKS, serializeSet(warnedNetworks() + ip)))
    }

    suspend fun suppressedIps(): Set<String> =
        deserializeSet(dao.get(AppStateKeys.SUPPRESSED_IPS))

    suspend fun suppressIp(ip: String) {
        dao.put(AppStateEntity(AppStateKeys.SUPPRESSED_IPS, serializeSet(suppressedIps() + ip)))
    }

    suspend fun ipCache(): IpCache? = dao.get(AppStateKeys.IP_CACHE)?.let {
        runCatching { json.decodeFromString(IpCache.serializer(), it) }.getOrNull()
    }

    suspend fun setIpCache(cache: IpCache) {
        dao.put(AppStateEntity(AppStateKeys.IP_CACHE, json.encodeToString(IpCache.serializer(), cache)))
    }

    suspend fun externalUrl(): String? = dao.get(AppStateKeys.EXTERNAL_URL)

    suspend fun setExternalUrl(url: String?) {
        if (url == null) dao.remove(AppStateKeys.EXTERNAL_URL)
        else dao.put(AppStateEntity(AppStateKeys.EXTERNAL_URL, url))
    }

    suspend fun newSessionId(): String {
        val id = java.util.UUID.randomUUID().toString()
        dao.put(AppStateEntity(AppStateKeys.SESSION_ID, id))
        return id
    }

    suspend fun sessionId(): String? = dao.get(AppStateKeys.SESSION_ID)

    // ---------- AI agent settings ----------

    val agentSettings: Flow<AgentSettings> =
        dao.observe(AppStateKeys.AGENT_SETTINGS).map { raw ->
            raw?.let { runCatching { json.decodeFromString(AgentSettings.serializer(), it) }.getOrNull() }
                ?: AgentSettings()
        }

    suspend fun agentSettingsSnapshot(): AgentSettings =
        dao.get(AppStateKeys.AGENT_SETTINGS)?.let {
            runCatching { json.decodeFromString(AgentSettings.serializer(), it) }.getOrNull()
        } ?: AgentSettings()

    suspend fun saveAgentSettings(settings: AgentSettings) {
        // Non-cancellable persistence primitive: finishing the calling
        // activity mid-write must never lose the agent settings (e.g. the
        // default provider/model selected right after saving a provider).
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            dao.put(AppStateEntity(AppStateKeys.AGENT_SETTINGS, json.encodeToString(AgentSettings.serializer(), settings)))
        }
    }

    // ---------- Local AI (Ollama) tuning ----------

    val localAiTuning: Flow<LocalAiTuning> =
        dao.observe(AppStateKeys.LOCAL_AI_SETTINGS).map { raw ->
            raw?.let { runCatching { json.decodeFromString(LocalAiTuning.serializer(), it) }.getOrNull() }
                ?: LocalAiTuning()
        }

    suspend fun localAiTuningSnapshot(): LocalAiTuning =
        dao.get(AppStateKeys.LOCAL_AI_SETTINGS)?.let {
            runCatching { json.decodeFromString(LocalAiTuning.serializer(), it) }.getOrNull()
        } ?: LocalAiTuning()

    suspend fun saveLocalAiTuning(tuning: LocalAiTuning) {
        // Same non-cancellable persistence primitive as saveAgentSettings:
        // a tuning save (or a backup import restoring host + GPU layers)
        // racing the activity teardown must never write half its intent.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            dao.put(AppStateEntity(AppStateKeys.LOCAL_AI_SETTINGS, json.encodeToString(LocalAiTuning.serializer(), tuning)))
        }
    }

    private fun serializeSet(values: Set<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values.toList())

    private fun deserializeSet(raw: String?): Set<String> =
        raw?.let {
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        }?.toSet() ?: emptySet()
}
