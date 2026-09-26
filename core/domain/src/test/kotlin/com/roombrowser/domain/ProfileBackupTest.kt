package com.roombrowser.domain.export

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import org.junit.Test

class ProfileBackupTest {

    private fun payload() = ProfileBackup.BackupPayload(
        profile = Profile(
            id = ProfileId("11111111-2222-3333-4444-555555555555"),
            name = "Research",
            createdAt = 1720000000000
        ),
        bookmarks = listOf(ProfileBackup.BookmarkExport("https://example.com", "Example")),
        sitePermissions = listOf(ProfileBackup.SitePermissionExport("example.com", "CAMERA", "BLOCK")),
        siteSettings = listOf(ProfileBackup.SiteSettingExport("example.com", "{\"js\":false}"))
    )

    @Test
    fun `roundtrip preserves profile identity and settings`() {
        val raw = ProfileBackup.serialize(payload())
        val restored = ProfileBackup.deserialize(raw)
        assertThat(restored.profile.id.value).isEqualTo("11111111-2222-3333-4444-555555555555")
        assertThat(restored.profile.name).isEqualTo("Research")
        assertThat(restored.profile.settings.searchEngineId).isEqualTo("duckduckgo")
        assertThat(restored.bookmarks).hasSize(1)
        assertThat(restored.sitePermissions.single().decision).isEqualTo("BLOCK")
        assertThat(restored.siteSettings.single().host).isEqualTo("example.com")
    }

    @Test
    fun `export never contains cookie or session fields`() {
        val raw = ProfileBackup.serialize(payload()).lowercase()
        ProfileBackup.neverExported.forEach { forbidden ->
            assertThat(raw).doesNotContain("\"$forbidden\"")
        }
    }

    @Test
    fun `newer format version rejected`() {
        val raw = ProfileBackup.serialize(payload())
            .replace(Regex("(\"formatVersion\"\\s*:\\s*)\\d+"), "$1" + "99")
        val threw = runCatching { ProfileBackup.deserialize(raw) }.isFailure
        assertThat(threw).isTrue()
    }

    @Test
    fun `unknown keys tolerated for forward compatibility`() {
        val raw = ProfileBackup.serialize(payload()).replaceFirst(
            "{",
            "{\"futureField\":42,"
        )
        val restored = ProfileBackup.deserialize(raw)
        assertThat(restored.profile.name).isEqualTo("Research")
    }
}
