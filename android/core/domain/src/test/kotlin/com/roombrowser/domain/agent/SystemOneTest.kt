package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemOneTest {

    // ---------- SystemOneWire ----------

    @Test
    fun `choice question serializes criteria and keeps null as null`() {
        val body = SystemOneWire.request(
            model = "nimble",
            state = ActionGate.state("Click [12] Sign in", "https://example.com/", "Example"),
            questions = mapOf(
                "action" to SystemOneQuestion.Choice(
                    instructions = "Which label fits?",
                    criteria = mapOf("billing" to "Payments and refunds", "other" to null)
                )
            )
        )
        val root = AgentJson.parseToJsonElement(body)
        assertThat(body).contains("\"model\":\"nimble\"")
        assertThat(body).contains("\"type\":\"choice\"")
        assertThat(body).contains("\"criteria\":{\"billing\":\"Payments and refunds\",\"other\":null}")
        assertThat(root.toString()).isNotEmpty()
    }

    @Test
    fun `noul question uses the true and false wire keys`() {
        val question = SystemOneWire.question(
            SystemOneQuestion.Noul("Did they ask for a refund?", NoulCriteria("They asked", "They did not"))
        )
        assertThat(question.toString())
            .isEqualTo("""{"type":"noul","instructions":"Did they ask for a refund?","criteria":{"true":"They asked","false":"They did not"}}""")
    }

    @Test
    fun `noul question without criteria omits the field entirely`() {
        val question = SystemOneWire.question(SystemOneQuestion.Noul("Is this urgent?"))
        assertThat(question.containsKey("criteria")).isFalse()
    }

    @Test
    fun `score question sends its levels as an ordered array`() {
        val question = SystemOneWire.question(
            SystemOneQuestion.Score("How urgent?", listOf("Routine", "Soon", "Urgent"))
        )
        assertThat(question.toString())
            .isEqualTo("""{"type":"score","instructions":"How urgent?","criteria":["Routine","Soon","Urgent"]}""")
    }

    @Test
    fun `keep_alive is only sent when asked for`() {
        val base = mapOf("q" to SystemOneQuestion.Noul("Is it?"))
        assertThat(SystemOneWire.request("nimble", kotlinx.serialization.json.JsonPrimitive("s"), base))
            .doesNotContain("keep_alive")
        assertThat(SystemOneWire.request("nimble", kotlinx.serialization.json.JsonPrimitive("s"), base, "5m"))
            .contains("\"keep_alive\":\"5m\"")
    }

    @Test
    fun `a structured state is sent verbatim`() {
        val body = SystemOneWire.request(
            model = "tev1",
            state = ActionGate.state("Like visible posts", "https://x.com/home", "Home / X"),
            questions = ActionGate.questions(null)
        )
        assertThat(body).contains("\"page_url\":\"https://x.com/home\"")
        assertThat(body).contains("\"page_title\":\"Home / X\"")
        assertThat(body).contains("\"agent_action\":\"Like visible posts\"")
    }

    // ---------- SystemOneParser ----------

    /** The worked example from Ollama's own docs, verbatim. */
    private val docsResponse = """
        {
          "model": "nimble",
          "answers": {
            "team": {
              "type": "choice",
              "choice": "billing",
              "probabilities": {"billing": 0.985, "technical": 0.012, "other": 0.003},
              "confidence": 0.922
            },
            "refund": {"type": "noul", "noul": 0.997},
            "urgency": {
              "type": "score",
              "score": 0.815,
              "legend": {"0": "Routine", "1": "Soon", "2": "Urgent"},
              "probabilities": {"0": 0.378, "1": 0.429, "2": 0.193},
              "confidence": 0.046
            }
          },
          "usage": {"input_tokens": 841, "output_tokens": 4}
        }
    """.trimIndent()

    @Test
    fun `parses every answer type from the documented example`() {
        val parsed = SystemOneParser.parse(docsResponse)
        assertThat(parsed.model).isEqualTo("nimble")
        assertThat(parsed.answers.keys).containsExactly("team", "refund", "urgency")

        val team = parsed.answers.getValue("team")
        assertThat(team.type).isEqualTo("choice")
        assertThat(team.choice).isEqualTo("billing")
        assertThat(team.probabilityOf("billing")).isEqualTo(0.985)
        assertThat(team.confidence).isEqualTo(0.922)

        assertThat(parsed.answers.getValue("refund").noul).isEqualTo(0.997)

        val urgency = parsed.answers.getValue("urgency")
        assertThat(urgency.score).isEqualTo(0.815)
        assertThat(urgency.legend.getValue("2")).isEqualTo("Urgent")

        assertThat(parsed.usage?.inputTokens).isEqualTo(841)
        assertThat(parsed.usage?.outputTokens).isEqualTo(4)
    }

    @Test
    fun `an unreadable body degrades to no answers rather than throwing`() {
        assertThat(SystemOneParser.parse("not json").answers).isEmpty()
        assertThat(SystemOneParser.parse("[]").answers).isEmpty()
        assertThat(SystemOneParser.parse("").answers).isEmpty()
        assertThat(SystemOneParser.parse("""{"answers":null}""").answers).isEmpty()
        assertThat(SystemOneParser.parse("""{"answers":{"a":42}}""").answers).isEmpty()
    }

    @Test
    fun `numbers quoted as strings still parse`() {
        val parsed = SystemOneParser.parse(
            """{"answers":{"action":{"type":"choice","choice":"allow","probabilities":{"allow":"0.9"},"confidence":"0.8"}}}"""
        )
        val answer = parsed.answers.getValue("action")
        assertThat(answer.probabilityOf("allow")).isEqualTo(0.9)
        assertThat(answer.confidence).isEqualTo(0.8)
    }

    @Test
    fun `an absent probability reads as zero`() {
        val parsed = SystemOneParser.parse("""{"answers":{"a":{"type":"choice","choice":"allow"}}}""")
        assertThat(parsed.answers.getValue("a").probabilityOf("allow")).isEqualTo(0.0)
    }

    // ---------- ActionGate ----------

    private fun answer(
        choice: String,
        probability: Double,
        type: String = "choice"
    ): SystemOneResponse = SystemOneResponse(
        answers = mapOf(
            ActionGate.QUESTION to SystemOneAnswer(
                type = type,
                choice = choice,
                probabilities = mapOf(choice to probability)
            )
        )
    )

    @Test
    fun `a confident allow runs without asking`() {
        assertThat(ActionGate.verdict(answer("allow", 0.93))).isEqualTo(ActionVerdict.Allow)
    }

    @Test
    fun `an unsure allow asks the user instead`() {
        val verdict = ActionGate.verdict(answer("allow", 0.55))
        assertThat(verdict).isInstanceOf(ActionVerdict.Ask::class.java)
        assertThat((verdict as ActionVerdict.Ask).reason).contains("55%")
    }

    @Test
    fun `the probability floor is inclusive`() {
        assertThat(ActionGate.verdict(answer("allow", ActionGate.MIN_PROBABILITY)))
            .isEqualTo(ActionVerdict.Allow)
        assertThat(ActionGate.verdict(answer("allow", ActionGate.MIN_PROBABILITY - 0.001)))
            .isInstanceOf(ActionVerdict.Ask::class.java)
    }

    @Test
    fun `an allow with no probability at all asks the user`() {
        val response = SystemOneResponse(
            answers = mapOf(ActionGate.QUESTION to SystemOneAnswer(type = "choice", choice = "allow"))
        )
        assertThat(ActionGate.verdict(response)).isInstanceOf(ActionVerdict.Ask::class.java)
    }

    @Test
    fun `confirm falls back to the user approval flow`() {
        val verdict = ActionGate.verdict(answer("confirm", 0.88))
        assertThat(verdict).isInstanceOf(ActionVerdict.Ask::class.java)
        assertThat((verdict as ActionVerdict.Ask).reason).contains("confirmation")
    }

    @Test
    fun `deny refuses the action and says why`() {
        val verdict = ActionGate.verdict(answer("deny", 0.77))
        assertThat(verdict).isInstanceOf(ActionVerdict.Deny::class.java)
        val reason = (verdict as ActionVerdict.Deny).reason
        assertThat(reason).contains("77%")
        assertThat(reason).contains("outside what you asked for")
    }

    @Test
    fun `an unsure deny asks rather than refusing`() {
        assertThat(ActionGate.verdict(answer("deny", 0.4))).isInstanceOf(ActionVerdict.Ask::class.java)
    }

    @Test
    fun `no answer at all asks the user`() {
        val verdict = ActionGate.verdict(SystemOneResponse())
        assertThat(verdict).isInstanceOf(ActionVerdict.Ask::class.java)
        assertThat((verdict as ActionVerdict.Ask).reason).contains("no answer")
    }

    @Test
    fun `an answer to a different question does not decide anything`() {
        val response = SystemOneResponse(
            answers = mapOf("team" to SystemOneAnswer(type = "choice", choice = "allow", probabilities = mapOf("allow" to 1.0)))
        )
        assertThat(ActionGate.verdict(response)).isInstanceOf(ActionVerdict.Ask::class.java)
    }

    @Test
    fun `a non-choice answer asks the user`() {
        val verdict = ActionGate.verdict(answer("allow", 1.0, type = "noul"))
        assertThat(verdict).isInstanceOf(ActionVerdict.Ask::class.java)
        assertThat((verdict as ActionVerdict.Ask).reason).contains("noul")
    }

    @Test
    fun `an option that is not ours asks the user`() {
        val verdict = ActionGate.verdict(answer("maybe", 0.99))
        assertThat(verdict).isInstanceOf(ActionVerdict.Ask::class.java)
        assertThat((verdict as ActionVerdict.Ask).reason).contains("unknown option")
    }

    @Test
    fun `a choice answer with no choice field asks the user`() {
        val response = SystemOneResponse(
            answers = mapOf(ActionGate.QUESTION to SystemOneAnswer(type = "choice"))
        )
        assertThat(ActionGate.verdict(response)).isInstanceOf(ActionVerdict.Ask::class.java)
    }

    @Test
    fun `the question offers exactly the three options the gate understands`() {
        val question = ActionGate.questions(null).getValue(ActionGate.QUESTION)
        assertThat(question).isInstanceOf(SystemOneQuestion.Choice::class.java)
        assertThat((question as SystemOneQuestion.Choice).criteria.keys)
            .containsExactly(ActionGate.ALLOW, ActionGate.CONFIRM, ActionGate.DENY)
    }

    @Test
    fun `a blank policy falls back to the built-in one and a real one is used verbatim`() {
        fun instructions(policy: String?): String =
            (ActionGate.questions(policy).getValue(ActionGate.QUESTION) as SystemOneQuestion.Choice).instructions

        assertThat(instructions(null)).isEqualTo(ActionGate.DEFAULT_POLICY)
        assertThat(instructions("   ")).isEqualTo(ActionGate.DEFAULT_POLICY)
        assertThat(instructions("Never sign in anywhere.")).isEqualTo("Never sign in anywhere.")
    }

    // ---------- state ----------

    @Test
    fun `a long page title is truncated`() {
        val state = ActionGate.state("Click [1]", "https://example.com/", "t".repeat(5000))
        val title = (state["page_title"] as kotlinx.serialization.json.JsonPrimitive).content
        assertThat(title.length).isEqualTo(300)
    }

    @Test
    fun `a long action label is truncated`() {
        val state = ActionGate.state("x".repeat(5000), null, null)
        val action = (state["agent_action"] as kotlinx.serialization.json.JsonPrimitive).content
        assertThat(action.length).isEqualTo(300)
    }

    @Test
    fun `a blank or missing url and title are left out`() {
        val state = ActionGate.state("Scroll down", "", "   ")
        assertThat(state.containsKey("page_url")).isFalse()
        assertThat(state.containsKey("page_title")).isFalse()
    }

    // ---------- percent ----------

    @Test
    fun `percent rounds and clamps`() {
        assertThat(ActionGate.percent(0.985)).isEqualTo("98%")
        assertThat(ActionGate.percent(0.0)).isEqualTo("0%")
        assertThat(ActionGate.percent(1.0)).isEqualTo("100%")
        assertThat(ActionGate.percent(1.4)).isEqualTo("100%")
        assertThat(ActionGate.percent(-0.2)).isEqualTo("0%")
    }
}
