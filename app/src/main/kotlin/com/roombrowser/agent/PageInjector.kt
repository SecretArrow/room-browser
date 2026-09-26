package com.roombrowser.agent

/**
 * JavaScript injected into the live WebView by the agent's tool executor.
 *
 * Interaction model (same family as WebVoyager/agent-ui approaches, adapted
 * to Android WebView): every visible interactive element gets a sequential
 * `data-agent-ref` number; the model refers to elements by that number and
 * the click/fill/enter scripts resolve the number back to the element.
 *
 * Notes:
 *  - All scripts return plain values/objects; evaluateJavascript delivers
 *    them as JSON text.
 *  - Input filling uses the native value setter + dispatched input/change
 *    events so React/Vue controlled inputs pick the value up.
 *  - No JS template literals are used, so the scripts survive Kotlin string
 *    interpolation without escaping problems.
 */
object PageInjector {

    const val REF_ATTR = "data-agent-ref"

    /**
     * Builds the page snapshot: URL, title, viewport text, scroll position
     * and every visible interactive element tagged with a [ref] number.
     */
    fun snapshotJs(): String = """
        (function(){
          function visible(el){
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
          }
          var els = [];
          var nodes = document.querySelectorAll(
            'a, button, input, select, textarea, [role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="tab"], [role="menuitem"], [onclick], [contenteditable="true"]'
          );
          var n = 0;
          for (var i = 0; i < nodes.length && n < 160; i++) {
            var el = nodes[i];
            if (!visible(el)) continue;
            var ref = ++n;
            el.setAttribute('$REF_ATTR', String(ref));
            var tag = el.tagName.toLowerCase();
            var label = (el.innerText || el.getAttribute('aria-label') ||
              el.getAttribute('placeholder') || el.value || el.getAttribute('title') || el.getAttribute('alt') || '')
              .trim().replace(/\s+/g, ' ').slice(0, 80);
            var o = {
              ref: ref,
              tag: tag,
              label: label,
              viewport: el.getBoundingClientRect().top < window.innerHeight && el.getBoundingClientRect().bottom > 0
            };
            if (tag === 'a') o.href = el.getAttribute('href') || '';
            if (tag === 'input' || tag === 'select' || tag === 'textarea') {
              o.type = el.type || tag;
              if (el.type === 'checkbox' || el.type === 'radio') o.checked = el.checked;
            }
            if (el.disabled) o.disabled = true;
            els.push(o);
          }
          var text = (document.body ? document.body.innerText : '') || '';
          return {
            url: location.href,
            title: document.title || '',
            text: text.slice(0, 9000),
            scrollY: Math.round(window.scrollY || document.documentElement.scrollTop || 0),
            maxScrollY: Math.round(Math.max(document.documentElement.scrollHeight - window.innerHeight, 0)),
            elements: els
          };
        })()
    """.trimIndent()

    /** Clicks the element with the given ref number. */
    fun clickJs(ref: Int): String = """
        (function(){
          var el = document.querySelector('[$REF_ATTR="$ref"]');
          if (!el) return 'element [$ref] not found — call read_page again for fresh refs';
          try { el.scrollIntoView({block: 'center'}); } catch (e) {}
          el.click();
          var label = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '')
            .trim().slice(0, 60);
          return 'clicked [$ref] ' + (label || '<' + el.tagName.toLowerCase() + '>');
        })()
    """.trimIndent()

    /**
     * Types text into the element with the given ref. [jsonText] MUST be a
     * JSON-encoded string (produced by Json.encodeToString(String.serializer)).
     */
    fun fillJs(ref: Int, jsonText: String): String = """
        (function(){
          var el = document.querySelector('[$REF_ATTR="$ref"]');
          if (!el) return 'element [$ref] not found — call read_page again for fresh refs';
          if (el.disabled || el.readOnly) return 'element [$ref] is disabled or read-only';
          el.focus();
          var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
            : (el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype);
          var d = Object.getOwnPropertyDescriptor(proto, 'value');
          if (d && d.set) d.set.call(el, $jsonText); else el.value = $jsonText;
          el.dispatchEvent(new Event('input', {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          return 'filled [$ref] with the given text';
        })()
    """.trimIndent()

    /**
     * Presses Enter: focuses the given ref (optional), dispatches a keydown
     * and submits the closest form via requestSubmit.
     */
    fun enterJs(ref: Int?): String {
        val selectorPart = if (ref != null) "var el = document.querySelector('[$REF_ATTR=\"$ref\"]');" else "var el = null;"
        return """
            (function(){
              $selectorPart
              if (el) { try { el.scrollIntoView({block: 'center'}); } catch (e) {} el.focus(); }
              var target = el || document.activeElement || document.body;
              target.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true}));
              target.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true}));
              var form = el ? el.closest('form') : (document.activeElement ? document.activeElement.closest('form') : null);
              if (!form) form = document.querySelector('form');
              if (form) { try { if (form.requestSubmit) form.requestSubmit(); else if (form.submit) form.submit(); } catch (e) {} }
              return 'enter sent' + (form ? ' (form submit attempted)' : '');
            })()
        """.trimIndent()
    }

    /** Scrolls by [dy] pixels (positive = down). */
    fun scrollJs(dy: Int): String = """
        (function(){
          window.scrollBy(0, $dy);
          return 'scrolled; now at ' + Math.round(window.scrollY || 0) + ' of ' +
            Math.round(Math.max(document.documentElement.scrollHeight - window.innerHeight, 0));
        })()
    """.trimIndent()
}
