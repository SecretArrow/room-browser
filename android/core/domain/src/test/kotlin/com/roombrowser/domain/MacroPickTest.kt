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

    @Test
    fun `point pick fills coordinates and element truth`() {
        val step = MacroStep(action = MacroStep.CLICK)
        val pick = MacroPick(
            token = "t",
            kind = MacroPick.KIND_POINT,
            x = 120f,
            y = 88f,
            selector = "#target",
            selectorKind = "ID",
            tagName = "div",
            text = "PickMe"
        )
        val applied = pick.appliedTo(step)
        assertThat(applied.x).isEqualTo(120f)
        assertThat(applied.y).isEqualTo(88f)
        assertThat(applied.selector).isEqualTo("#target")
        assertThat(applied.selectorKind).isEqualTo("ID")
        assertThat(applied.text).isEqualTo("PickMe")
        // a drag endpoint is untouched by a POINT pick
        assertThat(applied.endX).isNull()
    }

    @Test
    fun `point pick overwrites a previous position but keeps unseen element fields`() {
        val step = MacroStep(
            action = MacroStep.CLICK,
            x = 1f,
            y = 2f,
            selector = ".old",
            selectorKind = "STRUCTURAL",
            text = "old label"
        )
        val pick = MacroPick(
            token = "t",
            kind = MacroPick.KIND_POINT,
            x = 300f,
            y = 400f
        ) // the probe saw nothing under the finger
        val applied = pick.appliedTo(step)
        assertThat(applied.x).isEqualTo(300f)
        assertThat(applied.y).isEqualTo(400f)
        assertThat(applied.selector).isEqualTo(".old")
        assertThat(applied.selectorKind).isEqualTo("STRUCTURAL")
        assertThat(applied.text).isEqualTo("old label")
    }

    @Test
    fun `drag end pick lands only in the endpoint fields`() {
        val step = MacroStep(
            action = MacroStep.DRAG,
            x = 10f,
            y = 20f,
            selector = "#handle",
            selectorKind = "ID"
        )
        val pick = MacroPick(
            token = "t",
            kind = MacroPick.KIND_DRAG_END,
            x = 200f,
            y = 300f,
            selector = "#dropped",
            selectorKind = "ID"
        )
        val applied = pick.appliedTo(step)
        assertThat(applied.endX).isEqualTo(200f)
        assertThat(applied.endY).isEqualTo(300f)
        // start point and its element truth stay exactly as authored
        assertThat(applied.x).isEqualTo(10f)
        assertThat(applied.y).isEqualTo(20f)
        assertThat(applied.selector).isEqualTo("#handle")
        assertThat(applied.text).isNull()
    }

    @Test
    fun `drag start pick fills the start like a point`() {
        val step = MacroStep(action = MacroStep.DRAG)
        val pick = MacroPick(token = "t", kind = MacroPick.KIND_DRAG_START, x = 5f, y = 6f)
        val applied = pick.appliedTo(step)
        assertThat(applied.x).isEqualTo(5f)
        assertThat(applied.y).isEqualTo(6f)
        assertThat(applied.endX).isNull()
    }
}
