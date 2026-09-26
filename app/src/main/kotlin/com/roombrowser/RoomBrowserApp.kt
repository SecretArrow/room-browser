package com.roombrowser

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.roombrowser.di.AppGraph
import com.roombrowser.work.RetentionCleanupWorker
import java.util.concurrent.TimeUnit

class RoomBrowserApp : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        scheduleRetentionCleanup()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * Battery-friendly daily cleanup: purge expired IP-history records and
     * stale closed-tab tombstones. No network, no polling (spec section 50).
     */
    private fun scheduleRetentionCleanup() {
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "retention-cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionCleanupWorker>(1, TimeUnit.DAYS)
                    .build()
            )
        }
    }
}
