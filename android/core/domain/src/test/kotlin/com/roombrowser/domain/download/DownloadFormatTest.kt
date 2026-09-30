package com.roombrowser.domain.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadFormatTest {

    @Test
    fun `bytes below a kilobyte stay exact`() {
        assertThat(DownloadFormat.bytes(0)).isEqualTo("0 B")
        assertThat(DownloadFormat.bytes(1)).isEqualTo("1 B")
        assertThat(DownloadFormat.bytes(1023)).isEqualTo("1023 B")
    }

    @Test
    fun `bytes scale through the units`() {
        assertThat(DownloadFormat.bytes(1024)).isEqualTo("1.0 KB")
        assertThat(DownloadFormat.bytes(1024L * 1024)).isEqualTo("1.0 MB")
        assertThat(DownloadFormat.bytes(1024L * 1024 * 1024)).isEqualTo("1.0 GB")
        assertThat(DownloadFormat.bytes(1024L * 1024 * 1024 * 1024)).isEqualTo("1.0 TB")
    }

    @Test
    fun `a two gigabyte file does not read as two million kilobytes`() {
        // The regression this formatter exists for: the old screen printed
        // bytes / 1024 and called it KB, so this rendered as "2097152 KB".
        assertThat(DownloadFormat.bytes(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GB")
    }

    @Test
    fun `large values drop the tenth`() {
        // 150 MB — a tenth of a megabyte is noise at this size.
        assertThat(DownloadFormat.bytes(150L * 1024 * 1024)).isEqualTo("150 MB")
        assertThat(DownloadFormat.bytes(999L * 1024 * 1024)).isEqualTo("999 MB")
    }

    @Test
    fun `a size that rounds up to a hundred drops the tenth too`() {
        // 104_805_417 bytes is 99.9502 MB. It is under 100, and it prints as
        // 100 — so it takes the no-tenth branch; testing the raw value would
        // have sent it down the tenth branch and printed "100.0 MB".
        assertThat(DownloadFormat.bytes(104_805_417)).isEqualTo("100 MB")
    }

    @Test
    fun `a size just under a megabyte does not read as a thousand kilobytes`() {
        // 1_048_300 bytes is 1023.7 KB, which rounds to "1024 KB" — a figure
        // the next unit exists for. Promotion is decided on the rounded value
        // so this reads as the megabyte it is.
        assertThat(DownloadFormat.bytes(1_048_300)).isEqualTo("1.0 MB")
        // And the boundary itself: exactly a megabyte is a megabyte.
        assertThat(DownloadFormat.bytes(1024L * 1024)).isEqualTo("1.0 MB")
    }

    @Test
    fun `unknown size is an em dash not a negative`() {
        assertThat(DownloadFormat.bytes(-1)).isEqualTo("—")
        assertThat(DownloadFormat.bytes(Long.MIN_VALUE)).isEqualTo("—")
    }

    @Test
    fun `percent needs a known total`() {
        assertThat(DownloadFormat.percent(50, 100)).isEqualTo(50)
        assertThat(DownloadFormat.percent(0, 100)).isEqualTo(0)
        assertThat(DownloadFormat.percent(100, 100)).isEqualTo(100)
        // An unknown total must not be reported as 0% — "0%" claims nothing has
        // arrived, which is a different statement from "we cannot know".
        assertThat(DownloadFormat.percent(500, -1)).isNull()
        assertThat(DownloadFormat.percent(500, 0)).isNull()
    }

    @Test
    fun `percent is clamped so a lying server cannot overflow the bar`() {
        assertThat(DownloadFormat.percent(150, 100)).isEqualTo(100)
    }

    @Test
    fun `speed is null when there is no rate`() {
        assertThat(DownloadFormat.speed(0)).isNull()
        assertThat(DownloadFormat.speed(-5)).isNull()
        assertThat(DownloadFormat.speed(1024L * 1024)).isEqualTo("1.0 MB/s")
    }

    @Test
    fun `eta drops the seconds above a minute and the minutes above an hour`() {
        assertThat(DownloadFormat.eta(0)).isNull()
        assertThat(DownloadFormat.eta(-1)).isNull()
        assertThat(DownloadFormat.eta(45)).isEqualTo("45s")
        assertThat(DownloadFormat.eta(90)).isEqualTo("1m 30s")
        assertThat(DownloadFormat.eta(3600)).isEqualTo("1h 0m")
        assertThat(DownloadFormat.eta(3900)).isEqualTo("1h 5m")
    }

    @Test
    fun `etaSeconds needs a total and a moving transfer`() {
        assertThat(DownloadFormat.etaSeconds(0, 1000, 100)).isEqualTo(10)
        // Stalled: a rate of zero has no estimate, and a stale one would lie.
        assertThat(DownloadFormat.etaSeconds(0, 1000, 0)).isNull()
        assertThat(DownloadFormat.etaSeconds(0, -1, 100)).isNull()
        // Already there.
        assertThat(DownloadFormat.etaSeconds(1000, 1000, 100)).isNull()
    }
}
