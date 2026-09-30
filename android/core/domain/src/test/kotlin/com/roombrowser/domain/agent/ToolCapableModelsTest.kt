package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The model-name heuristic behind the provider editor's "can call tools"
 * filter.
 *
 * Worth its own tests because it is the one place the app guesses: a wrong
 * "yes" offers a model that will answer in prose instead of acting, and a
 * wrong "no" hides a model that would have worked. Both are silent.
 */
class ToolCapableModelsTest {

    @Test
    fun `the chat families of every supported provider are recognised`() {
        val known = listOf(
            "claude-sonnet-5", "claude-opus-5", "claude-3-5-haiku-20241022",
            "gpt-4o", "gpt-4.1-mini", "gpt-5", "o3-mini", "gpt-3.5-turbo",
            "gemini-2.0-flash", "gemini-1.5-pro",
            "llama-3.1-70b-versatile", "llama4:scout",
            "mistral-large-latest", "mixtral-8x7b", "devstral-small",
            "qwen2.5:7b", "qwen3:32b",
            "deepseek-chat", "deepseek-v3",
            "command-r-plus", "grok-4", "glm-4.6", "kimi-k2",
        )
        for (id in known) {
            assertThat(ToolCapableModels.supports(id)).isTrue()
        }
    }

    @Test
    fun `a vendor namespace and a tag do not hide the family`() {
        assertThat(ToolCapableModels.supports("meta-llama/Llama-3.1-8B-Instruct")).isTrue()
        assertThat(ToolCapableModels.supports("openai/gpt-4o-mini")).isTrue()
        assertThat(ToolCapableModels.supports("Qwen/Qwen2.5-7B-Instruct")).isTrue()
        assertThat(ToolCapableModels.supports("llama3.2:1b")).isTrue()
    }

    @Test
    fun `casing and surrounding space are ignored`() {
        assertThat(ToolCapableModels.supports("  GPT-4O  ")).isTrue()
        assertThat(ToolCapableModels.supports("Claude-Sonnet-5")).isTrue()
    }

    /**
     * The trap this exists for: a completion model wearing a chat model's
     * name. `gpt-3.5-turbo-instruct` would match the `gpt-3.5-turbo` prefix.
     */
    @Test
    fun `a completion model is not mistaken for its chat sibling`() {
        assertThat(ToolCapableModels.supports("gpt-3.5-turbo-instruct")).isFalse()
        assertThat(ToolCapableModels.supports("gpt-3.5-turbo")).isTrue()
    }

    @Test
    fun `models that are not for talking to are refused`() {
        val other = listOf(
            "text-embedding-3-large", "whisper-1", "tts-1",
            "dall-e-3", "omni-moderation-latest",
            "deepseek-coder", "codellama:13b", "phi-2", "gemma2:9b",
        )
        for (id in other) {
            assertThat(ToolCapableModels.supports(id)).isFalse()
        }
    }

    /** An id nobody recognises is reported unsupported, so a caller may hide
     *  it — and the editor stops filtering when that would hide everything. */
    @Test
    fun `an unknown id is unsupported`() {
        assertThat(ToolCapableModels.supports("some-org/mystery-model-v2")).isFalse()
        assertThat(ToolCapableModels.supports("")).isFalse()
        assertThat(ToolCapableModels.supports("   ")).isFalse()
        assertThat(ToolCapableModels.supports("/")).isFalse()
    }

    @Test
    fun `filter keeps the provider's order and drops only the rest`() {
        val listed = listOf("text-embedding-3-small", "gpt-4o", "whisper-1", "o3-mini")

        assertThat(ToolCapableModels.filter(listed))
            .containsExactly("gpt-4o", "o3-mini").inOrder()
    }

    @Test
    fun `filtering a list with nothing usable yields nothing`() {
        assertThat(ToolCapableModels.filter(listOf("whisper-1", "dall-e-3"))).isEmpty()
        assertThat(ToolCapableModels.filter(emptyList())).isEmpty()
    }
}
