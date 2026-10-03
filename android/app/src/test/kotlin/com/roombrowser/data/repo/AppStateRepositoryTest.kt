package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppStateDao
import com.roombrowser.data.db.AppStateEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * JVM tests of the agent-settings write path against a map-backed DAO — no
 * Room, no Android.
 *
 * The agent settings live as ONE json blob under one key, so every save
 * replaces the whole value and SQLite has no per-field write to interleave
 * safely. That leaves exactly one property worth pinning: a settings save must
 * MERGE into what is stored, never resurrect the copy the caller was holding.
 *
 * That property is the difference between a switch that stays on and one that
 * silently flips back the next time an unrelated setting is edited somewhere
 * else.
 */
class AppStateRepositoryTest {

    /**
     * Hand-written map-backed DAO.
     *
     * Written out rather than mocked because both accessors `delay(1)` on
     * purpose, and a hand-written `suspend fun` may call `delay` directly
     * without depending on whether a mocking library's answer block is
     * suspend-capable.
     *
     * The delay is what makes these tests able to fail: under runTest it is
     * virtual time, so it costs nothing, but it SUSPENDS — which is what lets
     * two concurrent updates interleave at their read/write boundary. A fake
     * that returned without ever suspending would run each update start to
     * finish and would pass against a racing implementation just as happily as
     * against a serialised one.
     */
    private class MapDao : AppStateDao {
        val rows = linkedMapOf<String, String>()

        override suspend fun get(key: String): String? {
            delay(1)
            return rows[key]
        }

        override fun observe(key: String): Flow<String?> = flowOf(rows[key])

        override suspend fun put(entity: AppStateEntity) {
            delay(1)
            rows[entity.key] = entity.value
        }

        override suspend fun remove(key: String) {
            rows.remove(key)
        }
    }

    private val dao = MapDao()
    private val repo = AppStateRepository(dao)

    private suspend fun stored(): AgentSettings = repo.agentSettingsSnapshot()

    @Test
    fun `the transform receives the stored blob, not a caller's copy`() = runTest {
        repo.saveAgentSettings(
            AgentSettings(useDefaultContext = true, defaultContext = "be terse", maxSteps = 5)
        )
        val seen = mutableListOf<AgentSettings>()

        repo.updateAgentSettings {
            seen += it
            it.copy(maxSteps = 9)
        }

        // useDefaultContext is the field that used to vanish: a writer holding
        // a copy from before it was switched on wrote the whole blob back with
        // useDefaultContext = false, and the standing context stopped being
        // sent with nothing on screen to say so.
        assertThat(seen.single().useDefaultContext).isTrue()
        assertThat(seen.single().defaultContext).isEqualTo("be terse")
        assertThat(seen.single().maxSteps).isEqualTo(5)
    }

    @Test
    fun `an unrelated edit does not revert the standing context`() = runTest {
        repo.updateAgentSettings {
            it.copy(useDefaultContext = true, defaultContext = "be terse")
        }

        // A later, entirely unrelated edit — a step budget. This is how the old
        // code lost the context: it saved its own stale copy of everything else.
        repo.updateAgentSettings { it.copy(maxSteps = 3) }

        val after = stored()
        assertThat(after.useDefaultContext).isTrue()
        assertThat(after.defaultContext).isEqualTo("be terse")
        assertThat(after.maxSteps).isEqualTo(3)
    }

    @Test
    fun `concurrent updates all survive`() = runTest {
        val edits = listOf<(AgentSettings) -> AgentSettings>(
            { it.copy(useDefaultContext = true) },
            { it.copy(defaultContext = "be terse") },
            { it.copy(maxSteps = 7) },
            { it.copy(temperature = 0.9) }
        )

        edits.map { edit -> async { repo.updateAgentSettings(edit) } }.forEach { it.await() }

        // Without the lock all four read the same starting blob and the last
        // writer wins, leaving exactly one of the four changes in place.
        val after = stored()
        assertThat(after.useDefaultContext).isTrue()
        assertThat(after.defaultContext).isEqualTo("be terse")
        assertThat(after.maxSteps).isEqualTo(7)
        assertThat(after.temperature).isEqualTo(0.9)
    }

    @Test
    fun `the update returns what it stored`() = runTest {
        val returned = repo.updateAgentSettings {
            it.copy(defaultContext = "be terse", useDefaultContext = true)
        }

        assertThat(returned).isEqualTo(stored())
    }

    @Test
    fun `saveAgentSettings still replaces the whole blob`() = runTest {
        repo.updateAgentSettings {
            it.copy(useDefaultContext = true, defaultContext = "be terse")
        }

        // Kept for writes that ARE authoritative by construction (a backup
        // import replacing every setting). That it clears what the update path
        // merges is the point of it rather than a bug, so it is pinned here
        // instead of being "fixed" later by a reader who assumes all writes
        // should merge.
        repo.saveAgentSettings(AgentSettings(maxSteps = 11))

        val after = stored()
        assertThat(after.useDefaultContext).isFalse()
        assertThat(after.defaultContext).isEmpty()
        assertThat(after.maxSteps).isEqualTo(11)
    }
}
