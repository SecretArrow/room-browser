package com.roombrowser.domain.engine

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
        /**
         * Pending upgrades, oldest first — and BOUNDED, which is the point.
         *
         * Only a FAILED load takes its entry back out ([consume]), so every
         * SUCCESSFUL upgrade used to leave one behind forever: one registry
         * serves a whole client for the life of the process, [clear] is a
         * profile-switch courtesy that nothing on the hot path calls, and a
         * long browsing session therefore grew this map by two URL strings per
         * upgraded request — a slow leak with no upper bound at all.
         *
         * A LinkedHashMap (insertion-ordered, NOT access-ordered) instead of
         * the ConcurrentHashMap that was here: eviction has to drop the oldest
         * REGISTRATION, since that is the one whose navigation is long since
         * settled, and an access-ordered map would keep an entry alive merely
         * for having been looked at. Losing it costs at worst one automatic
         * http retry that nobody was going to ask for any more.
         *
         * Not concurrent, so a plain monitor guards it — see the class doc for
         * why that is needed at all. Uncontended either way: these are three
         * map calls on the far side of a WebView callback.
         */
        private val pending = LinkedHashMap<String, String>()

        /** Record that [upgradedUrl] replaced [originalUrl]; retry the
         *  original automatically if the upgraded load fails. */
        fun register(upgradedUrl: String, originalUrl: String) {
            synchronized(pending) {
                pending[upgradedUrl.trim()] = originalUrl
                // Drop-oldest above the cap. A loop, not a single remove: the
                // cap is also enforced for a map that somehow arrived over it.
                while (pending.size > MAX_PENDING) {
                    val oldest = pending.keys.firstOrNull() ?: break
                    pending.remove(oldest)
                }
            }
        }

        /** Take (once) the original URL for a failed [url]; null when the
         *  failure was not preceded by one of our upgrades. */
        fun consume(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return synchronized(pending) { pending.remove(url.trim()) }
        }

        /** Drop every pending upgrade (e.g. profile switch). */
        fun clear() = synchronized(pending) { pending.clear() }

        val size: Int get() = synchronized(pending) { pending.size }

        private companion object {
            /**
             * Hard cap on remembered upgrades. Orders of magnitude more than
             * the handful of main-frame loads that can be in flight at once,
             * small enough that the worst case is a few kB of URL strings.
             */
            const val MAX_PENDING = 64
        }
    }
}
