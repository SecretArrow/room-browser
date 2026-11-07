package com.roombrowser.browser.macro

/**
 * The one-shot page probe behind "pick on page".
 *
 * The macro recorder's probe ([MacroRecorderScripts]) installs listeners and
 * owns a session buffer; picking needs neither — the overlay consumes the
 * tap before it ever reaches the document, and the answer is one lookup:
 * what sits at these CSS-viewport coordinates. The selector ladder is the
 * SAME one the recorder uses at record time (id, then name, then a bounded
 * structural path), so a picked point plays back exactly like a recorded
 * one: selector first, the coordinates as the fallback.
 */
object MacroPickProbe {

    /** Reads the CSS viewport width so view pixels can be scaled to page pixels. */
    fun viewportWidthJs(): String =
        "(function () { try { return String((document.documentElement && document.documentElement.clientWidth) || 0); } catch (e) { return '0'; } })()"

    /**
     * What sits at CSS-viewport ([cssX], [cssY]) — a JSON object:
     * `{ ok, viewportWidth, selector, selectorKind, tag, text }`. Never
     * throws; `ok:false` with the viewport width is an honest answer.
     */
    fun probeJs(cssX: Int, cssY: Int): String = """
        (function () {
          try {
            var x = $cssX, y = $cssY;
            var vw = (document.documentElement && document.documentElement.clientWidth) || 0;
            var el = document.elementFromPoint(x, y);
            if (!el || el.nodeType !== 1) {
              return JSON.stringify({ ok: false, viewportWidth: vw });
            }
            var kind = 'NONE', selector = null;
            try {
              if (el.id) {
                kind = 'ID';
                selector = '#' + CSS.escape(el.id);
              } else {
                var named = el.getAttribute ? el.getAttribute('name') : null;
                if (named) {
                  kind = 'NAME';
                  selector = el.tagName.toLowerCase() + '[name="' + String(named).replace(/"/g, '\\"') + '"]';
                } else {
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
                  kind = 'STRUCTURAL';
                  selector = parts.join(' > ');
                }
              }
            } catch (se) {
              kind = 'NONE';
              selector = null;
            }
            var text = null;
            try {
              var t = String(el.value != null && el.value !== '' ? el.value : (el.textContent || el.innerText || '')).replace(/\s+/g, ' ').trim();
              text = t.length > 80 ? t.slice(0, 80) : (t || null);
            } catch (te) { text = null; }
            var tag = el.tagName ? el.tagName.toLowerCase() : null;
            return JSON.stringify({ ok: true, viewportWidth: vw, selector: selector, selectorKind: kind, tag: tag, text: text });
          } catch (e) {
            return JSON.stringify({ ok: false, viewportWidth: 0 });
          }
        })()
    """.trimIndent()
}
