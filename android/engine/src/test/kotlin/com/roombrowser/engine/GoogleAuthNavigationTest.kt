package com.roombrowser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The re-issue contract for the sign-in hosts, pinned before it ever reaches a
 * WebView, because every branch of this decision is a security or a breakage
 * boundary:
 *
 * - an exact-host match only: `accounts.google.com.evil.example` must never
 *   re-issue, because the re-issue loads the URL with the session's own
 *   engine and a suffix match would hand that to a lookalike host;
 * - https only: the override exists for Google's sign-in surface, and a
 *   plain-http "sign-in" page is a phishing page the engine should treat as
 *   any other page;
 * - GET only: re-issuing a POST through `loadUrl` is not expressible and
 *   would silently turn a form submission into a navigation;
 * - main frame only: an iframe must never be able to drive engine-issued
 *   loads, the same rule the external-scheme handoff applies;
 * - and the guard: the re-issued load's own callback must recognise itself,
 *   or the navigation loops forever.
 */
class GoogleAuthNavigationTest {

    // ---- hostOf ----------------------------------------------------------

    @Test
    fun `hostOf reads the host out of a plain https url`() {
        assertEquals(
            "accounts.google.com",
            GoogleAuthNavigation.hostOf("https://accounts.google.com/SignIn?continue=x")
        )
    }

    @Test
    fun `hostOf strips the port`() {
        assertEquals(
            "accounts.google.com",
            GoogleAuthNavigation.hostOf("https://accounts.google.com:443/o/oauth2/auth")
        )
    }

    @Test
    fun `hostOf strips userinfo so an at-sign cannot forge a host`() {
        assertEquals(
            "accounts.google.com",
            GoogleAuthNavigation.hostOf("https://evil.example\\@accounts.google.com/signin")
        )
    }

    @Test
    fun `hostOf is case-insensitive on the scheme and lower-cases the host`() {
        assertEquals(
            "accounts.google.com",
            GoogleAuthNavigation.hostOf("HTTPS://ACCOUNTS.GOOGLE.COM/SignIn")
        )
    }

    @Test
    fun `hostOf answers null for anything that is not https`() {
        assertNull(GoogleAuthNavigation.hostOf("http://accounts.google.com/signin"))
        assertNull(GoogleAuthNavigation.hostOf("about:home"))
        assertNull(GoogleAuthNavigation.hostOf("oct://circle/doc"))
        assertNull(GoogleAuthNavigation.hostOf("javascript:alert(1)"))
        assertNull(GoogleAuthNavigation.hostOf(""))
    }

    // ---- isSignInTarget --------------------------------------------------

    @Test
    fun `every listed sign-in host is a target`() {
        for (host in GoogleAuthNavigation.SIGN_IN_HOSTS) {
            assertTrue("host listed but not matched: $host", GoogleAuthNavigation.isSignInTarget("https://$host/"))
        }
    }

    @Test
    fun `a lookalike host with the sign-in host as a suffix is not a target`() {
        assertFalse(
            GoogleAuthNavigation.isSignInTarget("https://accounts.google.com.evil.example/signin")
        )
        assertFalse(
            GoogleAuthNavigation.isSignInTarget("https://not-accounts.google.com/signin")
        )
        assertFalse(
            GoogleAuthNavigation.isSignInTarget("https://evil.example/path/accounts.google.com")
        )
    }

    @Test
    fun `a lookalike host with a port on a sign-in host is still matched`() {
        assertTrue(
            GoogleAuthNavigation.isSignInTarget("https://accounts.google.com:443/signin/v2/")
        )
    }

    // ---- isGoogleOwnedHost -----------------------------------------------

    @Test
    fun `every google-owned bare domain and subdomain is owned`() {
        for (domain in GoogleAuthNavigation.GOOGLE_OWNED_DOMAINS) {
            assertTrue("bare domain", GoogleAuthNavigation.isGoogleOwnedHost("https://$domain/"))
            assertTrue(
                "one subdomain",
                GoogleAuthNavigation.isGoogleOwnedHost("https://accounts.$domain/signin")
            )
            assertTrue(
                "deep subdomain",
                GoogleAuthNavigation.isGoogleOwnedHost("https://storage.cloud.$domain/x")
            )
        }
    }

