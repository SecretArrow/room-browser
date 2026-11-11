package com.roombrowser.domain.assistant

/**
 * Pure prompt/text logic for the browser's "Ask this page" assistant — the
 * JVM-tested half of the feature. The app side (PageAssistantController +
 * the sheet) only wires these into a provider call; every decision that can
 * be wrong without a network lives here so it can be unit-tested.
 */

/** What the user asked for, from the quick chips or the free-form field. */
enum class AskKind(val chipLabel: String) {
    SUMMARIZE("Summarize"),
    KEY_POINTS("Key points"),
    EXPLAIN("Explain simply"),
    TRANSLATE("Translate"),
    ASK("Ask")
}

/** The page the assistant is grounded in, captured on the engine thread. */
data class PageContext(
    val title: String,
    val url: String,
    val text: String
)

object PageAssistantPrompts {

    /**
     * Ceiling for the page text that rides inside one prompt. Small local
     * models run with 4k-16k contexts (see LocalAiTuning.num_ctx); a full
     * article is easily 40k+ characters, and a truncated context silently
     * loses the page's own conclusion. Head + tail keeps the lede AND the
     * wrap-up, which is where summaries and answers actually live.
     */
    const val MAX_PROMPT_CHARS = 12_000

    private val HEAD_SHARE = 0.7

    /** Mark inserted when the middle of the page was cut for the context. */
    const val ELISION_MARK = "[… middle of the page omitted to fit the context window …]"

    /**
     * The page text as it rides inside the prompt: HTML whitespace collapsed
     * to single newlines/spaces (innerText carries the DOM's blank runs and
     * \n repeats), blank lines dropped, then head+tail kept under [maxChars].
     * Never throws; a null/blank page yields an empty string so the caller
     * can refuse the ask with an honest message instead of inventing content.
     */
    fun pageTextForPrompt(raw: String?, maxChars: Int = MAX_PROMPT_CHARS): String {
        if (raw.isNullOrBlank()) return ""
        // Collapse per line (innerText carries the DOM's blank runs), drop
        // the lines that end up empty, and rejoin: a whitespace-only line
        // killed by the per-line trim must not leave interior blank lines
        // behind — the JVM test caught exactly that.
        val collapsed = raw
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (collapsed.isEmpty()) return ""
        if (collapsed.length <= maxChars) return collapsed
        val keep = maxChars - ELISION_MARK.length - 2
        val head = (keep * HEAD_SHARE).toInt().coerceAtLeast(1)
        val tail = (keep - head).coerceAtLeast(1)
        return collapsed.take(head) + "\n" + ELISION_MARK + "\n" + collapsed.takeLast(tail)
    }

    /**
     * The system half of the conversation. The assistant answers FROM the
     * page: when the page does not contain the answer it says so plainly
     * instead of filling the gap from its own prior knowledge — an invented
     * fact wearing the page's name is worse than an honest "not on this
     * page".
     */
    fun systemPrompt(): String =
        "You are the page assistant inside Room Browser, a mobile browser. " +
            "Answer ONLY from the page content the user gives you, in the same " +
            "language the user asked in. If the page does not contain the answer, " +
            "say plainly that it is not on this page — never fill the gap from " +
            "outside knowledge. Be concise: this renders on a phone."

    /**
     * The user turn for [kind]. [question] is only used by [AskKind.ASK];
     * [targetLanguage] is only used by [AskKind.TRANSLATE] (BCP-47 name as
     * the user would say it, e.g. "Indonesian"). [pageText] must already be
     * through [pageTextForPrompt].
     */
    fun buildUserPrompt(
        kind: AskKind,
        pageText: String,
        question: String? = null,
        targetLanguage: String? = null
    ): String {
        val body = when (kind) {
            AskKind.SUMMARIZE ->
                "Summarize this page in 3-5 sentences, then one line: who wrote/published it if the page says."
            AskKind.KEY_POINTS ->
                "List the key points of this page as short bullets, in page order. No preamble."
            AskKind.EXPLAIN ->
                "Explain this page in plain language a curious non-expert can follow. Keep it under 200 words."
            AskKind.TRANSLATE ->
                "Translate the main content of this page into ${targetLanguage ?: "English"}. " +
                    "Keep the structure (headings, lists). Do not add commentary."
            AskKind.ASK ->
                "Answer this question about the page: ${question?.trim().orEmpty()}\n" +
                    "If the page does not answer it, say so in one sentence."
        }
        return "$body\n\n=== PAGE: render the answer from this content ===\n$pageText"
    }

    /** True when there is nothing honest to ask about: no captured page text. */
    fun canAsk(pageText: String): Boolean = pageText.isNotBlank()

    /**
     * Post-processing on the answer: reasoning models that echo a
     * `<think>…</think>` block (a real DeepSeek-R1 behavior through /api/chat)
     * must not have their scratch pad rendered as the answer. Everything else
     * passes through untouched — never reflow or "improve" a model's words.
     */
    fun cleanAnswer(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noThink = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*<think>[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .trim()
        return noThink.ifBlank { raw.trim() }
    }
}
