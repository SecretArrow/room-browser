package com.roombrowser.domain.model

/**
 * One language option.
 *
 * [code] is a BCP-47 style tag (base language, optionally with a region) and
 * doubles as the target-language code for Google Translate — the same string
 * works for the browser UI language and for
 * `ProfileSettings.translateTargetLanguage`.
 */
data class LanguagePreset(
    val code: String,
    val displayName: String
)

/**
 * Curated language presets, ordered by relevance to this browser's audience:
 * Indonesian first, then English and the languages most commonly requested
 * after it.
 *
 * The codes double as Google Translate target-language codes (e.g. "id",
 * "en-US", "zh-CN"), so one list serves both the UI language picker and the
 * translate target picker.
 *
 * Custom codes are allowed beyond the presets: the list is the curated set
 * offered in the pickers, not the boundary of what can be stored. [isSupported]
 * therefore answers "is this a usable language code", while [byCode] answers
 * "is this one of the curated choices".
 */
object LanguagePresets {

    val all: List<LanguagePreset> = listOf(
        LanguagePreset(code = "id", displayName = "Bahasa Indonesia"),
        LanguagePreset(code = "en-US", displayName = "English (US)"),
        LanguagePreset(code = "en-GB", displayName = "English (UK)"),
        LanguagePreset(code = "es", displayName = "Español"),
        LanguagePreset(code = "pt-BR", displayName = "Português (Brasil)"),
        LanguagePreset(code = "fr", displayName = "Français"),
        LanguagePreset(code = "de", displayName = "Deutsch"),
        LanguagePreset(code = "it", displayName = "Italiano"),
        LanguagePreset(code = "nl", displayName = "Nederlands"),
        LanguagePreset(code = "ru", displayName = "Русский"),
        LanguagePreset(code = "tr", displayName = "Türkçe"),
        LanguagePreset(code = "ja", displayName = "日本語"),
        LanguagePreset(code = "ko", displayName = "한국어"),
        LanguagePreset(code = "zh-CN", displayName = "中文（简体）"),
        LanguagePreset(code = "zh-TW", displayName = "中文（繁體）"),
        LanguagePreset(code = "ar", displayName = "العربية"),
        LanguagePreset(code = "hi", displayName = "हिन्दी"),
        LanguagePreset(code = "bn", displayName = "বাংলা"),
        LanguagePreset(code = "ur", displayName = "اردو"),
        LanguagePreset(code = "fa", displayName = "فارسی"),
        LanguagePreset(code = "vi", displayName = "Tiếng Việt"),
        LanguagePreset(code = "th", displayName = "ไทย"),
        LanguagePreset(code = "ms", displayName = "Bahasa Melayu"),
        LanguagePreset(code = "fil", displayName = "Filipino"),
        LanguagePreset(code = "pl", displayName = "Polski")
    )

    /**
     * The curated preset for [code], or null when the code is not one of the
     * presets. Case-insensitive, because stored settings may carry "ID" where
     * the preset says "id".
     */
    fun byCode(code: String): LanguagePreset? =
        all.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }

    /**
     * Whether [code] can be used as a language setting: a curated preset code
     * qualifies, and so does any syntactically valid tag beyond the list —
     * custom codes are allowed. A blank or malformed string does not.
     */
    fun isSupported(code: String): Boolean {
        val c = code.trim()
        if (c.isEmpty()) return false
        if (byCode(c) != null) return true
        // 2-8 alpha primary subtag, then zero or more alphanumeric subtags.
        return VALID_TAG.matches(c)
    }

    private val VALID_TAG = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")
}
