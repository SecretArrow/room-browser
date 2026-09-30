package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.repo.AgentRepository
import com.roombrowser.domain.agent.ToolMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared provider persistence used by BOTH processes:
 *  - BrowserAgentController (':browser' process — the live agent)
 *  - AgentSettingsController (default process — the AI settings activities)
 *
 * Keeping the logic in one place guarantees the add/edit form behaves the
 * same no matter which surface saved the provider.
 */
object AgentProviderStore {

    /**
     * Validates, normalizes and persists a provider (encrypting the API key
     * with AndroidKeyStore). A blank API key on EDIT keeps the stored
     * ciphertext; a blank key on CREATE stores "" (local servers need none).
     *
     * The write itself is NON-CANCELLABLE: this is a persistence primitive —
     * finishing the calling activity mid-save (user taps Back / the window
     * is torn down / a UI test clicks Close 80ms after Save) must NEVER
     * abort the Room transaction. CI evidence (run 36312695165): the save
     * coroutine was cancelled before it entered the NonCancellable block in
     * the editor, and the provider vanished with no error shown.
     */
    /**
     * [toolMode] is a [ToolMode] name, or null for "not mentioned by this
     * caller" — which KEEPS an edited provider's stored mode rather than
     * resetting it. Only the provider editor offers the choice; the other
     * call sites (Local AI's "use in chat", the browser's own save path) must
     * not silently undo it.
     */
    suspend fun save(
        repo: AgentRepository,
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI,
        toolMode: String? = null
    ): Result<AgentProviderEntity> = withContext(NonCancellable) {
        saveNow(repo, id, name, baseUrl, apiKey, defaultModel, protocol, toolMode)
    }

    private suspend fun saveNow(
        repo: AgentRepository,
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String,
        toolMode: String?
    ): Result<AgentProviderEntity> {
        val trimmedName = name.trim()
        val trimmedUrl = OkHttpAgentGateway.normalizeBaseUrl(baseUrl)
        val proto = when (protocol) {
            AgentProviderEntity.PROTOCOL_OPENCODE -> AgentProviderEntity.PROTOCOL_OPENCODE
            AgentProviderEntity.PROTOCOL_OLLAMA -> AgentProviderEntity.PROTOCOL_OLLAMA
            AgentProviderEntity.PROTOCOL_LOCAL -> AgentProviderEntity.PROTOCOL_LOCAL
            AgentProviderEntity.PROTOCOL_ANTHROPIC -> AgentProviderEntity.PROTOCOL_ANTHROPIC
            else -> AgentProviderEntity.PROTOCOL_OPENAI
        }
        if (trimmedName.isBlank()) return Result.failure(IllegalArgumentException("provider name is required"))
        // Base-URL validation is PROTOCOL-AWARE: only server protocols need
        // an http(s) endpoint. The on-device engine has no server at all —
        // the UI pre-fills the placeholder "local://engine" (any non-blank
        // value is accepted there; the gateway ignores it and resolves the
        // .gguf model from app-private storage instead).
        if (proto == AgentProviderEntity.PROTOCOL_LOCAL) {
            if (trimmedUrl.isBlank()) {
                return Result.failure(IllegalArgumentException("base URL is required"))
            }
        } else if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("base URL must start with http:// or https://"))
        }
        if (defaultModel.isBlank()) return Result.failure(IllegalArgumentException("model is required"))
        return try {
            val existing = id?.let { repo.provider(it) }
            // The on-device engine has no `tools` channel, so its turns are
            // ALWAYS rewritten into the text contract. Storing TEXT is what
            // that provider actually does; a stored "AUTO" would be a label
            // the runtime quietly contradicts.
            val mode = when {
                proto == AgentProviderEntity.PROTOCOL_LOCAL -> ToolMode.TEXT.name
                toolMode != null -> ToolMode.fromStored(toolMode).name
                else -> existing?.toolMode ?: AgentProviderEntity.TOOL_MODE_DEFAULT
            }
            val encKey = when {
                apiKey.isBlank() -> existing?.apiKeyEnc ?: ""
                else -> KeyStoreCrypto.encrypt(apiKey)
                    ?: return Result.failure(IllegalStateException("AndroidKeyStore unavailable — could not encrypt the API key"))
            }
            val entity = AgentProviderEntity(
                id = id ?: 0,
                name = trimmedName,
                baseUrl = trimmedUrl,
                apiKeyEnc = encKey,
                defaultModel = defaultModel.trim(),
                protocol = proto,
                toolMode = mode,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            val savedId = repo.saveProvider(entity)
            Result.success(entity.copy(id = savedId))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
