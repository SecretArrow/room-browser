package com.roombrowser.browser.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Carries the download notification's buttons back to the engine.
 *
 * These buttons — Pause, Resume, Cancel, Retry, Open — were broadcast to
 * `com.roombrowser.DOWNLOAD_ACTION` and nothing was listening, so every one of
 * them was inert: the notification appeared, the user tapped Pause, and the
 * download carried on. A broadcast receiver is the only way to reach a
 * notification action, and the engine is the only thing that can act on one.
 *
 * The receiver is declared with `process=":browser"` for the same reason:
 * [DownloadEngine.current] is per-process state that only the engine process
 * ever assigns, so a receiver in the default process would find it null every
 * single time.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadEngine.ACTION) return
        val id = intent.getLongExtra("id", -1L)
        val action = intent.getStringExtra("action")
        if (id <= 0L || action == null) return
        // Null when no engine is alive — the process was killed (low memory,
        // Doze, or a profile switch) while a transfer was running. The progress
        // notification is setOngoing(true), so it is still on screen and the
        // user CANNOT swipe it away; leaving it there with dead buttons is the
        // one outcome worse than doing nothing. Clear it and let the row in the
        // Downloads screen be where the transfer is resumed.
        val engine = DownloadEngine.current ?: run {
            DownloadEngine.dismissOrphanedNotification(context, id)
            return
        }
        when (action) {
            "pause" -> engine.pause(id)
            "resume" -> engine.resume(id)
            "cancel" -> engine.cancel(id)
            "retry" -> engine.retry(id)
            "open" -> engine.open(id)
        }
    }
}
