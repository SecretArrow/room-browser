package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The external-scheme handoff decision (JVM).
 *
 * WHAT IS PINNED HERE AND WHY. For a navigation the app's own policy let
 * through, the engine classifies the scheme: a non-document scheme
 * (`tel:`, `market://`, `intent://`, ...) is never rendered -- loading it
 * produced only ERR_UNKNOWN_URL_SCHEME -- it is abandoned and reported as
 * [EngineSessionListener.onExternalSchemeRequested] so the app can hand the
 * URI to the OS. The classifier is the whole scheme policy, so its edges are
 * pinned:
 *
 *  - http/https are NEVER handed off (a web link stays in-app; handing one
 *    off would offer it to whatever handler the OS picks);
 *  - the app's own `oct://` stays in-app (circles are served by the app);
 *  - the engine-internal schemes (about/blob/data/javascript/file) stay
 *    in-app;
 *  - the common OS action schemes and a random custom scheme are handoff
 *    targets;
 *  - anything without a well-formed scheme is not a handoff target.
 */
class ExternalUrlPolicyTest {

    @Test
    fun `web schemes stay in-app`() {
        assertThat(ExternalUrlPolicy.isHandoffTarget("http://example.com/")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("https://example.com/x?y=1#z")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("HTTPS://EXAMPLE.COM/")).isFalse()
    }

    @Test
    fun `engine-internal schemes stay in-app`() {
        assertThat(ExternalUrlPolicy.isHandoffTarget("about:blank")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("about:home")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("blob:https://example.com/uuid")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("data:text/html,hello")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("javascript:void(0)")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("file:///sdcard/x.pdf")).isFalse()
    }

    @Test
    fun `the app's own circle scheme stays in-app`() {
        assertThat(ExternalUrlPolicy.isHandoffTarget("oct://finance/markets")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("OCT://finance/markets")).isFalse()
    }

    @Test
    fun `os action schemes are handoff targets`() {
        val playIntent =
            "intent://example.com/#Intent;scheme=https;package=com.example;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ff;end"
        assertThat(ExternalUrlPolicy.isHandoffTarget("tel:+15551234567")).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget("sms:+15551234567?body=hi")).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget("mailto:user@example.com")).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget("geo:52.37,4.89")).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget("market://details?id=com.example")).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget(playIntent)).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget("whatsapp://send?text=hi")).isTrue()
    }

    @Test
    fun `a scheme is recognised case-insensitively`() {
        assertThat(ExternalUrlPolicy.isHandoffTarget("TEL:+15551234567")).isTrue()
        assertThat(ExternalUrlPolicy.isHandoffTarget("MailTo:user@example.com")).isTrue()
    }

    @Test
    fun `a uri without a well-formed scheme is not a handoff target`() {
        assertThat(ExternalUrlPolicy.isHandoffTarget("")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("example.com/page")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget(":nonsense")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("1http://example.com/")).isFalse()
        assertThat(ExternalUrlPolicy.isHandoffTarget("not a uri")).isFalse()
    }
}
