package com.roombrowser.domain.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Search-box predicate of a saved login (domain / username / title). */
class SavedCredentialTest {

    private fun credential(
        domain: String = "github.com",
        username: String = "octocat@example.com",
        title: String? = "Work account"
    ) = SavedCredential(
        id = "id-1",
        profileId = "profile-1",
        domain = domain,
        username = username,
        password = "hunter2",
        title = title,
        createdAt = 10,
        updatedAt = 20
    )

    @Test
    fun `matches query against domain username or title`() {
        val c = credential()
        assertThat(c.matchesQuery("github")).isTrue()
        assertThat(c.matchesQuery("octocat")).isTrue()
        assertThat(c.matchesQuery("work")).isTrue()
    }

    @Test
    fun `matching is case-insensitive and trims the query`() {
        val c = credential()
        assertThat(c.matchesQuery("  GITHUB ")).isTrue()
        assertThat(c.matchesQuery("OCTOCAT@EXAMPLE.COM")).isTrue()
    }

    @Test
    fun `blank query matches everything`() {
        assertThat(credential().matchesQuery("")).isTrue()
        assertThat(credential().matchesQuery("   ")).isTrue()
    }

    @Test
    fun `unrelated query does not match`() {
        assertThat(credential().matchesQuery("gitlab")).isFalse()
    }

    @Test
    fun `password is never searchable and null title is safe`() {
        val c = credential(title = null)
        // Passwords are deliberately not a search field: nothing should ever
        // select rows by secret content.
        assertThat(c.matchesQuery("hunter2")).isFalse()
        assertThat(c.matchesQuery("work")).isFalse() // no title on this row
    }
}
