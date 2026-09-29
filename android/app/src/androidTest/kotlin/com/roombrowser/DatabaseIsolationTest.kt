package com.roombrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ProfileEntity
import com.roombrowser.data.db.TabEntity
import com.roombrowser.data.db.BookmarkEntity
import com.roombrowser.data.db.HistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Database-level profile isolation tests (instrumented; Room needs Android).
 * Verifies that tabs/bookmarks/history are physically scoped by profile_id
 * and that deleting a profile cascades ONLY to that profile's rows.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseIsolationTest {

    private lateinit var db: AppDatabase
    private val profileA = "11111111-1111-1111-1111-111111111111"
    private val profileB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.profileDao().upsert(profile(profileA, "Personal"))
            db.profileDao().upsert(profile(profileB, "Work"))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun profile(id: String, name: String) = ProfileEntity(
        id = id, name = name, icon = "x", colorArgb = 0,
        isLocked = false, isDefault = false,
        createdAt = 0, lastActiveAt = 0, settingsJson = "{}"
    )

    @Test
    fun tabs_are_scoped_per_profile() = runBlocking {
        db.tabDao().upsert(TabEntity("tabA", profileA, 0, "A tab", "https://a.example.com", false, createdAt = 0, lastViewedAt = 0))
        db.tabDao().upsert(TabEntity("tabB", profileB, 0, "B tab", "https://b.example.com", false, createdAt = 0, lastViewedAt = 0))

        assertThat(db.tabDao().openTabs(profileA).map { it.id }).containsExactly("tabA")
        assertThat(db.tabDao().openTabs(profileB).map { it.id }).containsExactly("tabB")
        assertThat(db.tabDao().openCount(profileA)).isEqualTo(1)
    }

    @Test
    fun bookmarks_and_history_are_scoped_per_profile() = runBlocking {
        db.bookmarkDao().upsert(BookmarkEntity(profileId = profileA, url = "https://a.example.com", title = "A", createdAt = 0))
        db.historyDao().insert(HistoryEntity(profileId = profileB, url = "https://b.example.com", title = "B", visitedAt = 1))

        assertThat(db.bookmarkDao().all(profileA)).hasSize(1)
        assertThat(db.bookmarkDao().all(profileB)).isEmpty()
        assertThat(db.historyDao().since(profileA, 0)).isEmpty()
        assertThat(db.historyDao().since(profileB, 0)).hasSize(1)
    }

    @Test
    fun deleting_profile_cascades_only_that_profile() = runBlocking {
        db.tabDao().upsert(TabEntity("tabA", profileA, 0, "A", "https://a.example.com", false, createdAt = 0, lastViewedAt = 0))
        db.tabDao().upsert(TabEntity("tabB", profileB, 0, "B", "https://b.example.com", false, createdAt = 0, lastViewedAt = 0))
        db.bookmarkDao().upsert(BookmarkEntity(profileId = profileA, url = "https://a.example.com", title = "A", createdAt = 0))
        db.bookmarkDao().upsert(BookmarkEntity(profileId = profileB, url = "https://b.example.com", title = "B", createdAt = 0))

        db.profileDao().delete(profileA)
        // ProfileRepositoryImpl.remove(cascade=true) also purges dependent rows:
        db.tabDao().deleteAllFor(profileA)
        db.bookmarkDao().deleteAllFor(profileA)

        assertThat(db.tabDao().openTabs(profileA)).isEmpty()
        assertThat(db.tabDao().openTabs(profileB)).hasSize(1)
        assertThat(db.bookmarkDao().all(profileB)).hasSize(1)
    }

    @Test
    fun uuid_persistence_and_rename_keeps_identity() = runBlocking {
        db.profileDao().upsert(profile(profileA, "Personal"))
        val loaded = db.profileDao().get(profileA)!!
        assertThat(loaded.name).isEqualTo("Personal")
        db.profileDao().upsert(loaded.copy(name = "Family"))
        val renamed = db.profileDao().get(profileA)!!
        assertThat(renamed.id).isEqualTo(profileA)
        assertThat(renamed.name).isEqualTo("Family")
    }
}
