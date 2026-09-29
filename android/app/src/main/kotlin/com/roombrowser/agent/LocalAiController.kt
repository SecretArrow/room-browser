package com.roombrowser.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.LocalAiBackup
import com.roombrowser.domain.agent.LocalAiBackupManifest
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.OllamaLibraryEntry
import com.roombrowser.domain.agent.OllamaLibraryHeuristics
import com.roombrowser.domain.agent.OllamaLibraryParser
import com.roombrowser.domain.agent.OllamaModelInfo
import com.roombrowser.domain.agent.OllamaModelPresets
import com.roombrowser.domain.agent.OllamaPullEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The state machine behind the Local AI (Ollama) screen — the default-process
 * counterpart of AgentSettingsController: no agent loop, no WebView, just
 * managing a local Ollama daemon (connection check, installed models, model
 * downloads with PAUSE/RESUME, tuning, import/export, "use in chat").
 *
 * Pause/resume design (honest about what a phone can do):
 *  - PAUSE cancels the pull coroutine → OllamaClient cancels the HTTP call.
 *  - RESUME simply re-issues the same `POST /api/pull`. Ollama stores
 *    completed blobs in its content-addressed store server-side and skips
 *    them on re-pull, so resume never re-downloads finished layers. There is
 *    no HTTP Range resume in Ollama's API — re-issue IS the resume primitive.
 *  - [DownloadState.receivedBytes] carries the high-water mark across pauses
 *    so the UI progress bar never jumps backwards after resuming.
 *
 * Export/import: the manifest carries the SETUP (host + tuning + model tag
 * list), NOT the multi-GB weights — an import re-pulls the models, which is
 * the honest "import" for 2GB gguf files.
 */
