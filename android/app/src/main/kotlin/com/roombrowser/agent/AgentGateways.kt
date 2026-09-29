package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.LocalAiTuning
import okhttp3.OkHttpClient

/**
 * Creates the correct [AgentGateway] for a saved provider:
 *  - OpenAI-compatible chat/completions,
 *  - the Anthropic Messages API (protocol "ANTHROPIC", POST /messages — used
 *    by aggregators like AgentRouter; [AnthropicAgentGateway] probes once and
 *    falls back to the OpenAI shape when the endpoint is not Anthropic-shaped),
 *  - an `opencode serve` server,
 *  - a native Ollama server (protocol "OLLAMA", /api/chat — the only
 *    protocol that consumes [LocalAiTuning]: num_ctx/num_gpu/num_thread +
 *    keep_alive are real /api/chat fields, so the Local AI tuning screen
 *    genuinely reaches the model instead of being decorative),
 *  - the EMBEDDED on-device llama.cpp engine (protocol "LOCAL" — no server,
 *    no network; [LocalLlamaGateway] resolves `.gguf` models from the
 *    on-device store's directory).
 *
 * [tuning] defaults to null so every pre-existing call site (model listing
 * in the editor, settings screens) stays source-compatible; only the live
 * agent-turn site in BrowserAgentController passes it.
 *
 * [appContext] likewise defaults to null: only the LIVE agent-turn path
 * (BrowserAgentController) and the model-listing paths that need disk
 * access pass it — the LOCAL gateway uses it to attach the on-device
 * models directory (`{noBackupFilesDir}/on_device_models`, the same
 * directory OnDeviceModelStore manages). Every pre-existing call site
 * compiles unchanged.
 */
object AgentGateways {

    fun forProvider(
        callFactory: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        protocol: String,
        tuning: LocalAiTuning? = null,
        appContext: android.content.Context? = null
    ): AgentGateway = when (protocol) {
        AgentProviderEntity.PROTOCOL_OPENCODE -> OpenCodeAgentGateway(callFactory, baseUrl, apiKey)
        AgentProviderEntity.PROTOCOL_OLLAMA -> OllamaAgentGateway(callFactory, baseUrl, apiKey, tuning)
        AgentProviderEntity.PROTOCOL_LOCAL -> localGateway(appContext)
        AgentProviderEntity.PROTOCOL_ANTHROPIC -> AnthropicAgentGateway(callFactory, baseUrl, apiKey)
        else -> OkHttpAgentGateway(callFactory, baseUrl, apiKey)
    }

    fun forProvider(
        callFactory: OkHttpClient,
        provider: AgentProviderEntity,
        apiKey: String,
        tuning: LocalAiTuning? = null,
        appContext: android.content.Context? = null
    ): AgentGateway = forProvider(callFactory, provider.baseUrl, apiKey, provider.protocol, tuning, appContext)

    /**
     * Builds the on-device gateway with its models directory attached.
     * Without a context (unit tests / legacy call sites) the directory stays
     * null and every operation fails with an honest error instead of
     * silently doing nothing.
     */
    private fun localGateway(appContext: android.content.Context?): LocalLlamaGateway =
        LocalLlamaGateway().also { gateway ->
            appContext?.let { context ->
                // Same directory OnDeviceModelStore uses — mirror, never own.
                val dir = java.io.File(context.noBackupFilesDir, "on_device_models")
                runCatching { dir.mkdirs() }
                gateway.modelsDirectory = dir
            }
        }
}
