package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Decision models — Ollama's `POST /v1/systemone`, the Jev-style API.
 *
 * A decision model is NOT a chat model. It has no reasoning step, no
 * streaming, no tools and no images, and it never writes prose: you send it
 * a `state` (the text to judge) plus up to 64 named `questions`, and it
 * answers each one with a **type and a probability** instead of a sentence.
 * Ollama 0.35+ only, and only for a model running on the machine holding
 * the weights — the endpoint refuses cloud and MLX/Safetensors models,
 * because it scores answer tokens directly and needs a GGUF runner that can
 * do that.
 *
 * Room Browser uses exactly one of its three question types' worth of this
 * API: the agent's action gate. Everything here serves that one caller, and
 * the vocabulary is kept complete anyway so the wire shape is testable on
 * its own terms rather than only through the one question we ask.
 *
 * [SystemOneWire] builds the request, [SystemOneParser] reads the response,
 * and [ActionGate] is the policy that turns an answer into a decision the
 * agent loop can act on. Nothing in this file touches Android, OkHttp or
 * the network — it is all pure JVM so it can be unit-tested end to end.
 */

// ---------- Questions (request) ----------

/** The three question types the endpoint accepts. */
enum class SystemOneQuestionType(val wire: String) {
    /** Pick one of 2–26 named options. */
    CHOICE("choice"),

    /**
     * Yes/no. The wire name is `noul` — not `bool` — and the answer comes
     * back as a probability that the answer is *true* rather than a verdict,
     * because a decision model never commits to a boolean.
     */
    NOUL("noul"),

    /** Place the state on an ordered rubric; levels are numbered from 0. */
    SCORE("score")
}

/**
 * One named question about the state.
 *
 * The three shapes differ in `criteria` — an object for [Choice] (option →
 * description, `null` to let the option name describe itself), an optional
 * `{"true","false"}` pair for [Noul], and a lowest-first array for [Score] —
 * which is why this is a sealed hierarchy rather than one data class with a
 * nullable field per shape.
 */
sealed interface SystemOneQuestion {

    /** What the model is being asked, in plain language. */
    val instructions: String

    /** [SystemOneQuestionType.CHOICE] — 2 to 26 options, keyed by the label to return. */
    data class Choice(
        override val instructions: String,
        val criteria: Map<String, String?>
    ) : SystemOneQuestion

    /**
     * [SystemOneQuestionType.NOUL] — `{"true": "...", "false": "..."}`.
     * Optional: with no criteria the model is left to interpret the question
     * on its own, which is the whole point of a question phrased as yes/no.
     */
    data class Noul(
        override val instructions: String,
        val criteria: NoulCriteria? = null
    ) : SystemOneQuestion

    /** [SystemOneQuestionType.SCORE] — 2 to 26 level descriptions, lowest first. */
    data class Score(
        override val instructions: String,
        val criteria: List<String>
    ) : SystemOneQuestion
}

/**
 * The optional two-sided description of a [SystemOneQuestion.Noul].
 *
 * Named [yes] / [no] because `true` and `false` are Kotlin keywords; the
 * wire keys they serialize to are `"true"` / `"false"`.
 */
data class NoulCriteria(val yes: String, val no: String)

/**
 * Builds the request body for `POST /v1/systemone`.
 *
 * Hand-built rather than `@Serializable` because `criteria` is three
 * different JSON shapes across the three question types; a polymorphic
 * serializer would cost more than the fourteen lines this takes.
 */
object SystemOneWire {

    /**
     * Serializes one call. [state] is any JSON value — a bare string, an
     * object, an array — and is sent verbatim; the server does not read it
     * as chat messages.
     *
     * The endpoint caps a request at 64 KiB and never truncates input, so an
     * oversized body is a 413 rather than a partial decision. Callers that
     * put page text in the state are responsible for staying under it; see
     * [ActionGate.state], which keeps only a short action description.
     */
    fun request(
        model: String,
        state: JsonElement,
        questions: Map<String, SystemOneQuestion>,
        keepAlive: String? = null
    ): String = buildJsonObject {
        put("model", model)
        put("state", state)
        put("questions", buildJsonObject {
            questions.forEach { (name, question) -> put(name, question(question)) }
        })
        keepAlive?.let { put("keep_alive", it) }
    }.toString()

