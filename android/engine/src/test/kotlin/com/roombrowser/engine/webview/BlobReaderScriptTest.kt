package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The page-side reader, pinned as text.
 *
 * This is the one part of the blob download with no compiler between its two
 * ends: [BlobReaderScript] builds a JavaScript string that calls back into a
 * Kotlin object by name and by arity, and nothing but this test would notice if
 * the two drifted apart. A mismatch does not fail loudly — the read simply
 * never reports, and the user sees a download that did nothing.
 *
 * The contract pinned here is begin-then-chunks: one
 * `begin(id, size, name)` before anything moves, then the chunk pump — and the
 * payload itself is read as a Blob through windowed `slice` calls, never as
 * one whole ArrayBuffer in the page's heap.
 */
class BlobReaderScriptTest {

    private val url = "blob:https://example.com/5b1f6f0c-0e0e-4c1a-9a3e-2f6f9d0f8a11"

    @Test
    fun the_script_calls_the_interface_the_bridge_registers() {
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("window.${WebViewBlobDownloads.INTERFACE}.chunk")
    }

    @Test
    fun the_id_and_the_url_arrive_as_escaped_literals() {
        val script = BlobReaderScript.forUrl("id-1", url)
        assertThat(script).contains("var ID = \"id-1\";")
        assertThat(script).contains("var URL_ = \"$url\";")
    }

    @Test
    fun a_url_cannot_break_out_of_its_literal() {
        // The URL comes from a page's own anchor, so it is hostile input by
        // default: a quote would end the literal and the rest would run.
        val script = BlobReaderScript.forUrl("id", "blob:https://example.com/\"></script><script>")
        assertThat(script).doesNotContain("</script>")
        assertThat(script).doesNotContain("\">")
        assertThat(script).contains("\\u003c")
    }

    @Test
    fun the_literal_escaper_quotes_everything_javascript_would_end_on() {
        assertThat(BlobReaderScript.stringLiteral("a\"b\\c\nd"))
            .isEqualTo("\"a\\\"b\\\\c\\nd\"")
    }

    @Test
    fun an_empty_file_still_finishes_the_read() {
        // Without this call a zero-byte export would leave the native side
        // waiting for a final slice that never comes.
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("chunk(ID, seq, '', true, '', name)")
    }

    @Test
    fun a_failed_read_reports_itself_instead_of_hanging() {
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("ID, 0, '', true, String((e && e.message) || e)")
    }

    @Test
    fun the_reader_stops_when_the_native_side_says_so() {
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("if (keep) setTimeout(send, 0);")
    }

    @Test
    fun the_page_name_comes_from_the_download_attribute() {
        // The URL of a blob names a UUID, so the anchor is the only place the
        // page ever states a filename.
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("querySelectorAll('a[download]')")
    }

    @Test
    fun the_response_is_read_as_a_blob_not_one_whole_array_buffer() {
        // G-8: the old script answered the fetch with r.arrayBuffer(), which
        // materialised the entire payload in the page's heap before the first
        // byte moved and could OOM the renderer on a real export. The response
        // is read as a Blob, and only each window pays for its own ArrayBuffer.
        val script = BlobReaderScript.forUrl("id", url)
        assertThat(script).contains("return r.blob()")
        assertThat(script).doesNotContain("r.arrayBuffer()")
        assertThat(script).contains("slice.arrayBuffer()")
    }

    @Test
    fun the_read_is_windowed_through_blob_slices() {
        // One window at a time in page memory: the slice is bounded by SLICE
        // (256 KiB) at the front and the blob's own size at the back.
        val script = BlobReaderScript.forUrl("id", url)
        assertThat(script)
            .contains("var slice = blob.slice(start, Math.min(start + SLICE, blob.size))")
        assertThat(script).contains("var SLICE = ${256 * 1024};")
    }

    @Test
    fun the_read_is_announced_before_anything_is_transferred() {
        // The begin handshake is what lets the native side refuse an over-cap
        // file before a single byte crosses the bridge, so the begin call has
        // to precede the data pump in the generated script — measured against
        // the btoa call, which is the first thing that encodes payload bytes.
        val script = BlobReaderScript.forUrl("id", url)
        val beginAt = script.indexOf(".begin(ID, blob.size, name)")
        val dataAt = script.indexOf("btoa(bin)")
        assertThat(beginAt).isAtLeast(0)
        assertThat(dataAt).isAtLeast(0)
        assertThat(beginAt).isLessThan(dataAt)
    }

    @Test
    fun a_refused_begin_stops_the_read_without_transferring() {
        // A false from begin is a refusal: the script must return without
        // slicing, not fall through into the pump.
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("if (!iface.begin(ID, blob.size, name)) return;")
    }

    @Test
    fun a_missing_begin_half_is_reported_like_any_other_failure() {
        // Defensive, for a page world whose interface lacks begin: the read
        // fails through the normal error channel instead of hanging the native
        // side until its watchdog fires.
        assertThat(BlobReaderScript.forUrl("id", url))
            .contains("typeof iface.begin !== 'function'")
    }
}
