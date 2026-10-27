package com.roombrowser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-origin cookie erasure plan, pinned because every part of it is a
 * deletion boundary: a wrong host in the chain erases somebody else's cookies,
 * a missing parent leaves the very cookie the user asked to erase, and a
 * malformed expiry header silently deletes nothing while claiming success.
 */
class SiteCookieClearerTest {

    // ---- hostChain ---------------------------------------------------------

    @Test
    fun `a third-level host inherits its two-label parent`() {
        assertEquals(
            listOf("accounts.google.com", "google.com"),
            SiteCookieClearer.hostChain("accounts.google.com")
        )
    }

    @Test
    fun `a two-label host stands alone`() {
        assertEquals(listOf("google.com"), SiteCookieClearer.hostChain("google.com"))
    }

    @Test
    fun `the chain is lower-cased and stripped of a trailing dot`() {
        assertEquals(
            listOf("example.com"),
            SiteCookieClearer.hostChain("EXAMPLE.COM.")
        )
    }

    @Test
    fun `a blank host is an empty chain`() {
        assertTrue(SiteCookieClearer.hostChain(" ").isEmpty())
        assertTrue(SiteCookieClearer.hostChain("").isEmpty())
    }

    // ---- parseNames ---------------------------------------------------------

    @Test
    fun `names are read out of a jar answer`() {
        assertEquals(
            listOf("SID", "HSID", "NID"),
            SiteCookieClearer.parseNames("SID=abc; HSID=def; NID=ghi")
        )
    }

    @Test
    fun `duplicate names are collapsed`() {
        assertEquals(listOf("SID"), SiteCookieClearer.parseNames("SID=a; SID=b"))
    }

    @Test
    fun `a blank jar answer is no names`() {
        assertTrue(SiteCookieClearer.parseNames(null).isEmpty())
        assertTrue(SiteCookieClearer.parseNames("").isEmpty())
    }

    // ---- expiryRequests -----------------------------------------------------

    @Test
    fun `every name is expired against every host in the chain, both shapes`() {
        val requests = SiteCookieClearer.expiryRequests(
            "https://accounts.google.com/ServiceLogin",
            "SID=abc"
        )
        // Two hosts in the chain, two cookie shapes each.
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.url.startsWith("https://") })
        assertTrue(requests.all { it.header.startsWith("SID=;") })
        assertTrue(requests.all { it.header.contains("Max-Age=0") })
        assertTrue(requests.any { it.header.contains("Domain=google.com") })
        assertTrue(requests.any { it.header.contains("Domain=accounts.google.com") })
        assertTrue(requests.any { it.url == "https://google.com/" })
    }

    @Test
    fun `a non-https url produces no plan`() {
        assertTrue(SiteCookieClearer.expiryRequests("http://accounts.google.com/", "SID=a").isEmpty())
    }

    @Test
    fun `an empty jar answer produces no plan`() {
        assertTrue(
            SiteCookieClearer.expiryRequests("https://accounts.google.com/", null).isEmpty()
        )
    }
}
