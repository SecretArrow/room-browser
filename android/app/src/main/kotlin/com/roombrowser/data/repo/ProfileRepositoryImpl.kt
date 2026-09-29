package com.roombrowser.data.repo

import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ProfileEntity
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.domain.profile.ProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Room-backed implementation of the domain ProfileStore.
 * Storage identity = immutable UUID; the profile NAME is never used as an
 * identifier (spec section 46).
 */
class ProfileRepositoryImpl(db: AppDatabase) : ProfileStore {

    private val dao = db.profileDao()
    private val bookmarkDao = db.bookmarkDao()
    private val tabDao = db.tabDao()
    private val historyDao = db.historyDao()
    private val siteSettingsDao = db.siteSettingsDao()
    private val ipDao = db.ipHistoryDao()
    private val statsDao = db.statsDao()
    private val downloadDao = db.downloadDao()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeProfiles(): Flow<List<Profile>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeProfile(id: ProfileId): Flow<Profile?> =
        dao.observeAll().map { list -> list.firstOrNull { it.id == id.value }?.toDomain() }

    suspend fun getProfile(id: ProfileId): Profile? = dao.get(id.value)?.toDomain()

    override suspend fun profiles(): List<Profile> = dao.all().map { it.toDomain() }

    override suspend fun get(id: ProfileId): Profile? = getProfile(id)

    override suspend fun put(profile: Profile) {
        dao.upsert(profile.toEntity())
    }

    override suspend fun remove(id: ProfileId, cascadeData: Boolean) {
        dao.delete(id.value)
        if (cascadeData) {
            tabDao.deleteAllFor(id.value)
            bookmarkDao.deleteAllFor(id.value)
            historyDao.deleteAllFor(id.value)
            siteSettingsDao.deleteAllPermissionsFor(id.value)
            siteSettingsDao.deleteAllSiteSettingsFor(id.value)
            ipDao.deleteAllFor(id.value)
            statsDao.deleteAllFor(id.value)
            downloadDao.deleteAllFor(id.value)
        }
    }

    override suspend fun updateSettings(id: ProfileId, settings: ProfileSettings) {
        dao.updateSettings(id.value, json.encodeToString(ProfileSettings.serializer(), settings))
    }

    /**
     * Duplicate profile data. Only metadata types (settings/bookmarks/history)
     * can be copied. Cookies/cache/sessions/site data NEVER cross profiles —
     * see CopyOptions and PROFILE_ISOLATION.md.
     */
    override suspend fun copyProfileData(from: ProfileId, to: ProfileId, options: CopyOptions) {
        if (options.bookmarks) {
            val maxPos = bookmarkDao.maxPosition(to.value) ?: 0
            bookmarkDao.all(from.value).forEachIndexed { i, b ->
                bookmarkDao.upsert(
                    b.copy(id = 0, profileId = to.value, position = maxPos + 1 + i)
                )
            }
        }
        if (options.history) {
            historyDao.since(from.value, 0).take(10_000).forEach {
                historyDao.insert(it.copy(id = 0, profileId = to.value))
            }
        }
        // options.settings handled by caller (settings serialized on ProfileEntity)
    }

    override suspend fun resetProfileData(id: ProfileId) {
        tabDao.deleteAllFor(id.value)
        bookmarkDao.deleteAllFor(id.value)
        historyDao.deleteAllFor(id.value)
        siteSettingsDao.deleteAllPermissionsFor(id.value)
        siteSettingsDao.deleteAllSiteSettingsFor(id.value)
        statsDao.deleteAllFor(id.value)
        ipDao.deleteAllFor(id.value)
    }

    suspend fun touch(id: ProfileId, ts: Long) = dao.touch(id.value, ts)

    /** Persist a full per-profile theme snapshot (Theme Studio "Apply"). */
    suspend fun updateTheme(id: ProfileId, themeJson: String) =
        dao.updateTheme(id.value, themeJson)

    private fun ProfileEntity.toDomain(): Profile = Profile(
        id = ProfileId(id),
        name = name,
        icon = icon,
        colorArgb = colorArgb,
        isLocked = isLocked,
        isDefault = isDefault,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        settings = runCatching {
            json.decodeFromString(ProfileSettings.serializer(), settingsJson)
        }.getOrDefault(ProfileSettings()),
        themeJson = themeJson
    )

    private fun Profile.toEntity(): ProfileEntity = ProfileEntity(
        id = id.value,
        name = name,
        icon = icon,
        colorArgb = colorArgb,
        isLocked = isLocked,
        isDefault = isDefault,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        settingsJson = json.encodeToString(ProfileSettings.serializer(), settings),
        themeJson = themeJson
    )
}
