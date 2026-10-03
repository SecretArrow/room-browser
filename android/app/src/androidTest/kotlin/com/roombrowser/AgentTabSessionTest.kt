package com.roombrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.repo.AgentRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-tab agent chats (Room v10) — the queries that decide WHICH conversation
 * a tab shows.
 *
 * This is the part of per-tab chats whose failure is silent: a lookup that
 * falls back to the wrong row does not crash, it just continues somebody
 * else's conversation, and the only trace is a chat that suddenly has history
 * it never had. The three ways that can happen are pinned here — a tab
 * resolving to another tab's chat, a detached chat resolving to a tab it no
 * longer belongs to, and a chat leaking across profiles.
 *
 * The migration itself (ALTER TABLE + CREATE INDEX, both additive) is not
 * exercised here: the database under test is created fresh at v10, so no
 * migration runs. A fresh install never runs one either; the migration path
 * only exists for an in-place upgrade.
 *
 * Every test ends on a VOID-returning Truth call, because a Kotlin method
 * whose last expression has a value is non-void and JUnit4 then refuses the
 * whole class (see the runBlocking<Unit> note in the project memory).
 */
@RunWith(AndroidJUnit4::class)
class AgentTabSessionTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AgentRepository

    private val profileA = "11111111-1111-1111-1111-111111111111"
    private val profileB = "22222222-2222-2222-2222-222222222222"
    private val tabOne = "aaaaaaaa-1111-1111-1111-111111111111"
    private val tabTwo = "bbbbbbbb-2222-2222-2222-222222222222"
    /** A tab id that is the SAME in both profiles, to prove profile scoping. */
    private val sharedTab = "cccccccc-3333-3333-3333-333333333333"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AgentRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `each tab resolves to its own conversation`() = runBlocking<Unit> {
        val first = repo.createSession(profileA, "about cats", 1, "m", tabId = tabOne)
        val second = repo.createSession(profileA, "about dogs", 1, "m", tabId = tabTwo)

        assertThat(repo.sessionForTab(profileA, tabOne)?.id).isEqualTo(first)
        assertThat(repo.sessionForTab(profileA, tabTwo)?.id).isEqualTo(second)
        // A third tab has no conversation of its own — it must NOT be handed
        // one of the two above, which is the whole point of the lookup.
        assertThat(repo.sessionForTab(profileA, sharedTab)).isNull()
    }

    @Test
    fun `a detached chat keeps its history but no longer owns the tab`() = runBlocking<Unit> {
        val id = repo.createSession(profileA, "old chat", 1, "m", tabId = tabOne)
        repo.addMessage(id, "user", "hello")

        // What the "+" button does: the tab gives the chat up so the next
        // message starts a new one.
        repo.bindSessionToTab(id, "")

        assertThat(repo.sessionForTab(profileA, tabOne)).isNull()
        // Detached is not deleted: it is still the profile's history.
        assertThat(repo.sessions(profileA).map { it.id }).contains(id)
        assertThat(repo.messages(id)).hasSize(1)
    }

    @Test
    fun `adopting a chat moves it off the tab that had it`() = runBlocking<Unit> {
        val id = repo.createSession(profileA, "moving", 1, "m", tabId = tabOne)
        // What the history list does on tap: bind it to the tab on screen.
        repo.bindSessionToTab(id, tabTwo)

        assertThat(repo.sessionForTab(profileA, tabTwo)?.id).isEqualTo(id)
        assertThat(repo.sessionForTab(profileA, tabOne)).isNull()
    }

    @Test
    fun `the same tab id in two profiles is two different conversations`() = runBlocking<Unit> {
        val a = repo.createSession(profileA, "personal", 1, "m", tabId = sharedTab)
        val b = repo.createSession(profileB, "work", 1, "m", tabId = sharedTab)

        assertThat(repo.sessionForTab(profileA, sharedTab)?.id).isEqualTo(a)
        assertThat(repo.sessionForTab(profileB, sharedTab)?.id).isEqualTo(b)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `a blank tab id never resolves to an unbound chat`() = runBlocking<Unit> {
        // Unbound chats all share tab_id = "": a caller with no tab must not
        // be handed one of them as though it owned it.
        repo.createSession(profileA, "no tab", 1, "m")

        assertThat(repo.sessionForTab(profileA, "")).isNull()
        assertThat(repo.sessions(profileA)).hasSize(1)
    }
}
