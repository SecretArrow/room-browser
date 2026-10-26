package com.roombrowser.engine.webview

/**
 * The page-side half of a `blob:` / `data:` download read, as text.
 *
 * Kept apart from [WebViewBlobDownloads] because it is the one part of that
 * class with no Android dependency and the one part with no compiler between
 * its two ends: the Kotlin that builds this string and the JavaScript that
 * runs it only agree by inspection, so the agreement is pinned in a unit test
 * rather than left to whoever edits the string next.
 *
 * WHAT THE SCRIPT PROMISES. It calls
 * `window.<interface>.chunk(id, seq, base64, last, error, name)` once per
 * slice, in ascending `seq` from 0, ending with `last = true` — either on the
 * empty final call or on the error call. It stops slicing as soon as the
 * native side returns false, which is how a refused or abandoned read avoids
 * base64-ing a file nobody will take.
 */
internal object BlobReaderScript {

    /** Slice size, small enough that one base64 string stays cheap. */
    private const val SLICE_BYTES = 256 * 1024

    /**
     * `fetch` rather than XHR: it resolves a `blob:` and a `data:` URL alike,
     * and it is the same origin as the document, which is what makes the blob
     * addressable at all.
     *
     * The filename comes from the anchor's `download` attribute, which is the
     * only place a page ever states one for a blob — the URL itself names a
     * UUID.
     */
    fun forUrl(id: String, url: String): String = """
        (function () {
          var ID = ${stringLiteral(id)};
          var URL_ = ${stringLiteral(url)};
          var SLICE = $SLICE_BYTES;
          function hint() {
            try {
              var links = document.querySelectorAll('a[download]');
              for (var i = 0; i < links.length; i++) {
                if (links[i].href === URL_) return links[i].getAttribute('download') || '';
              }
            } catch (e) {}
            return '';
          }
          var name = hint();
          fetch(URL_).then(function (r) { return r.arrayBuffer(); }).then(function (buf) {
            var bytes = new Uint8Array(buf);
            var seq = 0;
            function send() {
              var start = seq * SLICE;
              if (start >= bytes.length) {
                window.${WebViewBlobDownloads.INTERFACE}.chunk(ID, seq, '', true, '', name);
                return;
              }
              var slice = bytes.subarray(start, Math.min(start + SLICE, bytes.length));
              var bin = '';
              for (var i = 0; i < slice.length; i += 0x8000) {
                bin += String.fromCharCode.apply(null, slice.subarray(i, i + 0x8000));
              }
              var keep = window.${WebViewBlobDownloads.INTERFACE}.chunk(
                ID, seq, btoa(bin), false, '', name
              );
              seq++;
              if (keep) setTimeout(send, 0);
            }
            send();
          }).catch(function (e) {
            window.${WebViewBlobDownloads.INTERFACE}.chunk(
              ID, 0, '', true, String((e && e.message) || e), name
            );
          });
        })();
    """.trimIndent()

    /**
     * A Kotlin string as a JavaScript literal, quotes and newlines escaped.
     *
     * The angle-bracket and ampersand escapes are not about JavaScript at all:
     * this text is injected into a document, and `</script>` or `<!--` inside a
     * string literal is enough to end the block early in some embeddings.
     */
    fun stringLiteral(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                else -> append(c)
            }
        }
        append('"')
    }
}
