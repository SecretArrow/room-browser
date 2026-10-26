package com.roombrowser.browser

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.TabEntity
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.EngineState
import io.mockk.mockk
import org.junit.Test

/**
 * JVM tests for the tab manager's in-memory bookkeeping:
 *
 *  - the saved-state cap evicts the entry saved LONGEST AGO. The cap used to
 *    take `keys.firstOrNull()` off a plain HashMap, whose iteration order is
 *    hash order -- an arbitrary entry was dropped while the comment (and the
 *    intent) promised oldest-first. The map is insertion-ordered now, and
 *    re-saving an id re-inserts it at the newest end, so both tests below
 *    pin that order;
 *  - the popup-privacy inheritance: a popup must inherit the privacy of the
 *    tab that owns the firing session. The old handler hardwired
 *    `isPrivate = false`, so a `window.open` from a private tab produced a
 *    regular tab whose cookies went into the on-disk profile jar and whose
 *    visit went into history -- the private session's contents outlived it.
 *
 * The facade's [EngineSession] is mocked because the manager keys engines by
 * IDENTITY (implementations are forbidden to override equals/hashCode), and
 * these tests rely on exactly that identity behaviour.
 */
class TabManagerTest {

    private fun entity(id: String, isPrivate: Boolean = false) = TabEntity(
        id = id,
        profileId = "profile-1",
        position = 0,
        title = "",
        url = "https://example.com/$id",
        isPrivate = isPrivate,
        createdAt = 0L,
        lastViewedAt = 0L
    )

    /** Opaque facade state token; the manager stores it without looking in. */
    private class StateToken : EngineState

    @Test
    fun `saved states beyond the cap evict the oldest saved first`() {
        val manager = TabManager()
        val ids = (1..TabManager.MAX_SAVED_STATES).map { "tab$it" }
        ids.forEach { manager.saveEngineState(it, StateToken()) }

        // A ninth state pushes the FIRST-saved one out, not a hash-order
        // accident.
        manager.saveEngineState("tab9", StateToken())
        assertThat(manager.engineState("tab1")).isNull()
        ids.drop(1).forEach { id -> assertThat(manager.engineState(id)).isNotNull() }
        assertThat(manager.engineState("tab9")).isNotNull()
    }

    @Test
    fun `re-saving a state counts as fresh for the eviction cap`() {
        val manager = TabManager()
        manager.saveEngineState("old", StateToken())
        manager.saveEngineState("kept", StateToken())
        // Refreshing "old" re-inserts it at the newest end.
        manager.saveEngineState("old", StateToken())

        // One more state than the cap fits: the entry saved longest ago --
        // "kept", whose save was never refreshed -- is the victim, and the
        // freshly re-saved "old" survives.
        (1..TabManager.MAX_SAVED_STATES - 1).forEach { manager.saveEngineState("n$it", StateToken()) }
        assertThat(manager.engineState("kept")).isNull()
        assertThat(manager.engineState("old")).isNotNull()
    }

    @Test
    fun `a popup inherits the privacy of the tab that owns the session`() {
        val manager = TabManager()
        val privateSession = mockk<EngineSession>(relaxed = true)
        manager.add(entity("private-tab", isPrivate = true), privateSession)
        val regularSession = mockk<EngineSession>(relaxed = true)
        manager.add(entity("regular-tab"), regularSession)

        assertThat(manager.ownerIsPrivate(privateSession, fallback = false)).isTrue()
        assertThat(manager.ownerIsPrivate(regularSession, fallback = true)).isFalse()
    }

    @Test
    fun `an untracked session falls back to the caller's answer, never to regular`() {
        val manager = TabManager()
        val untracked = mockk<EngineSession>(relaxed = true)
        // A hardcoded `false` here was the leak: a popup whose owner session
        // the index has already lost would silently become a regular tab. The
        // fallback is the CALLER's own answer for its active tab.
        assertThat(manager.ownerIsPrivate(untracked, fallback = true)).isTrue()
        assertThat(manager.ownerIsPrivate(untracked, fallback = false)).isFalse()
        assertThat(manager.ownerIsPrivate(null, fallback = true)).isTrue()
    }
}
