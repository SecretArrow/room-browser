package com.roombrowser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY is_default DESC, last_active_at DESC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE profiles SET settings_json = :json WHERE id = :id")
    suspend fun updateSettings(id: String, json: String)

    @Query("UPDATE profiles SET theme_json = :json WHERE id = :id")
    suspend fun updateTheme(id: String, json: String)

    @Query("UPDATE profiles SET last_active_at = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("UPDATE profiles SET is_default = (id = :id)")
    suspend fun setDefault(id: String)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs WHERE profile_id = :profileId AND closed_at IS NULL ORDER BY is_pinned DESC, position ASC")
    fun observeOpen(profileId: String): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE profile_id = :profileId AND closed_at IS NULL ORDER BY is_pinned DESC, position ASC")
    suspend fun openTabs(profileId: String): List<TabEntity>

    @Query("SELECT COUNT(*) FROM tabs WHERE profile_id = :profileId AND closed_at IS NULL")
    suspend fun openCount(profileId: String): Int

    @Query("SELECT COUNT(*) FROM tabs WHERE profile_id = :profileId AND closed_at IS NULL")
    fun observeCount(profileId: String): Flow<Int>

    @Query("SELECT * FROM tabs WHERE id = :id")
    suspend fun get(id: String): TabEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tab: TabEntity)

    @Update
    suspend fun update(tab: TabEntity)

    /** Marks a tab as the profile's most-recently-viewed one (persisted
     *  active-tab restore + close-tab neighbour selection read this). */
    @Query("UPDATE tabs SET last_viewed_at = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    /**
     * Monotonic, collision-free new-tab insert: the position is
     * `MAX(position) + 1` over ALL of the profile's rows (closed ones
     * included), computed inside the SAME statement that inserts —
     * SQLite evaluates the scalar subquery before the row lands, so two
     * concurrent newTab calls can never draw the same position, and a
     * closed tab reopened later can never collide with a newer tab.
     */
    @Query(
        "INSERT INTO tabs (id, profile_id, position, title, url, is_private, is_pinned, group_name, " +
            "created_at, last_viewed_at, closed_at) VALUES " +
            "(:id, :profileId, (SELECT IFNULL(MAX(position), -1) + 1 FROM tabs WHERE profile_id = :profileId), " +
            ":title, :url, :isPrivate, 0, NULL, :createdAt, :lastViewedAt, NULL)"
    )
    suspend fun insertNextPosition(
        id: String,
        profileId: String,
        title: String,
        url: String,
        isPrivate: Boolean,
        createdAt: Long,
        lastViewedAt: Long
    )

    @Query("UPDATE tabs SET closed_at = :ts WHERE id = :id")
    suspend fun close(id: String, ts: Long)

    @Query("UPDATE tabs SET closed_at = NULL WHERE id = :id")
    suspend fun reopen(id: String)

    @Query("SELECT * FROM tabs WHERE profile_id = :profileId AND closed_at IS NOT NULL ORDER BY closed_at DESC LIMIT :limit")
    suspend fun recentlyClosed(profileId: String, limit: Int = 10): List<TabEntity>

    @Query("UPDATE tabs SET position = :position WHERE id = :id")
    suspend fun setposition(id: String, position: Int)

    @Query("UPDATE tabs SET group_name = :group WHERE id = :id")
    suspend fun setGroup(id: String, group: String?)

    @Query("UPDATE tabs SET is_pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM tabs WHERE profile_id = :profileId")
    suspend fun deleteAllFor(profileId: String)

    @Query("DELETE FROM tabs WHERE closed_at IS NOT NULL AND closed_at < :cutoff")
    suspend fun purgeClosedBefore(cutoff: Long)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId ORDER BY folder IS NULL, folder, position, created_at")
    fun observeAll(profileId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId")
    suspend fun all(profileId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE profile_id = :profileId AND url = :url LIMIT 1")
    suspend fun find(profileId: String, url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE profile_id = :profileId")
    suspend fun deleteAllFor(profileId: String)

    @Query("UPDATE bookmarks SET title = :title, folder = :folder WHERE id = :id")
    suspend fun updateMeta(id: Long, title: String, folder: String?)

    @Query("SELECT MAX(position) FROM bookmarks WHERE profile_id = :profileId")
    suspend fun maxPosition(profileId: String): Int?
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE profile_id = :profileId AND visited_at >= :since ORDER BY visited_at DESC")
    suspend fun since(profileId: String, since: Long): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE profile_id = :profileId ORDER BY visited_at DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int = 20): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE profile_id = :profileId AND url LIKE '%' || :needle || '%' OR (profile_id = :profileId AND title LIKE '%' || :needle || '%') ORDER BY visited_at DESC LIMIT 200")
    suspend fun search(profileId: String, needle: String): List<HistoryEntity>

    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE profile_id = :profileId AND visited_at >= :since")
    suspend fun deleteSince(profileId: String, since: Long)

    @Query("DELETE FROM history WHERE profile_id = :profileId")
    suspend fun deleteAllFor(profileId: String)

    @Query("SELECT COUNT(DISTINCT url) FROM history WHERE profile_id = :profileId AND visited_at >= :since")
    suspend fun distinctSites(profileId: String, since: Long): Int
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE profile_id = :profileId ORDER BY created_at DESC")
    fun observeAll(profileId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    /**
     * Queued and in-flight rows for ONE profile.
     *
     * The profile filter is the point: this feeds the engine's queue pump, and
     * an unfiltered version let the active profile's engine start — and fetch —
     * a download belonging to a different profile.
     */
    @Query(
        "SELECT * FROM downloads WHERE profile_id = :profileId AND status IN ('QUEUED','RUNNING') " +
            "ORDER BY created_at ASC"
    )
    suspend fun activeFor(profileId: String): List<DownloadEntity>

    @Insert
    suspend fun insert(entry: DownloadEntity): Long

    @Update
    suspend fun update(entry: DownloadEntity)

    /**
     * Progress is written on a tight loop (every 64 KiB or every second), so it
     * deliberately touches only the two byte columns. Writing the whole row from
     * a stale entity is how a live download used to resurrect its own RUNNING
     * status a moment after the user paused it.
     */
    @Query("UPDATE downloads SET downloaded_bytes = :downloaded, total_bytes = :total WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long)

    /** Terminal/resumable transitions: status only, never the byte counters. */
    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?)

    @Query(
        "UPDATE downloads SET status = :status, destination = :destination, " +
            "downloaded_bytes = :downloaded, total_bytes = :total, " +
            "completed_at = :completedAt, error = NULL WHERE id = :id"
    )
    suspend fun updateCompleted(
        id: Long,
        status: String,
        destination: String,
        downloaded: Long,
        total: Long,
        completedAt: Long
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE profile_id = :profileId")
    suspend fun deleteAllFor(profileId: String)
}

@Dao
interface SiteSettingsDao {
    @Query("SELECT * FROM site_permissions WHERE profile_id = :profileId")
    suspend fun permissions(profileId: String): List<SitePermissionEntity>

    @Query("SELECT * FROM site_permissions WHERE profile_id = :profileId AND host = :host")
    suspend fun permissionsFor(profileId: String, host: String): List<SitePermissionEntity>

    @Query("SELECT * FROM site_permissions WHERE profile_id = :profileId")
    fun observePermissions(profileId: String): Flow<List<SitePermissionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermission(entity: SitePermissionEntity)

    @Query("DELETE FROM site_permissions WHERE profile_id = :profileId AND host = :host")
    suspend fun resetPermissionsFor(profileId: String, host: String)

    @Query("DELETE FROM site_permissions WHERE profile_id = :profileId")
    suspend fun deleteAllPermissionsFor(profileId: String)

    @Query("SELECT * FROM site_settings WHERE profile_id = :profileId AND host = :host")
    suspend fun siteSetting(profileId: String, host: String): SiteSettingEntity?

    @Query("SELECT * FROM site_settings WHERE profile_id = :profileId")
    suspend fun siteSettings(profileId: String): List<SiteSettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSiteSetting(entity: SiteSettingEntity)

    @Query("DELETE FROM site_settings WHERE profile_id = :profileId AND host = :host")
    suspend fun clearSiteSetting(profileId: String, host: String)

    @Query("DELETE FROM site_settings WHERE profile_id = :profileId")
    suspend fun deleteAllSiteSettingsFor(profileId: String)
}

@Dao
interface IpHistoryDao {
    @Query("SELECT * FROM ip_history ORDER BY last_seen_at DESC")
    suspend fun all(): List<IpHistoryEntity>

    @Query("SELECT * FROM ip_history WHERE ip = :ip")
    suspend fun forIp(ip: String): List<IpHistoryEntity>

    @Query("SELECT * FROM ip_history WHERE profile_id = :profileId ORDER BY last_seen_at DESC")
    fun observeForProfile(profileId: String): Flow<List<IpHistoryEntity>>

    @Query("SELECT * FROM ip_history WHERE profile_id = :profileId ORDER BY last_seen_at DESC")
    suspend fun forProfile(profileId: String): List<IpHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IpHistoryEntity): Long

    @Query("DELETE FROM ip_history WHERE profile_id = :profileId AND ip = :ip")
    suspend fun forgetIp(profileId: String, ip: String)

    @Query("DELETE FROM ip_history WHERE profile_id = :profileId")
    suspend fun deleteAllFor(profileId: String)

    @Query("DELETE FROM ip_history WHERE last_seen_at < :cutoff")
    suspend fun purgeBefore(cutoff: Long)

    @Query("DELETE FROM ip_history")
    suspend fun clearAll()
}

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(event: BlockEventEntity)

    @Query(
        "SELECT category, COUNT(*) AS count FROM block_events " +
            "WHERE profile_id = :profileId AND ts >= :since GROUP BY category"
    )
    suspend fun countsSince(profileId: String, since: Long): List<CategoryCount>

    @Query(
        "SELECT category, COUNT(*) AS count FROM block_events " +
            "WHERE profile_id = :profileId AND ts >= :since AND host = :host GROUP BY category"
    )
    suspend fun countsSinceForHost(profileId: String, host: String, since: Long): List<CategoryCount>

    @Query("DELETE FROM block_events WHERE profile_id = :profileId")
    suspend fun deleteAllFor(profileId: String)

    @Query("DELETE FROM block_events WHERE ts < :cutoff")
    suspend fun purgeBefore(cutoff: Long)
}

data class CategoryCount(val category: String, val count: Int)

@Dao
interface AppStateDao {
    @Query("SELECT value FROM app_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_state WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: AppStateEntity)

    @Query("DELETE FROM app_state WHERE `key` = :key")
    suspend fun remove(key: String)
}

// =========================================================================
// PER-PROFILE THEMES (gallery of user-saved custom themes)
// =========================================================================

@Dao
interface ThemeDao {
    @Query("SELECT * FROM themes ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CustomThemeEntity>>

    @Query("SELECT * FROM themes ORDER BY created_at DESC")
    suspend fun all(): List<CustomThemeEntity>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun get(id: String): CustomThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(theme: CustomThemeEntity)

    @Query("DELETE FROM themes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM themes")
    suspend fun deleteAll()
}

// =========================================================================
// AI AGENT
// =========================================================================

@Dao
interface AgentDao {

    // ---------- Providers ----------

    @Query("SELECT * FROM agent_providers ORDER BY created_at ASC")
    fun observeProviders(): Flow<List<AgentProviderEntity>>

    @Query("SELECT * FROM agent_providers ORDER BY created_at ASC")
    suspend fun providers(): List<AgentProviderEntity>

    @Query("SELECT * FROM agent_providers WHERE id = :id")
    suspend fun provider(id: Long): AgentProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(entity: AgentProviderEntity): Long

    @Query("DELETE FROM agent_providers WHERE id = :id")
    suspend fun deleteProvider(id: Long)

    @Query("DELETE FROM agent_providers")
    suspend fun deleteAllProviders()

    // ---------- Sessions ----------

    @Query("SELECT * FROM agent_sessions WHERE profile_id = :profileId ORDER BY updated_at DESC")
    fun observeSessions(profileId: String): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE profile_id = :profileId ORDER BY updated_at DESC")
    suspend fun sessions(profileId: String): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun session(id: Long): AgentSessionEntity?

    @Insert
    suspend fun insertSession(entity: AgentSessionEntity): Long

    @Query("UPDATE agent_sessions SET title = :title, updated_at = :ts WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, ts: Long)

    @Query("UPDATE agent_sessions SET updated_at = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    @Query("DELETE FROM agent_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM agent_sessions WHERE profile_id = :profileId")
    suspend fun deleteSessionsFor(profileId: String)

    @Query("DELETE FROM agent_sessions")
    suspend fun deleteAllSessions()

    // ---------- Messages ----------

    @Query("SELECT * FROM agent_messages WHERE session_id = :sessionId ORDER BY id ASC")
    suspend fun messages(sessionId: Long): List<AgentMessageEntity>

    @Insert
    suspend fun insertMessage(entity: AgentMessageEntity): Long

    @Query("SELECT COUNT(*) FROM agent_messages WHERE session_id = :sessionId")
    suspend fun messageCount(sessionId: Long): Int

    @Query("DELETE FROM agent_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesFor(sessionId: Long)

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()
}

// =========================================================================
// PASSWORD MANAGER (per-profile credential vault)
// =========================================================================

@Dao
interface CredentialDao {
    /** Insert-or-replace by id (save new + edit existing). */
    @Upsert
    suspend fun upsert(entity: CredentialEntity)

    @Query(
        "SELECT * FROM credentials WHERE profile_id = :profileId " +
            "ORDER BY domain COLLATE NOCASE ASC, username ASC"
    )
    fun observe(profileId: String): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun byId(id: String): CredentialEntity?

    /**
     * Substring search over domain / username / title, mirroring
     * HistoryDao.search's LIKE style. The caller passes the raw needle —
     * the wildcards live in the SQL so the parameter never needs escaping.
     */
    @Query(
        "SELECT * FROM credentials WHERE profile_id = :profileId AND " +
            "(domain LIKE '%' || :q || '%' OR username LIKE '%' || :q || '%' " +
            "OR title LIKE '%' || :q || '%') " +
            "ORDER BY domain COLLATE NOCASE ASC, username ASC"
    )
    suspend fun search(profileId: String, q: String): List<CredentialEntity>

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: String)

    /** Profile-deletion cascade. */
    @Query("DELETE FROM credentials WHERE profile_id = :profileId")
    suspend fun deleteAllForProfile(profileId: String)

    /** Full scan of one profile's rows — feeds domain matching + export. */
    @Query("SELECT * FROM credentials WHERE profile_id = :profileId")
    suspend fun allForProfile(profileId: String): List<CredentialEntity>

    @Query("SELECT COUNT(*) FROM credentials WHERE profile_id = :profileId")
    suspend fun countForProfile(profileId: String): Int
}