    @Test
    fun `the surfaces the user signs in on are owned`() {
        // The guard widened FROM these hosts; they must remain inside it.
        for (host in GoogleAuthNavigation.SIGN_IN_HOSTS) {
            assertTrue(
                "sign-in host left the bare scope: $host",
                GoogleAuthNavigation.isGoogleOwnedHost("https://$host/")
            )
        }
    }

    @Test
    fun `a lookalike or foreign host is never google-owned`() {
        assertFalse(GoogleAuthNavigation.isGoogleOwnedHost("https://notgoogle.com/"))
        assertFalse(GoogleAuthNavigation.isGoogleOwnedHost("https://google.com.evil.example/"))
        assertFalse(GoogleAuthNavigation.isGoogleOwnedHost("https://evil.google.com.attacker.io/"))
        assertFalse(GoogleAuthNavigation.isGoogleOwnedHost("https://example.com/"))
        assertFalse(GoogleAuthNavigation.isGoogleOwnedHost("http://accounts.google.com/"))
        assertFalse(GoogleAuthNavigation.isGoogleOwnedHost("about:home"))
    }

    // ---- shouldReissueWithIdentityHeaders --------------------------------

    private fun reissue(
        url: String,
        isForMainFrame: Boolean = true,
        method: String = "GET",
        lastReissuedUrl: String? = null,
        lastReissuedAtMs: Long = 0L,
        nowMs: Long = 10_000L,
    ): Boolean = GoogleAuthNavigation.shouldReissueWithIdentityHeaders(
        url, isForMainFrame, method, lastReissuedUrl, lastReissuedAtMs, nowMs
    )

    @Test
    fun `the everyday case re-issues - a sign-in link click`() {
        assertTrue(reissue("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.google.com"))
    }

    @Test
    fun `a subframe navigation never re-issues`() {
        assertFalse(
            reissue(
                "https://accounts.google.com/o/oauth2/iframe",
                isForMainFrame = false
            )
        )
    }

    @Test
    fun `a form POST never re-issues`() {
        assertFalse(
            reissue("https://accounts.google.com/_/signin/post", method = "POST")
        )
    }

    @Test
    fun `a non-sign-in host never re-issues - including other google hosts`() {
        assertFalse(reissue("https://www.google.com/"))
        assertFalse(reissue("https://mail.google.com/mail/"))
        assertFalse(reissue("https://evil.example/signin"))
    }

    @Test
    fun `a plain-http sign-in url never re-issues`() {
        assertFalse(reissue("http://accounts.google.com/signin"))
    }

