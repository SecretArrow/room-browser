package com.roombrowser.domain

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.macro.MacroPick
import org.junit.Test

/**
 * The pick handoff contract: round-trips survive the encode/decode ride,
 * a corrupted or foreign payload decodes to null (never a crash), and the
 * kind constants stay stable — the editor and the engine agree on them
 * across a process boundary.
 */
class MacroPickTest {

    @Test
    fun `round trip preserves every field`() {
        val pick = MacroPick(
            token = "tok-123",
            kind = MacroPick.KIND_DRAG_START,
            x = 320.5f,
            y = 88.0f,
            selector = "#user_password",
            selectorKind = "ID",
            tagName = "input",
            text = "Password"
        )
        val decoded = MacroPick.decode(MacroPick.encode(pick))
        assertThat(decoded).isEqualTo(pick)
    }

    @Test
    fun `decode tolerates unknown keys and missing optionals`() {
        val decoded = MacroPick.decode(
            """{"token":"t","kind":"POINT","x":12,"y":34,"future":"ignored"}"""
        )
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.token).isEqualTo("t")
        assertThat(decoded.kind).isEqualTo("POINT")
        assertThat(decoded.x).isEqualTo(12f)
        assertThat(decoded.y).isEqualTo(34f)
        assertThat(decoded.selector).isNull()
        assertThat(decoded.selectorKind).isEqualTo("NONE")
    }

    @Test
    fun `decode of garbage is null`() {
        assertThat(MacroPick.decode("not json at all")).isNull()
        assertThat(MacroPick.decode("""{"token":"t"}""")).isNull()
    }

    @Test
    fun `kind constants are the cross-process contract`() {
        assertThat(MacroPick.KIND_POINT).isEqualTo("POINT")
        assertThat(MacroPick.KIND_DRAG_START).isEqualTo("DRAG_START")
        assertThat(MacroPick.KIND_DRAG_END).isEqualTo("DRAG_END")
    }
}
