package com.roombrowser.browser.engine

import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.WebRtcPolicy
import kotlin.math.roundToInt

/**
 * The page adjustments a profile can ask for — a claimed screen size, a
 * WebRTC policy, a text scale — as one document-start script. Each part is
 * installed only when the profile has actually asked for it, and a profile
 * that asked for none of them gets an empty string, which the engine reads as
 * "install nothing".
 *
 * THE IDENTITY IS NOT HERE, ON PURPOSE. The object that used to live beside
 * these parts (`DeviceShim`) also presented a device identity in JavaScript —
 * `navigator.userAgent`, the brand list, `userAgentData`, memory, cores and
 * the WebGL strings, all installed with `Object.defineProperty`. That is the
 * one presentation a page can catch from inside: a spoofed accessor has a
 * `.get` function where the platform ships a native one, `getHighEntropyValues`
 * answers on a microtask where the engine answers from its own metadata, and
 * every such wrinkle is a stronger automation signal than the identity it was
 * trying to manage — the exact class of tell that reads as "This browser or
 * app may not be secure". The identity work now happens where it cannot be
 * caught from the page: the User-Agent string is set on the engine, the
 * `Sec-CH-UA*` metadata is derived from that same string
 * (`WebViewClientHints`), and the engine serves `navigator.userAgent` and
 * `navigator.userAgentData` natively from those two. Nothing in the page world
 * is overridden, so nothing in the page world can contradict the wire.
 *
 * The three adjustments that remain are presentation and transport policy, not
 * identity claims: a screen size the profile states by hand, the WebRTC
 * behaviour the profile chose, and a text scale for the reader. None of them
 * says who the browser is, and none of them can contradict a request header.
 *
 * The policy is a parameter with a default rather than something read from a
 * profile, because this object holds no profile state: callers pass what the
 * profile asked for. The default is DEFAULT, which adds nothing, so a caller
 * that passes nothing gets exactly nothing.
 */
object PageAdjustments {

    /**
     * The document-start script for the adjustments a profile asked for.
     * Blank when there is no screen claim, the WebRTC policy is
     * [WebRtcPolicy.DEFAULT] and the text scale is 1.0 — nothing asked for is
     * changed, and nothing absent is installed.
     */
    fun scriptFor(
        screen: ClaimedScreen? = null,
        webRtc: WebRtcPolicy = WebRtcPolicy.DEFAULT,
        textScale: Float = 1f
    ): String = buildString {
        if (screen != null) append(screenScript(screen))
        append(webRtcScript(webRtc))
        append(textScaleScript(textScale))
    }

    /** The screen shim: what a page is told the display is. */
    private fun screenScript(screen: ClaimedScreen): String = SCREEN
        .replace("__SCREEN_W__", screen.widthPx.toString())
        .replace("__SCREEN_H__", screen.heightPx.toString())
        .replace(
            "__SCREEN_TYPE__",
            jsString(if (screen.isLandscape) "landscape-primary" else "portrait-primary")
        )
        .replace("__SCREEN_ANGLE__", if (screen.isLandscape) "90" else "0")

    /**
     * The text-size shim for a profile whose text scale is not 1.0, and nothing
     * at all for one whose scale is. The percentage is clamped to 50..200, the
     * range the setting offers, so a corrupt stored value cannot render a page
     * unreadable.
     */
    private fun textScaleScript(textScale: Float): String {
        if (textScale == 1f) return ""
        val percent = (textScale * 100).roundToInt().coerceIn(50, 200)
        return TEXT_SCALE.replace("__PERCENT__", percent.toString())
    }

