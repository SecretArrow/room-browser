package com.roombrowser.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The certificate-failure discriminator, pinned.
 *
 * These cases are not hypothetical: the reported symptom was a page replaced
 * by "Connection Not Secure" because one third-party sub-resource had a
 * broken certificate, and the fix is only as good as the comparison under it.
 * The two directions of error are not symmetric, so both are tested: a false
 * "main frame" costs the user a page they did not need to lose, and a false
 * "sub-resource" hides a failed navigation behind the page still on screen.
 */
class SslFrameMatchTest {

    private val page = "https://example.com/article"

    // ------------------------------------------------------- the reported bug

    @Test
    fun `a failing sub-resource on another host is not the page's failure`() {
        // The ad iframe with the expired certificate. The page keeps loading.
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "https://ads.tracker.example/pixel.gif",
                mainFrameUrl = page,
                committedUrl = page
            )
        ).isFalse()
    }

    @Test
    fun `a failing main-frame navigation is the page's failure`() {
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "https://expired.example/",
                mainFrameUrl = "https://expired.example/",
                committedUrl = page
            )
        ).isTrue()
    }

    @Test
    fun `the committed url is enough on its own`() {
        // onPageStarted/onPageFinished are main-frame-only, but a failure can
        // arrive when only the engine's committed url has caught up.
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = page,
                mainFrameUrl = null,
                committedUrl = page
            )
        ).isTrue()
    }

    // --------------------------------------------------------- what must differ

    @Test
    fun `another port of the same host is a different certificate`() {
        // Same host, different service, different certificate. Comparing host
        // alone would swallow this one.
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "https://example.com:8443/api",
                mainFrameUrl = page,
                committedUrl = page
            )
        ).isFalse()
    }

    @Test
    fun `an explicit default port matches the implicit one`() {
        // https://example.com and https://example.com:443 are the same origin,
        // and pages really do link to both.
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "https://example.com:443/article",
                mainFrameUrl = page,
                committedUrl = null
            )
        ).isTrue()
    }

    @Test
    fun `scheme is part of the comparison`() {
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "http://example.com/article",
                mainFrameUrl = page,
                committedUrl = null
            )
        ).isFalse()
    }

    @Test
    fun `a case difference in the host is not a different host`() {
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "https://EXAMPLE.com/article",
                mainFrameUrl = page,
                committedUrl = null
            )
        ).isTrue()
    }

    // ---------------------------------------------------- the safe direction

    @Test
    fun `an unparseable failing url is treated as the main frame`() {
        // Never the other way: refusing to load a page the user asked for,
        // silently, is the worse failure of the two.
        assertThat(
            SslFrameMatch.isMainFrameFailure(
                failingUrl = "not a url at all",
                mainFrameUrl = page,
                committedUrl = page
            )
        ).isTrue()
    }

    @Test
    fun `a missing failing url is treated as the main frame`() {
        assertThat(
            SslFrameMatch.isMainFrameFailure(null, page, page)
        ).isTrue()
        assertThat(
            SslFrameMatch.isMainFrameFailure("   ", page, page)
        ).isTrue()
    }

    @Test
    fun `a failing url with no host is treated as the main frame`() {
        assertThat(
            SslFrameMatch.isMainFrameFailure("data:text/plain,hello", page, page)
        ).isTrue()
    }

    @Test
    fun `nothing known at all is still treated as the main frame`() {
        assertThat(SslFrameMatch.isMainFrameFailure(page, null, null)).isTrue()
    }

    // -------------------------------------------------------------- authority

    @Test
    fun `authority fills in the default port for the web schemes`() {
        assertThat(SslFrameMatch.authorityOf("https://example.com/x")).isEqualTo("https://example.com:443")
        assertThat(SslFrameMatch.authorityOf("http://example.com/x")).isEqualTo("http://example.com:80")
        assertThat(SslFrameMatch.authorityOf("https://example.com:8443/x")).isEqualTo("https://example.com:8443")
    }

    @Test
    fun `authority ignores the path, the query and any credentials`() {
        // Two urls on one host are one certificate, whatever they point at.
        assertThat(SslFrameMatch.authorityOf("https://example.com/a/b?c=d#e"))
            .isEqualTo(SslFrameMatch.authorityOf("https://example.com/"))
        assertThat(SslFrameMatch.authorityOf("https://user:pw@example.com/"))
            .isEqualTo("https://example.com:443")
    }

    @Test
    fun `a scheme with no default port gets none`() {
        assertThat(SslFrameMatch.authorityOf("wss://example.com/socket"))
            .isEqualTo("wss://example.com:-1")
    }
}
