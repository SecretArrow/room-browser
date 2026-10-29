package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Google-owned bare scope around every document-start script: on any
 * google.com or youtube.com host the wrapper must evaluate to nothing at all,
 * and everywhere else it must be behaviourally invisible around scripts that
 * were already self-contained IIFEs. The scope test mirrors
 * [com.roombrowser.engine.GoogleAuthNavigation.isGoogleOwnedHost] through the
 * BUILT regex literal, because the regex is what the page world actually
 * executes.
 */
class SignInHostGuardTest {

    private val wrapped = SignInHostGuard.wrap("/* body */(function(){window.__x=1})();")

    @Test
    fun `the pattern is built from the google-owned domains`() {
        // One regex, built from GoogleAuthNavigation.GOOGLE_OWNED_DOMAINS --
        // the same list isGoogleOwnedHost answers from, so the JS the page
        // runs and the Kotlin the tests run cannot drift.
        for (domain in com.roombrowser.engine.GoogleAuthNavigation.GOOGLE_OWNED_DOMAINS) {
            assertThat(SignInHostGuard.jsPattern).contains(domain.replace(".", "\\."))
        }
    }

    @Test
    fun `every google-owned host and subdomain depth matches the pattern`() {
        val re = Regex(SignInHostGuard.jsPattern)
        assertThat(re.containsMatchIn("https://google.com/")).isTrue()
        assertThat(re.containsMatchIn("https://google.com")).isTrue()
        assertThat(re.containsMatchIn("https://accounts.google.com/ServiceLogin")).isTrue()
        assertThat(re.containsMatchIn("https://storage.cloud.google.com/x")).isTrue()
        assertThat(re.containsMatchIn("https://youtube.com/")).isTrue()
        assertThat(re.containsMatchIn("https://www.youtube.com/watch?v=1")).isTrue()
        assertThat(re.containsMatchIn("https://accounts.youtube.com/x?y=1#z")).isTrue()
        assertThat(re.containsMatchIn("https://mail.google.com/mail/u/0")).isTrue()
    }

    @Test
    fun `a lookalike host a foreign page and http never match`() {
        val re = Regex(SignInHostGuard.jsPattern)
        // The XSS-grade mistake the domain boundary exists to prevent.
        assertThat(re.containsMatchIn("https://accounts.google.com.evil.example/")).isFalse()
        assertThat(re.containsMatchIn("https://evil.google.com.attacker.io/")).isFalse()
        assertThat(re.containsMatchIn("https://notgoogle.com/")).isFalse()
        assertThat(re.containsMatchIn("https://googlecommunity.com/")).isFalse()
        assertThat(re.containsMatchIn("http://accounts.google.com/")).isFalse()
        assertThat(re.containsMatchIn("https://example.com/?next=https://google.com/")).isFalse()
    }

    @Test
    fun `the wrapper returns before the script on a google host`() {
        // The early return must precede the script's first statement: on a
        // google-owned host nothing after it ever evaluates.
        val guard = wrapped.substringBefore("/* body */")
        assertThat(guard).startsWith("(function(){if(")
        assertThat(guard).contains(".test(location.href))return;")
    }

    @Test
    fun `the script text is carried verbatim and the frame closes`() {
        assertThat(wrapped).contains("/* body */(function(){window.__x=1})();")
        assertThat(wrapped).endsWith("})();")
    }

    @Test
    fun `wrapping twice still keeps the inner script intact`() {
        // Scripts are already IIFEs; the guard is the OUTER frame only. A
        // second wrap (a session re-arming an already guarded text) must not
        // have consumed or rewritten the first guard.
        val twice = SignInHostGuard.wrap(wrapped)
        assertThat(twice).contains(wrapped)
        assertThat(twice).endsWith("})();")
    }
}
