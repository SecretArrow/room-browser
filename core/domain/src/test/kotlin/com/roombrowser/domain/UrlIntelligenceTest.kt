package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.engine.UrlIntelligence.Input
import org.junit.Test

class UrlIntelligenceTest {

    @Test
    fun `full https url is web`() {
        val (input, url) = UrlIntelligence.classify("https://example.com/path")
        assertThat(input).isEqualTo(Input.Web("https://example.com/path", upgradedToHttps = false))
        assertThat(url).isEqualTo("https://example.com/path")
    }

    @Test
    fun `http url upgraded to https`() {
        val (input, _) = UrlIntelligence.classify("http://example.com")
        assertThat(input).isEqualTo(Input.Web("https://example.com", upgradedToHttps = true))
    }

    @Test
    fun `bare host gets https scheme`() {
        val (input, url) = UrlIntelligence.classify("example.com")
        assertThat(input).isInstanceOf(Input.Web::class.java)
        assertThat(url).isEqualTo("https://example.com")
    }

    @Test
    fun `bare host with path`() {
        val (_, url) = UrlIntelligence.classify("example.com/a/b?x=1")
        assertThat(url).isEqualTo("https://example.com/a/b?x=1")
    }

    @Test
    fun `ipv4 literal becomes http url`() {
        val (_, url) = UrlIntelligence.classify("192.168.1.10:8080")
        assertThat(url).isEqualTo("http://192.168.1.10:8080")
    }

    @Test
    fun `ipv6 literal becomes bracketed http url`() {
        val (_, url) = UrlIntelligence.classify("2001:db8::1")
        assertThat(url).isEqualTo("http://[2001:db8::1]")
    }

    @Test
    fun `localhost detected`() {
        val (_, url) = UrlIntelligence.classify("localhost:3000")
        assertThat(url).isEqualTo("http://localhost:3000")
    }

    @Test
    fun `file uri passed through`() {
        val (_, url) = UrlIntelligence.classify("file:///sdcard/doc.pdf")
        assertThat(url).isEqualTo("file:///sdcard/doc.pdf")
    }

    @Test
    fun `plain query is search`() {
        val (input, url) = UrlIntelligence.classify("how to boil eggs")
        assertThat(input).isInstanceOf(Input.Search::class.java)
        assertThat(url).contains("duckduckgo.com")
        assertThat(url).contains("how+to+boil+eggs")
    }

    @Test
    fun `query with spaces is search`() {
        val (input, _) = UrlIntelligence.classify("example.com has spaces")
        assertThat(input).isInstanceOf(Input.Search::class.java)
    }

    @Test
    fun `javascript scheme is treated as search`() {
        val (input, _) = UrlIntelligence.classify("javascript:alert(1)")
        assertThat(input).isInstanceOf(Input.Search::class.java)
    }

    @Test
    fun `hostOf parses hosts`() {
        assertThat(UrlIntelligence.hostOf("https://Example.COM/path")).isEqualTo("example.com")
        assertThat(UrlIntelligence.hostOf("https://user@site.org:8443/x")).isEqualTo("site.org")
        assertThat(UrlIntelligence.hostOf("not a url")).isNull()
    }

    @Test
    fun `displayUrl strips scheme`() {
        assertThat(UrlIntelligence.displayUrl("https://example.com/")).isEqualTo("example.com")
        assertThat(UrlIntelligence.displayUrl("http://example.com/x")).isEqualTo("example.com/x")
    }

    @Test
    fun `looksLikeUrl heuristics`() {
        assertThat(UrlIntelligence.looksLikeUrl("https://example.com")).isTrue()
        assertThat(UrlIntelligence.looksLikeUrl("example.com")).isTrue()
        assertThat(UrlIntelligence.looksLikeUrl("hello world")).isFalse()
    }
}