    /**
     * The WebRTC shim for a profile whose policy is not DEFAULT, and nothing
     * at all for one whose policy is.
     *
     * Neither script is a substitute for a TURN server, and neither touches
     * the camera/microphone permission prompts the engine already shows:
     *
     *  - [WebRtcPolicy.DISABLED] removes `RTCPeerConnection` and the
     *    `webkitRTCPeerConnection` alias from the window, so
     *    `'RTCPeerConnection' in window` is false and code that feature-detects
     *    correctly concludes WebRTC is unavailable. That is what the engine
     *    itself looks like with WebRTC turned off, and removing the property
     *    (rather than defining it as undefined) is what that state actually
     *    is: a property that is present with an undefined value fails an
     *    own-property check and then throws at the first `new`, which turns a
     *    clean feature test into a crash inside library setup code.
     *
     *  - [WebRtcPolicy.RESTRICT_LOCAL_IP] leaves peer connections working and
     *    keeps the host candidates that carry a literal IP address out of what
     *    the page can read, so the far end does not learn the address this
     *    device holds on its own network. An mDNS host candidate — the
     *    `<uuid>.local` form a modern engine hands out instead of a literal —
     *    is left alone: there is no address in it, and it is the candidate two
     *    peers on the same network connect over when no STUN or TURN server is
     *    reachable, so withholding it would buy nothing and cost the call.
     *    Candidates are filtered on their way to the page's `onicecandidate`
     *    handler and to `addEventListener('icecandidate')` listeners, and
     *    stripped from the local SDP the page reads or hands back.
     *    Server-reflexive and relay candidates are untouched, and no
     *    relay-only transport policy is forced — with no TURN server
     *    configured, relay-only would make every call fail, which is not what
     *    "restrict local IP exposure" means.
     */
    private fun webRtcScript(policy: WebRtcPolicy): String = when (policy) {
        WebRtcPolicy.DEFAULT -> ""
        WebRtcPolicy.DISABLED -> WEBRTC_DISABLED
        WebRtcPolicy.RESTRICT_LOCAL_IP -> WEBRTC_RESTRICT
    }