class LocalAiController(
    private val application: Application,
    libraryBaseUrl: String? = null
) {

    /** Connection banner state for the screen header. */
    sealed interface ConnectionState {
        object Idle : ConnectionState
        object Checking : ConnectionState
        data class Online(val version: String) : ConnectionState
        data class Offline(val reason: String) : ConnectionState
    }

    /** One row of the Downloads list, keyed by model tag in [downloads]. */
    data class DownloadState(
        val phase: Phase,
        val statusLine: String,
        val completedBytes: Long,
        val totalBytes: Long?,
        /** High-water mark of completedBytes — survives pause/resume. */
        val receivedBytes: Long,
        val error: String? = null
    )

    enum class Phase { STARTING, DOWNLOADING, VERIFYING, SUCCESS, PAUSED, FAILED }

    /**
     * State of the LIVE catalog refresh ("Find new models"): the curated
     * presets are frozen at release time, this fetches the public
     * ollama.com/library so newly published families surface too. Fails
     * SOFT — a Failed state keeps the curated tiers fully usable.
     */
    sealed interface CatalogState {
        /** Never refreshed this session — the UI shows the invite hint. */
        object Idle : CatalogState
        object Loading : CatalogState
        data class Ready(val entries: List<OllamaLibraryEntry>, val fetchedAtMs: Long) : CatalogState
        data class Failed(val reason: String) : CatalogState
    }

    private val graph = (application as RoomBrowserApp).graph
    private val repo = graph.agentRepo
    private val appState = graph.appState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Plain HTTP stack is fine here: this screen only manages a local/LAN
    // Ollama daemon over plain HTTP — no browsing, no DoH needed (same
    // rationale as AgentSettingsController; the agent's real turns keep the
    // DoH-configured client inside the ':browser' process).
    private val httpClient: OkHttpClient = OkHttpClient()

    /** Live ollama.com/library client — base URL injectable for e2e tests. */
    private val libraryClient = OllamaLibraryClient(
        httpClient,
        libraryBaseUrl ?: OllamaLibraryClient.DEFAULT_BASE
    )

    // ------------------------------------------------------------- UI state

    var tuning by mutableStateOf(LocalAiTuning())
        private set
    var connection by mutableStateOf<ConnectionState>(ConnectionState.Idle)
        private set
    var installed by mutableStateOf<List<OllamaModelInfo>>(emptyList())
        private set
    var downloads by mutableStateOf<Map<String, DownloadState>>(emptyMap())
        private set
    var catalog by mutableStateOf<CatalogState>(CatalogState.Idle)
        private set

    /** Active pull jobs per model tag (the cancellation handle for pause). */
    private val pullJobs = mutableMapOf<String, Job>()

    /** Tags whose cancellation was a USER pause (vs. scope shutdown). */
    private val pauseRequests = mutableSetOf<String>()

    // ------------------------------------------------------------- lifecycle

    fun start() {
        scope.launch {
            tuning = runCatching { appState.localAiTuningSnapshot() }.getOrDefault(LocalAiTuning())
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    // ------------------------------------------------------------- tuning

    /** Clamps, persists (best-effort) and applies a new tuning snapshot. */
    fun saveTuning(new: LocalAiTuning) {
        val clamped = new.clampToSanity()
        tuning = clamped
        scope.launch { runCatching { appState.saveLocalAiTuning(clamped) } }
    }

    // ------------------------------------------------------------- connection

    fun checkConnection() {
        scope.launch {
            connection = ConnectionState.Checking
            try {
                val version = OllamaClient(httpClient, tuning.host).version()
                connection = ConnectionState.Online("Ollama $version")
                refreshInstalled()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                connection = ConnectionState.Offline(t.message ?: "connection failed")
            }
        }
    }

    /**
     * Reloads the installed-model list WITHOUT touching the connection banner:
     * a silent refresh (e.g. right after a pull finished, or a delete) must
     * not flicker an Online header into Offline for one frame when the server
     * is briefly busy — [checkConnection] owns that state.
     */
    fun refreshInstalled() {
        scope.launch {
            try {
                installed = OllamaClient(httpClient, tuning.host).listModels()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Leave `installed` unchanged — see KDoc above.
            }
        }
    }

    // ------------------------------------------------------------- pull / pause / resume

    /**
     * Starts (or resumes) a model download. Idempotent: an already-active
     * pull for the tag is ignored. A PAUSED/FAILED row re-issues the pull —
     * Ollama skips its already-completed blobs server-side.
     */
    fun pull(tag: String) {
        val phase = downloads[tag]?.phase
        if (phase == Phase.STARTING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING) return
        if (pullJobs[tag]?.isActive == true) return
        val job = scope.launch { runPull(tag) }
        pullJobs[tag] = job
    }

    private suspend fun runPull(tag: String) {
        downloads = downloads + (tag to DownloadState(
            phase = Phase.STARTING,
            statusLine = "starting",
            completedBytes = 0L,
            totalBytes = null,
            receivedBytes = 0L
        ))
        try {
            OllamaClient(httpClient, tuning.host).pull(tag) { event ->
                // Hop to Main before mutating compose state: the NDJSON read
                // loop runs on Dispatchers.IO and importBackup can pull
                // several models in parallel — Main confinement keeps the
                // read-modify-write of `downloads` race-free and ordered.
                withContext(Dispatchers.Main) { applyPullEvent(tag, event) }
            }
            // Normal return == a terminal "success" event was seen.
            val last = downloads[tag] ?: DownloadState(Phase.STARTING, "", 0L, null, 0L)
            downloads = downloads + (tag to last.copy(phase = Phase.SUCCESS, statusLine = "installed"))
            pullJobs.remove(tag)
            refreshInstalled()
        } catch (c: CancellationException) {
            // Distinguish USER PAUSE from scope shutdown: a pause keeps the
            // row (phase PAUSED, progress preserved for resume); shutdown
            // (activity destroy) must propagate normally so the scope dies.
            if (tag in pauseRequests) {
                pauseRequests.remove(tag)
                pullJobs.remove(tag)
                val last = downloads[tag] ?: DownloadState(Phase.PAUSED, "", 0L, null, 0L)
                downloads = downloads + (tag to last.copy(
                    phase = Phase.PAUSED,
                    statusLine = "paused — resume continues from the last completed layer"
                ))
            } else {
                throw c
            }
        } catch (t: Throwable) {
            val last = downloads[tag] ?: DownloadState(Phase.FAILED, "", 0L, null, 0L)
            downloads = downloads + (tag to last.copy(
                phase = Phase.FAILED,
                statusLine = "failed",
                error = t.message ?: "download failed"
            ))
            pullJobs.remove(tag)
        }
    }

    private fun applyPullEvent(tag: String, event: OllamaPullEvent) {
        val prev = downloads[tag]
            ?: DownloadState(Phase.STARTING, "", 0L, null, 0L)
        val received = maxOf(prev.receivedBytes, event.completed ?: 0L)
        val phase = when {
            event.isTerminal -> Phase.SUCCESS
            event.isDownloading -> Phase.DOWNLOADING
            // Ollama's finishing statuses: "verifying sha256 digest" and
            // "writing manifest" — show them as the VERIFYING stage.
            event.status.contains("verifying", ignoreCase = true) ||
                event.status.contains("writing", ignoreCase = true) -> Phase.VERIFYING
            else -> Phase.DOWNLOADING
        }
        downloads = downloads + (tag to DownloadState(
            phase = phase,
            statusLine = progressLine(event),
            completedBytes = event.completed ?: prev.completedBytes,
            totalBytes = event.total ?: prev.totalBytes,
            receivedBytes = received,
            error = null
        ))
    }

    /** "sha256:abc… 12%" style line; falls back to the raw status text. */
    private fun progressLine(event: OllamaPullEvent): String {
        val total = event.total?.takeIf { it > 0 } ?: return event.status
        val completed = event.completed ?: 0L
        val percent = ((completed * 100L) / total).coerceIn(0L, 100L)
        val digest = event.digest?.takeIf { it.isNotBlank() }
            ?.let { if (it.length <= 10) it else it.take(10) + "…" }
        return "${digest ?: event.status} $percent%"
    }

    /** User-initiated pause: cancels the pull; the row stays for resume. */
    fun pause(tag: String) {
        val job = pullJobs[tag] ?: return
        if (!job.isActive) return
        pauseRequests.add(tag)
        job.cancel()
    }

    /**
     * Resume == pull: a fresh request that re-downloads only the layers the
     * server has not already stored (Ollama blob cache — see class KDoc).
     */
    fun resume(tag: String) = pull(tag)

    /** Full cancel: stops the job AND removes the row entirely. */
    fun cancelDownload(tag: String) {
        pullJobs.remove(tag)?.cancel()
        pauseRequests.remove(tag)
        downloads = downloads - tag
    }

    // ------------------------------------------------------------- catalog refresh

    /**
     * Fetches the live ollama.com/library (newest first) and moves [catalog]
     * through Loading → Ready/Failed. Idempotent while a refresh is running.
     * The curated presets stay untouched — discovery only ADDS families the
     * presets do not cover.
     */
    fun refreshCatalog() {
        if (catalog is CatalogState.Loading) return
        catalog = CatalogState.Loading
        scope.launch {
            try {
                val html = libraryClient.libraryHtml(sort = "newest")
                val entries = OllamaLibraryParser.parse(html)
                catalog = if (entries.isEmpty()) {
                    CatalogState.Failed("the Ollama library page returned no readable models")
                } else {
                    CatalogState.Ready(entries, System.currentTimeMillis())
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                catalog = CatalogState.Failed(t.message ?: "refresh failed")
            }
        }
    }

    /**
     * Live families the curated presets do NOT cover yet, newest-page order,
     * chat-able, with at least one phone-usable size badge (see
     * [OllamaLibraryHeuristics]). Reads [catalog] state so Compose
     * recomposes on every refresh.
     */
    fun discoveredPhoneEntries(): List<OllamaLibraryEntry> {
        val ready = catalog as? CatalogState.Ready ?: return emptyList()
        val knownFamilies = OllamaModelPresets.PRESETS
            .map { it.tag.substringBefore(':') }
            .toSet()
        return OllamaLibraryHeuristics.discoverPhoneModels(ready.entries, knownFamilies)
    }

    // ------------------------------------------------------------- delete

    /**
     * Uninstalls a model by name (the OllamaModelInfo `name` tag); the
     * optional [onDone] callback receives success. Model-name based (not
     * entity based) so it also works for tags that failed mid-download.
     */
    fun deleteModel(name: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                OllamaClient(httpClient, tuning.host).delete(name)
                refreshInstalled()
                onDone(true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                onDone(false)
            }
        }
    }

    // ------------------------------------------------------------- use in chat

    /**
     * Points the browsing agent at this local server + [modelName]:
     * finds the OLLAMA-protocol provider whose base URL equals the tuned
     * host (normalized compare), creating "Local Ollama" on first use, then
     * writes the agent default provider/model.
     *
     * Wrapped in [NonCancellable]: persistence primitive — finishing the
     * activity mid-tap must never lose the selection (same CI-proven
     * rationale as AgentProviderStore.save).
     */
    fun useInChat(modelName: String, onDone: (ok: Boolean, message: String) -> Unit) {
        scope.launch {
            try {
                val providerName = withContext(NonCancellable) {
                    val normalizedHost = OllamaClient.normalizeHost(tuning.host)
                    val existing = repo.providers().firstOrNull { p ->
                        p.protocol == AgentProviderEntity.PROTOCOL_OLLAMA &&
                            (OllamaClient.normalizeHost(p.baseUrl) == normalizedHost || p.baseUrl == tuning.host)
                    }
                    val provider = existing ?: AgentProviderStore.save(
                        repo,
                        id = null,
                        name = "Local Ollama",
                        baseUrl = tuning.host,
                        apiKey = "",
                        defaultModel = modelName,
                        protocol = AgentProviderEntity.PROTOCOL_OLLAMA
                    ).getOrThrow()
                    val snapshot = appState.agentSettingsSnapshot()
                    appState.saveAgentSettings(
                        snapshot.copy(defaultProviderId = provider.id, defaultModel = modelName)
                    )
                    existing?.name ?: "Local Ollama"
                }
                onDone(true, "Agent set to $providerName · $modelName")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onDone(false, t.message ?: "failed to select the model")
            }
        }
    }

    // ------------------------------------------------------------- backup

    /** Builds the shareable SETUP manifest (host + tuning + model tags). */
    fun buildExportManifest(): LocalAiBackupManifest = LocalAiBackupManifest(
        host = tuning.host,
        tuning = tuning,
        models = installed.map { it.name },
        exportedAtEpochMs = System.currentTimeMillis()
    )

    /**
     * Imports a manifest: restores the tuning/host (non-cancellable write)
     * and re-pulls every model that is not installed yet. Returns the number
     * of queued pulls via [onDone], or -1 when the text is not a manifest.
     * (The multi-GB weights are never inside the file — see class KDoc.)
     */
    fun importBackup(text: String, onDone: (queued: Int) -> Unit = {}) {
        val manifest = LocalAiBackup.decode(text)
        if (manifest == null) {
            onDone(-1)
            return
        }
        scope.launch {
            try {
                val restored = manifest.tuning.copy(host = manifest.host)
                withContext(NonCancellable) {
                    runCatching { appState.saveLocalAiTuning(restored) }
                }
                tuning = restored
                // Note: against a stale/empty `installed` list this queues
                // models the server already has — their pull resolves as a
                // quick verify + "success", which is harmless and honest.
                val have = installed.map { it.name }.toSet()
                val queued = manifest.models.filter { it.isNotBlank() && it !in have }
                queued.forEach { pull(it) }
                onDone(queued.size)
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }
}
