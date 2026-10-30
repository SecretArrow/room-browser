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
        // A foreign page pulling a foreign asset: outside the Google context,
        // the engine's fast path stands and nobody grades the stamp.
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
    fun a_resource_of_a_google_owned_page_is_cleaned_the_login_pages_own_assets() {
        // The v1.0.194 gap: accounts.google.com pulls its fonts and scripts
        // from gstatic and googleapis -- hosts that are NOT sign-in hosts --
        // and those requests went out carrying the app's package. The PAGE
        // being Google-owned is what admits them now.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://fonts.gstatic.com/s/roboto/v30/font.woff2",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser"),
                pageUrl = "https://accounts.google.com/v3/signin/identifier"
            )
        ).isTrue()
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://www.googleapis.com/_/lookup/x",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser"),
                pageUrl = "https://www.google.com/search?q=x"
            )
        ).isTrue()
    }

    @Test
    fun a_resource_served_by_a_google_owned_host_is_cleaned_from_any_page() {
        // The resource's OWN host is Google's -- the OAuth iframe embedded in
        // another site's page, the static asset google.com ships. Google's
        // context, whatever the page around it is.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://www.google.com/x.js",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser"),
                pageUrl = "https://example.com/login"
            )
        ).isTrue()
    }

    @Test
    fun a_foreign_page_never_widens_the_scope_even_to_its_own_assets() {
        // A page that is not Google's keeps its assets on the native path,
        // whatever hosts it pulls from -- the widening is Google-owned pages
        // only, and a lookalike page is not one of those.
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://cdn.example.com/app.js",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser"),
                pageUrl = "https://example.com/login"
            )
        ).isFalse()
        assertThat(
            RequestedWithCleaner.shouldClean(
                isForMainFrame = false,
                url = "https://fonts.gstatic.com/s/roboto/v30/font.woff2",
                method = "GET",
                requestHeaders = headers("X-Requested-With" to "com.roombrowser.browser"),
                pageUrl = "https://google.com.evil.example/fake-login"
            )
        ).isFalse()
    }

    @Test
    fun the_reissue_headers_rewrite_the_stamp_to_chromes_application_id() {
        // Absence was the v1.0.193/194 answer and the refusal outlived it;
        // the bare wrapper that signs in sends a known browser package. The
        // re-issue therefore stamps Chrome -- under whatever spelling the
        // engine used.
        val out = RequestedWithCleaner.reissueHeaders(
            headers(
                "Accept" to "text/html",
                "X-Requested-With" to "com.roombrowser.browser",
                "Cookie" to "NID=123"
            )
        )
        assertThat(out).containsEntry("X-Requested-With", "com.android.chrome")
        assertThat(out).containsEntry("Accept", "text/html")
        assertThat(out).containsEntry("Cookie", "NID=123")
        assertThat(out).doesNotContainKey("x-requested-with")
    }

    @Test
    fun the_reissue_headers_still_drop_the_headers_the_connection_owns() {
        val out = RequestedWithCleaner.reissueHeaders(
            headers(
                "Host" to "accounts.google.com",
                "Content-Length" to "0",
                "Accept-Encoding" to "gzip",
                "Accept" to "text/html",
                "x-requested-with" to "com.roombrowser.browser"
            )
        )
        assertThat(out).doesNotContainKey("Host")
        assertThat(out).doesNotContainKey("Content-Length")
        assertThat(out).doesNotContainKey("Accept-Encoding")
        assertThat(out).containsEntry("x-requested-with", "com.android.chrome")
    }

    @Test
    fun a_blank_stamp_is_not_upgraded_to_a_claim_nobody_asked_for() {
        // The app's own loadUrl override echoes through as an empty value;
        // the re-issue must leave it absent, not stamp Chrome onto a load
        // that deliberately carried nothing.
        val out = RequestedWithCleaner.reissueHeaders(
            headers("X-Requested-With" to " ", "Accept" to "text/html")
        )
        assertThat(out).doesNotContainKey("X-Requested-With")
        assertThat(out).containsEntry("Accept", "text/html")
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
