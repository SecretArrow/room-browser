package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.repo.AgentRepository
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
    suspend fun save(
        repo: AgentRepository,
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI
    ): Result<AgentProviderEntity> = withContext(NonCancellable) {
        saveNow(repo, id, name, baseUrl, apiKey, defaultModel, protocol)
    }

    private suspend fun saveNow(
        repo: AgentRepository,
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String
    ): Result<AgentProviderEntity> {
        val trimmedName = name.trim()
        val trimmedUrl = OkHttpAgentGateway.normalizeBaseUrl(baseUrl)
        val proto = when (protocol) {
            AgentProviderEntity.PROTOCOL_OPENCODE -> AgentProviderEntity.PROTOCOL_OPENCODE
            AgentProviderEntity.PROTOCOL_OLLAMA -> AgentProviderEntity.PROTOCOL_OLLAMA
            else -> AgentProviderEntity.PROTOCOL_OPENAI
        }
        if (trimmedName.isBlank()) return Result.failure(IllegalArgumentException("provider name is required"))
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("base URL must start with http:// or https://"))
        }
        if (defaultModel.isBlank()) return Result.failure(IllegalArgumentException("model is required"))
        return try {
            val existing = id?.let { repo.provider(it) }
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
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            val savedId = repo.saveProvider(entity)
            Result.success(entity.copy(id = savedId))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
