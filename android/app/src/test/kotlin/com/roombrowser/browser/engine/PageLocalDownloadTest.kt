package com.roombrowser.browser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which downloads the HTTP engine may fetch, and what it may call them.
 *
 * A `blob:` URL names an object in the renderer's memory and a `data:` URL
 * carries its bytes inline; neither has an address an OkHttp request can be
 * sent to. Before this rule existed the download engine was handed both, and
 * the row died instantly on the scheme — "Expected URL scheme 'http' or
 * 'https' but was 'blob'" — while the file the page had already built was never
 * written. The classification and the filename rule that depends on it are pure
 * functions, so they are held here.
 */
class PageLocalDownloadTest {

    @Test
    fun a_blob_url_and_a_data_url_are_page_local() {
        assertThat(DownloadEngine.isPageLocal("blob:https://example.com/abc")).isTrue()
        assertThat(DownloadEngine.isPageLocal("data:text/plain;base64,aGk=")).isTrue()
        assertThat(DownloadEngine.isPageLocal("BLOB:https://example.com/abc")).isTrue()
    }

    @Test
    fun an_ordinary_download_is_not_page_local() {
        assertThat(DownloadEngine.isPageLocal("https://example.com/file.pdf")).isFalse()
        assertThat(DownloadEngine.isPageLocal("http://example.com/file.pdf")).isFalse()
        assertThat(DownloadEngine.isPageLocal("file:///sdcard/file.pdf")).isFalse()
    }

    @Test
    fun a_filename_is_not_taken_from_a_page_local_url() {
        // A blob URL's last segment is a UUID and a data URL's is its own
        // bytes. Either would become a nonsense filename, so the MIME type
        // decides the extension instead.
        val blob = DownloadEngine.guessFileName(
            "blob:https://example.com/5b1f6f0c-0e0e-4c1a-9a3e-2f6f9d0f8a11",
            null,
            "application/pdf"
        )
        assertThat(blob).startsWith("download-")
        assertThat(blob).endsWith(".pdf")

        val data = DownloadEngine.guessFileName(
            "data:text/plain;base64,aGVsbG8udHh0",
            null,
            "text/plain"
        )
        assertThat(data).startsWith("download-")
        assertThat(data).endsWith(".txt")
    }

    @Test
    fun a_content_disposition_still_names_a_page_local_download() {
        val name = DownloadEngine.guessFileName(
            "blob:https://example.com/uuid",
            "attachment; filename=\"report.pdf\"",
            "application/pdf"
        )
        assertThat(name).isEqualTo("report.pdf")
    }

    @Test
    fun a_name_from_the_page_cannot_become_a_path() {
        // The anchor's `download` attribute is page-controlled, and the stored
        // name is joined to the Downloads directory with `File(dir, name)`, so
        // a name with separators in it would be a write outside that directory.
        val name = DownloadEngine.sanitizeFileName("../../data/system/passwords.txt")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("\\")
        assertThat(name).doesNotContain("..")
        assertThat(name).isNotEmpty()
    }

    @Test
    fun a_name_with_nothing_usable_in_it_falls_back() {
        assertThat(DownloadEngine.sanitizeFileName("   ")).isEqualTo("download")
        assertThat(DownloadEngine.sanitizeFileName("")).isEqualTo("download")
    }
}
