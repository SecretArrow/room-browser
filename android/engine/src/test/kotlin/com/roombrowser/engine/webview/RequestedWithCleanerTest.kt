package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The decision rules of the pre-emptive XRW cleaner. The execution half needs
 * an Android socket stack; the DECISION is the whole policy -- the execution
 * must never be reachable for a request any of these decline -- so these are
 * the cases that matter.
 */
class RequestedWithCleanerTest {

    private fun headers(vararg pairs: Pair<String, String>): Map<String, String?> =
        pairs.toMap()

    @Test
    fun a_stamped_main_frame_get_is_cleaned_the_redirect_hop() {
        // The cross-origin 302 hop into accounts.google.com: the engine
        // re-stamped it, no loadUrl lever reaches it, the cleaner must.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = true,
                url = "https://accounts.google.com/ServiceLogin?continue=https://mail.google.com",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser")
            )
        ).isTrue()
    }

    @Test
    fun an_already_empty_main_frame_get_is_left_alone() {
        // The loadUrl(uri, identityHeaders) override: the request is already
        // clean, re-issuing it would only spend a socket.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = true,
                url = "https://accounts.google.com/",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "")
            )
        ).isFalse()
    }

    @Test
    fun a_stamped_post_is_never_cleaned_it_would_lose_its_body() {
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = true,
                url = "https://accounts.google.com/signin/v1/lookup",
                method = "POST",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser")
            )
        ).isFalse()
    }

    @Test
    fun a_stamped_sign_in_host_subresource_is_cleaned_the_oauth_iframe() {
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://accounts.youtube.com/accounts/CheckConnection?pstamp=1",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser")
            )
        ).isTrue()
    }

    @Test
    fun a_stamped_subresource_outside_the_sign_in_hosts_is_left_alone() {
        // The gstatic fonts and googleapis scripts of a login page stay on the
        // engine's fast path; the stamp on static assets grades nothing.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://fonts.gstatic.com/s/roboto/v30/font.woff2",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser")
            )
        ).isFalse()
    }

    @Test
    fun an_unstamped_request_is_never_cleaned() {
        // A provider that no longer stamps at all: nothing to clean, every
        // request takes the native path.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://accounts.google.com/",
                method = "GET",
                requestHeaders = headers("Accept" to "text/html")
            )
        ).isFalse()
    }

    @Test
    fun the_stamp_is_recognised_under_any_header_spelling() {
        assertThat(
            RequestedWithCleaner.stamped(headers("x-requested-with" to "com.roombrowser.browser"))
        ).isTrue()
        assertThat(
            RequestedWithCleaner.stamped(headers("X-Requested-With" to " "))
        ).isFalse()
        assertThat(
            RequestedWithCleaner.stamped(headers("Accept" to "text/html"))
        ).isFalse()
    }

    @Test
    fun a_lookalike_host_is_not_a_sign_in_host() {
        // The equality rule GoogleAuthNavigation applies everywhere: a host
        // that merely STARTS with a listed host is somebody else's page.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://accounts.google.com.evil.example/login",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser")
            )
        ).isFalse()
    }
}
