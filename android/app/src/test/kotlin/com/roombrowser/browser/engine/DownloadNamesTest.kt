package com.roombrowser.browser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Filename decisions the download engine makes from pure text: the
 * Content-Disposition forms, the MIME-to-extension table, and the filename
 * sanitiser. The blob/data side of the same decision lives in
 * [PageLocalDownloadTest]; this file holds the parts a hostile or merely
 * exotic server can get wrong — RFC 5987 decoding, the plain-form fallback
 * order, and names that must not leave the directory they are joined to.
 */
class DownloadNamesTest {

    // ----- Content-Disposition: which form wins --------------------------

    @Test
    fun a_filename_star_outranks_a_plain_filename_when_both_are_present() {
        // RFC 6266 sends the plain form as the ASCII fallback and the star
        // form as the real name; the order they appear in must not matter.
        val name = DownloadEngine.guessFileName(
            "https://example.com/fallback.bin",
            "attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E2%82%AC%20rates.pdf",
            "application/octet-stream"
        )
        assertThat(name).isEqualTo("€ rates.pdf")
    }

    @Test
    fun a_star_form_first_with_a_plain_form_after_still_prefers_the_star() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/fallback.bin",
            "attachment; filename*=UTF-8''%C3%A9tude.pdf; filename=\"fallback.txt\"",
            ""
        )
        assertThat(name).isEqualTo("étude.pdf")
    }

    @Test
    fun a_plain_filename_is_still_used_when_no_star_form_exists() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename=\"report 2024.pdf\"",
            ""
        )
        assertThat(name).isEqualTo("report 2024.pdf")
    }

    @Test
    fun an_unquoted_plain_filename_is_used_too() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename=report.pdf",
            ""
        )
        assertThat(name).isEqualTo("report.pdf")
    }

    // ----- RFC 5987 decoding ---------------------------------------------

    @Test
    fun an_rfc5987_utf8_name_is_percent_decoded() {
        // A real multibyte name — the case the plain form cannot carry at all.
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82%202024.pdf",
            ""
        )
        assertThat(name).isEqualTo("отчёт 2024.pdf")
    }

    @Test
    fun a_lowercase_charset_declaration_is_accepted() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename*=utf-8''na%C3%AFve%20file.txt",
            "text/plain"
        )
        assertThat(name).isEqualTo("naïve file.txt")
    }

    @Test
    fun a_plus_in_a_filename_star_value_stays_a_plus() {
        // java.net.URLDecoder would fold this into a space; RFC 5987
        // percent-encoding is not form encoding, so '+' is a literal plus.
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename*=UTF-8''c++_notes.txt",
            "text/plain"
        )
        assertThat(name).isEqualTo("c++_notes.txt")
    }

    @Test
    fun an_iso_8859_1_filename_star_is_decoded_by_its_own_charset() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename*=iso-8859-1''caf%E9%20menu.pdf",
            "application/pdf"
        )
        assertThat(name).isEqualTo("café menu.pdf")
    }

    @Test
    fun a_star_value_with_a_language_tag_still_decodes() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename*=UTF-8'en'%C2%A3rates.csv",
            "text/csv"
        )
        assertThat(name).isEqualTo("£rates.csv")
    }

    @Test
    fun an_unknown_charset_falls_back_to_the_plain_form() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/fallback.bin",
            "attachment; filename*=utf-16''%FF%FE.pdf; filename=\"plain.pdf\"",
            ""
        )
        assertThat(name).isEqualTo("plain.pdf")
    }

    @Test
    fun a_star_value_with_raw_non_ascii_falls_back_to_the_plain_form() {
        // The RFC permits only ASCII outside the %-escapes; a raw character
        // is a malformed header, and the plain form beats guessing.
        val name = DownloadEngine.guessFileName(
            "https://example.com/fallback.bin",
            "attachment; filename*=UTF-8''café.pdf; filename=\"plain.pdf\"",
            ""
        )
        assertThat(name).isEqualTo("plain.pdf")
    }

    @Test
    fun a_malformed_star_value_falls_back_to_the_plain_form() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/fallback.bin",
            "attachment; filename*=; filename=\"plain.pdf\"",
            ""
        )
        assertThat(name).isEqualTo("plain.pdf")
    }

    @Test
    fun a_percent_encoded_plain_filename_still_decodes() {
        val name = DownloadEngine.guessFileName(
            "https://example.com/x",
            "attachment; filename=\"%CE%B7%CE%BC%CE%AD%CF%81%CE%B1.txt\"",
            "text/plain"
        )
        assertThat(name).isEqualTo("ημέρα.txt")
    }

    // ----- MIME → extension table ----------------------------------------

    @Test
    fun the_extension_table_covers_the_common_set() {
        assertThat(guess("application/vnd.android.package-archive")).endsWith(".apk")
        assertThat(guess("text/csv")).endsWith(".csv")
        assertThat(guess("application/json")).endsWith(".json")
        assertThat(guess("application/msword")).endsWith(".doc")
        assertThat(guess("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .endsWith(".docx")
        assertThat(guess("application/vnd.ms-excel")).endsWith(".xls")
        assertThat(guess("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .endsWith(".xlsx")
        assertThat(guess("application/vnd.ms-powerpoint")).endsWith(".ppt")
        assertThat(guess("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            .endsWith(".pptx")
        assertThat(guess("video/webm")).endsWith(".webm")
        assertThat(guess("video/x-matroska")).endsWith(".mkv")
        assertThat(guess("audio/mp4")).endsWith(".m4a")
        assertThat(guess("audio/x-wav")).endsWith(".wav")
        assertThat(guess("audio/ogg")).endsWith(".ogg")
        assertThat(guess("text/calendar")).endsWith(".ics")
        assertThat(guess("application/epub+zip")).endsWith(".epub")
        assertThat(guess("image/svg+xml")).endsWith(".svg")
        assertThat(guess("font/ttf")).endsWith(".ttf")
        assertThat(guess("font/otf")).endsWith(".otf")
        assertThat(guess("application/x-tar")).endsWith(".tar")
        assertThat(guess("application/gzip")).endsWith(".gz")
        assertThat(guess("application/vnd.rar")).endsWith(".rar")
        assertThat(guess("application/x-7z-compressed")).endsWith(".7z")
    }

    @Test
    fun a_mime_type_with_parameters_still_picks_its_extension() {
        assertThat(guess("text/csv; charset=utf-8")).endsWith(".csv")
        assertThat(guess("image/svg+xml; charset=utf-8")).endsWith(".svg")
    }

    @Test
    fun an_unknown_mime_falls_back_to_a_bare_download_name() {
        val name = DownloadEngine.guessFileName("https://example.com/x", null, "application/x-unknown")
        assertThat(name).startsWith("download-")
        assertThat(name).doesNotContain(".x-unknown")
    }

    // ----- sanitising ------------------------------------------------------

    @Test
    fun control_characters_become_underscores() {
        assertThat(DownloadEngine.sanitizeFileName("re\u0000port\nv2.txt"))
            .isEqualTo("re_port_v2.txt")
    }

    @Test
    fun a_traversal_name_cannot_leave_the_directory() {
        val name = DownloadEngine.sanitizeFileName("../../etc/passwd")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("\\")
        assertThat(name).doesNotContain("..")
    }

    @Test
    fun a_name_cut_mid_emoji_drops_the_lone_high_surrogate() {
        // 119 ASCII chars, then a two-char surrogate pair: the 120-char bound
        // lands exactly between the pair's halves.
        val long = "a".repeat(119) + "\uD83D\uDE00" + "b".repeat(40)
        val name = DownloadEngine.sanitizeFileName(long)
        assertThat(name).hasLength(119)
        assertThat(name.last().isHighSurrogate()).isFalse()
    }

    @Test
    fun an_emoji_that_fits_whole_is_kept() {
        val name = DownloadEngine.sanitizeFileName("a".repeat(118) + "\uD83D\uDE00")
        assertThat(name).hasLength(120)
        assertThat(name.last().isLowSurrogate()).isTrue()
    }

    @Test
    fun a_blank_name_still_falls_back() {
        assertThat(DownloadEngine.sanitizeFileName("   ")).isEqualTo("download")
        assertThat(DownloadEngine.sanitizeFileName("")).isEqualTo("download")
    }

    private fun guess(mime: String): String =
        DownloadEngine.guessFileName("https://example.com/x", null, mime)
}
