package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches the PUBLIC ollama.com/library listing (HTML) for the catalog
 * refresh — deliberately a SEPARATE client from [OllamaClient]:
 *
 *  - [OllamaClient] talks to the user's own Ollama daemon (Termux / LAN PC)
 *    over its native admin API — plain HTTP on the local network;
 *  - THIS client talks to the public website over HTTPS, with a browser-ish
 *    User-Agent (the site serves a stripped page to bare HTTP clients), and
 *    only ever READS a listing — never sends keys, never mutates anything.
 *
 * The base URL is injectable (default [DEFAULT_BASE]) so e2e tests can point
 * it at a MockWebServer and drive discovery deterministically — the same
 * pattern the agent tests use for provider base URLs.
 *
 * Timeouts: connect 20 s, read 60 s (the listing is a single ~1 MB document),
 * no whole-call cap (mobile networks can be slow, and the caller's coroutine
 * cancellation aborts the HTTP call mid-flight via [execute]).
 */
class OllamaLibraryClient(
    client: OkHttpClient,
    baseUrl: String
) {

    private val callFactory: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val base: String = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Ollama library base URL must start with http:// or https://"
        }
    }

    /**
     * GET `/library` (default popularity order) or `/library?sort=newest`
     * ([sort] = "newest" surfaces the newest families first — the intent of
     * the "Find new models" button). Returns the raw HTML body; parsing
     * belongs to the domain layer.
     */
    suspend fun libraryHtml(sort: String? = "newest"): String {
        val url = if (sort.isNullOrBlank()) "$base/library" else "$base/library?sort=$sort"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/html")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = execute(request)
        try {
            if (!response.isSuccessful) {
                throw AgentHttpException(response.code, "ollama.com/library HTTP ${response.code}")
            }
            return withContext(Dispatchers.IO) { response.body?.string() ?: "" }
        } finally {
            runCatching { response.close() }
        }
    }

    // -------------------------------------------------------------- utils

    /** Enqueues the call and suspends; cancelling the coroutine cancels HTTP. */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        if (call.isCanceled()) {
                            continuation.resumeWithException(
                                CancellationException("ollama library request cancelled")
                            )
                        } else {
                            continuation.resumeWithException(
                                AgentHttpException(
                                    -1,
                                    "connection failed: ${e.message ?: e.javaClass.simpleName}"
                                )
                            )
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    companion object {
        const val DEFAULT_BASE = "https://ollama.com"

        /** Browser-ish UA: the public site serves a degraded page to bare clients. */
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) RoomBrowser/1.0"
    }
}
