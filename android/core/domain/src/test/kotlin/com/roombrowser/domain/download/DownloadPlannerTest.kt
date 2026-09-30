package com.roombrowser.domain.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadPlannerTest {

    private val unknown = DownloadPlanner.UNKNOWN

    @Test
    fun `a download with nothing on disk writes from zero`() {
        val plan = DownloadPlanner.plan(
            resumeFrom = 0, responseCode = 200, contentRange = null, contentLength = 500
        )
        assertThat(plan.appendAt).isEqualTo(0)
        assertThat(plan.totalBytes).isEqualTo(500)
        assertThat(plan.refetch).isFalse()
    }

    @Test
    fun `a satisfied range appends at exactly the byte we hold`() {
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 206,
            contentRange = "bytes 200-999/1000", contentLength = 800
        )
        assertThat(plan.appendAt).isEqualTo(200)
        assertThat(plan.totalBytes).isEqualTo(1000)
        assertThat(plan.refetch).isFalse()
    }

    @Test
    fun `a server that ignores the range restarts instead of splicing`() {
        // The dangerous case: 200 to a Range request is the WHOLE file. Appending
        // it to 200 bytes already on disk duplicates those bytes and corrupts the
        // file while every counter still reports success.
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 200, contentRange = null, contentLength = 1000
        )
        assertThat(plan.appendAt).isEqualTo(0)
        assertThat(plan.totalBytes).isEqualTo(1000)
        assertThat(plan.refetch).isFalse()
    }

    @Test
    fun `a range starting somewhere else is refetched, never appended`() {
        // Server clamped the range, or the resource changed under us. Our bytes
        // are not a prefix of this body.
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 206,
            contentRange = "bytes 0-799/800", contentLength = 800
        )
        assertThat(plan.appendAt).isEqualTo(0)
        assertThat(plan.refetch).isTrue()
    }

    @Test
    fun `a 206 without content-range is refetched`() {
        // Required by RFC 7233; without it the body's start offset is a guess.
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 206, contentRange = null, contentLength = 800
        )
        assertThat(plan.appendAt).isEqualTo(0)
        assertThat(plan.refetch).isTrue()
    }

    @Test
    fun `a range with no total header adds the body to the bytes already held`() {
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 206,
            contentRange = "bytes 200-999/*", contentLength = 800
        )
        assertThat(plan.appendAt).isEqualTo(200)
        // Falls back to held + body, which is honest arithmetic on what we know.
        assertThat(plan.totalBytes).isEqualTo(1000)
    }

    @Test
    fun `a chunked restart reports an unknown total`() {
        val plan = DownloadPlanner.plan(
            resumeFrom = 0, responseCode = 200, contentRange = null, contentLength = -1
        )
        assertThat(plan.appendAt).isEqualTo(0)
        assertThat(plan.totalBytes).isEqualTo(unknown)
    }

    @Test
    fun `a chunked range with no total header is unknown`() {
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 206,
            contentRange = "bytes 200-999/*", contentLength = -1
        )
        assertThat(plan.appendAt).isEqualTo(200)
        assertThat(plan.totalBytes).isEqualTo(unknown)
    }

    @Test
    fun `malformed content-range is not trusted`() {
        for (bad in listOf("bytes abc-999/1000", "items 0-9/10", "", "bytes ", "bytes 0-")) {
            val plan = DownloadPlanner.plan(
                resumeFrom = 200, responseCode = 206, contentRange = bad, contentLength = 800
            )
            assertThat(plan.refetch).isTrue()
            assertThat(plan.appendAt).isEqualTo(0)
        }
    }

    @Test
    fun `the header is matched case-insensitively on its unit`() {
        val plan = DownloadPlanner.plan(
            resumeFrom = 200, responseCode = 206,
            contentRange = "Bytes 200-999/1000", contentLength = 800
        )
        assertThat(plan.appendAt).isEqualTo(200)
    }
}
