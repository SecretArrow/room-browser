package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.repo.AgentRepository

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
     */
    suspend fun save(
        repo: AgentRepository,
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String
    ): Result<AgentProviderEntity> {
        val trimmedName = name.trim()
        val trimmedUrl = OkHttpAgentGateway.normalizeBaseUrl(baseUrl)
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
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            val savedId = repo.saveProvider(entity)
            Result.success(entity.copy(id = savedId))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
