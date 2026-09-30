package com.roombrowser.domain.agent

/**
 * Which model ids are worth offering as an agent's brain.
 *
 * No provider's model-list endpoint says whether a model can call tools — the
 * OpenAI-compatible `/v1/models` returns bare ids, and Ollama's `/api/tags`
 * carries no capability field (its `/api/show` does, but only per model and
 * only on recent builds). So "can this model act?" has to be answered from the
 * name, and that is what this is: an allowlist of the families that ship a
 * tool/function template, matched against the id the provider returned.
 *
 * It exists because the failure it prevents is silent. A model without tool
 * support does not error — it answers the turn in prose, the agent loop sees
 * no calls, and the run ends with a polite paragraph instead of the action the
 * user asked for. Picking one is therefore a trap, and the picker is the only
 * place it can be avoided.
 *
 * Deliberately conservative in ONE direction: an unrecognised id is reported
 * as unsupported, so the caller can choose to hide it — but a caller must not
 * let this empty the list (see the editor, which stops filtering when nothing
 * matches). Hiding a working model is a worse failure than showing a doubtful
 * one, because the user cannot see what they are missing. [PromptToolGateway]
 * is the safety net underneath: any provider can still be driven through the
 * text contract, so this is about which models are worth defaulting to, not
 * about what is possible.
 */
object ToolCapableModels {

    /**
     * Prefixes of families that accept a tool schema, or that follow the text
     * contract well enough to act on it.
     *
     * Matched against a whole path segment (see [supports]) rather than the
     * raw string, so `meta-llama/Llama-3.1-8B-Instruct` and `llama3.1:8b` both
     * resolve without the vendor prefix or the tag getting in the way.
     */
    private val FAMILIES = listOf(
        // Anthropic — every Claude 3 and later takes `tools`.
        "claude-3", "claude-4", "claude-5",
        "claude-opus", "claude-sonnet", "claude-haiku", "claude-fable",
        // OpenAI — the chat families, plus the reasoning models.
        "gpt-3.5-turbo", "gpt-4", "gpt-5", "chatgpt-4o", "o1", "o3", "o4",
        // Google
        "gemini-1.5", "gemini-2", "gemini-3", "gemini-pro",
        // Meta — 3.1 was the release that added the tool template.
        "llama-3", "llama-4", "llama3", "llama4",
        // Mistral
        "mistral-large", "mistral-medium", "mistral-small", "mistral-nemo",
        "mixtral", "ministral", "devstral", "magistral",
        // Alibaba
        "qwen2", "qwen3", "qwen-2", "qwen-3", "qwq",
        // DeepSeek — the chat line, not the coder line.
        "deepseek-chat", "deepseek-v3", "deepseek-v4",
        // Cohere, xAI, Zhipu, Moonshot
        "command-r", "command-a", "grok-2", "grok-3", "grok-4", "grok-code",
        "glm-4", "kimi-k2",
        // Fine-tunes built for exactly this
        "hermes", "functionary", "firefunction", "gorilla",
    )

    /**
     * Ids that carry a [FAMILIES] prefix but still take no tools.
     *
     * `gpt-3.5-turbo-instruct` is the case that matters: it is the completion
     * endpoint wearing a chat model's name, and it would otherwise be caught
     * by the `gpt-3.5-turbo` prefix and offered as if it could act.
     */
    private val EXCLUDED = listOf(
        "gpt-3.5-turbo-instruct",
    )

    /**
     * Whether [modelId] names a model known to call tools.
     *
     * Vendor namespace and tag are stripped first: an id is split on `/` and
     * `:` and a family matches when a segment STARTS WITH it, so
     * `openai/gpt-4o-mini`, `qwen2.5:7b` and `Qwen2.5-7B-Instruct` all match
     * while `text-embedding-3-large` does not.
     */
    fun supports(modelId: String): Boolean {
        val segments = modelId.trim().lowercase().split('/', ':')
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.any { segment -> EXCLUDED.any { segment.startsWith(it) } }) {
            return false
        }
        return segments.any { segment -> FAMILIES.any { segment.startsWith(it) } }
    }

    /** The subset of [modelIds] that [supports] — order preserved. */
    fun filter(modelIds: List<String>): List<String> =
        modelIds.filter { supports(it) }
}
