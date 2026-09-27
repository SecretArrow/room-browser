package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HttpsUpgradeFallbackTest {

    @Test
    fun `registry returns the original url exactly once`() {
        val registry = HttpsUpgradeFallbackPolicy.Registry()
        registry.register("https://only-http.example/", "http://only-http.example/")
        assertThat(registry.consume("https://only-http.example/"))
            .isEqualTo("http://only-http.example/")
        // consume() is destructive — never retry more than once
        assertThat(registry.consume("https://only-http.example/")).isNull()
    }

    @Test
    fun `registry ignores unknown urls`() {
        val registry = HttpsUpgradeFallbackPolicy.Registry()
        registry.register("https://a.example/", "http://a.example/")
        assertThat(registry.consume("https://b.example/")).isNull()
        assertThat(registry.consume(null)).isNull()
        assertThat(registry.consume("")).isNull()
    }

    @Test
    fun `registry trims whitespace defensively`() {
        val registry = HttpsUpgradeFallbackPolicy.Registry()
        registry.register(" https://a.example/ ", "http://a.example/")
        assertThat(registry.consume("https://a.example/")).isEqualTo("http://a.example/")
    }

    @Test
    fun `clear drops everything`() {
        val registry = HttpsUpgradeFallbackPolicy.Registry()
        registry.register("https://a.example/", "http://a.example/")
        registry.clear()
        assertThat(registry.size).isEqualTo(0)
        assertThat(registry.consume("https://a.example/")).isNull()
    }

    @Test
    fun `connect and timeout errors are recoverable`() {
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-6)).isTrue() // ERROR_CONNECT
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-8)).isTrue() // ERROR_TIMEOUT
    }

    @Test
    fun `unrelated errors are not recoverable`() {
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-2)).isFalse() // HOST_LOOKUP: http would fail too
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-9)).isFalse() // REDIRECT_LOOP
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-12)).isFalse() // BAD_URL
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-13)).isFalse() // file error family
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(-14)).isFalse() // FILE_NOT_FOUND
        assertThat(HttpsUpgradeFallbackPolicy.isRecoverable(0)).isFalse()
    }
}
