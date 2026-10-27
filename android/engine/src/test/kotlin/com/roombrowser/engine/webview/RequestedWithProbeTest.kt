package com.roombrowser.engine.webview

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The probe's main-frame separation, pinned because the diagnostics screen now
 * answers the sign-in question from the MAIN-FRAME sightings alone.
 *
 * The decisive case: a provider that stamps subresource requests but honours
 * the session's main-frame override must read PRESENT on the aggregate and
 * ABSENT on the main frame. Before the split, one such subresource stamp made
 * the screen tell the owner their sign-in request was dirty when it was not --
 * the exact false alarm this object's split exists to stop.
 */
class RequestedWithProbeTest {

    @Before
    fun reset() {
        RequestedWithProbe.resetForTest()
    }

    @After
    fun resetAgain() {
        // The probe is process-global state; leaving sightings behind would
        // leak into whichever test runs next in this JVM.
        RequestedWithProbe.resetForTest()
    }

    @Test
    fun `a main-frame sighting is recorded on both the aggregate and the main-frame counters`() {
        RequestedWithProbe.record(mapOf("X-Requested-With" to "com.roombrowser.debug"), true)
        assertTrue(RequestedWithProbe.sawPresent())
        assertTrue(RequestedWithProbe.sawMainFramePresent())
    }

    @Test
    fun `a subresource sighting is aggregate-only`() {
        RequestedWithProbe.record(mapOf("X-Requested-With" to "com.roombrowser.debug"), false)
        assertTrue(RequestedWithProbe.sawPresent())
        assertFalse(RequestedWithProbe.sawMainFramePresent())
    }

    @Test
    fun `a clean main frame is recorded on both absent counters`() {
        RequestedWithProbe.record(mapOf("Accept" to "text/html"), true)
        assertTrue(RequestedWithProbe.sawAbsent())
        assertTrue(RequestedWithProbe.sawMainFrameAbsent())
    }

    @Test
    fun `the decisive mixed case - stamped subresources, clean main frame`() {
        RequestedWithProbe.record(mapOf("Accept" to "text/html"), true)
        RequestedWithProbe.record(mapOf("X-Requested-With" to "com.roombrowser.debug"), false)
        assertTrue("aggregate must report the subresource stamp", RequestedWithProbe.sawPresent())
        assertFalse(
            "a stamped subresource must not dirty the main-frame answer",
            RequestedWithProbe.sawMainFramePresent()
        )
        assertTrue(RequestedWithProbe.sawMainFrameAbsent())
    }

    @Test
    fun `header names are matched case-insensitively`() {
        RequestedWithProbe.record(mapOf("x-requested-with" to "com.roombrowser.debug"), true)
        assertTrue(RequestedWithProbe.sawMainFramePresent())
    }

    @Test
    fun `a null header map is ignored entirely`() {
        RequestedWithProbe.record(null, true)
        assertFalse(RequestedWithProbe.sawPresent())
        assertFalse(RequestedWithProbe.sawAbsent())
        assertFalse(RequestedWithProbe.sawMainFramePresent())
        assertFalse(RequestedWithProbe.sawMainFrameAbsent())
    }

    @Test
    fun `reset clears every counter`() {
        RequestedWithProbe.record(mapOf("X-Requested-With" to "x"), true)
        RequestedWithProbe.record(mapOf("Accept" to "*/*"), false)
        RequestedWithProbe.resetForTest()
        assertFalse(RequestedWithProbe.sawPresent())
        assertFalse(RequestedWithProbe.sawAbsent())
        assertFalse(RequestedWithProbe.sawMainFramePresent())
        assertFalse(RequestedWithProbe.sawMainFrameAbsent())
    }
}
