package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.LocalAiTuning
import okhttp3.OkHttpClient

/**
 * Creates the correct [AgentGateway] for a saved provider:
 *  - OpenAI-compatible chat/completions,
 *  - an `opencode serve` server,
 *  - a native Ollama server (protocol "OLLAMA", /api/chat — the only
 *    protocol that consumes [LocalAiTuning]: num_ctx/num_gpu/num_thread +
 *    keep_alive are real /api/chat fields, so the Local AI tuning screen
 *    genuinely reaches the model instead of being decorative).
 *
 * [tuning] defaults to null so every pre-existing call site (model listing
 * in the editor, settings screens) stays source-compatible; only the live
 * agent-turn site in BrowserAgentController passes it.
 */
object AgentGateways {

    fun forProvider(
        callFactory: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        protocol: String,
        tuning: LocalAiTuning? = null
    ): AgentGateway = when (protocol) {
        AgentProviderEntity.PROTOCOL_OPENCODE -> OpenCodeAgentGateway(callFactory, baseUrl, apiKey)
        AgentProviderEntity.PROTOCOL_OLLAMA -> OllamaAgentGateway(callFactory, baseUrl, apiKey, tuning)
        else -> OkHttpAgentGateway(callFactory, baseUrl, apiKey)
    }

    fun forProvider(
        callFactory: OkHttpClient,
        provider: AgentProviderEntity,
        apiKey: String,
        tuning: LocalAiTuning? = null
    ): AgentGateway = forProvider(callFactory, provider.baseUrl, apiKey, provider.protocol, tuning)
}
