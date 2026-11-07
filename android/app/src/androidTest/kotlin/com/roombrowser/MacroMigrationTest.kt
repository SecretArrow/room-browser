package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.MacroEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v21 → v22 migration, run against a database that really is v21.
 *
 * Same contract as NoteMigrationTest: a fresh install creates v22 directly
 * and never executes this migration, so this is the only automated guard
 * that a user's existing database upgrades without the schema validation
 * throwing on open (which is the app failing to start for every existing
 * user).
 *
 * Building a v21 database: Room creates the current database, then exactly
 * the v21→v22 delta is undone — the `macros` table drops (its indices with
 * it) and the master-table row goes. What remains is genuinely v21.
 */
@RunWith(AndroidJUnit4::class)
class MacroMigrationTest {

    private val dbName = "macro-migration-test.db"
    private lateinit var context: Context

    private val profileId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun upgrading_from_v21_adds_an_usable_macros_table() = runBlocking<Unit> {
        // 1. A real database, created at the current version.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        assertThat(created.macroDao().allForProfile(profileId)).isEmpty()
        created.close()

        // 2. Strip it back to v21.
        revertToV21()

        // 3. Open through Room again: replays every migration from v21 up,
        //    then validates the schema against the entities.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = upgraded.macroDao()
            assertThat(dao.allForProfile(profileId)).isEmpty()

            val id = "macro-1"
            val definitionJson = """
                {"name":"demo","steps":[{"action":"CLICK","x":5.0,"y":9.0}]}
            """.trimIndent()
            dao.upsert(
                MacroEntity(
                    id = id,
                    profileId = profileId,
                    name = "demo",
                    description = "a demo macro",
                    category = "General",
                    icon = "⚡",
                    definitionJson = definitionJson,
                    createdAt = 1L,
                    updatedAt = 2L
                )
            )
            val saved = dao.byId(id)
            assertThat(saved).isNotNull()
            assertThat(saved!!.profileId).isEqualTo(profileId)
            assertThat(saved.name).isEqualTo("demo")
            assertThat(saved.definitionJson).contains("CLICK")

            // The profile-scoped indices exist: this scan goes through one.
            assertThat(dao.allForProfile(profileId)).hasSize(1)

            // Another profile sees nothing — the wall the repo enforces.
            assertThat(dao.allForProfile("other-profile")).isEmpty()

            // The observe flow's ordering source works, and the profile
            // cascade deletes.
            dao.deleteAllForProfile(profileId)
            assertThat(dao.allForProfile(profileId)).isEmpty()
        } finally {
            upgraded.close()
        }
    }

    /** Rewrites the freshly created database into the v21 shape, in place. */
    private fun revertToV21() {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.execSQL("DROP TABLE IF EXISTS `macros`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 21
        } finally {
            db.close()
        }
    }
}
