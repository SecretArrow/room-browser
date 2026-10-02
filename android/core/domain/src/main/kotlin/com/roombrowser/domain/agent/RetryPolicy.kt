package com.roombrowser.domain.agent

/**
 * One selectable HTTP status in the retry list.
 *
 * [transient] is the app's judgement, not a fact about the code: a transient
 * status describes a condition that can plausibly be gone a second later
 * (a rate limit, a gateway with no upstream, a timeout), so retrying it is
 * the right default. A permanent one describes a request the server has
 * already judged — the same bytes will be judged the same way — so retrying
 * it only spends the user's quota. Both groups are offered because the user
 * asked for the full list; only the first is pre-ticked.
 */
data class RetryStatusCode(
    val code: Int,
    val reason: String,
    val transient: Boolean
)

/**
 * Every status the retry setting can be told to act on.
 *
 * Ordered by code inside each group so the settings screen reads like a
 * status table rather than an opinion. The 5xx additions past 504 are the
 * Cloudflare family (520-524), which a provider behind Cloudflare returns
 * for an origin that is down or slow — exactly the case a retry fixes.
 */
object RetryCodes {

    val TRANSIENT: List<RetryStatusCode> = listOf(
        RetryStatusCode(408, "Request Timeout", true),
        RetryStatusCode(425, "Too Early", true),
        RetryStatusCode(429, "Too Many Requests", true),
        RetryStatusCode(500, "Internal Server Error", true),
        RetryStatusCode(502, "Bad Gateway", true),
        RetryStatusCode(503, "Service Unavailable", true),
        RetryStatusCode(504, "Gateway Timeout", true),
        RetryStatusCode(507, "Insufficient Storage", true),
        RetryStatusCode(509, "Bandwidth Limit Exceeded", true),
        RetryStatusCode(520, "Web Server Returned an Unknown Error", true),
        RetryStatusCode(521, "Web Server Is Down", true),
        RetryStatusCode(522, "Connection Timed Out", true),
        RetryStatusCode(523, "Origin Is Unreachable", true),
        RetryStatusCode(524, "A Timeout Occurred", true)
    )

    val PERMANENT: List<RetryStatusCode> = listOf(
        RetryStatusCode(400, "Bad Request", false),
        RetryStatusCode(401, "Unauthorized", false),
        RetryStatusCode(402, "Payment Required", false),
        RetryStatusCode(403, "Forbidden", false),
        RetryStatusCode(404, "Not Found", false),
        RetryStatusCode(405, "Method Not Allowed", false),
        RetryStatusCode(409, "Conflict", false),
        RetryStatusCode(413, "Payload Too Large", false),
        RetryStatusCode(415, "Unsupported Media Type", false),
        RetryStatusCode(422, "Unprocessable Entity", false)
    )

    val ALL: List<RetryStatusCode> = TRANSIENT + PERMANENT

    /** The reason phrase for [code], or null when it is not in the catalog. */
    fun reason(code: Int): String? = ALL.firstOrNull { it.code == code }?.reason
}

/**
 * When to send a failed provider request again, and how many times.
 *
 * Attempts are bounded and spaced by a doubling delay, so a provider that is
 * genuinely down is abandoned in seconds rather than hammered — this is a
 * courtesy to the provider and a bound on how long the user watches a
 * spinner, not an optimisation.
 *
 * A failure with NO HTTP status (the request never got a reply: DNS, TLS,
 * connection reset, or a stream that died mid-flight) has no code to match
 * against, so it is governed by [retryConnectionFailures] instead of by
 * [statusCodes]. The gateways report both such cases as code `-1`, which is
 * why it is not offered as a checkbox in the catalog — it is not a status.
 */
data class RetryPolicy(
    val enabled: Boolean = false,
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
    val statusCodes: Set<Int> = DEFAULT_STATUS_CODES,
    val retryConnectionFailures: Boolean = true
) {

    /** Attempts actually made, clamped to what the UI and the loop can honour. */
    val attempts: Int get() = maxAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)

    /** A response arrived carrying [code], and that code is ticked. */
    fun retriesStatus(code: Int): Boolean = enabled && code in statusCodes

    /** No response arrived at all, and the user allows retrying those. */
    fun retriesConnectionFailure(): Boolean = enabled && retryConnectionFailures

    companion object {
        /** One attempt means "no retry"; the switch is what turns the feature on. */
        const val MIN_ATTEMPTS = 1
        const val MAX_ATTEMPTS = 10
        const val DEFAULT_ATTEMPTS = 3

        /**
         * Pre-ticked: the whole transient group. Deliberately excludes the
         * permanent group, including 400 — a malformed request retried is
         * still malformed, so it costs three round trips to learn nothing.
         * It stays selectable for providers that misuse the code.
         */
        val DEFAULT_STATUS_CODES: Set<Int> = RetryCodes.TRANSIENT.map { it.code }.toSet()
    }
}
