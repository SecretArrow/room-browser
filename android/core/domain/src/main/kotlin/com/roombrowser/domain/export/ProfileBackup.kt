package com.roombrowser.domain.export

import com.roombrowser.domain.model.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Profile settings export / import (spec section 29).
 *
 * The export contains NO cookies, NO sessions, NO credentials and NO
 * browsing history — only configuration, bookmarks and (optionally)
 * per-site settings. See ProfileBackupFormat.docs for the exact content.
 */
object ProfileBackup {

    const val FORMAT_VERSION = 1

    @Serializable
    data class BookmarkExport(val url: String, val title: String, val folder: String? = null, val position: Int = 0)

    @Serializable
    data class SitePermissionExport(val host: String, val permission: String, val decision: String)

    @Serializable
    data class SiteSettingExport(val host: String, val settingsJson: String)

    @Serializable
    data class BackupPayload(
        val formatVersion: Int = FORMAT_VERSION,
        val profile: Profile,
        val bookmarks: List<BookmarkExport> = emptyList(),
        val sitePermissions: List<SitePermissionExport> = emptyList(),
        val siteSettings: List<SiteSettingExport> = emptyList()
    ) {
        init {
            require(profile.id.value.isNotBlank())
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    fun deserialize(raw: String): BackupPayload {
        val payload = json.decodeFromString(BackupPayload.serializer(), raw)
        require(payload.formatVersion <= FORMAT_VERSION) {
            "Backup format version ${payload.formatVersion} is newer than supported $FORMAT_VERSION"
        }
        return payload
    }

    /** The backup never contains these — enforced by construction. */
    val neverExported = listOf(
        "cookies", "sessions", "cache", "credentials", "passwords",
        "indexeddb", "localstorage", "browsing history"
    )
}
