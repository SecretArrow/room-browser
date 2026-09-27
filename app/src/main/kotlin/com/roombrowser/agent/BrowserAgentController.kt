package com.roombrowser.agent

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.domain.agent.AgentEvent
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentLoop
import com.roombrowser.domain.agent.AgentPrompts
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.SearchEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.time.ZoneId
import kotlin.coroutines.resume

/** Display model for one row of the agent chat. */
sealed interface AgentEntry {
    data class User(val text: String, val at: Long) : AgentEntry
    data class Assistant(val text: String, val thinking: String, val streaming: Boolean, val at: Long) : AgentEntry
    data class Tool(
        val callId: String,
        val name: String,
        val label: String,
        val running: Boolean,
        val ok: Boolean,
        val summary: String,
        val at: Long
    ) : AgentEntry

    data class Notice(val text: String, val error: Boolean, val at: Long) : AgentEntry
}

/** A pending action that waits for the user's Allow/Deny decision. */
data class AgentApproval(
    val name: String,
    val label: String,
    val at: Long,
    val respond: (Boolean) -> Unit
)

/**
 * BrowserAgentController — ties everything together for ONE profile:
 *  - provider/model selection (settings UI state)
 *  - chat session lifecycle (create / continue / history)
 *  - the agent loop with streaming UI updates
 *  - action approvals when "confirm actions" is enabled
 *  - persistence of messages
 *
 * The controller runs in the ':browser' process (it needs the WebView).
 */
