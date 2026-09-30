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
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadEngine.ACTION) return
        val id = intent.getLongExtra("id", -1L)
        val action = intent.getStringExtra("action")
        if (id <= 0L || action == null) return
        // Null when no engine is alive — the process was restarted and nothing
        // is transferring, so the notification is a leftover with nothing
        // behind it. Doing nothing is the honest outcome; the row in the
        // Downloads screen is where the user can retry it.
        val engine = DownloadEngine.current ?: return
        when (action) {
            "pause" -> engine.pause(id)
            "resume" -> engine.resume(id)
            "cancel" -> engine.cancel(id)
            "retry" -> engine.retry(id)
            "open" -> engine.open(id)
        }
    }
}
