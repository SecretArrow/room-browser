package com.roombrowser.agent.ui

import kotlin.math.roundToInt

/**
 * Where the draggable agent pill sits, and where a drag moves it.
 *
 * WHY THIS IS NOT INLINE IN THE COMPOSABLE: the pill stores its position as a
 * FRACTION of the range it can travel through, never in pixels, so that a
 * position set in portrait survives landscape, a resize and a different phone
 * (see DraggableAgentPill). That fraction is the whole contract, and the two
 * ways it can go wrong are both arithmetic rather than visual — a range that
 * collapses to zero divides by it, and a fraction that escapes 0..1 strands
 * the pill off-screen with no way to grab it back. Neither is reachable by
 * looking at the screen, so both are pinned here instead, where a JVM test
 * can reach them.
 */
internal object AgentPillPosition {

    /**
     * The distance, in pixels, the pill's top-left corner may travel on one
     * axis. [bounds] is the box it lives in, [pill] the pill itself and
     * [margin] the breathing room kept at each end.
     *
     * Zero when the pill and its margins already fill the box — a small
     * viewport, or a very large pill. Callers must treat that as "this axis
     * cannot move" rather than as a distance, which is why it is clamped here
     * once instead of at each use.
     */
    fun range(bounds: Int, pill: Int, margin: Int): Int =
        (bounds - pill - margin * 2).coerceAtLeast(0)

    /**
     * The pill's offset from the start of its box, in pixels, for a stored
     * [fraction].
     *
     * The fraction is clamped on the way IN as well as on the way out: a value
     * written by an older build, or by a hand-edited backup, must not be able
     * to put the pill somewhere it cannot be reached.
     */
    fun offsetPx(fraction: Float, range: Int, margin: Int): Int =
        margin + (range * fraction.coerceIn(0f, 1f)).roundToInt()

    /**
     * The fraction after dragging [delta] pixels across [range].
     *
     * A zero range is a no-op rather than a division by zero, and the result
     * is clamped so that no drag, however long, can carry the pill past the
     * edge it is being dragged towards.
     */
    fun dragBy(fraction: Float, delta: Float, range: Int): Float =
        if (range <= 0) fraction else (fraction + delta / range).coerceIn(0f, 1f)
}
