package com.roombrowser.engine.webview

import android.webkit.WebView

/**
 * The one place in the tree that turns WebView remote debugging on or off.
 *
 * `WebView.setWebContentsDebuggingEnabled` is PROCESS-WIDE STATIC -- it decides
 * for every WebView in the app whether the Chromium DevTools socket exists, so
 * a second caller would race the first over a global nothing can scope. It is
 * enabled only while a protocol session is open and released when the last one
 * closes, so the resting state of a build that never opens one exposes no
 * inspector to a same-UID peer or adb.
 *
 * Safe to call from any thread; counts holders so two sessions do not fight.
 */
internal object ChromiumDevTools {

    private val lock = Any()
    private var holders = 0

    /** Enable, idempotently. Every accepted call needs a matching [disable]. */
    fun enable(): Boolean = synchronized(lock) {
        if (holders > 0) {
            holders++
            return@synchronized true
        }
        val accepted = runCatching { WebView.setWebContentsDebuggingEnabled(true) }.isSuccess
        if (accepted) holders = 1
        accepted
    }

    /** Release one hold; turns debugging off when the last one goes. Idempotent. */
    fun disable() {
        synchronized(lock) {
            if (holders == 0) return
            holders--
            if (holders == 0) runCatching { WebView.setWebContentsDebuggingEnabled(false) }
        }
    }
}
