package com.roombrowser.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.AgentGateways
import com.roombrowser.agent.KeyStoreCrypto
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.assistant.AskKind
import com.roombrowser.domain.assistant.PageAssistantPrompts
import com.roombrowser.domain.assistant.PageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * "Ask this page" — a one-shot, page-grounded AI turn (summarize / key
 * points / plain-language explain / translate / free-form question).
 *
 * It rides the SAME provider infrastructure as the Room Agent — the active
 * provider from the agent settings (any OpenAI-compatible server, a native
 * Ollama server, or the embedded on-device engine) with the Local AI tuning
 * — so a model the user already configured just works here, and "no
 * provider" is an HONEST state that points at setup, never a silent guess.
 *
 * One question = one turn: no conversation memory on purpose. The page text
 * is captured fresh per ask (the sheet may sit open while the page loads),
 * chunked by [PageAssistantPrompts] to fit the model's context window, and
 * the answer is judged against the page — the system prompt forbids filling
 * gaps from outside knowledge.
 */
class PageAssistantController(application: Application, httpClient: OkHttpClient) {

    private val graph = (application as RoomBrowserApp).graph
    private val repo = graph.agentRepo
    private val appState = graph.appState
    private val appContext: android.content.Context = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callFactory: OkHttpClient = httpClient

    /** What the sheet renders. */
    sealed class UiState {
        object Idle : UiState()
        data class Thinking(val label: String) : UiState()
        data class Answer(val text: String) : UiState()
        data class Failed(val message: String) : UiState()
        object NoProvider : UiState()
    }

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set
    var providers by mutableStateOf<List<AgentProviderEntity>>(emptyList())
        private set
    var settings by mutableStateOf(AgentSettings())
        private set

    /** Re-read on every sheet open: Room's cross-process ping can lag. The
     *  state is resolved HERE so an unconfigured setup shows its honest
     *  "no provider" message the moment the sheet opens, not after a tap. */
    fun refresh() {
        scope.launch {
            runCatching {
                providers = repo.providers()
                settings = appState.agentSettingsSnapshot()
                if (state !is UiState.Thinking) {
                    state = if (activeProvider() == null) UiState.NoProvider else UiState.Idle
                }
            }
        }
    }

    /** Closing the sheet drops the answer — the next open starts honest. */
    fun reset() {
        if (state !is UiState.Thinking) state = UiState.Idle
    }

    private fun activeProvider(): AgentProviderEntity? =
        providers.firstOrNull { it.id == settings.defaultProviderId }
            ?: providers.firstOrNull()

    /**
     * Blank API key = local server (no secret); otherwise the stored key
     * decrypts through the same KeyStore the agent uses.
     */
    private fun apiKeyFor(provider: AgentProviderEntity): String {
        if (provider.apiKeyEnc.isBlank()) return ""
        return runCatching { KeyStoreCrypto.decrypt(provider.apiKeyEnc) }.getOrNull() ?: ""
    }

    /**
     * The model this ask rides: the session's explicit pick, else the
     * provider's own default, else the provider's first listed model. A
     * provider that cannot list or name a model fails the ask with a
     * message that says exactly that, rather than hitting an unnamed
     * endpoint.
     */
    private suspend fun resolveModel(
        provider: AgentProviderEntity,
        gateway: com.roombrowser.domain.agent.AgentGateway
    ): String {
        settings.defaultModel?.takeIf { it.isNotBlank() }?.let { return it }
        provider.defaultModel.takeIf { it.isNotBlank() }?.let { return it }
        return gateway.listModels().firstOrNull()
            ?: throw IllegalStateException("The provider lists no models to ask.")
    }

    /**
     * One grounded turn. [page] is captured fresh by the caller right before
     * (or while) the sheet opened; a page with no readable text refuses the
     * ask instead of letting the model invent one.
     */
    fun ask(page: PageContext?, kind: AskKind, question: String) {
        val provider = activeProvider() ?: run {
            state = UiState.NoProvider
            return
        }
        val pageText = PageAssistantPrompts.pageTextForPrompt(page?.text)
        if (!PageAssistantPrompts.canAsk(pageText)) {
            state = UiState.Failed(
                "This page has no readable text yet — let it finish loading " +
                    "(or try Reader mode first), then ask again."
            )
            return
        }
        val label = if (kind == AskKind.ASK) {
            "Answering: ${question.trim().take(60).ifBlank { "your question" }}"
        } else {
            "${kind.chipLabel}…"
        }
        state = UiState.Thinking(label)
        val targetLanguage = java.util.Locale.getDefault().displayLanguage
        scope.launch {
            val result = runCatching {
                val gateway = AgentGateways.forProvider(
                    callFactory, provider.baseUrl, apiKeyFor(provider), provider.protocol,
                    tuning = com.roombrowser.domain.agent.LocalAiTuning(), appContext = appContext
                )
                val model = resolveModel(provider, gateway)
                val messages = listOf(
                    ChatMessage(role = "system", content = PageAssistantPrompts.systemPrompt()),
                    ChatMessage(
                        role = "user",
                        content = PageAssistantPrompts.buildUserPrompt(kind, pageText, question, targetLanguage)
                    )
                )
                gateway.chat(ChatRequest(model = model, messages = messages, stream = false), {})
            }
            state = result.fold(
                onSuccess = { message ->
                    PageAssistantPrompts.cleanAnswer(message.content).takeIf { it.isNotBlank() }
                        ?.let { UiState.Answer(it) }
                        ?: UiState.Failed("The model returned an empty answer — try again or pick another model.")
                },
                onFailure = { error ->
                    UiState.Failed(error.message ?: "The ask failed without a reason from the provider.")
                }
            )
        }
    }
}
