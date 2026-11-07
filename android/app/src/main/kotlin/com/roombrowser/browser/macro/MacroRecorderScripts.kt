package com.roombrowser.browser.macro

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Macro Recorder's page-side probe.
 *
 * The same class of thing as the DevTools recorder's
 * (DeveloperToolsRecorderScripts): the app authors these and ships them. The
 * listeners are the whole mechanism — a click, a typed value, a scroll and a
 * navigation each become one step carrying the selector, the coordinates and
 * the moment it happened.
 *
 * What this probe records that the DevTools one does not:
 *  - coordinates + the element's own text (a CLICK can replay by selector
 *    with a coordinate fallback);
 *  - dblclick, long-press, drag (pointerdown/up with movement);
 *  - scroll deltas;
 *  - an extended secret mask: password fields AND fields whose name/id hints
 *    at a token/OTP/secret — their value never leaves the page.
 *
 * The steps live in the page's own `sessionStorage` under one key, so a
 * same-site full page load does not lose them. [MacroRecorderController]
 * re-evaluates [startJs] on every URL change while recording, which is what
 * reinstalls them, and pulls the buffer on a timer — so the native side owns
 * the merged step list, and a cross-origin navigation loses only the steps
 * of the origin left behind, which is exactly what an honest recorder can
 * keep.
 */
internal object MacroRecorderScripts {

    /** Steps the page keeps. The recorder enforces the same cap natively. */
    const val CAP = 500

    private const val KEY = "__rbMacroState"

    private val HELPERS = """
        var __rbKey = '$KEY';
        function __rbRead() {
          try {
            var raw = sessionStorage.getItem(__rbKey);
            if (!raw) return { dropped: 0, steps: [], paused: false };
            var parsed = JSON.parse(raw);
            if (!parsed || !parsed.steps) return { dropped: 0, steps: [], paused: false };
            return { dropped: parsed.dropped || 0, steps: parsed.steps || [], paused: !!parsed.paused };
          } catch (e) { return { dropped: 0, steps: [], paused: false }; }
        }
        function __rbWrite(state) {
          try { sessionStorage.setItem(__rbKey, JSON.stringify(state)); } catch (e) {}
        }
        function __rbPush(step) {
          var state = __rbRead();
          if (state.paused) return;
          step.timestampMs = Date.now();
          state.steps.push(step);
          if (state.steps.length > $CAP) {
            state.steps = state.steps.slice(state.steps.length - $CAP);
            state.dropped = (state.dropped || 0) + 1;
          }
          __rbWrite(state);
        }
        function __rbSecret(el, tag) {
          if (!el) return false;
          var type = String(el.type || '').toLowerCase();
          if (type === 'password') return true;
          var hay = String(el.id || '') + ' ' + String(el.name || '') + ' ' + String(el.autocomplete || '');
          return /pass|pwd|secret|token|otp|pin|ssn|cvv|cvc|cardnum|privatekey/i.test(hay);
        }
        function __rbNav(note) {
          __rbPush({ action: 'NAVIGATE', url: String(location.href), note: note });
        }
        function __rbUninstall() {
          var h = window.__rbMacroHandlers;
          if (!h) return;
          try {
            document.removeEventListener('pointerdown', h.down, true);
            document.removeEventListener('pointerup', h.up, true);
            document.removeEventListener('click', h.click, true);
            document.removeEventListener('dblclick', h.dblclick, true);
            document.removeEventListener('input', h.input, true);
            document.removeEventListener('change', h.change, true);
            window.removeEventListener('scroll', h.scroll, true);
            window.removeEventListener('popstate', h.popstate);
            window.removeEventListener('hashchange', h.hashchange);
            if (h.pushState) history.pushState = h.pushState;
            if (h.replaceState) history.replaceState = h.replaceState;
          } catch (e) {}
          window.__rbMacroHandlers = null;
        }
    """

    private val SELECTOR = """
        function __rbSelector(el) {
          try {
            if (!el || el.nodeType !== 1) return { kind: 'NONE', selector: null };
            if (el.id) return { kind: 'ID', selector: '#' + CSS.escape(el.id) };
            var named = el.getAttribute ? el.getAttribute('name') : null;
            if (named) {
              return {
                kind: 'NAME',
                selector: el.tagName.toLowerCase() + '[name="' + String(named).replace(/"/g, '\\"') + '"]'
              };
            }
            var parts = [];
            var node = el;
            var depth = 0;
            while (node && node.nodeType === 1 && depth < 6) {
              var tag = node.tagName.toLowerCase();
              var parent = node.parentElement;
              if (parent) {
                var index = 1;
                var sib = node;
                while ((sib = sib.previousElementSibling)) index++;
                parts.unshift(tag + ':nth-of-type(' + index + ')');
              } else {
                parts.unshift(tag);
              }
              node = parent;
              depth++;
            }
            return { kind: 'STRUCTURAL', selector: parts.join(' > ') };
          } catch (e) {
            return { kind: 'NONE', selector: null };
          }
        }
        function __rbText(el) {
          try {
            if (!el) return null;
            var t = String(el.value != null && el.value !== '' ? el.value : (el.textContent || el.innerText || '')).replace(/\\s+/g, ' ').trim();
            return t.length > 80 ? t.slice(0, 80) : (t || null);
          } catch (e) { return null; }
        }
        function __rbPoint(el, e) {
          try {
            var r = el && el.getBoundingClientRect ? el.getBoundingClientRect() : null;
            if (r && r.width > 0 && r.height > 0) {
              return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
            }
          } catch (err) {}
          return e ? { x: Math.round(e.clientX || 0), y: Math.round(e.clientY || 0) } : { x: 0, y: 0 };
        }
    """

