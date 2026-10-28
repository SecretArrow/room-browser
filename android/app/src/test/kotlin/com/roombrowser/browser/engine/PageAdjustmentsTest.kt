package com.roombrowser.browser.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.WebRtcPolicy
import org.junit.Test

/**
 * The adjustments' contract. The JavaScript itself is exercised against a
 * browser-shaped context outside the JVM; what a unit test can hold down is
 * the splicing: every placeholder filled, nothing left behind, and a value
 * that cannot break out of the string literal it is written into.
 *
 * The WebRTC predicate is held down the same way, by asserting the text of the
 * one function that makes the decision rather than by running it: there is no
 * JavaScript engine on this classpath, and pinning the decision where it is
 * written is what keeps the event filter and the SDP stripper from drifting
 * apart. A change to the predicate therefore fails these tests on purpose.
 *
 * THE IDENTITY ASSERTIONS ARE DELIBERATELY ABSENT. The object that used to sit
 * here (`DeviceShim`) also spoofed `navigator.userAgent`, the brand list and
 * the high-entropy hints in JavaScript; that half is gone — the identity is
 * the engine's own now, asserted end to end by `GoogleIdentityE2eTest` on the
 * exact bytes a request carried. What keeps THAT contract honest is an
 * assertion that the adjustments never grow identity back: the scripts below
 * must not name a single navigator property.
 */
class PageAdjustmentsTest {

    /** The restricted script on its own, which is what the WebRTC tests read. */
    private fun restricted(): String =
        PageAdjustments.scriptFor(null, WebRtcPolicy.RESTRICT_LOCAL_IP)

