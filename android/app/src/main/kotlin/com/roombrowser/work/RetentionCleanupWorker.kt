package com.roombrowser.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.roombrowser.RoomBrowserApp
import com.roombrowser.domain.model.NetworkRetention

/**
 * Daily maintenance worker: applies the IP-history retention policy and
 * prunes closed-tab tombstones older than 30 days. Fully local — no
 * network requests, no polling.
 */
class RetentionCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val app = applicationContext as? RoomBrowserApp
                ?: return Result.success()
            val graph = app.graph
            val global = graph.appState.globalSettingsSnapshot()
            val cutoff = global.retention.cutoff(System.currentTimeMillis())
            if (cutoff != null) {
                graph.browserRepo.purgeIpHistory(cutoff)
            } else {
                // FOREVER retention: nothing to purge
            }
            graph.browserRepo.purgeOldClosedTabs(days = 30)
            Result.success()
        }.getOrElse {
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
