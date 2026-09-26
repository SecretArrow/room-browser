package com.roombrowser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        AppStateEntity::class
    ],
    version = 1,
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

    companion object {
        const val NAME = "room-browser.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .enableMultiInstanceInvalidation()
                .build()
    }
}
