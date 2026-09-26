package com.roombrowser.data.repo

import com.roombrowser.data.db.AgentDao
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Agent persistence: providers (API keys stay encrypted at rest — see
 * com.roombrowser.agent.KeyStoreCrypto), chat sessions and messages.
 *
 * Sessions are profile-scoped; providers are app-global (they are
 * credentials/infrastructure, not browsing data).
 */
class AgentRepository(private val db: AppDatabase) {

    private val dao: AgentDao get() = db.agentDao()

    // ---------- Providers ----------

    val providers: Flow<List<AgentProviderEntity>> = dao.observeProviders()

    suspend fun providers(): List<AgentProviderEntity> = dao.providers()

    suspend fun provider(id: Long): AgentProviderEntity? = dao.provider(id)

    suspend fun saveProvider(entity: AgentProviderEntity): Long = dao.upsertProvider(entity)

    suspend fun deleteProvider(id: Long) = dao.deleteProvider(id)

    // ---------- Sessions ----------

    fun observeSessions(profileId: String): Flow<List<AgentSessionEntity>> =
        dao.observeSessions(profileId)

    suspend fun sessions(profileId: String): List<AgentSessionEntity> = dao.sessions(profileId)

    suspend fun session(id: Long): AgentSessionEntity? = dao.session(id)

    suspend fun createSession(
        profileId: String,
        title: String,
        providerId: Long,
        model: String
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSessionEntity(
                profileId = profileId,
                title = title.ifBlank { "Agent task" },
                providerId = providerId,
                model = model,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun touchSession(id: Long) = dao.touch(id, System.currentTimeMillis())

    suspend fun updateSessionTitle(id: Long, title: String) =
        dao.updateTitle(id, title.ifBlank { "Agent task" }, System.currentTimeMillis())

    suspend fun deleteSession(id: Long) = dao.deleteMessagesFor(id).let { dao.deleteSession(id) }

    suspend fun deleteSessionsForProfile(profileId: String): Int {
        val ids = dao.sessions(profileId).map { it.id }
        ids.forEach { dao.deleteMessagesFor(it) }
        dao.deleteSessionsFor(profileId)
        return ids.size
    }

    /** Nukes sessions + messages + providers (the "delete agent data" action). */
    suspend fun deleteAllAgentData() {
        dao.deleteAllMessages()
        dao.deleteAllSessions()
        dao.deleteAllProviders()
    }

    // ---------- Messages ----------

    suspend fun messages(sessionId: Long): List<AgentMessageEntity> = dao.messages(sessionId)

    suspend fun addMessage(
        sessionId: Long,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolResult: String? = null
    ): Long = dao.insertMessage(
        AgentMessageEntity(
            sessionId = sessionId,
            role = role,
            content = content,
            toolName = toolName,
            toolArgs = toolArgs,
            toolResult = toolResult,
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun messageCount(sessionId: Long): Int = dao.messageCount(sessionId)
}
