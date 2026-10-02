package com.roombrowser.agent.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The agent pill's position arithmetic.
 *
 * Two failures this pins that no amount of looking at the screen would catch:
 * a fractional position that escapes 0..1 puts the pill where it cannot be
 * grabbed, and a travel range that collapses to zero turns the drag into a
 * division by zero. Both are one line of arithmetic away, in either case.
 */
class AgentPillPositionTest {

    private val margin = 12

    // ----------------------------------------------------------------- range

    @Test
    fun `the range is what is left after the pill and its margins`() {
        assertThat(AgentPillPosition.range(bounds = 1000, pill = 100, margin = margin))
            .isEqualTo(1000 - 100 - 24)
    }

    @Test
    fun `a pill that fills its box has no range rather than a negative one`() {
        // A small viewport, or a large pill. Negative here would become a
        // negative offset — the pill drawn outside its own box.
        assertThat(AgentPillPosition.range(bounds = 100, pill = 100, margin = margin)).isEqualTo(0)
        assertThat(AgentPillPosition.range(bounds = 50, pill = 100, margin = margin)).isEqualTo(0)
    }

    // ---------------------------------------------------------------- offset

    @Test
    fun `the corners are the margins, never zero and never past the box`() {
        val range = 800

        // fraction 0 is the top/start edge, 1 the bottom/end edge.
        assertThat(AgentPillPosition.offsetPx(0f, range, margin)).isEqualTo(margin)
        assertThat(AgentPillPosition.offsetPx(1f, range, margin)).isEqualTo(margin + range)
    }

    @Test
    fun `a stored fraction outside the range still lands inside the box`() {
        // Written by an older build, or restored from a hand-edited backup.
        // Clamping on the way in is what stops it stranding the pill.
        val range = 800

        assertThat(AgentPillPosition.offsetPx(-4f, range, margin)).isEqualTo(margin)
        assertThat(AgentPillPosition.offsetPx(9f, range, margin)).isEqualTo(margin + range)
    }

    @Test
    fun `an immovable pill sits at its margin`() {
        // range 0 is the collapsed case: the offset must still be the margin,
        // not zero, or the pill ends up flush against the edge.
        assertThat(AgentPillPosition.offsetPx(0.5f, range = 0, margin = margin)).isEqualTo(margin)
    }

    // ---------------------------------------------------------------- dragging

    @Test
    fun `a drag moves the fraction by the share of the range it covered`() {
        assertThat(AgentPillPosition.dragBy(0.5f, delta = 200f, range = 800)).isEqualTo(0.75f)
        assertThat(AgentPillPosition.dragBy(0.5f, delta = -200f, range = 800)).isEqualTo(0.25f)
    }

    @Test
    fun `a drag past an edge stops at the edge`() {
        assertThat(AgentPillPosition.dragBy(0.5f, delta = 100_000f, range = 800)).isEqualTo(1f)
        assertThat(AgentPillPosition.dragBy(0.5f, delta = -100_000f, range = 800)).isEqualTo(0f)
    }

    @Test
    fun `dragging across a zero range changes nothing`() {
        // Not a division by zero, and not a fraction that becomes NaN: NaN
        // would survive the clamp (`coerceIn` on NaN returns NaN) and be
        // written to the database, stranding the pill on the next launch.
        assertThat(AgentPillPosition.dragBy(0.25f, delta = 500f, range = 0)).isEqualTo(0.25f)
        assertThat(AgentPillPosition.dragBy(1f, delta = -500f, range = 0)).isEqualTo(1f)
    }

    @Test
    fun `repeated small drags accumulate without drifting outside the range`() {
        // What a real drag is: dozens of small events, each rounded nowhere
        // in between because only the fraction is carried.
        var fraction = 0f
        repeat(200) { fraction = AgentPillPosition.dragBy(fraction, delta = 50f, range = 800) }

        assertThat(fraction).isEqualTo(1f)
    }
}
