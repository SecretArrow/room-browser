package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.PromptToolGateway
import com.roombrowser.domain.agent.RetryPolicy
import com.roombrowser.domain.agent.ToolMode
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
 *
 * [retry] follows the same rule and defaults to null, meaning "no policy
 * configured" — the provider is called exactly as before. Only the live
 * paths pass `AgentSettings.retryPolicy()`.
 */
object AgentGateways {

    fun forProvider(
        callFactory: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        protocol: String,
        tuning: LocalAiTuning? = null,
        appContext: android.content.Context? = null,
        toolMode: ToolMode = ToolMode.DEFAULT,
        retry: RetryPolicy? = null,
        /**
         * Called before each retry, with the failed attempt number and the
         * reason. The pause between attempts defaults to six seconds, so
         * without this a retry is indistinguishable from a freeze — the turn
         * site is expected to put it on the status line.
         */
        onRetry: (attempt: Int, reason: String) -> Unit = { _, _ -> }
    ): AgentGateway {
        val gateway = withToolMode(
            gateway = when (protocol) {
                AgentProviderEntity.PROTOCOL_OPENCODE -> OpenCodeAgentGateway(callFactory, baseUrl, apiKey)
                AgentProviderEntity.PROTOCOL_OLLAMA -> OllamaAgentGateway(callFactory, baseUrl, apiKey, tuning)
                AgentProviderEntity.PROTOCOL_LOCAL -> localGateway(appContext)
                AgentProviderEntity.PROTOCOL_ANTHROPIC -> AnthropicAgentGateway(callFactory, baseUrl, apiKey)
                else -> OkHttpAgentGateway(callFactory, baseUrl, apiKey)
            },
            protocol = protocol,
            toolMode = toolMode
        )
        // OUTERMOST on purpose: a retry is a second attempt at the whole
        // turn, tool-mode round trips included — not a resumed half-turn.
        // Null means "no policy configured", which is not the same as a
        // disabled policy: the decorator is skipped entirely.
        return if (retry != null) {
            RetryingAgentGateway(gateway, retry, onRetry = onRetry)
        } else {
            gateway
        }
    }

    fun forProvider(
        callFactory: OkHttpClient,
        provider: AgentProviderEntity,
        apiKey: String,
        tuning: LocalAiTuning? = null,
        appContext: android.content.Context? = null,
        retry: RetryPolicy? = null,
        onRetry: (attempt: Int, reason: String) -> Unit = { _, _ -> }
    ): AgentGateway = forProvider(
        callFactory = callFactory,
        baseUrl = provider.baseUrl,
        apiKey = apiKey,
        protocol = provider.protocol,
        tuning = tuning,
        appContext = appContext,
        toolMode = ToolMode.fromStored(provider.toolMode),
        retry = retry,
        onRetry = onRetry
    )

    /**
     * Applies the provider's tool mode, leaving the two cases that need
     * nothing alone:
     *
     *  - NATIVE is what every gateway already does, so wrapping would only add
     *    a layer between the loop and the wire;
     *  - LOCAL already speaks the text contract inside [LocalLlamaGateway],
     *    because the embedded engine has no `tools` channel at all. Wrapping it
     *    would parse the same reply twice — the inner gateway would turn the
     *    call into a `toolCalls` message, the outer would then read its empty
     *    content as an answer, and the action would be silently lost.
     *
     * Every other protocol takes the decorator, which is what makes tool
     * calling available to a provider that refuses the `tools` array.
     */
    private fun withToolMode(gateway: AgentGateway, protocol: String, toolMode: ToolMode): AgentGateway =
        when {
            protocol == AgentProviderEntity.PROTOCOL_LOCAL -> gateway
            toolMode == ToolMode.NATIVE -> gateway
            else -> PromptToolGateway(gateway, toolMode)
        }

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