    /** Installs the listeners and answers with the buffer. Idempotent for one document. */
    fun startJs(): String = """
        (function () {
          $HELPERS
          $SELECTOR
          if (window.__rbMacroHandlers) {
            var live = __rbRead();
            return JSON.stringify(live);
          }
          var handlers = {};
          var press = { t: 0, x: 0, y: 0, moved: false, sx: 0, sy: 0 };
          handlers.down = function (e) {
            press.t = Date.now();
            press.x = e.clientX || 0;
            press.y = e.clientY || 0;
            press.sx = press.x;
            press.sy = press.y;
            press.moved = false;
          };
          handlers.up = function (e) {
            var dt = Date.now() - press.t;
            var dx = (e.clientX || 0) - press.sx;
            var dy = (e.clientY || 0) - press.sy;
            if (press.t && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
              var dEl = document.elementFromPoint(e.clientX || 0, e.clientY || 0);
              var dSel = __rbSelector(dEl);
              __rbPush({
                action: 'DRAG',
                selectorKind: dSel.kind,
                selector: dSel.selector,
                tagName: dEl && dEl.tagName ? dEl.tagName.toLowerCase() : null,
                x: Math.round(press.sx), y: Math.round(press.sy),
                endX: Math.round(e.clientX || 0), endY: Math.round(e.clientY || 0)
              });
              press.t = 0;
              return;
            }
            if (press.t && dt >= 600) {
              var el = e.target;
              var sel = __rbSelector(el);
              var pt = __rbPoint(el, e);
              __rbPush({
                action: 'LONG_PRESS',
                selectorKind: sel.kind,
                selector: sel.selector,
                tagName: el && el.tagName ? el.tagName.toLowerCase() : null,
                text: __rbText(el),
                x: pt.x, y: pt.y
              });
              press.t = 0;
            }
          };
          handlers.click = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var sel = __rbSelector(el);
            var pt = __rbPoint(el, e);
            __rbPush({
              action: 'CLICK',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: el.tagName ? el.tagName.toLowerCase() : null,
              text: __rbText(el),
              x: pt.x, y: pt.y
            });
          };
          handlers.dblclick = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var sel = __rbSelector(el);
            var pt = __rbPoint(el, e);
            __rbPush({
              action: 'DBLCLICK',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: el.tagName ? el.tagName.toLowerCase() : null,
              text: __rbText(el),
              x: pt.x, y: pt.y
            });
          };
          handlers.input = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag !== 'input' && tag !== 'textarea') return;
            var type = String(el.type || 'text').toLowerCase();
            if (type === 'checkbox' || type === 'radio' || type === 'range' ||
                type === 'file' || type === 'submit' || type === 'button') return;
            var secret = __rbSecret(el, tag);
            var sel = __rbSelector(el);
            __rbPush({
              action: 'INPUT',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: tag,
              text: __rbText(el),
              value: secret ? null : String(el.value == null ? '' : el.value),
              note: secret ? 'value withheld: sensitive field' : null
            });
          };
          handlers.change = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag !== 'select') return;
            var secret = __rbSecret(el, tag);
            var sel = __rbSelector(el);
            __rbPush({
              action: 'CHANGE',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: tag,
              text: __rbText(el),
              value: secret ? null : String(el.value == null ? '' : el.value),
              note: secret ? 'value withheld: sensitive field' : null
            });
          };
          handlers.scroll = function () {
            try {
              __rbThrottledScroll();
            } catch (e) {}
          };
          var __rbScrollTimer = null;
          function __rbThrottledScroll() {
            if (__rbScrollTimer) return;
            __rbScrollTimer = setTimeout(function () {
              __rbScrollTimer = null;
              var sx = window.scrollX || 0, sy = window.scrollY || 0;
              if (!__rbLastScroll) { __rbLastScroll = { x: sx, y: sy }; return; }
              var dx = sx - __rbLastScroll.x, dy = sy - __rbLastScroll.y;
              __rbLastScroll = { x: sx, y: sy };
              if (Math.abs(dx) < 24 && Math.abs(dy) < 24) return;
              __rbPush({ action: 'SCROLL', dx: Math.round(dx), dy: Math.round(dy) });
            }, 400);
          }
          var __rbLastScroll = null;
          handlers.popstate = function () { __rbNav('history'); };
          handlers.hashchange = function () { __rbNav('hash'); };
          try {
            handlers.pushState = history.pushState;
            handlers.replaceState = history.replaceState;
            history.pushState = function () {
              var r = handlers.pushState.apply(this, arguments);
              __rbNav('history');
              return r;
            };
            history.replaceState = function () {
              var r = handlers.replaceState.apply(this, arguments);
              __rbNav('history');
              return r;
            };
          } catch (e) {}
          document.addEventListener('pointerdown', handlers.down, true);
          document.addEventListener('pointerup', handlers.up, true);
          document.addEventListener('click', handlers.click, true);
          document.addEventListener('dblclick', handlers.dblclick, true);
          document.addEventListener('input', handlers.input, true);
          document.addEventListener('change', handlers.change, true);
          window.addEventListener('scroll', handlers.scroll, true);
          window.addEventListener('popstate', handlers.popstate);
          window.addEventListener('hashchange', handlers.hashchange);
          window.__rbMacroHandlers = handlers;
          var state = __rbRead();
          var last = state.steps.length ? state.steps[state.steps.length - 1] : null;
          if (!last || last.action !== 'NAVIGATE' || last.url !== String(location.href)) {
            __rbNav('load');
          }
          state = __rbRead();
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /** Reads the buffer without touching the listeners. */
    fun pullJs(): String = """
        (function () {
          $HELPERS
          var state = __rbRead();
          state.paused = !!window.__rbMacroPaused;
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /**
     * Freezes/resumes capture WITHOUT dropping what was recorded: the pause
     * flag the push helper checks, plus the listeners stay warm so a resume
     * on the same document is instant.
     */
    fun pausedJs(paused: Boolean): String = """
        (function () {
          $HELPERS
          window.__rbMacroPaused = ${if (paused) "true" else "false"};
          var state = __rbRead();
          state.paused = ${if (paused) "true" else "false"};
          __rbWrite(state);
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /** Removes the listeners and KEEPS the steps — what a PAUSED-tab switch runs. */
    fun uninstallJs(): String = """
        (function () {
          $HELPERS
          __rbUninstall();
          var state = __rbRead();
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /** Removes the listeners and the buffer — what CANCEL runs. */
    fun stopJs(): String = """
        (function () {
          $HELPERS
          __rbUninstall();
          try { sessionStorage.removeItem(__rbKey); } catch (e) {}
          return JSON.stringify({ dropped: 0, steps: [], paused: false });
        })()
    """.trimIndent()

    /** Empties the buffer without stopping a running recording. */
    fun clearJs(): String = """
        (function () {
          $HELPERS
          try { sessionStorage.removeItem(__rbKey); } catch (e) {}
          return JSON.stringify({ dropped: 0, steps: [], paused: false });
        })()
    """.trimIndent()
}

/** One recorded step, exactly as the page wrote it. Every field is nullable: absent, never invented. */
@Serializable
internal data class MacroRecorderStep(
    val action: String = "UNKNOWN",
    val selectorKind: String = "NONE",
    val selector: String? = null,
    val tagName: String? = null,
    val text: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val dx: Float? = null,
    val dy: Float? = null,
    val value: String? = null,
    val url: String? = null,
    val timestampMs: Long = 0L,
    val note: String? = null
)

/** What one read of the page buffer returned. */
@Serializable
internal data class MacroRecorderSnapshot(
    val dropped: Int = 0,
    val steps: List<MacroRecorderStep> = emptyList(),
    val paused: Boolean = false
)

internal object MacroRecorderCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeSnapshot(payload: String?): MacroRecorderSnapshot {
        // evaluateJs answers a JSON-ENCODED string: the probe's own
        // JSON.stringify wrapped in quotes. Unwrap it, then decode.
        val raw = payload?.let {
            runCatching { json.decodeFromString<String>(it) }.getOrDefault(it)
        } ?: return MacroRecorderSnapshot()
        return runCatching { json.decodeFromString<MacroRecorderSnapshot>(raw) }
            .getOrDefault(MacroRecorderSnapshot())
    }

    /**
     * The page script's own answer, unquoted: a probe that answered the
     * string `ok` arrives here as `"ok"` (quotes included). Unknown or null
     * payloads answer null.
     */
    fun jsonStringOf(payload: String?): String? = payload?.let {
        runCatching { json.decodeFromString<String>(it) }.getOrDefault(it)
    }
}