    /** One question in its wire shape. */
    fun question(question: SystemOneQuestion): JsonObject = buildJsonObject {
        when (question) {
            is SystemOneQuestion.Choice -> {
                put("type", SystemOneQuestionType.CHOICE.wire)
                put("instructions", question.instructions)
                put("criteria", buildJsonObject {
                    question.criteria.forEach { (option, description) ->
                        // `null` is a legal value: the option name describes itself.
                        put(option, description?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                })
            }

            is SystemOneQuestion.Noul -> {
                put("type", SystemOneQuestionType.NOUL.wire)
                put("instructions", question.instructions)
                question.criteria?.let { criteria ->
                    put("criteria", buildJsonObject {
                        put("true", criteria.yes)
                        put("false", criteria.no)
                    })
                }
            }

            is SystemOneQuestion.Score -> {
                put("type", SystemOneQuestionType.SCORE.wire)
                put("instructions", question.instructions)
                put("criteria", JsonArray(question.criteria.map { JsonPrimitive(it) }))
            }
        }
    }
}

// ---------- Answers (response) ----------

/**
 * One answer. Only the field matching [type] is populated, and every field
 * is nullable/defaulted because the server omits what a question type does
 * not produce.
 *
 * [confidence] runs 0–1 and measures how *concentrated* the probabilities
 * are — NOT the chance the answer is right. The distinction matters to
 * [ActionGate], which never treats confidence as accuracy.
 */
data class SystemOneAnswer(
    val type: String = "",
    /** [SystemOneQuestionType.CHOICE] only. */
    val choice: String? = null,
    /** [SystemOneQuestionType.NOUL] only — the probability the answer is true. */
    val noul: Double? = null,
    /** [SystemOneQuestionType.SCORE] only — the probability-weighted level. */
    val score: Double? = null,
    /** [SystemOneQuestionType.SCORE] only — level index → description. */
    val legend: Map<String, String> = emptyMap(),
    /** Option (or level index, as text) → probability. */
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double? = null
) {
    /** Probability the answer picked [option] — 0 when it was not reported. */
    fun probabilityOf(option: String): Double = probabilities[option] ?: 0.0
}

/** Token counts the server reports for the call. */
data class SystemOneUsage(val inputTokens: Int = 0, val outputTokens: Int = 0)

/** A parsed `/v1/systemone` response. */
data class SystemOneResponse(
    val model: String = "",
    val answers: Map<String, SystemOneAnswer> = emptyMap(),
    val usage: SystemOneUsage? = null
)

/**
 * Parses `/v1/systemone` bodies leniently.
 *
 * Same philosophy as [OllamaTagsParser]: the shape is young and the server
 * omits whole fields depending on question type, so anything unreadable
 * degrades to *absent* rather than throwing. An absent answer is what makes
 * [ActionGate] fall back to asking the user, so a parse failure has to be
 * quiet and survivable — a caller must never turn it into an allow.
 *
 * Numbers are accepted as JSON numbers or as quoted strings: `probabilities`
 * is a map of floats that some fronts may serialize as text, and `longOrNull`
 * / `doubleOrNull` reject string primitives outright.
 */
object SystemOneParser {

    fun parse(body: String): SystemOneResponse {
        // Block body on purpose: the early return below is a non-local return
        // out of the inline lambda, which an expression body would reject.
        return runCatching {
            val root = AgentJson.parseToJsonElement(body.trim()) as? JsonObject
                ?: return SystemOneResponse()
            SystemOneResponse(
                model = str(root, "model") ?: "",
                answers = (root["answers"] as? JsonObject)?.entries
                    ?.mapNotNull { (name, value) -> answerOf(value)?.let { name to it } }
                    ?.toMap()
                    ?: emptyMap(),
                usage = usageOf(root["usage"] as? JsonObject)
            )
        }.getOrDefault(SystemOneResponse())
    }

    private fun answerOf(element: JsonElement): SystemOneAnswer? {
        val obj = element as? JsonObject ?: return null
        return SystemOneAnswer(
            type = str(obj, "type") ?: "",
            choice = str(obj, "choice"),
            noul = number(obj, "noul"),
            score = number(obj, "score"),
            legend = (obj["legend"] as? JsonObject)?.entries
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap()
                ?: emptyMap(),
            probabilities = (obj["probabilities"] as? JsonObject)?.entries
                ?.mapNotNull { (k, v) -> number(v)?.let { k to it } }
                ?.toMap()
                ?: emptyMap(),
            confidence = number(obj, "confidence")
        )
    }

    private fun usageOf(obj: JsonObject?): SystemOneUsage? {
        obj ?: return null
        return SystemOneUsage(
            inputTokens = int(obj, "input_tokens"),
            outputTokens = int(obj, "output_tokens")
        )
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun number(obj: JsonObject, key: String): Double? = number(obj[key])

    private fun number(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun int(obj: JsonObject, key: String): Int =
        number(obj, key)?.toInt() ?: 0
}

// ---------- The action gate ----------

/**
 * What the local decision model said about one action the agent wants to
 * take. The agent loop turns this into either a tool result or a question
 * for the user; see `BrowserAgentController.gate`.
 */
sealed interface ActionVerdict {

    /** Run the action without asking. */
    data object Allow : ActionVerdict

    /**
     * Refuse the action and tell the model why, so it can change course
     * instead of retrying the same call. A deny is NOT a promise that the
     * action was harmful — it is a 9B model's reading of the user's own
     * policy text, and the message says as much.
     */
    data class Deny(val reason: String) : ActionVerdict

    /** Not confident enough to decide alone — fall back to the user. */
    data class Ask(val reason: String) : ActionVerdict
}

/**
 * The one question Room Browser asks a decision model, and the policy that
 * reads the answer.
 *
 * ## Why this exists
 *
 * The agent's approval control used to be a single switch: confirm
 * *everything*, or confirm *nothing*. Neither is a policy. Confirming
 * everything trains the user to tap Allow without reading, which is worse
 * than no gate at all; confirming nothing gives an autonomous agent the run
 * of the machine. A decision model answers the question the switch cannot —
 * *which* actions need a human — in about a tenth of a second, on the
 * user's own hardware, with the action text never leaving the device.
 *
 * ## What it is not
 *
 * It is not a security boundary. The judge is a 9B model reading prose the
 * user wrote; it can be talked out of a decision by a page, and it has no
 * idea what "the account" actually is. It reduces how often the user is
 * asked, and it can refuse an action outright — but the guarantee comes
 * from the user reading the prompt, not from the model. The settings screen
 * and `SECURITY.md` both say so.
 */
object ActionGate {

    /** Name of the single question asked. Answers are keyed by it. */
    const val QUESTION = "action"

    /** Criteria keys — also the option names the model chooses between. */
    const val ALLOW = "allow"
    const val CONFIRM = "confirm"
    const val DENY = "deny"

    /**
     * How sure the model has to be about its own chosen option before the
     * gate acts on it alone. Below this the action is confirmed with the
     * user instead.
     *
     * Deliberately about the chosen option's probability rather than
     * `confidence`: confidence reports how *concentrated* the distribution
     * is, so a model that is evenly torn between `confirm` and `deny` has
     * low confidence in both while still being sure it is not `allow`. What
     * matters here is only "how sure is it of the answer it gave".
     */
    const val MIN_PROBABILITY = 0.60

    /**
     * The built-in policy, used when the user has not written their own.
     *
     * Phrased as rules about *effects*, not about tool names: the tools are
     * the app's vocabulary, and a decision model asked about `auto_post`
     * would have to be taught what that means. "Sends a message as the user"
     * it already understands.
     */
    const val DEFAULT_POLICY: String =
        "Decide what should happen with one action a browsing agent wants to " +
            "take, judging only what the action can do and the page it is on."

    /** Option descriptions shown to the model alongside [DEFAULT_POLICY]. */
    private val CRITERIA: Map<String, String?> = mapOf(
        ALLOW to "Routine and reversible. Reading, scrolling, searching, " +
            "navigating, switching or opening tabs, and typing into an ordinary " +
            "search box or form field on a page the agent is already on.",
        CONFIRM to "Something a person should agree to first: spending money, " +
            "signing in or out, sending or posting anything under the user's " +
            "identity, uploading, downloading, deleting, or changing an account " +
            "or privacy setting.",
        DENY to "Outside the task the user asked for, or an action the user's " +
            "instructions rule out. Not merely risky — wrong."
    )

    /** The question(s) to send, given the user's policy text (blank = built-in). */
    fun questions(policy: String?): Map<String, SystemOneQuestion> = mapOf(
        QUESTION to SystemOneQuestion.Choice(
            instructions = policy?.takeIf { it.isNotBlank() } ?: DEFAULT_POLICY,
            criteria = CRITERIA
        )
    )

    /**
     * The `state` describing one action.
     *
     * Kept to a few short fields on purpose: the endpoint rejects a body over
     * 64 KiB, and page text would blow through that on a long article. The
     * URL and title are what let the policy tell "type into a search box" on
     * a shop's own site from the same words on the user's bank.
     *
     * Both are truncated — a title is attacker-controlled text of unbounded
     * length, and this is the one place page content reaches the judge.
     */
    fun state(action: String, pageUrl: String?, pageTitle: String?): JsonObject = buildJsonObject {
        put("agent_action", action.take(MAX_FIELD))
        pageUrl?.takeIf { it.isNotBlank() }?.let { put("page_url", it.take(MAX_FIELD)) }
        pageTitle?.takeIf { it.isNotBlank() }?.let { put("page_title", it.take(MAX_FIELD)) }
    }

    /**
     * Reads the answer for [QUESTION] into a verdict.
     *
     * Everything unrecognised — no answer, a different question type, an
     * option name that is not one of ours, a probability under
     * [MIN_PROBABILITY] — becomes [ActionVerdict.Ask], never
     * [ActionVerdict.Allow]. The failure mode of a confused judge is that
     * the user gets asked, which is what would have happened with the gate
     * switched off.
     */
    fun verdict(response: SystemOneResponse): ActionVerdict {
        val answer = response.answers[QUESTION]
            ?: return ActionVerdict.Ask("the decision model returned no answer")
        if (answer.type != SystemOneQuestionType.CHOICE.wire) {
            return ActionVerdict.Ask("the decision model answered as \"${answer.type}\", not a choice")
        }
        val choice = answer.choice
            ?: return ActionVerdict.Ask("the decision model chose nothing")
        val probability = answer.probabilityOf(choice)
        if (probability < MIN_PROBABILITY) {
            return ActionVerdict.Ask(
                "the decision model was not sure (${percent(probability)} on \"$choice\")"
            )
        }
        return when (choice) {
            ALLOW -> ActionVerdict.Allow
            CONFIRM -> ActionVerdict.Ask(
                "the local decision model asked for confirmation (${percent(probability)})"
            )
            DENY -> ActionVerdict.Deny(
                "a local decision model judged this outside what you asked for " +
                    "(${percent(probability)} confident). Change approach rather than repeating it."
            )
            else -> ActionVerdict.Ask("the decision model chose an unknown option \"$choice\"")
        }
    }

    /** Formats a 0–1 probability as a whole percent for user-facing text. */
    fun percent(value: Double): String =
        "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"

    /** Longest page title or URL fragment sent as state. */
    private const val MAX_FIELD = 300
}
