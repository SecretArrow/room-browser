package com.roombrowser.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguagePresetsTest {

    @Test
    fun `codes are unique and display names are non-blank`() {
        val codes = LanguagePresets.all.map { it.code }
        assertThat(codes.toSet()).hasSize(codes.size)
        for (l in LanguagePresets.all) {
            assertThat(l.code).isNotEmpty()
            assertThat(l.displayName).isNotEmpty()
        }
    }

    @Test
    fun `order puts Indonesian first and keeps the top tier next`() {
        assertThat(LanguagePresets.all).hasSize(25)
        assertThat(LanguagePresets.all[0].code).isEqualTo("id")
        assertThat(LanguagePresets.all[0].displayName).isEqualTo("Bahasa Indonesia")
        assertThat(LanguagePresets.all[1].code).isEqualTo("en-US")
        assertThat(LanguagePresets.all[2].code).isEqualTo("en-GB")
        assertThat(LanguagePresets.all.last().code).isEqualTo("pl")
        assertThat(LanguagePresets.all.last().displayName).isEqualTo("Polski")
    }

    @Test
    fun `byCode resolves presets and is case-insensitive`() {
        assertThat(LanguagePresets.byCode("id")!!.displayName).isEqualTo("Bahasa Indonesia")
        assertThat(LanguagePresets.byCode("ID")!!.code).isEqualTo("id")
        assertThat(LanguagePresets.byCode(" en-US ")!!.code).isEqualTo("en-US")
        assertThat(LanguagePresets.byCode("zh-CN")!!.displayName).isEqualTo("中文（简体）")
        assertThat(LanguagePresets.byCode("no-such-language")).isNull()
        assertThat(LanguagePresets.byCode("")).isNull()
    }

    @Test
    fun `isSupported accepts presets and valid custom codes, rejects junk`() {
        // Curated preset codes are supported.
        assertThat(LanguagePresets.isSupported("id")).isTrue()
        assertThat(LanguagePresets.isSupported("fil")).isTrue()
        // Custom codes beyond the presets are allowed.
        assertThat(LanguagePresets.isSupported("kri")).isTrue()
        assertThat(LanguagePresets.isSupported("es-419")).isTrue()
        assertThat(LanguagePresets.isSupported("zh-Hant")).isTrue()
        // Blank and malformed strings are not usable language codes.
        assertThat(LanguagePresets.isSupported("")).isFalse()
        assertThat(LanguagePresets.isSupported("   ")).isFalse()
        assertThat(LanguagePresets.isSupported("not a code")).isFalse()
        assertThat(LanguagePresets.isSupported("-")).isFalse()
        assertThat(LanguagePresets.isSupported("waytoolongprimarytag")).isFalse()
    }
}
