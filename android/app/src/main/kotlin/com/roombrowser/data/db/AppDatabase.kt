package com.roombrowser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room Browser metadata database.
 *
 * NOTE ON MULTI-PROCESS: the browser engine runs in the ':browser' process
 * while profile management runs in the main process. Both access this
 * database, so multi-instance invalidation is mandatory.
 *
 * Browser-engine storage (cookies, localStorage, IndexedDB, cache, service
 * workers) is NEVER stored in Room — it lives in the per-profile WebView
 * data directories (see PROFILE_ISOLATION.md).
 */
@Database(
    entities = [
        ProfileEntity::class,
        TabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        SitePermissionEntity::class,
        SiteSettingEntity::class,
        IpHistoryEntity::class,
        BlockEventEntity::class,
        AppStateEntity::class,
        CustomThemeEntity::class,
        AgentProviderEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun siteSettingsDao(): SiteSettingsDao
    abstract fun ipHistoryDao(): IpHistoryDao
    abstract fun statsDao(): StatsDao
    abstract fun appStateDao(): AppStateDao
    abstract fun themeDao(): ThemeDao
    abstract fun agentDao(): AgentDao

    companion object {
        const val NAME = "room-browser.db"

        /**
         * v1 → v2: adds the AI agent tables (providers / sessions /
         * messages). Pure additive CREATE TABLE + INDEX statements — no
         * existing table is touched, so the migration is lossless.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_providers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`base_url` TEXT NOT NULL, " +
                        "`api_key_enc` TEXT NOT NULL, " +
                        "`default_model` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`provider_id` INTEGER NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_sessions_profile_id` " +
                        "ON `agent_sessions` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`tool_name` TEXT, " +
                        "`tool_args` TEXT, " +
                        "`tool_result` TEXT, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_messages_session_id` " +
                        "ON `agent_messages` (`session_id`)"
                )
            }
        }

        /**
         * v2 → v3: adds the `protocol` column to agent_providers
         * (OPENAI = chat/completions, OPENCODE = `opencode serve`). Additive
         * ALTER TABLE with a default — existing rows stay OPENAI. Lossless.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `agent_providers` ADD COLUMN `protocol` TEXT NOT NULL DEFAULT 'OPENAI'"
                )
            }
        }

        /**
         * v3 → v4: per-profile theme system. Adds `profiles.theme_json`
         * (full RoomThemeSpec snapshot; "" = built-in default theme) and the
         * `themes` gallery table for user-saved custom themes. Purely
         * additive — no existing data is touched. Lossless.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `profiles` ADD COLUMN `theme_json` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `themes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`spec_json` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Adds the per-provider tool mode. Existing rows get the AUTO default,
         * which is what they were already doing implicitly — send `tools` and
         * read `tool_calls` back — so an upgrade changes no provider's
         * behaviour until the user picks a different mode.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `agent_providers` ADD COLUMN `tool_mode` TEXT NOT NULL DEFAULT 'AUTO'"
                )
            }
        }

        /**
         * Adds the User-Agent a download must present. Existing rows get the
         * empty string, which the engine reads as "no UA on record" and sends
         * no User-Agent header for — the same request they would have made
         * before this column existed, so an upgrade changes no behaviour.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `user_agent` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .enableMultiInstanceInvalidation()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
