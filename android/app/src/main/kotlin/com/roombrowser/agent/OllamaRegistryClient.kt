package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.OllamaRegistry
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
 * Reads the PUBLIC Ollama registry (`registry.ollama.ai`) — the source
 * `ollama pull` itself downloads from — so the catalog can feed the built-in
 * on-device engine WITHOUT a local Ollama server.
 *
 * This is deliberately a THIRD client, separate from both others:
 *
 *  - [OllamaClient] talks to the user's own daemon (Termux / LAN PC) over its
 *    native admin API — it needs a server, which a phone has no reason to run;
 *  - [OllamaLibraryClient] scrapes the public WEBSITE for the "what's new"
 *    listing;
 *  - THIS client performs the two registry reads that resolve a tag to real
 *    bytes: the manifest ([resolve]), and then the blob, which is handed to
 *    [com.roombrowser.localai.store.OnDeviceDownloadController] as an ordinary
 *    resumable HTTPS download.
 *
 * Only ever READS public metadata and public model bytes — no keys, no
 * writes, nothing user-specific. The base URL is injectable (default
 * [OllamaRegistry.HOST]) so e2e tests can drive the whole flow against a
 * MockWebServer, the same pattern [OllamaLibraryClient] established.
 */
class OllamaRegistryClient(
    client: OkHttpClient,
    baseUrl: String = OllamaRegistry.HOST
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
            "Ollama registry base URL must start with http:// or https://"
        }
    }

    /** A resolved catalog tag: the real GGUF blob, ready to download. */
    data class RegistryDownload(
        /** Absolute HTTPS URL of the model blob (feeding the download controller). */
        val url: String,
        /** Local file name the blob should land as, e.g. `qwen2.5-0.5b.gguf`. */
        val fileName: String,
        /** Exact byte count the registry will serve (0 when the manifest omits it). */
        val sizeBytes: Long,
        /** `sha256:` digest — known BEFORE the download, so the bytes are verifiable. */
        val digest: String
    )

    /**
     * Resolves [tag] (e.g. `"qwen2.5:0.5b"`) to its downloadable GGUF blob.
     *
     * Throws [AgentHttpException] when the registry refuses (a 404 means the
     * tag does not exist — worth surfacing verbatim), and returns `null` when
     * the manifest is readable but carries no model layer, i.e. there is
     * nothing to install.
     */
    suspend fun resolve(tag: String): RegistryDownload? {
        val manifest = fetchManifest(tag)
        val blob = OllamaRegistry.modelBlob(manifest) ?: return null
        return RegistryDownload(
            url = OllamaRegistry.blobUrl(tag, blob.digest, host = base),
            fileName = OllamaRegistry.fileName(tag),
            sizeBytes = blob.sizeBytes,
            digest = blob.digest
        )
    }

    /** GETs the manifest body for [tag] — parsing belongs to [OllamaRegistry]. */
    suspend fun fetchManifest(tag: String): String {
        val request = Request.Builder()
            .url(OllamaRegistry.manifestUrl(tag, host = base))
            .get()
            .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = execute(request)
        try {
            if (!response.isSuccessful) {
                throw AgentHttpException(
                    response.code,
                    "registry manifest HTTP ${response.code} for $tag"
                )
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
                    if (!continuation.isActive) return
                    if (call.isCanceled()) {
                        continuation.resumeWithException(
                            CancellationException("registry request cancelled")
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

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    private companion object {
        private const val USER_AGENT = "RoomBrowser-ModelDownload/1.0"
    }
}
