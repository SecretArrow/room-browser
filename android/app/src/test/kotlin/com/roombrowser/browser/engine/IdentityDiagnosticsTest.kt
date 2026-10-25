package com.roombrowser.browser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The self-check has one job: say which surface marks this browser as embedded,
 * and never blame the wrong one.
 *
 * The failure this guards against is not a crash — it is a diagnostic that
 * reassures somebody on a device where sign-in cannot work, or that sends them
 * to update a WebView that is already current. So each case below pins both
 * halves: which row fails, and what the row says.
 */
class IdentityDiagnosticsTest {

    private val chromeUa =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36"

    /**
     * A current provider: every capability present.
     *
     * [ua] is nullable because "the engine has not reported a User-Agent yet"
     * is one of the answers this has to be able to express — it is the state
     * of a session before its first configure pass, and the row has to say so
     * rather than call a missing string clean.
     */
    private fun modern(ua: String? = chromeUa, js: Boolean = true) = IdentityDiagnostics.checks(
        userAgent = ua,
        javascriptEnabled = js,
        suppressesRequestedWith = true,
        setsClientHints = true,
        installsDocumentStartScripts = true
    )

    /** A provider older than every identity API, as the CI emulator's WebView 83 is. */
    private fun legacy(ua: String? = chromeUa) = IdentityDiagnostics.checks(
        userAgent = ua,
        javascriptEnabled = true,
        suppressesRequestedWith = false,
        setsClientHints = false,
        installsDocumentStartScripts = false
    )

    private fun checkNamed(checks: List<IdentityCheck>, label: String): IdentityCheck =
        checks.first { it.label == label }

    @Test
    fun a_current_provider_with_a_chrome_identity_has_nothing_to_report() {
        val checks = modern()

        assertThat(checks.map { it.ok }).containsExactly(true, true, true, true, true)
        // Every row is still reported: "nothing is wrong" is an answer the
        // person came here for, and an empty list would not give it.
        assertThat(checks).hasSize(5)
    }

    @Test
    fun an_embedded_user_agent_is_named_by_the_token_it_carries() {
        val stock = "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 " +
            "Mobile Safari/537.36"

        val row = checkNamed(modern(ua = stock), "User-Agent")

        assertThat(row.ok).isFalse()
        assertThat(row.detail).contains("; wv")
    }

    @Test
    fun a_user_agent_with_no_browser_token_is_reported_rather_than_called_clean() {
        // No embedded token, but nothing a site recognises either — which is
        // not a pass, and must not be shown as one.
        val row = checkNamed(modern(ua = "Mozilla/5.0 (Linux; Android 14)"), "User-Agent")

        assertThat(row.ok).isFalse()
        assertThat(row.detail).contains("no browser a sign-in page recognises")
    }

    @Test
    fun a_firefox_identity_passes_even_though_it_names_no_chrome_build() {
        // The catalogue ships Firefox, Edge, Opera and Samsung identities, and
        // Google's supported list names all of them. A row that only accepted
        // "Chrome/" would raise a false alarm on every one — which is worse
        // than no diagnostic at all, because it sends the person to fix an
        // identity that is already right.
        val firefox = "Mozilla/5.0 (Android 14; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"

        val row = checkNamed(modern(ua = firefox), "User-Agent")

        assertThat(row.ok).isTrue()
        assertThat(row.detail).isEqualTo(firefox)
    }

    @Test
    fun an_engine_with_no_user_agent_yet_says_so_instead_of_failing_silently() {
        val row = checkNamed(modern(ua = null), "User-Agent")

        assertThat(row.ok).isFalse()
        assertThat(row.detail).contains("has not reported one yet")
    }

    @Test
    fun an_old_provider_blames_the_provider_and_not_the_profile() {
        val checks = legacy()

        // The UA is the app's own string and stays correct; the three surfaces
        // the provider owns are the ones that fail.
        assertThat(checkNamed(checks, "User-Agent").ok).isTrue()
        assertThat(checkNamed(checks, "X-Requested-With header").ok).isFalse()
        assertThat(checkNamed(checks, "Sec-CH-UA client hints").ok).isFalse()
        assertThat(checkNamed(checks, "navigator.userAgentData").ok).isFalse()

        // Each names the provider as the cause, because "update Android System
        // WebView" is the only action that can fix any of them.
        for (label in listOf(
            "X-Requested-With header",
            "Sec-CH-UA client hints",
            "navigator.userAgentData"
        )) {
            assertThat(checkNamed(checks, label).detail).contains("Android System WebView")
        }
    }

    @Test
    fun javascript_off_is_reported_as_a_profile_choice_not_a_provider_limit() {
        val row = checkNamed(modern(js = false), "JavaScript")

        assertThat(row.ok).isFalse()
        assertThat(row.detail).contains("profile's own toggle")
    }

    @Test
    fun javascript_on_says_so_even_though_googles_page_lists_it_as_a_cause() {
        val row = checkNamed(modern(), "JavaScript")

        assertThat(row.ok).isTrue()
        // The row exists because Google's page names JavaScript as a possible
        // cause; a diagnostic that stayed silent here would leave the person
        // wondering whether it applied to them.
        assertThat(row.detail).contains("JavaScript turned off")
    }

    @Test
    fun the_rows_read_in_the_order_a_site_reads_them() {
        assertThat(modern().map { it.label }).containsExactly(
            "User-Agent",
            "X-Requested-With header",
            "Sec-CH-UA client hints",
            "navigator.userAgentData",
            "JavaScript"
        ).inOrder()
    }
}
