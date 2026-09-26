package com.roombrowser.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for pure browser-module logic (JVM). */
class BrowserLogicTest {

    @Test
    fun `guessFileName extracts filename from content disposition`() {
        val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(
            url = "https://example.com/some/path",
            contentDisposition = "attachment; filename=\"report.pdf\"",
            mimeType = "application/pdf"
        )
        assertThat(name).isEqualTo("report.pdf")
    }

    @Test
    fun `guessFileName decodes RFC5987 encoded filenames`() {
        val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(
            url = "https://example.com/file",
            contentDisposition = "attachment; filename*=UTF-8''caf%C3%A9%20menu.txt",
            mimeType = "text/plain"
        )
        assertThat(name).isEqualTo("café menu.txt")
    }

    @Test
    fun `guessFileName falls back to url last segment`() {
        val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(
            url = "https://example.com/downloads/photo.jpg?token=abc",
            contentDisposition = null,
            mimeType = "image/jpeg"
        )
        assertThat(name).isEqualTo("photo.jpg")
    }

    @Test
    fun `guessFileName sanitizes dangerous characters`() {
        val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(
            url = "https://example.com/x",
            contentDisposition = "attachment; filename=\"evil..\\\\..\\\\win.ini\"",
            mimeType = "application/octet-stream"
        )
        assertThat(name).doesNotContain("\\")
        assertThat(name).doesNotContain("..")
    }

    @Test
    fun `guessFileName derives extension from mime type`() {
        val name = com.roombrowser.browser.engine.DownloadEngine.guessFileName(
            url = "https://example.com/",
            contentDisposition = null,
            mimeType = "application/pdf"
        )
        assertThat(name).endsWith(".pdf")
    }

    @Test
    fun `stat categories map filter engine categories`() {
        assertThat(StatCategories.from(com.roombrowser.domain.engine.FilterEngine.FilterCategory.AD))
            .isEqualTo(StatCategories.AD)
        assertThat(StatCategories.from(com.roombrowser.domain.engine.FilterEngine.FilterCategory.CROSS_SITE_TRACKER))
            .isEqualTo(StatCategories.CROSS_SITE_TRACKER)
        assertThat(StatCategories.from(com.roombrowser.domain.engine.FilterEngine.FilterCategory.MALICIOUS))
            .isEqualTo(StatCategories.MALICIOUS)
        assertThat(StatCategories.HTTPS_UPGRADE).isEqualTo("HTTPS_UPGRADE")
    }
}
