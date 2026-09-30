package com.roombrowser.domain.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parent-domain matching semantics: subdomains of a saved login match (both
 * directions), but hosts that merely share a suffix never do.
 */
class CredentialDomainMatcherTest {

    @Test
    fun `equal hosts match case-insensitively and ignore the trailing dot`() {
        assertThat(CredentialDomainMatcher.matches("example.com", "example.com")).isTrue()
        assertThat(CredentialDomainMatcher.matches("Example.COM", "example.com")).isTrue()
        assertThat(CredentialDomainMatcher.matches("example.com.", "example.com")).isTrue()
        assertThat(CredentialDomainMatcher.matches(" example.com ", "example.com")).isTrue()
    }

    @Test
    fun `page subdomain matches saved parent domain`() {
        // The classic autofill case: login saved on the bare domain, offered
        // on the login subdomain.
        assertThat(CredentialDomainMatcher.matches("google.com", "accounts.google.com")).isTrue()
        // Deeper subdomains still match.
        assertThat(CredentialDomainMatcher.matches("example.com", "a.b.example.com")).isTrue()
    }

    @Test
    fun `saved subdomain matches page parent domain`() {
        // Saved on the login subdomain, offered on the bare domain.
        assertThat(CredentialDomainMatcher.matches("accounts.google.com", "google.com")).isTrue()
    }

    @Test
    fun `sibling subdomains do not match`() {
        assertThat(CredentialDomainMatcher.matches("accounts.google.com", "mail.google.com"))
            .isFalse()
    }

    @Test
    fun `shared suffix without the dot boundary does not match`() {
        assertThat(CredentialDomainMatcher.matches("evil.com", "notevil.com")).isFalse()
        assertThat(CredentialDomainMatcher.matches("evil.com", "evil.com.evil.example")).isFalse()
    }

    @Test
    fun `different sites do not match`() {
        assertThat(CredentialDomainMatcher.matches("google.com", "google.org")).isFalse()
        assertThat(CredentialDomainMatcher.matches("example.com", "another.example")).isFalse()
    }

    @Test
    fun `empty or blank hosts never match`() {
        assertThat(CredentialDomainMatcher.matches("", "example.com")).isFalse()
        assertThat(CredentialDomainMatcher.matches("example.com", "")).isFalse()
        assertThat(CredentialDomainMatcher.matches("   ", "example.com")).isFalse()
        assertThat(CredentialDomainMatcher.matches("example.com", ".")).isFalse()
    }

    @Test
    fun `normalize produces the canonical host`() {
        assertThat(CredentialDomainMatcher.normalize("  Example.COM. ")).isEqualTo("example.com")
        assertThat(CredentialDomainMatcher.normalize("accounts.google.com"))
            .isEqualTo("accounts.google.com")
        assertThat(CredentialDomainMatcher.normalize("")).isEmpty()
    }
}
