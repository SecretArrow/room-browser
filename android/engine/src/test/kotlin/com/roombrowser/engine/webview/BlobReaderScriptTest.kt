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
}
