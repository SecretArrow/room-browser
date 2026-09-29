package com.roombrowser.domain.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * HTTPS-First fallback policy (the compatibility half of HTTPS upgrades).
 *
 * When Room Browser upgrades an http navigation to https and the secure
 * version turns out to be unavailable (connection refused / timeout / SSL
 * failure), the ORIGINAL http URL is retried once automatically instead of
 * showing a dead error page. This mirrors how modern "HTTPS-First" modes
 * behave and keeps http-only sites fully reachable while upgrades stay on
 * by default.
 *
 * Pure JVM — unit-testable without Android.
 */
object HttpsUpgradeFallbackPolicy {

    /**
     * WebView main-frame error codes that are worth an automatic http retry
     * after a failed upgrade. Deliberately conservative: codes that indicate
     * problems the http version would share (out of memory, too many
     * redirects, file errors, ...) are excluded.
     */
    fun isRecoverable(errorCode: Int): Boolean = when (errorCode) {
        // ERROR_UNKNOWN: generic connect failure observed on some stacks
        -1,
        // ERROR_CONNECT: socket connect failed (nothing listening on 443)
        -6,
        // ERROR_TIMEOUT: connection timed out
        -8,
        // ERROR_FAILED_SSL_HANDSHAKE: TLS transport failed on an endpoint WE
        // upgraded ourselves — typical for http-only hosts with the port kept
        // (localhost dev servers, router/IoT admin panels). Retrying the
        // original http URL once is exactly the HTTPS-First semantic of the
        // major browsers. CI-proven necessary: without it, an in-page link
        // click on a plain-http host dead-ends with no error surface.
        -11 -> true
        else -> false
    }

    /**
     * Registry of pending upgrades: upgraded URL -> original http URL.
     * Thread-safe: shouldInterceptRequest / onReceivedError arrive on
     * different threads.
     */
    class Registry {
        private val pending = ConcurrentHashMap<String, String>()

        /** Record that [upgradedUrl] replaced [originalUrl]; retry the
         *  original automatically if the upgraded load fails. */
        fun register(upgradedUrl: String, originalUrl: String) {
            pending[upgradedUrl.trim()] = originalUrl
        }

        /** Take (once) the original URL for a failed [url]; null when the
         *  failure was not preceded by one of our upgrades. */
        fun consume(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return pending.remove(url.trim())
        }

        /** Drop every pending upgrade (e.g. profile switch). */
        fun clear() = pending.clear()

        val size: Int get() = pending.size
    }
}
