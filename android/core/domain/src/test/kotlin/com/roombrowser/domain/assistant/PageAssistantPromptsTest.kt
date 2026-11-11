package com.roombrowser.domain.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageAssistantPromptsTest {

    // ---------- pageTextForPrompt ----------

    @Test
    fun `blank page yields empty prompt text`() {
        assertThat(PageAssistantPrompts.pageTextForPrompt(null)).isEmpty()
        assertThat(PageAssistantPrompts.pageTextForPrompt("   ")).isEmpty()
        assertThat(PageAssistantPrompts.pageTextForPrompt("\n\n\t")).isEmpty()
    }

    @Test
    fun `html whitespace collapses to single blanks and newlines`() {
        val raw = "Line one\r\n\r\nLine   two\n\n\n\nLine\t\tthree\n \n \nLine four"
        val out = PageAssistantPrompts.pageTextForPrompt(raw)
        assertThat(out).isEqualTo("Line one\nLine two\nLine three\nLine four")
    }

    @Test
    fun `short text passes through untouched`() {
        val raw = "A short page body."
        assertThat(PageAssistantPrompts.pageTextForPrompt(raw)).isEqualTo(raw)
    }

    @Test
    fun `long text keeps head and tail with the elision mark`() {
        val head = "HEAD ".repeat(300).trim() // 1499 chars
        val tail = "TAIL ".repeat(300).trim() // 1499 chars
        val raw = head + "\n" + "MIDDLE ".repeat(3000) + "\n" + tail
        val out = PageAssistantPrompts.pageTextForPrompt(raw, maxChars = 1200)
        assertThat(out.length).isAtMost(1200)
        assertThat(out).startsWith("HEAD")
        assertThat(out).endsWith("TAIL")
        assertThat(out).contains(PageAssistantPrompts.ELISION_MARK)
        assertThat(out).doesNotContain("MIDDLE")
    }

    @Test
    fun `head share outweighs tail`() {
        // A 1000-char page cut to 100 chars: 70 head, 30 tail (minus the mark).
        val raw = "H".repeat(500) + "M".repeat(1) + "T".repeat(499)
        val out = PageAssistantPrompts.pageTextForPrompt(raw, maxChars = 100)
        val mark = PageAssistantPrompts.ELISION_MARK
        val keep = 100 - mark.length - 2
        val headLen = (keep * 0.7).toInt()
        val tailLen = keep - headLen
        assertThat(out.count { it == 'H' }).isEqualTo(headLen)
        assertThat(out.count { it == 'T' }).isEqualTo(tailLen)
    }

    // ---------- buildUserPrompt ----------

    @Test
    fun `summarize prompt names its job and carries the page`() {
        val p = PageAssistantPrompts.buildUserPrompt(AskKind.SUMMARIZE, "PAGE BODY")
        assertThat(p).contains("Summarize this page")
        assertThat(p).contains("PAGE BODY")
    }

    @Test
    fun `ask prompt carries the question`() {
        val p = PageAssistantPrompts.buildUserPrompt(AskKind.ASK, "PAGE", question = "  Who is cited?  ")
        assertThat(p).contains("Who is cited?")
        assertThat(p).contains("PAGE")
    }

    @Test
    fun `translate prompt carries the target language`() {
        val p = PageAssistantPrompts.buildUserPrompt(AskKind.TRANSLATE, "PAGE", targetLanguage = "Indonesian")
        assertThat(p).contains("Indonesian")
    }

    @Test
    fun `translate without a target falls back to English`() {
        val p = PageAssistantPrompts.buildUserPrompt(AskKind.TRANSLATE, "PAGE")
        assertThat(p).contains("English")
    }

    // ---------- cleanAnswer ----------

    @Test
    fun `reasoning scratch pad is stripped from the answer`() {
        val raw = "<think>I should check the lede first.</think>The page says X."
        assertThat(PageAssistantPrompts.cleanAnswer(raw)).isEqualTo("The page says X.")
    }

    @Test
    fun `unclosed think block never renders as the answer`() {
        val raw = "<think>half a thought and the stream died"
        assertThat(PageAssistantPrompts.cleanAnswer(raw)).isEqualTo(raw.trim())
    }

    @Test
    fun `a normal answer passes through byte-exact`() {
        val raw = "  The page says X.\n\nDetails follow.  "
        assertThat(PageAssistantPrompts.cleanAnswer(raw)).isEqualTo("The page says X.\n\nDetails follow.")
    }

    @Test
    fun `empty answer stays empty`() {
        assertThat(PageAssistantPrompts.cleanAnswer(null)).isEmpty()
        assertThat(PageAssistantPrompts.cleanAnswer("")).isEmpty()
    }

    // ---------- canAsk ----------

    @Test
    fun `an extracted-but-empty page refuses the ask`() {
        assertThat(PageAssistantPrompts.canAsk("")).isFalse()
        assertThat(PageAssistantPrompts.canAsk("some body text")).isTrue()
    }
}
