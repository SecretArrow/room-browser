package com.roombrowser.agent

import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.AgentGateway
import okhttp3.OkHttpClient

/**
 * Creates the correct [AgentGateway] for a saved provider:
 * OpenAI-compatible chat/completions or an `opencode serve` server.
 */
object AgentGateways {

    fun forProvider(
        callFactory: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        protocol: String
    ): AgentGateway =
        if (protocol == AgentProviderEntity.PROTOCOL_OPENCODE) {
            OpenCodeAgentGateway(callFactory, baseUrl, apiKey)
        } else {
            OkHttpAgentGateway(callFactory, baseUrl, apiKey)
        }

    fun forProvider(callFactory: OkHttpClient, provider: AgentProviderEntity, apiKey: String): AgentGateway =
        forProvider(callFactory, provider.baseUrl, apiKey, provider.protocol)
}
