package com.roombrowser.domain.download

/**
 * What to do with a response body, given what is already on disk.
 *
 * @property appendAt byte offset to write at. `0` means the partial file must
 *   be truncated and written from the start.
 * @property totalBytes the finished size, or [DownloadPlanner.UNKNOWN] when the
 *   server did not say. This is what the progress bar divides by.
 * @property refetch the body cannot be used — close it and ask again with no
 *   `Range` header.
 */
data class ResumePlan(
    val appendAt: Long,
    val totalBytes: Long,
    val refetch: Boolean = false
)

/**
 * Decides how to write a download response over a partial file.
 *
 * This is extracted from `DownloadEngine` because the version that lived there
 * was wrong in a way no test could see: it opened the part file with
 * `FileOutputStream(part, append = false)`, which *truncates on open*, and only
 * afterwards branched on whether it meant to resume. So the resume branch
 * appended into a file it had just emptied, and every resumed download lost its
 * first N bytes while reporting success.
 *
 * The rule this encodes is that the offset we append at must be the offset the
 * server actually started sending from — not the offset we asked for, and not
 * the number we stored in the database. A server is free to ignore `Range`
 * (answer 200) or to answer with a different range than requested, and both
 * have to be handled by discarding what we have rather than splicing two
 * unrelated byte runs together into a file that looks fine and is not.
 */
object DownloadPlanner {

    const val UNKNOWN = -1L

    /**
     * @param resumeFrom bytes already on disk — read from the file, never from
     *   the stored counter, which is written on a throttle and can lag.
     * @param responseCode the HTTP status the server answered with.
     * @param contentRange the `Content-Range` header, absent on a 200.
     * @param contentLength the response body length, or -1 when chunked.
     */
    fun plan(
        resumeFrom: Long,
        responseCode: Int,
        contentRange: String?,
        contentLength: Long
    ): ResumePlan {
        if (resumeFrom <= 0L) return fresh(contentLength)
        // 200 to a Range request means the server ignored it and is sending the
        // whole file from byte 0. Appending that to the part file would produce
        // a file with the first N bytes duplicated — a corrupt download that
        // opens and plays, which is the worst kind.
        if (responseCode != 206) return fresh(contentLength)

        val range = parseContentRange(contentRange)
            // A 206 is required to carry Content-Range. Without it we cannot
            // know where this body starts, and guessing is how the file gets
            // corrupted, so ask again from the top instead.
            ?: return ResumePlan(appendAt = 0, totalBytes = UNKNOWN, refetch = true)

        if (range.start != resumeFrom) {
            // The server started somewhere else — either it clamped the range to
            // the file size it has, or the file changed under us. Either way our
            // partial bytes are not a prefix of this body.
            return ResumePlan(appendAt = 0, totalBytes = UNKNOWN, refetch = true)
        }

        val total = when {
            range.total > 0 -> range.total
            contentLength > 0 -> resumeFrom + contentLength
            else -> UNKNOWN
        }
        return ResumePlan(appendAt = resumeFrom, totalBytes = total)
    }

    /** A whole-file response: truncate whatever is there and write from 0. */
    fun fresh(contentLength: Long): ResumePlan =
        ResumePlan(appendAt = 0, totalBytes = if (contentLength > 0) contentLength else UNKNOWN)

    private data class Range(val start: Long, val total: Long)

    /** Parses `bytes 200-1000/12345`; `*` for an unknown total. */
    private fun parseContentRange(value: String?): Range? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("bytes", ignoreCase = true)) return null
        val spec = text.substringAfter(' ', "").trim()
        val start = spec.substringBefore('-').trim().toLongOrNull() ?: return null
        val totalText = spec.substringAfter('/', "").trim()
        val total = if (totalText == "*") UNKNOWN else totalText.toLongOrNull() ?: UNKNOWN
        return Range(start, total)
    }
}
