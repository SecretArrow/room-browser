package com.roombrowser.domain.macro

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One on-page pick: the editor asked the browser to capture a position (a
 * tap, or one endpoint of a swipe), the user tapped the page, and the page
 * probe answered with where the finger landed and what sits there.
 *
 * Cross-process by construction — the editor runs in the default process and
 * the engine in `:browser` — so the payload is delivered as a one-shot file
 * keyed by [token]: the editor generates the token, the browser writes the
 * answer, the editor consumes it on resume and deletes the file. A unique
 * token per request sidesteps the stale-cache problem that makes
 * SharedPreferences unreliable across processes.
 */
@Serializable
data class MacroPick(
    /** The one-shot id the editor generated for this request. */
    val token: String,
    /** [KIND_POINT], [KIND_DRAG_START] or [KIND_DRAG_END]. */
    val kind: String,
    /** CSS-pixel viewport coordinates — the same scale [MacroStep] carries. */
    val x: Float,
    val y: Float,
    /** What the probe saw under the finger, when it saw anything. */
    val selector: String? = null,
    val selectorKind: String = "NONE",
    val tagName: String? = null,
    /** The element's visible text at pick time (trimmed), or null. */
    val text: String? = null
) {
    /**
     * Applies this pick to a step: POINT and DRAG_START land in x/y and
     * carry the element truth the probe saw; DRAG_END lands in endX/endY.
     * Element fields already on the step are KEPT when the probe saw
     * nothing — a pick never erases information the editor already had.
     */
    fun appliedTo(step: MacroStep): MacroStep = when (kind) {
        KIND_DRAG_END -> step.copy(endX = x, endY = y)
        else -> step.copy(
            x = x,
            y = y,
            selector = selector ?: step.selector,
            selectorKind = if (selector != null) selectorKind else step.selectorKind,
            text = text ?: step.text
        )
    }

    companion object {
        const val KIND_POINT = "POINT"
        const val KIND_DRAG_START = "DRAG_START"
        const val KIND_DRAG_END = "DRAG_END"

        /** Same tolerance as [MacroCodec]: unknown keys ignored, missing keep defaults. */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(pick: MacroPick): String = json.encodeToString(MacroPick.serializer(), pick)

        fun decode(raw: String): MacroPick? =
            runCatching { json.decodeFromString(MacroPick.serializer(), raw) }.getOrNull()
    }
}