    @Test
    fun `the guard recognises its own echo inside the window`() {
        val url = "https://accounts.google.com/ServiceLogin"
        assertFalse(
            reissue(
                url,
                lastReissuedUrl = url,
                lastReissuedAtMs = 10_000L - 1,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun `the guard expires so a later click re-issues again`() {
        val url = "https://accounts.google.com/ServiceLogin"
        assertTrue(
            reissue(
                url,
                lastReissuedUrl = url,
                lastReissuedAtMs = 10_000L - GoogleAuthNavigation.GUARD_WINDOW_MS,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun `a different url inside the guard window is never mistaken for the echo`() {
        assertTrue(
            reissue(
                "https://accounts.google.com/o/oauth2/auth?client_id=x",
                lastReissuedUrl = "https://accounts.google.com/ServiceLogin",
                lastReissuedAtMs = 10_000L - 1,
                nowMs = 10_000L
            )
        )
    }

    // ---- shouldReissueAfterRedirect ---------------------------------------

    private fun redirect(
        url: String,
        lastReissuedUrl: String? = null,
        lastReissuedAtMs: Long = 0L,
        nowMs: Long = 10_000L,
    ): Boolean = GoogleAuthNavigation.shouldReissueAfterRedirect(
        url, lastReissuedUrl, lastReissuedAtMs, nowMs
    )

    @Test
    fun `a server-side redirect landing on a sign-in host is re-issued`() {
        // The gmail.com -> accounts.google.com route: the engine followed a
        // 302 on its own, no shouldOverrideUrlLoading ever fired, and the
        // document request went out stamped. This is the case the redirect
        // path exists for.
        assertTrue(
            redirect("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmail.google.com")
        )
    }

    @Test
    fun `a redirect to a non-sign-in host is never re-issued`() {
        assertFalse(redirect("https://www.google.com/"))
        assertFalse(redirect("https://mail.google.com/mail/u/0"))
        assertFalse(redirect("http://accounts.google.com/ServiceLogin"))
    }

    @Test
    fun `the redirect path recognises its own re-issue within the guard window`() {
        val url = "https://accounts.google.com/ServiceLogin?continue=x"
        assertFalse(
            redirect(
                url,
                lastReissuedUrl = url,
                lastReissuedAtMs = 10_000L - 1,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun `the redirect path re-issues again after the guard window expires`() {
        val url = "https://accounts.google.com/ServiceLogin?continue=x"
        assertTrue(
            redirect(
                url,
                lastReissuedUrl = url,
                lastReissuedAtMs = 10_000L - GoogleAuthNavigation.GUARD_WINDOW_MS,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun `a different sign-in url inside the window is not mistaken for the redirect echo`() {
        assertTrue(
            redirect(
                "https://accounts.google.com/o/oauth2/auth?client_id=x",
                lastReissuedUrl = "https://accounts.google.com/ServiceLogin",
                lastReissuedAtMs = 10_000L - 1,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun `the sign-in host set is exactly the surfaces a user reaches by clicking`() {
        assertEquals(
            setOf(
                "accounts.google.com",
                "accounts.youtube.com",
                "myaccount.google.com"
            ),
            GoogleAuthNavigation.SIGN_IN_HOSTS
        )
    }

    // ---- isSignInContext ---------------------------------------------------

    @Test
    fun `a resource belonging to a sign-in page is in the sign-in context`() {
        assertTrue(
            GoogleAuthNavigation.isSignInContext(
                "https://accounts.google.com/v3/signin/identifier",
                "https://www.gstatic.com/_/boq-signin/_/js/x.js"
            )
        )
        assertTrue(
            GoogleAuthNavigation.isSignInContext(
                "https://accounts.youtube.com/",
                "https://evil.example/track.js"
            )
        )
    }

    @Test
    fun `a resource served by a sign-in host is in the context from any page`() {
        // The OAuth iframe embedded in another site's page: blocking it
        // breaks the flow even though the PAGE is not Google's.
        assertTrue(
            GoogleAuthNavigation.isSignInContext(
                "https://example.com/login",
                "https://accounts.google.com/o/oauth2/iframe"
            )
        )
    }

    @Test
    fun `a request with neither side on a sign-in host is not in the context`() {
        assertFalse(
            GoogleAuthNavigation.isSignInContext(
                "https://www.google.com/search?q=x",
                "https://www.google.com/x.js"
            )
        )
        assertFalse(
            GoogleAuthNavigation.isSignInContext(
                "https://example.com/",
                "https://tracker.example/pixel"
            )
        )
    }

    @Test
    fun `a null page url falls back to the resource host alone`() {
        assertFalse(
            GoogleAuthNavigation.isSignInContext(null, "https://tracker.example/pixel")
        )
        assertTrue(
            GoogleAuthNavigation.isSignInContext(null, "https://accounts.google.com/o/oauth2/iframe")
        )
    }

    @Test
    fun `lookalike hosts are never the sign-in context`() {
        assertFalse(
            GoogleAuthNavigation.isSignInContext(
                "https://accounts.google.com.evil.example/",
                "https://cdn.evil.example/x.js"
            )
        )
        assertFalse(
            GoogleAuthNavigation.isSignInContext(
                "https://example.com/",
                "http://accounts.google.com/iframe"
            )
        )
    }
}
