package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sign-in host guard around every document-start script: on Google's
 * sign-in surfaces the wrapper must evaluate to nothing at all, and
 * everywhere else it must be behaviourally invisible around scripts that
 * were already self-contained IIFEs.
 */
class SignInHostGuardTest {

    private val wrapped = SignInHostGuard.wrap("/* body */(function(){window.__x=1})();")

    @Test
    fun the_pattern_names_exactly_the_sign_in_hosts() {
        // One regex, built from GoogleAuthNavigation.SIGN_IN_HOSTS -- the same
        // list the re-issue levers use. Sorted for a stable literal.
        assertThat(SignInHostGuard.jsPattern).contains("accounts\\.google\\.com")
        assertThat(SignInHostGuard.jsPattern).contains("accounts\\.youtube\\.com")
        assertThat(SignInHostGuard.jsPattern).contains("myaccount\\.google\\.com")
    }

    @Test
    fun a_sign_in_host_url_matches_the_pattern() {
        val re = Regex(SignInHostGuard.jsPattern)
        assertThat(re.containsMatchIn("https://accounts.google.com/ServiceLogin")).isTrue()
        assertThat(re.containsMatchIn("https://accounts.google.com")).isTrue()
        assertThat(re.containsMatchIn("https://accounts.youtube.com/x?y=1#z")).isTrue()
        assertThat(re.containsMatchIn("https://myaccount.google.com/security")).isTrue()
    }

    @Test
    fun a_lookalike_host_and_a_page_scheme_do_not_match() {
        val re = Regex(SignInHostGuard.jsPattern)
        // The XSS-grade mistake the host equality rule exists to prevent.
        assertThat(re.containsMatchIn("https://accounts.google.com.evil.example/")).isFalse()
        assertThat(re.containsMatchIn("http://accounts.google.com/")).isFalse()
        assertThat(re.containsMatchIn("https://mail.google.com/")).isFalse()
    }

    @Test
    fun the_wrapper_returns_before_the_script_on_a_sign_in_host() {
        // The early return must precede the script's first statement: on a
        // sign-in host nothing after it ever evaluates.
        val guard = wrapped.substringBefore("/* body */")
        assertThat(guard).startsWith("(function(){if(")
        assertThat(guard).contains(".test(location.href))return;")
    }

    @Test
    fun the_script_text_is_carried_verbatim_and_the_frame_closes() {
        assertThat(wrapped).contains("/* body */(function(){window.__x=1})();")
        assertThat(wrapped).endsWith("})();")
    }

    @Test
    fun wrapping_twice_still_keeps_the_inner_script_intact() {
        // Scripts are already IIFEs; the guard is the OUTER frame only. A
        // second wrap (a session re-arming an already guarded text) must not
        // have consumed or rewritten the first guard.
        val twice = SignInHostGuard.wrap(wrapped)
        assertThat(twice).contains(wrapped)
        assertThat(twice).endsWith("})();")
    }
}
