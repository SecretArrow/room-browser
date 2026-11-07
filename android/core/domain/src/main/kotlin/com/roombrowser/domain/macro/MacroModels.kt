package com.roombrowser.domain.macro

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The macro model — the ONE definition of a recorded automation, shared by
 * the recorder (which produces it), the editor (which reshapes it), the
 * library (which stores it) and the runner (which executes it).
 *
 * A step is a FLAT record with every field nullable except the action:
 * the page-side probe writes what it saw, the editor edits what it wants,
 * and neither invents fields the other never had. The runner interprets a
 * step by [action] and ignores fields that action does not use.
 */
@Serializable
data class MacroStep(
    val action: String = NAVIGATE,
    /** CSS selector that identified the target at record time, if any. */
    val selector: String? = null,
    val selectorKind: String = "NONE",
    val tagName: String? = null,
    /** The element's own visible text at record time — a human-readable fallback for finding it again. */
    val text: String? = null,
    /** CSS-pixel coordinates of the target's center (CLICK/DBLCLICK/LONG_PRESS) or the gesture's start (DRAG). */
    val x: Float? = null,
    val y: Float? = null,
    /** DRAG: the gesture's end point. */
    val endX: Float? = null,
    val endY: Float? = null,
    /** SCROLL: the deltas that were applied. */
    val dx: Float? = null,
    val dy: Float? = null,
    /** INPUT: the typed value (masked for sensitive fields). NAVIGATE: the URL. KEY: the key's name. TAB_SWITCH: the tab's label. */
    val value: String? = null,
    /** The page URL the step was recorded on — playback sanity-checks it. */
    val url: String? = null,
    /** Pause BEFORE this step runs, in ms. Derived from record timestamps, editable. */
    val delayMs: Long = 0L,
    /** Per-step ceiling for element waits, in ms. */
    val timeoutMs: Long? = null,
    /** Per-step retry budget on top of the macro-level policy. */
    val retries: Int? = null,
    /** Record-time marker. The recorder's own clock; never replayed. */
    val timestampMs: Long = 0L,
    val note: String? = null
) {
    companion object {
        // Page-level actions (recorded by the page probe or played into it)
        const val CLICK = "CLICK"
        const val DBLCLICK = "DBLCLICK"
        const val LONG_PRESS = "LONG_PRESS"
        const val DRAG = "DRAG"
        const val SCROLL = "SCROLL"
        const val INPUT = "INPUT"
        const val CHANGE = "CHANGE"
        const val KEY = "KEY"

        // Chrome-level actions (recorded and played natively by the app)
        const val NAVIGATE = "NAVIGATE"
        const val BACK = "BACK"
        const val FORWARD = "FORWARD"
        const val RELOAD = "RELOAD"
        const val TAB_SWITCH = "TAB_SWITCH"
        const val NEW_TAB = "NEW_TAB"

        // Flow-control actions (authored in the editor)
        const val WAIT = "WAIT"
        const val WAIT_ELEMENT = "WAIT_ELEMENT"
        const val SCREENSHOT = "SCREENSHOT"
    }
}

/** One input a macro asks for at launch time. */
@Serializable
data class MacroVariable(
    val name: String,
    val defaultValue: String = ""
)

/** Playback-wide knobs. The editor owns them; the runner honors them. */
@Serializable
data class MacroPlayback(
    /** Multiplier applied to every delay: 1.0 = as recorded, 2.0 = half speed, 0.5 = double speed. */
    val speed: Float = 1.0f,
    /** 1 = once, N = N times, 0 = loop until stopped. */
    val repeat: Int = 1,
    /** "stop" (default), "skip" — the macro-level reaction to a failed step once its retries are spent. */
    val onError: String = "stop",
    /** Ceiling for a WAIT_ELEMENT that carries no timeout of its own, in ms. */
    val defaultTimeoutMs: Long = 8_000L
)

