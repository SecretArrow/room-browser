package com.roombrowser.domain.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeSpecTest {

    @Test
    fun `all 18 built-in themes exist with unique ids`() {
        assertThat(BuiltInThemes.all).hasSize(18)
        val ids = BuiltInThemes.all.map { it.id }
        assertThat(ids.distinct()).hasSize(ids.size)
        val names = BuiltInThemes.all.map { it.name }
        assertThat(names.distinct()).hasSize(names.size)
    }

    @Test
    fun `requested built-in ids are present`() {
        val wanted = listOf(
            "obsidian", "arctic", "ocean", "emerald", "midnight", "aurora",
            "sunset", "cyber", "royal", "sakura", "forest", "aqua",
            "crimson", "golden", "slate", "lavender", "coffee", "rose"
        )
        wanted.forEach { id ->
            val theme = BuiltInThemes.byId(id)
            assertThat(theme).isNotNull()
            assertThat(theme!!.light).isNotSameInstanceAs(theme.dark)
        }
    }

    @Test
    fun `json round trip preserves the spec`() {
        val spec = BuiltInThemes.aurora
        val encoded = ThemeJson.encode(spec)
        val decoded = ThemeJson.decode(encoded)
        assertThat(decoded).isEqualTo(spec)
    }

    @Test
    fun `json decode tolerates unknown fields (forward compatibility)`() {
        val encoded = ThemeJson.encode(BuiltInThemes.rose)
        val withExtra = encoded.dropLast(1) + ",\"futureField\":42}"
        val decoded = ThemeJson.decode(withExtra)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.id).isEqualTo("rose")
    }

    @Test
    fun `json decode returns null for garbage`() {
        assertThat(ThemeJson.decode(null)).isNull()
        assertThat(ThemeJson.decode("")).isNull()
        assertThat(ThemeJson.decode("not json at all")).isNull()
        assertThat(ThemeJson.decode("{\"id\":\"x\"}")).isNull() // missing required fields
    }

    @Test
    fun `resolve honors mode`() {
        val spec = BuiltInThemes.ocean
        val autoLight = spec.copy(mode = RoomThemeMode.AUTO).resolve(systemDark = false)
        val autoDark = spec.copy(mode = RoomThemeMode.AUTO).resolve(systemDark = true)
        assertThat(autoLight).isEqualTo(spec.light)
        assertThat(autoDark).isEqualTo(spec.dark)
        assertThat(spec.copy(mode = RoomThemeMode.LIGHT).resolve(systemDark = true))
            .isEqualTo(spec.light)
        assertThat(spec.copy(mode = RoomThemeMode.DARK).resolve(systemDark = false))
            .isEqualTo(spec.dark)
    }

    @Test
    fun `amoled forces pure black background and bars`() {
        val spec = BuiltInThemes.obsidian.copy(mode = RoomThemeMode.AMOLED)
        val resolved = spec.resolve(systemDark = false)
        assertThat(resolved.background).isEqualTo(0xFF000000)
        assertThat(resolved.tabBar).isEqualTo(0xFF000000)
        assertThat(resolved.navBar).isEqualTo(0xFF000000)
        assertThat(resolved.addressBar).isEqualTo(0xFF000000)
        // accents survive
        assertThat(resolved.primary).isEqualTo(spec.dark.primary)
    }

    @Test
    fun `sanitized clamps numeric sliders`() {
        val wild = BuiltInThemes.slate.copy(
            cornerRadius = 999, transparency = -5, blur = 400, contrast = 1
        ).sanitized()
        assertThat(wild.cornerRadius).isEqualTo(32)
        assertThat(wild.transparency).isEqualTo(0)
        assertThat(wild.blur).isEqualTo(100)
        assertThat(wild.contrast).isEqualTo(50)
    }

    @Test
    fun `every built-in palette keeps text readable against its background`() {
        // Sanity: textPrimary must be brighter than the background on dark
        // palettes and darker than the background on light palettes.
        BuiltInThemes.all.forEach { theme ->
            assertThat(luminance(theme.dark.textPrimary))
                .isGreaterThan(luminance(theme.dark.background) + 0.25f)
            assertThat(luminance(theme.light.textPrimary))
                .isLessThan(luminance(theme.light.background) - 0.25f)
        }
    }

    @Test
    fun `resolveOrDefault never returns null`() {
        assertThat(BuiltInThemes.resolveOrDefault("")).isEqualTo(BuiltInThemes.default())
        assertThat(BuiltInThemes.resolveOrDefault("garbage {{{"))
            .isEqualTo(BuiltInThemes.default())
        val encoded = ThemeJson.encode(BuiltInThemes.cyber.copy(mode = RoomThemeMode.DARK))
        assertThat(BuiltInThemes.resolveOrDefault(encoded).id).isEqualTo("cyber")
    }

    @Test
    fun `custom identity rebranding keeps the palette`() {
        val original = BuiltInThemes.lavender
        val custom = original.withIdentity("custom-1", "My Theme")
        assertThat(custom.id).isEqualTo("custom-1")
        assertThat(custom.name).isEqualTo("My Theme")
        assertThat(custom.light).isEqualTo(original.light)
        assertThat(custom.dark).isEqualTo(original.dark)
    }

    private fun luminance(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF).toInt() / 255f
        val g = ((argb shr 8) and 0xFF).toInt() / 255f
        val b = (argb and 0xFF).toInt() / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