    /**
     * How many times [needle] occurs in [haystack]. Used to pin that a decision
     * is written once and reached from every path that needs it, rather than
     * copied into each — a count that moves is a call site that moved, which is
     * exactly what those tests have to be told about.
     */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun `a profile that asks for nothing gets nothing`() {
        // The default state: no screen claim, default WebRTC policy, scale 1.0.
        assertThat(PageAdjustments.scriptFor()).isEmpty()
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 1f)).isEmpty()
    }

    @Test
    fun `no adjustment ever touches the identity the engine serves`() {
        // THE CONTRACT THE REMOVAL OF THE IDENTITY SHIM BOUGHT. The User-Agent,
        // the brand list, userAgentData and the hardware reads are the engine's
        // own now — presented natively and asserted on the wire by
        // GoogleIdentityE2eTest. If an adjustment ever redefines one of those
        // properties in JavaScript, the page can again catch the app presenting
        // an identity its own accessor functions do not believe, so this fails
        // on purpose before such a script can ship.
        val blocks = listOf(
            PageAdjustments.scriptFor(ClaimedScreen(393, 852)),
            PageAdjustments.scriptFor(null, WebRtcPolicy.DISABLED),
            restricted(),
            PageAdjustments.scriptFor(null, WebRtcPolicy.RESTRICT_LOCAL_IP, 1.5f),
            PageAdjustments.scriptFor(
                ClaimedScreen(852, 393), WebRtcPolicy.RESTRICT_LOCAL_IP, 1.5f
            )
        )
        assertThat(blocks).isNotEmpty()
        for (js in blocks) {
            assertThat(js).doesNotContain("navigator.userAgent")
            assertThat(js).doesNotContain("userAgentData")
            assertThat(js).doesNotContain("deviceMemory")
            assertThat(js).doesNotContain("hardwareConcurrency")
            assertThat(js).doesNotContain("WebGL")
            assertThat(js).doesNotContain("Navigator.prototype, 'platform'")
            assertThat(js).doesNotContain("Google Chrome")
        }
    }

    @Test
    fun `script fills every placeholder`() {
        val js = PageAdjustments.scriptFor(ClaimedScreen(393, 852))
        // A leftover "__FOO__" would be a syntax error in the page, not a
        // silent no-op, so this is the check that matters most.
        assertThat(js).doesNotContain("__")
        assertThat(js).contains("var SCREEN_W = 393;")
        assertThat(js).contains("var SCREEN_H = 852;")
    }

    @Test
    fun `a claimed screen replaces the screen family and nothing else`() {
        val js = PageAdjustments.scriptFor(ClaimedScreen(393, 852))
        assertThat(js).contains("'width'")
        assertThat(js).contains("'height'")
        assertThat(js).contains("'availWidth'")
        assertThat(js).contains("'availHeight'")
        assertThat(js).doesNotContain("__")
        // The trade, asserted rather than described: the viewport and the pixel
        // ratio are the display's own, because they are what the page is really
        // laid out and rendered at. See SECURITY.md.
        assertThat(js).doesNotContain("innerWidth")
        assertThat(js).doesNotContain("innerHeight")
        assertThat(js).doesNotContain("devicePixelRatio")
    }

    @Test
    fun `a claimed screen agrees with the shape it claims`() {
        // A 393x852 claim is portrait; a 852x393 claim is the same screen held
        // the other way, and it must not answer "portrait-primary". Getting
        // this wrong is self-evident from the numbers, which is why it is a
        // check rather than a comment. Both answers are asserted because both
        // are what a page reads: `type` is the shape and `angle` the way round.
        val portrait = PageAdjustments.scriptFor(ClaimedScreen(393, 852))
        assertThat(portrait).contains("var TYPE = 'portrait-primary';")
        assertThat(portrait).contains("var ANGLE = 0;")

        val landscape = PageAdjustments.scriptFor(ClaimedScreen(852, 393))
        assertThat(landscape).contains("var TYPE = 'landscape-primary';")
        assertThat(landscape).contains("var ANGLE = 90;")
    }

    @Test
    fun `the default WebRTC policy installs no script of its own`() {
        // A profile that never touched the policy reads what it read before
        // the policy had a script at all: DEFAULT leaves the peer connection
        // alone, and the parameter's own default is DEFAULT, so a caller that
        // passes only a screen cannot accidentally get a policy applied.
        val js = PageAdjustments.scriptFor(ClaimedScreen(393, 852), WebRtcPolicy.DEFAULT)
        assertThat(js).doesNotContain("RTCPeerConnection")
        assertThat(js).doesNotContain("icecandidate")
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT)).isEmpty()
    }

    @Test
    fun `disabling WebRTC takes the peer connection away`() {
        // An engine built without WebRTC does not publish the constructor, so
        // the policy removes it rather than leaving an undefined value behind:
        // a feature test has to answer "no", not "yes, and the first new
        // will throw". Removing the property is checked here as the literal
        // the script performs.
        val js = PageAdjustments.scriptFor(null, WebRtcPolicy.DISABLED)
        assertThat(js).contains("remove(window, 'RTCPeerConnection')")
        assertThat(js).contains("delete target[prop]")
        assertThat(js).contains("'webkitRTCPeerConnection' in window")
        // Nothing here can answer a candidate or an SDP read, because there is
        // no connection left to produce either.
        assertThat(js).doesNotContain("typ host")
        assertThat(js).doesNotContain("icecandidate")
        assertThat(js).doesNotContain("__")
    }

    @Test
    fun `restricting the local IP covers both ways a candidate can escape`() {
        // The marker, and both places a candidate can escape: the event the
        // page's handler is called with, and the SDP it reads or hands back.
        // Which host candidates are withheld, and why, is pinned by the tests
        // that follow.
        val js = restricted()
        assertThat(js).contains("var HOST_MARKER = 'typ host';")
        assertThat(js).contains("onicecandidate")
        assertThat(js).contains("addEventListener")
        assertThat(js).contains("setLocalDescription")
        assertThat(js).contains("'localDescription'")
        assertThat(js).doesNotContain("__")
    }

    @Test
    fun `a host candidate is withheld only when its address is a literal IP`() {
        // The decision, pinned where it is written: the marker names the
        // candidates to consider and the address decides, so a host candidate
        // that carries no address is not a reason to drop anything.
        val js = restricted()
        assertThat(js).contains("function withholds(text) {")
        assertThat(js).contains("return isLiteralAddress(addressToken(text));")
        // The per-path judgement the shim used to have is gone, not merely
        // unused: what it asked was the candidate's own type, which reads
        // "host" for an addressless mDNS candidate too.
        assertThat(js).doesNotContain("isHostCandidate")
    }

    @Test
    fun `an mDNS host candidate survives the filter`() {
        // Chrome hands out "<uuid>.local" in the address position of a host
        // candidate before any page code sees it. There is no address in one,
        // so withholding it buys no privacy, and it is the candidate two peers
        // on the same network pair over when no STUN or TURN server answers —
        // the call that fails outright if it is dropped. The type is what the
        // old shim keyed on, and that is the assertion this pins. A name is
        // not four dot-separated labels, so the literal test is false for it.
        val js = restricted()
        assertThat(js).doesNotContain(".type === 'host'")
        assertThat(js).contains("if (labels.length !== 4) return false;")
    }

    @Test
    fun `an IPv4 literal host candidate is dropped`() {
        // The address this policy exists to withhold: four numeric labels,
        // each in range. That is what the predicate requires before it says
        // "literal", and it is what a dotted-quad address satisfies.
        val js = restricted()
        assertThat(js).contains("var labels = token.split('.');")
        assertThat(js).contains("if (code < 48 || code > 57) return false;")
        assertThat(js).contains("if (Number(label) > 255) return false;")
    }

    @Test
    fun `an IPv6 literal host candidate is dropped, compressed form included`() {
        // Every IPv6 spelling carries a colon — the compressed "2001:db8::1",
        // the full form, and the bracketed "[...]" form a zone can be attached
        // to — so one colon test covers all of them. A predicate that only
        // knew dotted-quad IPv4 would let every IPv6 literal through, which is
        // the failure this pins.
        val js = restricted()
        assertThat(js).contains("var zone = token.indexOf('%');")
        assertThat(js).contains("if (token.charAt(0) === '[') return true;")
        assertThat(js).contains("if (token.indexOf(':') !== -1) return true;")
    }

    @Test
    fun `srflx and relay candidates are never withheld`() {
        // Connectivity survives, asserted rather than described: a candidate
        // reaches the address test only through a "typ host" marker, and no
        // other candidate type is named anywhere in the script, so
        // server-reflexive and relay candidates cannot be dropped — and the
        // transport policy the page asked for is never overridden. Relay-only
        // with no TURN server configured would fail every call, which is not
        // this policy.
        val js = restricted()
        assertThat(js).contains("if (at === -1 || !endsMarker(text, at)) return false;")
        assertThat(js).doesNotContain("typ srflx")
        assertThat(js).doesNotContain("typ relay")
        assertThat(js).doesNotContain("iceTransportPolicy")
    }

    @Test
    fun `the event filter and the SDP stripper ask the same predicate`() {
        // One definition, reached from both paths: the event-level spelling
        // both listeners use, and the SDP stripper. A candidate removed from
        // one path but not the other still reaches the page, so a second copy
        // of the judgement is the bug this pins — the address test is called
        // from exactly one place, and a call site added or moved has to be
        // counted here deliberately.
        val js = restricted()
        assertThat(occurrences(js, "function withholds(")).isEqualTo(1)
        assertThat(occurrences(js, "withholdsEvent(")).isEqualTo(3)
        assertThat(occurrences(js, "isLiteralAddress(")).isEqualTo(2)
        assertThat(js).contains("if (withholdsEvent(event)) return undefined;")
        assertThat(js).contains("if (line.indexOf('a=candidate:') === 0 && withholds(line)) continue;")
    }

    @Test
    fun `a WebRTC policy applies with neither a device nor a screen`() {
        // The case the engine's early return has to keep: a profile that
        // presents no device and claims no screen still has a policy, and
        // dropping the script for those profiles alone would leave most
        // profiles with a setting that does nothing.
        val restrictedScript = PageAdjustments.scriptFor(null, WebRtcPolicy.RESTRICT_LOCAL_IP)
        assertThat(restrictedScript).isNotEmpty()
        assertThat(restrictedScript).doesNotContain("Screen.prototype")

        val disabled = PageAdjustments.scriptFor(null, WebRtcPolicy.DISABLED)
        assertThat(disabled).isNotEmpty()
        assertThat(disabled).doesNotContain("Screen.prototype")
    }

    @Test
    fun `a WebRTC policy combines with the screen claim`() {
        val js = PageAdjustments.scriptFor(
            ClaimedScreen(393, 852), WebRtcPolicy.RESTRICT_LOCAL_IP
        )
        assertThat(js).contains("var SCREEN_W = 393;")
        assertThat(js).contains("var HOST_MARKER = 'typ host';")
        assertThat(js).doesNotContain("__")
    }

    @Test
    fun `the two WebRTC policies produce different scripts`() {
        val disabled = PageAdjustments.scriptFor(null, WebRtcPolicy.DISABLED)
        val restrictedScript = PageAdjustments.scriptFor(null, WebRtcPolicy.RESTRICT_LOCAL_IP)
        assertThat(disabled).isNotEqualTo(restrictedScript)
        // Same policy, same script: a fingerprint that moved between loads
        // would be worse than one that is simply unusual.
        assertThat(restrictedScript)
            .isEqualTo(PageAdjustments.scriptFor(null, WebRtcPolicy.RESTRICT_LOCAL_IP))
    }

    @Test
    fun `a text scale of 1 contributes no script`() {
        // The untouched setting. 1.0 must add nothing, and nothing even to a
        // script that has other parts: the block is spliced in only when the
        // profile asked for a scale other than the default.
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 1f)).isEmpty()
        val js = PageAdjustments.scriptFor(ClaimedScreen(393, 852), WebRtcPolicy.DEFAULT, 1f)
        assertThat(js).doesNotContain("text-size-adjust")
    }

    @Test
    fun `a text scale of 1 point 5 asks for 150 percent`() {
        // The scale times 100, rounded, written as the percentage the property
        // takes. Gecko does not accept that form, so the script asks the engine
        // first and zooms the page when the answer is no -- a setting that did
        // nothing on one of the two editions is the bug being fixed here. The
        // root may be missing at document-start, so the value is set at once
        // when it exists and otherwise on the first mutation or
        // readystatechange, and the watch stops once it is set.
        val js = PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 1.5f)
        assertThat(js).contains("-webkit-text-size-adjust")
        assertThat(js).contains("var PERCENT = 150 + '%';")
        assertThat(js).contains("CSS.supports")
        assertThat(js).contains("getComputedStyle")
        assertThat(js).contains("removeProperty")
        assertThat(js).contains("root.style.zoom = SCALE")
        assertThat(js).contains("document.documentElement")
        assertThat(js).contains("MutationObserver")
        assertThat(js).contains("readystatechange")
        assertThat(js).contains("observer.disconnect()")
        assertThat(js).doesNotContain("__")
    }

    @Test
    fun `the text scale is clamped to the range the setting offers`() {
        // A stored value below the floor or above the ceiling lands on the
        // bound rather than rendering the page microscopic or unreadable.
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 0.1f))
            .contains("var PERCENT = 50 + '%';")
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 0.5f))
            .contains("var PERCENT = 50 + '%';")
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 2f))
            .contains("var PERCENT = 200 + '%';")
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 5f))
            .contains("var PERCENT = 200 + '%';")
    }

    @Test
    fun `a text scale of 1 leaves the script exactly as it was`() {
        // The parameter must be invisible at its default, for a screen claim
        // and for the empty case alike — and a scale that is not 1 must add
        // something, so the equality above is not vacuous.
        assertThat(PageAdjustments.scriptFor(ClaimedScreen(393, 852), WebRtcPolicy.DEFAULT, 1f))
            .isEqualTo(PageAdjustments.scriptFor(ClaimedScreen(393, 852)))
        assertThat(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 1f))
            .isEqualTo(PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT))
        assertThat(PageAdjustments.scriptFor(ClaimedScreen(393, 852), WebRtcPolicy.DEFAULT, 1.5f))
            .isNotEqualTo(PageAdjustments.scriptFor(ClaimedScreen(393, 852)))
    }

    @Test
    fun `no script block carries a dollar sign`() {
        // The blocks are spliced into Kotlin raw strings, where a "$" is read
        // as interpolation: the build would fail rather than the page, so the
        // rule is worth pinning for the blocks as they are and as they grow.
        val blocks = listOf(
            PageAdjustments.scriptFor(ClaimedScreen(393, 852), WebRtcPolicy.DEFAULT),
            PageAdjustments.scriptFor(null, WebRtcPolicy.DISABLED),
            PageAdjustments.scriptFor(null, WebRtcPolicy.RESTRICT_LOCAL_IP),
            PageAdjustments.scriptFor(ClaimedScreen(393, 852), WebRtcPolicy.RESTRICT_LOCAL_IP),
            PageAdjustments.scriptFor(null, WebRtcPolicy.DEFAULT, 1.5f)
        )
        for (js in blocks) {
            assertThat(js).doesNotContain("\$")
        }
    }
}
