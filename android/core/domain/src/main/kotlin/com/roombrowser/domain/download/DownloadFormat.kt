package com.roombrowser.domain.download

import kotlin.math.roundToInt

/**
 * Human-readable formatting for the Downloads screen.
 *
 * This exists because the screen used to print `bytes / 1024` and call the
 * result KB, which is wrong twice: a 2 GB download read "2097152 KB", and a
 * 900-byte file read "0 KB". The arithmetic below is trivial, which is exactly
 * why it belongs somewhere a JVM test can pin it down rather than inline in a
 * composable where nothing checks it.
 *
 * Pure Kotlin on purpose — no `android.text.format`, no `String.format` with a
 * default locale. The device locale decides the decimal separator in some of
 * those, so the same download could read "1,4 MB" on one phone and "1.4 MB" on
 * another; a byte count is a technical figure, not prose.
 */
object DownloadFormat {

    private val UNITS = listOf("KB", "MB", "GB", "TB")

    /**
     * A byte count as a short string: "0 B", "512 B", "1.4 MB", "2.0 GB".
     *
     * Negative means "unknown" everywhere a size is stored in this app (the
     * `total_bytes` sentinel), and renders as an em dash rather than as a
     * nonsense negative size.
     */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        var scaled = value.toDouble() / 1024
        var unit = 0
        // Promotion is decided on the ROUNDED figure, because that is the one
        // that gets printed: 1_048_500 bytes is 1023.9 KB, and a loop that
        // tested the raw value would stop there and print "1024 KB" — a number
        // the next unit up exists for.
        while (scaled.roundToInt() >= 1024 && unit < UNITS.lastIndex) {
            scaled /= 1024
            unit++
        }
        // Below 100 a tenth is meaningful ("1.4 MB"); above it the tenth is
        // noise on a number the user is only glancing at ("128 MB"). The
        // threshold is tested on the rounded figure for the same reason: 99.96
        // scaled is not ">= 100" raw, but it prints as "100.0", and a size must
        // not gain a decimal it was supposed to have dropped.
        val rounded = scaled.roundToInt()
        val text = if (rounded >= 100) "$rounded" else oneDecimal(scaled)
        return "$text ${UNITS[unit]}"
    }

    /**
     * Completion as a whole percent, or null when it cannot be known.
     *
     * Null rather than 0 for an unknown total: "0%" would be a claim that
     * nothing has arrived, which is the opposite of what an unknown-length
     * stream means.
     */
    fun percent(downloaded: Long, total: Long): Int? {
        if (total <= 0 || downloaded < 0) return null
        return ((downloaded * 100.0) / total).roundToInt().coerceIn(0, 100)
    }

    /** "1.4 MB/s", or null when there is no rate to report. */
    fun speed(bytesPerSecond: Long): String? =
        if (bytesPerSecond <= 0) null else "${bytes(bytesPerSecond)}/s"

    /**
     * A remaining time as a short string, or null when it is not knowable.
     *
     * Seconds are omitted above a minute and minutes above an hour: an ETA is
     * an estimate, and "1h 3m 12s" would dress a guess up as a measurement.
     */
    fun eta(seconds: Long): String? = when {
        seconds <= 0 -> null
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    /**
     * Seconds remaining at [bytesPerSecond], or null when either the total is
     * unknown or the transfer is not moving. A stalled download has no ETA —
     * reporting one from a stale rate is how a progress bar lies.
     */
    fun etaSeconds(downloaded: Long, total: Long, bytesPerSecond: Long): Long? {
        if (total <= 0 || bytesPerSecond <= 0 || downloaded >= total) return null
        return (total - downloaded) / bytesPerSecond
    }

    private fun oneDecimal(value: Double): String {
        val scaled = (value * 10).roundToInt()
        return "${scaled / 10}.${scaled % 10}"
    }
}
