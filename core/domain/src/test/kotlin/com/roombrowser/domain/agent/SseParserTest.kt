package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SseParserTest {

    @Test
    fun `data lines join and dispatch on blank line`() {
        val parser = SseParser()
        assertThat(parser.feed("data: hello")).isNull()
        assertThat(parser.feed("data: world")).isNull()
        assertThat(parser.feed("")).isEqualTo("hello\nworld")
    }

    @Test
    fun `single line event`() {
        val parser = SseParser()
        assertThat(parser.feed("data:{\"a\":1}")).isNull()
        assertThat(parser.feed("")).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `leading space in data is stripped once`() {
        val parser = SseParser()
        assertThat(parser.feed("data:  two spaces")).isNull()
        assertThat(parser.feed("")).isEqualTo(" two spaces") // only the separator space is removed
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        val parser = SseParser()
        assertThat(parser.feed(": keep-alive")).isNull()
        assertThat(parser.feed("event: message")).isNull()
        assertThat(parser.feed("id: 42")).isNull()
        assertThat(parser.feed("retry: 1000")).isNull()
        assertThat(parser.feed("data: payload")).isNull()
        assertThat(parser.feed("")).isEqualTo("payload")
    }

    @Test
    fun `blank lines between events do not produce empty payloads`() {
        val parser = SseParser()
        assertThat(parser.feed("")).isNull()
        assertThat(parser.feed("data: first")).isNull()
        assertThat(parser.feed("")).isEqualTo("first")
        assertThat(parser.feed("")).isNull()
        assertThat(parser.feed("")).isNull()
    }

    @Test
    fun `carriage returns are handled`() {
        val parser = SseParser()
        assertThat(parser.feed("data: crlf\r")).isNull()
        assertThat(parser.feed("\r")).isEqualTo("crlf")
    }

    @Test
    fun `typical chat stream sequence`() {
        val parser = SseParser()
        val payloads = mutableListOf<String>()
        listOf(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"He\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}",
            "",
            "data: [DONE]",
            ""
        ).forEach { line -> parser.feed(line)?.let { payloads.add(it) } }
        assertThat(payloads).containsExactly(
            "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"He\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}",
            "[DONE]"
        ).inOrder()
    }
}