@Serializable
data class MacroDefinition(
    val id: String = "",
    val name: String = "Untitled macro",
    val description: String = "",
    val category: String = "General",
    val icon: String = "⚡",
    val steps: List<MacroStep> = emptyList(),
    val variables: List<MacroVariable> = emptyList(),
    val playback: MacroPlayback = MacroPlayback(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * Encode/decode [MacroDefinition]. One tolerant Json for the whole feature:
 * hand-edited exports and older rows must open, so unknown keys are ignored
 * and missing ones keep their defaults.
 */
object MacroCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(def: MacroDefinition): String =
        json.encodeToString(MacroDefinition.serializer(), def)

    fun decode(raw: String): MacroDefinition? = runCatching {
        json.decodeFromString(MacroDefinition.serializer(), raw)
    }.getOrNull()
}

/**
 * Strips values the app refuses to persist, per the macro contract: nothing
 * that authenticates a user may be stored. The page probe already withholds
 * password fields; this is the defense in depth that also catches fields the
 * page did not label as passwords but named like secrets.
 */
object MacroSanitizer {

    private val SENSITIVE_HINTS = listOf(
        "password", "passwd", "passwort", "pwd", "secret", "token", "otp",
        "pin", "ssn", "creditcard", "cardnumber", "cvv", "cvc", "privatekey"
    )

    private fun hintsAtSecret(step: MacroStep): Boolean {
        if (step.note?.contains("withheld", ignoreCase = true) == true) return true
        if (step.tagName?.equals("input", ignoreCase = true) != true) return false
        val hay = (step.selector ?: "") + " " + (step.tagName ?: "")
        return SENSITIVE_HINTS.any { hay.contains(it, ignoreCase = true) }
    }

    /** Returns the step with its value withheld when it looks sensitive. */
    fun sanitizeStep(step: MacroStep): MacroStep =
        if (step.value != null && hintsAtSecret(step)) {
            step.copy(value = null, note = "value withheld: sensitive field")
        } else {
            step
        }

    fun sanitize(def: MacroDefinition): MacroDefinition =
        def.copy(steps = def.steps.map { sanitizeStep(it) })
}

/**
 * The pre-run risk read: does this macro LOOK like it performs an
 * irreversible action (a payment, a submit, a delete)? The library shows a
 * warning before running one that does — automation that acts on the user's
 * behalf must announce itself first.
 */
object MacroRisk {

    private val DANGER_TEXT = listOf(
        "submit", "send", "pay", "transfer", "checkout", "purchase", "buy",
        "delete", "remove", "confirm", "order", "post", "publish", "withdraw"
    )

    /** True when any step looks like the commit action of a transactional flow. */
    fun isRisky(def: MacroDefinition): Boolean = def.steps.any { step ->
        when (step.action) {
            MacroStep.CLICK, MacroStep.DBLCLICK, MacroStep.KEY -> {
                val hay = (step.text ?: "") + " " + (step.selector ?: "") + " " + (step.value ?: "")
                DANGER_TEXT.any { hay.contains(it, ignoreCase = true) }
            }
            else -> false
        }
    }
}

/**
 * `{{variable}}` substitution. A step's free-text fields (value, url) may
 * reference a declared variable; at launch the runner binds values and
 * resolves every reference. An unknown name is left verbatim — the user
 * should see what failed to resolve, not a silently emptied string.
 */
object MacroVariables {

    private val REF = Regex("\\{\\{([A-Za-z0-9_ .-]+)\\}\\}")

    fun resolve(text: String, values: Map<String, String>): String =
        REF.replace(text) { m -> values[m.groupValues[1]] ?: m.value }

    fun resolve(step: MacroStep, values: Map<String, String>): MacroStep = step.copy(
        value = step.value?.let { resolve(it, values) },
        url = step.url?.let { resolve(it, values) }
    )

    /** The `{{names}}` a raw string references — used to hint the launch dialog. */
    fun referencedIn(text: String): List<String> =
        REF.findAll(text).map { it.groupValues[1] }.distinct().toList()
}
