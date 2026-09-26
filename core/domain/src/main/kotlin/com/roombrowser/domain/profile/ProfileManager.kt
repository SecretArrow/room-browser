package com.roombrowser.domain.profile

import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings

/**
 * Storage port implemented by the app layer (Room-backed).
 * The domain keeps pure logic and stays JVM-testable.
 */
interface ProfileStore {
    suspend fun profiles(): List<Profile>
    suspend fun get(id: ProfileId): Profile?
    suspend fun put(profile: Profile)
    suspend fun remove(id: ProfileId, cascadeData: Boolean)
    suspend fun updateSettings(id: ProfileId, settings: ProfileSettings)
    suspend fun copyProfileData(from: ProfileId, to: ProfileId, options: CopyOptions)
    suspend fun resetProfileData(id: ProfileId)
}

data class CopyOptions(
    val settings: Boolean = true,
    val bookmarks: Boolean = true,
    val history: Boolean = true,
    /** Cookies / cache / sessions / site data are NEVER copied by default. */
    val cookies: Boolean = false,
    val cache: Boolean = false,
    val sessions: Boolean = false,
    val siteData: Boolean = false
)

/** Tab-count port for the profile cards. */
interface TabCountStore {
    suspend fun tabCount(profileId: ProfileId): Int
}

/**
 * Profile manager: create / open / edit / duplicate / delete / rename /
 * re-style / reset / lock. UUID is the immutable storage identity.
 */
class ProfileManager(
    private val store: ProfileStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun create(
        name: String,
        icon: String,
        colorArgb: Long,
        settings: ProfileSettings = ProfileSettings(),
        isDefault: Boolean = false
    ): Profile {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be empty" }
        require(trimmed.length <= MAX_NAME) { "Profile name too long (max $MAX_NAME)" }
        val existing = store.profiles()
        require(existing.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile with this name already exists"
        }
        val profile = Profile(
            id = ProfileId.new(),
            name = trimmed,
            icon = icon.ifBlank { "\uD83D\uDC64" },
            colorArgb = colorArgb,
            isDefault = isDefault || existing.isEmpty(),
            createdAt = clock(),
            lastActiveAt = clock(),
            settings = settings
        )
        store.put(profile)
        return profile
    }

    suspend fun duplicate(id: ProfileId, options: CopyOptions, nameSuffix: String = " Copy"): Profile {
        val source = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val existingNames = store.profiles().map { it.name.lowercase() }
        var candidate = source.name + nameSuffix
        var i = 2
        while (candidate.lowercase() in existingNames) {
            candidate = source.name + nameSuffix + " " + i
            i++
        }
        val copy = source.copy(
            id = ProfileId.new(),
            name = candidate,
            isDefault = false,
            isLocked = false,
            createdAt = clock(),
            lastActiveAt = clock()
        )
        store.put(copy)
        store.copyProfileData(source.id, copy.id, options)
        return copy
    }

    suspend fun rename(id: ProfileId, newName: String): Profile {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be empty" }
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val others = store.profiles().filter { it.id != id }
        require(others.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile with this name already exists"
        }
        val updated = profile.copy(name = trimmed)
        store.put(updated)
        return updated
    }

    /**
     * Cosmetic update (icon/color). Storage identity (UUID) is untouched.
     */
    suspend fun restyle(id: ProfileId, icon: String? = null, colorArgb: Long? = null): Profile {
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val updated = profile.copy(
            icon = icon ?: profile.icon,
            colorArgb = colorArgb ?: profile.colorArgb
        )
        store.put(updated)
        return updated
    }

    suspend fun setLocked(id: ProfileId, locked: Boolean): Profile {
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val updated = profile.copy(isLocked = locked)
        store.put(updated)
        return updated
    }

    suspend fun setDefault(id: ProfileId) {
        val profiles = store.profiles()
        require(profiles.any { it.id == id }) { "Profile not found: $id" }
        profiles.forEach {
            if (it.isDefault != (it.id == id)) store.put(it.copy(isDefault = it.id == id))
        }
    }

    suspend fun updateSettings(id: ProfileId, settings: ProfileSettings) {
        store.updateSettings(id, settings)
    }

    suspend fun markActive(id: ProfileId) {
        val profile = store.get(id) ?: return
        store.put(profile.copy(lastActiveAt = clock()))
    }

    suspend fun delete(id: ProfileId) {
        val profiles = store.profiles()
        val target = profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Profile not found: $id")
        val wasDefault = target.isDefault
        store.remove(id, cascadeData = true)
        if (wasDefault) {
            store.profiles().firstOrNull()?.let { store.put(it.copy(isDefault = true)) }
        }
    }

    suspend fun resetData(id: ProfileId) {
        store.resetProfileData(id)
    }

    suspend fun profiles(): List<Profile> = store.profiles()

    companion object {
        const val MAX_NAME = 40
    }
}