    /** A JS string literal, so a claimed value can never break out of the script. */
    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
        return "'$escaped'"
    }

    // Installed only for a profile that set a screen size by hand, so the
    // numbers below are always the claimed ones and never a "real" default.
    private val SCREEN = """
(function () {
  'use strict';
  try {
    var SCREEN_W = __SCREEN_W__;
    var SCREEN_H = __SCREEN_H__;
    var TYPE = __SCREEN_TYPE__;
    var ANGLE = __SCREEN_ANGLE__;

    function define(target, prop, value) {
      try {
        Object.defineProperty(target, prop, {
          get: function () { return value; },
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    // What the page is told the screen is. Chrome on Android reports the whole
    // display as the available rectangle too — there is no persistent chrome to
    // subtract — so both pairs carry the same numbers rather than inventing a
    // difference a real handset does not have.
    define(Screen.prototype, 'width', SCREEN_W);
    define(Screen.prototype, 'height', SCREEN_H);
    define(Screen.prototype, 'availWidth', SCREEN_W);
    define(Screen.prototype, 'availHeight', SCREEN_H);

    // The layout viewport and the pixel ratio are NOT touched here, on purpose.
    // The viewport is the page's real width and height on this display and the
    // ratio is what the compositor actually renders at; overriding either would
    // re-lay the page out at a size the screen does not have, which is the
    // breakage this file exists to avoid. The consequence is real and is stated
    // in settings and in SECURITY.md: a claimed screen that differs from the
    // phone's is a disagreement with the viewport, and a script can find it.
    // The unit test pins those names out of this script so the trade cannot be
    // undone by a later edit without the test saying so.

    // Orientation follows the shape that was claimed, not the hinge. A profile
    // claiming a landscape screen must not also answer "portrait-primary" —
    // that pairing is the contradiction this shim exists to remove.
    try {
      if (typeof ScreenOrientation !== 'undefined' && ScreenOrientation.prototype) {
        define(ScreenOrientation.prototype, 'type', TYPE);
        define(ScreenOrientation.prototype, 'angle', ANGLE);
      }
    } catch (e) {}
    try {
      // The pre-standard spelling, still present in Chromium. Guarded because
      // it is on its way out and a missing property is not an error: 0 is
      // portrait, 90 is landscape, which is what the legacy value always was.
      if ('orientation' in window) {
        Object.defineProperty(window, 'orientation', {
          get: function () { return ANGLE; },
          configurable: true,
          enumerable: true
        });
      }
    } catch (e) {}
  } catch (e) {
    // A page must still load even if the platform refuses one of these.
  }
})();
"""

    // Installed only for a profile whose text scale is not 1.0.
    //
    // `-webkit-text-size-adjust` with a percentage is the right primitive -- it
    // scales text without scaling the layout -- but Gecko does not accept the
    // percentage form (it only knows `auto` and `none`), so on an engine that
    // refuses it the whole page is zoomed instead. That fallback scales the
    // layout too, which is why it is the fallback: a setting that does nothing
    // on one of the two editions is the bug this whole shim exists to avoid.
    private val TEXT_SCALE = """
(function () {
  'use strict';
  try {
    var PERCENT = __PERCENT__ + '%';
    var SCALE = __PERCENT__ / 100;

    var percentWorks = false;
    try {
      percentWorks = !!(window.CSS && CSS.supports &&
        CSS.supports('-webkit-text-size-adjust', '150%'));
    } catch (e) {}

    function apply() {
      var root = document.documentElement;
      if (!root || !root.style) return false;
      if (percentWorks) {
        root.style.setProperty('-webkit-text-size-adjust', PERCENT);
        // A parser that accepts the value is not a promise that the engine uses
        // it, so it is read back. Only a real percentage counts as applied; an
        // initial `auto` or an empty answer means the declaration was dropped
        // and the page would otherwise be left exactly as it was.
        var kept = '';
        try {
          kept = getComputedStyle(root).getPropertyValue('-webkit-text-size-adjust');
        } catch (e) {}
        if (kept && kept !== 'auto' && kept !== 'none') return true;
        root.style.removeProperty('-webkit-text-size-adjust');
      }
      root.style.zoom = SCALE;
      return true;
    }

    // The root element is present at document-start on Chromium but is not
    // guaranteed on every engine, so a missing one is waited for rather than
    // assumed away: a page must not be left unscaled. The watch ends the moment
    // the value is set — or when the document finishes loading, since a root
    // that has not appeared by then never will — so nothing observes the page
    // afterwards and the property is never written twice.
    var observer = null;
    var done = false;
    function stop() {
      if (done) return;
      done = true;
      try { if (observer) observer.disconnect(); } catch (e) {}
      try { document.removeEventListener('readystatechange', attempt); } catch (e) {}
    }
    function attempt() {
      if (done) return;
      if (apply() || document.readyState === 'complete') stop();
    }

    if (apply()) return;
    if (typeof MutationObserver === 'function') {
      try {
        observer = new MutationObserver(attempt);
        observer.observe(document, { childList: true, subtree: true });
      } catch (e) {}
    }
    try { document.addEventListener('readystatechange', attempt); } catch (e) {}
    attempt();
  } catch (e) {
    // A page must still load even if the platform refuses this.
  }
})();
"""

    // Installed only for a profile whose policy is Disabled. An engine built
    // without WebRTC does not have the constructor at all, and that — not a
    // property whose value is undefined — is the state this reproduces.
    private val WEBRTC_DISABLED = """
(function () {
  'use strict';
  try {
    function remove(target, prop) {
      try {
        if (delete target[prop]) return;
      } catch (e) {}
      try {
        Object.defineProperty(target, prop, {
          value: undefined,
          configurable: true,
          writable: true,
          enumerable: false
        });
      } catch (e) {}
    }
    remove(window, 'RTCPeerConnection');
    // The pre-standard alias, present in some engines only: removed where it
    // exists, never created where it never did.
    if ('webkitRTCPeerConnection' in window) remove(window, 'webkitRTCPeerConnection');
  } catch (e) {
    // A page must still load even if the platform refuses this.
  }
})();
"""

    // Installed only for a profile whose policy is Restrict local IP exposure.
    //
    // The rule, in one place in the script below: a "typ host" candidate whose
    // address is a literal IP is an address this device holds on its own
    // network, and the page does not get to see it. A "typ host" candidate
    // whose address is an mDNS name is not that — there is no address in
    // ".local" for the far end to learn, and it is the candidate two peers on
    // the same network pair over when no STUN or TURN server is reachable, so
    // withholding it would buy nothing and cost the call. Server-reflexive and
    // relay candidates are addresses the far end already reaches this device
    // at, so they stay, and the transport policy the page asked for is left
    // exactly as it was — forcing relay-only with no TURN server configured
    // would make every call fail, which is not what this policy says.
    //
    // Where a host candidate can escape, and what is done about it:
    //   1. the icecandidate event, whether the page used the onicecandidate
    //      property or addEventListener — the event is dropped, so no
    //      scrubbed copy has to be forged;
    //   2. the local SDP the page reads (localDescription and its current and
    //      pending forms) or submits (setLocalDescription), and the SDP
    //      createOffer/createAnswer resolve with.
    // Both paths ask the same predicate, so a candidate removed from one
    // cannot escape through the other and the judgement cannot drift.
    //
    // Honest limits, stated rather than implied:
    //  - getStats() is not wrapped, so a page reading ICE candidate statistics
    //    still sees whatever the engine reports there — a local candidate pair
    //    statistic carries the address this filter removes from the event and
    //    the SDP.
    //  - a Web Worker is a realm this document-start script does not reach; a
    //    page that opens one gets the engine's own RTCPeerConnection.
    //  - an engine that spells a candidate in a shape this parser does not
    //    recognise — no "candidate:" text, or an address in an unexpected
    //    position — has that candidate kept rather than dropped: nothing is
    //    parsed, so nothing is claimed about it. The Chrome and Firefox
    //    spellings are the ones covered.
    private val WEBRTC_RESTRICT = """
(function () {
  'use strict';
  try {
    var Native = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (typeof Native !== 'function' || !Native.prototype) return;
    // Reflect.construct is how the wrapper builds a connection whose
    // prototype is still the engine's, which a page that subclasses the
    // constructor needs. On an engine without it the shim installs nothing at
    // all rather than leaving a constructor that throws on every call.
    if (typeof Reflect !== 'object' || typeof Reflect.construct !== 'function') return;
    // Which names the engine actually published, so the shim never creates an
    // alias that was not there: a page feature-detecting the legacy spelling
    // must keep getting the answer the engine gave.
    var hadStandard = !!window.RTCPeerConnection;
    var hadLegacy = !!window.webkitRTCPeerConnection;
    var PROTO = Native.prototype;
    var HOST_MARKER = 'typ host';
    var CANDIDATE_MARKER = 'candidate:';

    function endsMarker(text, at) {
      var next = text.charAt(at + HOST_MARKER.length);
      return next === '' || next === ' ';
    }

    // The address of a candidate sits fifth after "candidate:" — foundation,
    // component, transport, priority, address — and both shapes carry that
    // same text: the candidate object's own .candidate string, and the
    // "a=candidate:..." line in an SDP. That is why one reader serves both.
    // Empty tokens are skipped so a doubled space shifts nothing.
    function addressToken(text) {
      var at = text.indexOf(CANDIDATE_MARKER);
      if (at === -1) return '';
      var raw = text.slice(at + CANDIDATE_MARKER.length).split(' ');
      var tokens = [];
      for (var i = 0; i < raw.length; i++) {
        if (raw[i] !== '') tokens.push(raw[i]);
      }
      return tokens.length > 4 ? tokens[4] : '';
    }

    // Whether an address token is a literal IP, which is the thing this policy
    // withholds. An mDNS name ("<uuid>.local") is not one: it carries no
    // address at all, and it is the candidate that connects two peers on the
    // same network when nothing else can. Every IPv6 spelling has a colon in
    // it — the compressed "2001:db8::1", the full form, and the bracketed
    // "[...]" form with a zone on the end — so one colon test covers them all,
    // and a dotted quad is four numeric labels in range.
    function isLiteralAddress(token) {
      if (!token) return false;
      var zone = token.indexOf('%');
      if (zone !== -1) token = token.slice(0, zone);
      if (token.charAt(0) === '[') return true;
      if (token.indexOf(':') !== -1) return true;
      var labels = token.split('.');
      if (labels.length !== 4) return false;
      for (var i = 0; i < 4; i++) {
        var label = labels[i];
        if (label.length < 1 || label.length > 3) return false;
        for (var j = 0; j < label.length; j++) {
          var code = label.charCodeAt(j);
          if (code < 48 || code > 57) return false;
        }
        if (Number(label) > 255) return false;
      }
      return true;
    }

    // The one judgement both paths make, and the only place either asks
    // whether a candidate is withheld: a "typ host" marker whose address is a
    // literal IP. The candidate object's own .type is deliberately not
    // consulted — it reads "host" for an mDNS candidate too, and that is
    // exactly the candidate that has to survive.
    function withholds(text) {
      if (typeof text !== 'string' || text === '') return false;
      var at = text.indexOf(HOST_MARKER);
      if (at === -1 || !endsMarker(text, at)) return false;
      return isLiteralAddress(addressToken(text));
    }

    // The text an event carries, or "" when it carries none: an
    // end-of-candidates event has a null candidate, and the page still has to
    // be told that gathering finished or a caller may wait forever.
    function candidateText(candidate) {
      if (!candidate) return '';
      try {
        return typeof candidate.candidate === 'string' ? candidate.candidate : '';
      } catch (e) {
        return '';
      }
    }

    // The event-level spelling of that same judgement, so both listeners ask
    // one expression rather than each building its own.
    function withholdsEvent(event) {
      return withholds(candidateText(event && event.candidate));
    }

    // A candidate line looks like:
    //   a=candidate:1 1 UDP 2122252543 192.168.1.5 54321 typ host
    // Only the "a=candidate:" lines are considered, so nothing else in the
    // SDP that happens to contain those words can be removed by accident.
    function scrubSdp(sdp) {
      if (typeof sdp !== 'string' || sdp.indexOf(HOST_MARKER) === -1) return sdp;
      var eol = sdp.indexOf('\r\n') === -1 ? '\n' : '\r\n';
      var lines = sdp.split(eol);
      var kept = [];
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf('a=candidate:') === 0 && withholds(line)) continue;
        kept.push(line);
      }
      return kept.join(eol);
    }

    // The page gets a description of the same type and prototype where the
    // engine still offers RTCSessionDescription, and a plain object shaped
    // like one where it does not.
    function copyDescription(description, sdp) {
      if (!description || description.sdp === sdp) return description;
      if (typeof RTCSessionDescription === 'function') {
        try { return new RTCSessionDescription({ type: description.type, sdp: sdp }); } catch (e) {}
      }
      return { type: description.type, sdp: sdp };
    }

    function scrubDescription(description) {
      if (!description || typeof description.sdp !== 'string') return description;
      return copyDescription(description, scrubSdp(description.sdp));
    }

    function defineValue(target, prop, fn) {
      try {
        Object.defineProperty(target, prop, {
          value: fn, configurable: true, writable: true, enumerable: false
        });
        return true;
      } catch (e) {
        return false;
      }
    }

    // (2) The SDP path.
    var setLocal = PROTO.setLocalDescription;
    if (typeof setLocal === 'function') {
      defineValue(PROTO, 'setLocalDescription', function (description) {
        var args = Array.prototype.slice.call(arguments);
        if (args.length > 0) args[0] = scrubDescription(args[0]);
        return setLocal.apply(this, args);
      });
    }

    function wrapOffer(name) {
      var native = PROTO[name];
      if (typeof native !== 'function') return;
      defineValue(PROTO, name, function () {
        var args = Array.prototype.slice.call(arguments);
        var self = this;
        if (typeof args[0] === 'function') {
          // The legacy success/failure callback form, which must keep
          // working: the page's callback is wrapped, not dropped.
          var success = args[0];
          var failure = (typeof args[1] === 'function') ? args[1] : null;
          return native.call(this, function (description) {
            return success.call(self, scrubDescription(description));
          }, failure, args[2]);
        }
        var result = native.apply(this, args);
        if (result && typeof result.then === 'function') {
          return result.then(function (description) {
            return scrubDescription(description);
          });
        }
        return result;
      });
    }
    wrapOffer('createOffer');
    wrapOffer('createAnswer');

    var DESCRIPTIONS = ['localDescription', 'currentLocalDescription', 'pendingLocalDescription'];
    for (var d = 0; d < DESCRIPTIONS.length; d++) {
      (function (prop) {
        var existing = null;
        try { existing = Object.getOwnPropertyDescriptor(PROTO, prop); } catch (e) {}
        if (!existing || typeof existing.get !== 'function') return;
        try {
          Object.defineProperty(PROTO, prop, {
            get: function () { return scrubDescription(existing.get.call(this)); },
            configurable: true,
            enumerable: existing.enumerable
          });
        } catch (e) {}
      })(DESCRIPTIONS[d]);
    }

    // (1) The icecandidate event. Wrapping the constructor gives every
    // connection its own filter and keeps the page's handler in a closure
    // rather than on the object, so nothing new is there to enumerate.
    var eventHandlerSet = null;
    try {
      var onIce = Object.getOwnPropertyDescriptor(PROTO, 'onicecandidate');
      if (onIce && typeof onIce.set === 'function') eventHandlerSet = onIce.set;
    } catch (e) {}

    function wrapConnection(connection) {
      var pageHandler = null;

      function delivered(event) {
        if (withholdsEvent(event)) return undefined;
        var handler = pageHandler;
        if (!handler) return undefined;
        if (typeof handler === 'function') return handler.call(connection, event);
        if (typeof handler.handleEvent === 'function') return handler.handleEvent(event);
        return undefined;
      }

      if (eventHandlerSet) {
        try {
          Object.defineProperty(connection, 'onicecandidate', {
            configurable: true,
            enumerable: true,
            get: function () { return pageHandler; },
            set: function (handler) {
              var usable = (typeof handler === 'function' || (handler !== null && typeof handler === 'object'));
              pageHandler = usable ? handler : null;
              eventHandlerSet.call(connection, pageHandler ? delivered : null);
            }
          });
        } catch (e) {}
      }

      // The same filter for the addEventListener spelling. The wrapper is
      // remembered per listener so removeEventListener with the page's own
      // function still detaches it.
      var registered = [];
      function wrapperFor(listener) {
        for (var i = 0; i < registered.length; i++) {
          if (registered[i].listener === listener) return registered[i].wrapper;
        }
        return null;
      }
      // The DOM accepts a function or an object with handleEvent, and both
      // have to be wrapped — and later found again — by the same rule.
      function isListener(value) {
        if (typeof value === 'function') return true;
        return typeof value === 'object' && value !== null && typeof value.handleEvent === 'function';
      }
      function invokes(listener, event) {
        if (typeof listener === 'function') return listener.call(connection, event);
        if (listener && typeof listener.handleEvent === 'function') return listener.handleEvent(event);
        return undefined;
      }
      defineValue(connection, 'addEventListener', function (type, listener, options) {
        if (type !== 'icecandidate' || !isListener(listener)) {
          return EventTarget.prototype.addEventListener.call(connection, type, listener, options);
        }
        var wrapper = wrapperFor(listener);
        if (!wrapper) {
          wrapper = function (event) {
            if (withholdsEvent(event)) return undefined;
            return invokes(listener, event);
          };
          registered.push({ listener: listener, wrapper: wrapper });
        }
        return EventTarget.prototype.addEventListener.call(connection, type, wrapper, options);
      });
      defineValue(connection, 'removeEventListener', function (type, listener, options) {
        var wrapper = (type === 'icecandidate' && isListener(listener)) ? wrapperFor(listener) : null;
        return EventTarget.prototype.removeEventListener.call(connection, type, wrapper || listener, options);
      });
    }

    function ShimConnection() {
      if (!new.target) {
        throw new TypeError("Failed to construct 'RTCPeerConnection': Please use the 'new' operator.");
      }
      var connection = Reflect.construct(Native, Array.prototype.slice.call(arguments), new.target);
      wrapConnection(connection);
      return connection;
    }
    // Same prototype object, so instanceof and a page that subclasses this
    // both keep working; the identity of the function is the only change.
    ShimConnection.prototype = PROTO;
    try { Object.defineProperty(ShimConnection, 'name', { value: 'RTCPeerConnection', configurable: true }); } catch (e) {}
    try { ShimConnection.toString = function () { return Native.toString(); }; } catch (e) {}
    // A connection's own constructor property points back at the shim, the
    // way it points at the engine's constructor without one.
    try {
      Object.defineProperty(PROTO, 'constructor', {
        value: ShimConnection, configurable: true, writable: true, enumerable: false
      });
    } catch (e) {}

    function install(prop) {
      var enumerable = false;
      try {
        var current = Object.getOwnPropertyDescriptor(window, prop);
        if (current) enumerable = current.enumerable;
      } catch (e) {}
      try {
        Object.defineProperty(window, prop, {
          value: ShimConnection, configurable: true, writable: true, enumerable: enumerable
        });
      } catch (e) {}
    }
    if (hadStandard) install('RTCPeerConnection');
    if (hadLegacy) install('webkitRTCPeerConnection');
  } catch (e) {
    // A page must still load, and a call must still connect, even if the
    // platform refuses one of these.
  }
})();
"""
}