class BrowserAgentController(
    application: Application,
    private val profileId: ProfileId,
    private val vm: BrowserViewModel,
    httpClient: OkHttpClient
) {

    private val graph = (application as RoomBrowserApp).graph
    private val appContext: android.content.Context = application.applicationContext
    private val repo = graph.agentRepo
    private val appState = graph.appState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var callFactory: OkHttpClient = tuned(httpClient)

    // ------------------------------------------------------------- UI state

    var entries by mutableStateOf<List<AgentEntry>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var statusLine by mutableStateOf<String?>(null)
        private set
    var approval by mutableStateOf<AgentApproval?>(null)
        private set
    var activeSessionId by mutableStateOf<Long?>(null)
        private set
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
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    /** Cached /models results per provider id. */
    private val modelCache = HashMap<Long, List<String>>()
    /** Decrypted API keys — memory only, never persisted. */
    private val apiKeyCache = HashMap<Long, String>()

    private var turnJob: Job? = null

    val messages = MutableStateFlow<String?>(null)

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
        scope.launch {
            repo.observeSessions(profileId.value).collect { sessions = it }
        }
    }

    fun updateClient(client: OkHttpClient) {
        callFactory = tuned(client)
    }

    fun shutdown() {
        turnJob?.cancel()
        AgentForeground.stopCurrentTurn = null
        AgentForeground.finish()
        scope.cancel()
    }

    private fun tuned(client: OkHttpClient): OkHttpClient = client.newBuilder().build()

    private fun refreshSelection() {
        val list = providers
        val preferred = settings.defaultProviderId?.let { id -> list.firstOrNull { it.id == id } }
        val provider = preferred ?: list.firstOrNull()
        activeProvider = provider
        val model = settings.defaultModel?.takeIf { it.isNotBlank() && provider != null }
            ?: provider?.defaultModel?.takeIf { it.isNotBlank() }
        activeModel = model
    }

    // ------------------------------------------------------------- provider & model

    fun apiKeyFor(provider: AgentProviderEntity): String? {
        if (provider.apiKeyEnc.isBlank()) return ""
        apiKeyCache[provider.id]?.let { return it }
        val key = KeyStoreCrypto.decrypt(provider.apiKeyEnc)
        if (key != null) apiKeyCache[provider.id] = key
        return key
    }

    /** Persists a provider (encrypting the API key); blank key keeps the old one on edit. */
    suspend fun saveProvider(
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String
    ): Result<AgentProviderEntity> {
        val result = AgentProviderStore.save(repo, id, name, baseUrl, apiKey, defaultModel)
        if (result.isSuccess && id != null) apiKeyCache.remove(id)
        return result
    }

    suspend fun deleteProvider(id: Long) {
        repo.deleteProvider(id)
        apiKeyCache.remove(id)
        modelCache.remove(id)
        if (settings.defaultProviderId == id) {
            appState.saveAgentSettings(settings.copy(defaultProviderId = null, defaultModel = null))
        }
    }

    /** Fetches the provider's /models list (cached unless [force]). */
    suspend fun modelsFor(provider: AgentProviderEntity, force: Boolean = false): List<String> {
        if (!force) modelCache[provider.id]?.let { return it }
        modelsLoading = true
        modelsError = null
        try {
            val key = apiKeyFor(provider).orEmpty()
            val gateway = OkHttpAgentGateway(callFactory, provider.baseUrl, key)
            val models = gateway.listModels()
            modelCache[provider.id] = models
            return models
        } catch (t: Throwable) {
            modelsError = t.friendlyMessage()
            throw t
        } finally {
            modelsLoading = false
        }
    }

    /**
     * Fetches /models for a provider that is still being EDITED (uses the
     * typed base URL + API key, not stored credentials).
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        modelsLoading = true
        modelsError = null
        try {
            val gateway = OkHttpAgentGateway(callFactory, baseUrl, apiKey)
            return gateway.listModels()
        } catch (t: Throwable) {
            modelsError = t.friendlyMessage()
            throw t
        } finally {
            modelsLoading = false
        }
    }

    fun setDefault(provider: AgentProviderEntity, model: String) {
        scope.launch {
            appState.saveAgentSettings(
                settings.copy(defaultProviderId = provider.id, defaultModel = model)
            )
            activeProvider = provider
            activeModel = model
            messages.value = "Agent set to ${provider.name} · $model"
        }
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        scope.launch {
            val new = transform(settings)
            appState.saveAgentSettings(new)
            settings = new
        }
    }

    // ------------------------------------------------------------- sessions

    fun newSession() {
        if (running) return
        activeSessionId = null
        entries = emptyList()
    }

    fun openSession(id: Long) {
        if (running) return
        scope.launch {
            val session = repo.session(id) ?: return@launch
            activeSessionId = session.id
            entries = repo.messages(id).mapNotNull(::entryFromRow)
        }
    }

    fun deleteSession(id: Long) {
        scope.launch {
            repo.deleteSession(id)
            if (activeSessionId == id) newSession()
        }
    }

    fun clearAllSessions() {
        scope.launch {
            repo.deleteSessionsForProfile(profileId.value)
            newSession()
            messages.value = "Agent sessions cleared"
        }
    }

    private fun entryFromRow(row: AgentMessageEntity): AgentEntry? = when (row.role) {
        "user" -> AgentEntry.User(row.content, row.createdAt)
        "assistant" -> AgentEntry.Assistant(row.content, "", streaming = false, row.createdAt)
        "tool" -> AgentEntry.Tool(
            callId = "row_${row.id}",
            name = row.toolName ?: "tool",
            label = AgentTools.describeTool(row.toolName ?: "", row.toolArgs),
            running = false,
            ok = !row.content.startsWith("ERROR:"),
            summary = row.content.take(200),
            at = row.createdAt
        )
        else -> null
    }

    // ------------------------------------------------------------- turn execution

    fun send(text: String, includePage: Boolean) {
        val message = text.trim()
        if (message.isEmpty() || running) return
        val provider = activeProvider ?: run {
            messages.value = "Configure an AI provider first (Agent → settings)"
            return
        }
        val model = activeModel ?: provider.defaultModel
        if (model.isBlank()) {
            messages.value = "Pick a model for ${provider.name} first"
            return
        }
        turnJob = scope.launch { runTurn(message, includePage, provider, model) }
    }

    fun stop() {
        val job = turnJob ?: return
        turnJob = null
        job.cancel()
        running = false
        statusLine = null
        approval = null
        entries = entries + AgentEntry.Notice("Stopped by user", error = true, at = System.currentTimeMillis())
    }

    fun respondApproval(allow: Boolean) {
        approval?.let { it.respond(allow) }
        approval = null
    }

    private suspend fun runTurn(text: String, includePage: Boolean, provider: AgentProviderEntity, model: String) {
        running = true
        // Background mode: foreground service + wake lock so the turn keeps
        // running when the user leaves the app or the screen turns off.
        AgentForeground.stopCurrentTurn = { stop() }
        AgentForeground.begin(appContext)
        AgentForeground.status("Working: " + text.take(60))
        setStatus(null)
        entries = entries + AgentEntry.User(text, System.currentTimeMillis())
        var streamingIndex = -1
        try {
            // "Delete all agent chats" can run in the settings ACTIVITY while
            // a session is active here — verify it still exists, else start a
            // fresh one instead of writing to a dead row (FK safety).
            val sessionId = activeSessionId
                ?.takeIf { runCatching { repo.session(it) != null }.getOrDefault(false) }
                ?: repo.createSession(
                    profileId = profileId.value,
                    title = text.take(64),
                    providerId = provider.id,
                    model = model
                ).also { activeSessionId = it }
            repo.addMessage(sessionId, "user", text)

            val apiKey = apiKeyFor(provider).orEmpty()
            val executor = AgentToolExecutor(vm) { name, label -> requestApproval(name, label) }
            val gateway = OkHttpAgentGateway(callFactory, provider.baseUrl, apiKey)
            val engine = SearchEngines.byId(vm.profileSettings().searchEngineId).label
            val prompt = settings.systemPromptOverride?.takeIf { it.isNotBlank() }
                ?: AgentPrompts.render(System.currentTimeMillis(), ZoneId.systemDefault(), engine)
            val config = com.roombrowser.domain.agent.AgentConfig(
                model = model,
                maxSteps = settings.maxSteps,
                temperature = settings.temperature,
                systemPrompt = prompt
            )

            // Rebuild the conversation: system + prior user/assistant rows + this turn.
            val history = mutableListOf(ChatMessage(role = "system", content = prompt))
            repo.messages(sessionId)
                .filter { it.role == "user" || it.role == "assistant" }
                .forEach { history.add(ChatMessage(role = it.role, content = it.content)) }
            if (includePage) {
                executor.snapshotContext()?.let {
                    history.add(ChatMessage(role = "user", content = "$it\n\n(The user's request follows.)"))
                }
            }
            history.add(ChatMessage(role = "user", content = text))

            val loop = AgentLoop(gateway, executor, config)
            loop.runTurn(history) { event ->
                when (event) {
                    is AgentEvent.AssistantText -> {
                        streamingIndex = upsertStreamingAssistant(streamingIndex, event.text, null)
                        setStatus("Writing…")
                    }
                    is AgentEvent.AssistantThinking -> {
                        streamingIndex = upsertStreamingAssistant(streamingIndex, "", event.text)
                        setStatus("Thinking…")
                    }
                    is AgentEvent.ToolStarted -> {
                        streamingIndex = finalizeAssistant(streamingIndex)
                        entries = entries + AgentEntry.Tool(
                            callId = event.id,
                            name = event.name,
                            label = AgentTools.describeTool(event.name, event.argsJson),
                            running = true,
                            ok = true,
                            summary = "",
                            at = System.currentTimeMillis()
                        )
                        setStatus(AgentTools.describeTool(event.name, event.argsJson))
                    }
                    is AgentEvent.ToolFinished -> {
                        updateToolEntry(event.id) { it.copy(running = false, ok = event.ok, summary = event.summary) }
                        setStatus("Step done")
                        repo.addMessage(
                            sessionId, "tool",
                            (if (event.ok) "" else "ERROR: ") + event.summary,
                            toolName = event.name,
                            toolResult = event.summary
                        )
                    }
                    is AgentEvent.FinalAnswer -> {
                        streamingIndex = finalizeAssistant(streamingIndex)
                        entries = entries + AgentEntry.Assistant(
                            text = event.text,
                            thinking = "",
                            streaming = false,
                            at = System.currentTimeMillis()
                        )
                        repo.addMessage(sessionId, "assistant", event.text)
                        repo.touchSession(sessionId)
                        setStatus(null)
                    }
                    is AgentEvent.AgentError -> {
                        streamingIndex = finalizeAssistant(streamingIndex)
                        entries = entries + AgentEntry.Notice(
                            text = "Agent error: ${event.message}",
                            error = true,
                            at = System.currentTimeMillis()
                        )
                        repo.addMessage(sessionId, "assistant", "⚠ error: ${event.message}")
                        setStatus(null)
                    }
                    is AgentEvent.Notice -> {
                        entries = entries + AgentEntry.Notice(event.text, error = false, at = System.currentTimeMillis())
                    }
                }
            }
            repo.touchSession(sessionId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            entries = entries + AgentEntry.Notice(
                text = "Agent error: ${t.friendlyMessage()}",
                error = true,
                at = System.currentTimeMillis()
            )
        } finally {
            running = false
            setStatus(null)
            approval = null
            AgentForeground.stopCurrentTurn = null
            AgentForeground.finish()
        }
    }

    // ------------------------------------------------------------- entry helpers

    /** Finds-or-appends the streaming assistant entry; appends text/thinking. */
    private fun upsertStreamingAssistant(index: Int, text: String, thinking: String?): Int {
        val i = if (index >= 0 && index < entries.size &&
            entries[index] is AgentEntry.Assistant && (entries[index] as AgentEntry.Assistant).streaming
        ) index else {
            entries = entries + AgentEntry.Assistant("", "", streaming = true, at = System.currentTimeMillis())
            entries.size - 1
        }
        val current = entries[i] as AgentEntry.Assistant
        entries = entries.toMutableList().apply {
            set(i, current.copy(text = current.text + text, thinking = current.thinking + (thinking ?: "")))
        }
        return i
    }

    /** Marks the streaming assistant entry as finished (if one exists). */
    private fun finalizeAssistant(index: Int): Int {
        if (index >= 0 && index < entries.size) {
            val entry = entries[index]
            if (entry is AgentEntry.Assistant && entry.streaming) {
                entries = entries.toMutableList().apply { set(index, entry.copy(streaming = false)) }
            }
        }
        return -1
    }

    private fun updateToolEntry(callId: String, transform: (AgentEntry.Tool) -> AgentEntry.Tool) {
        val index = entries.indexOfFirst { it is AgentEntry.Tool && it.callId == callId }
        if (index >= 0) {
            entries = entries.toMutableList().apply {
                set(index, transform(get(index) as AgentEntry.Tool))
            }
        }
    }

    // ------------------------------------------------------------- approvals

    /** Sets the in-app status line and mirrors it to the background notification. */
    private fun setStatus(text: String?) {
        statusLine = text
        AgentForeground.status(text ?: "Working…")
    }

    private suspend fun requestApproval(name: String, label: String): Boolean {
        if (!settings.confirmActions) return true
        setStatus("Approve? $label")
        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    approval = AgentApproval(name, label, SystemClock.elapsedRealtime()) { allow ->
                        if (continuation.isActive) continuation.resume(allow)
                    }
                }
            }
        } catch (ce: CancellationException) {
            false // timeout or turn cancelled → deny
        } finally {
            approval = null
        }
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is AgentHttpException -> when (code) {
            401, 403 -> "authentication failed ($code) — check the API key"
            404 -> "endpoint not found (404) — check the base URL (…/v1)"
            429 -> "rate limited (429) — try again later"
            in 500..599 -> "provider server error ($code)"
            -1 -> message ?: "network error"
            else -> "HTTP $code ${body.take(120)}"
        }
        else -> message ?: javaClass.simpleName
    }

    companion object {
        private const val APPROVAL_TIMEOUT_MS = 120_000L
    }
}
