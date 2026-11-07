package com.roombrowser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The macro domain model's own guarantees: what the sanitizer withholds,
 * what the codec round-trips, how variables resolve and what the risk read
 * flags. Pure JVM — no Android, no engine.
 */
class MacroModelsTest {

    @Test
    fun `sanitizer withholds values of password-like fields`() {
        val step = MacroStep(
            action = MacroStep.INPUT,
            selector = "input[name='user_password']",
            selectorKind = "NAME",
            tagName = "input",
            value = "hunter2"
        )
        val clean = MacroSanitizer.sanitizeStep(step)
        assertThat(clean.value).isNull()
        assertThat(clean.note).contains("withheld")
    }

    @Test
    fun `sanitizer keeps ordinary field values`() {
        val step = MacroStep(
            action = MacroStep.INPUT,
            selector = "input[name='q']",
            selectorKind = "NAME",
            tagName = "input",
            value = "room browser"
        )
        val clean = MacroSanitizer.sanitizeStep(step)
        assertThat(clean.value).isEqualTo("room browser")
    }

    @Test
    fun `sanitizer masks token and otp named fields`() {
        for (name in listOf("auth_token", "sms_otp", "card_cvv")) {
            val step = MacroStep(
                action = MacroStep.INPUT,
                selector = "input[name='$name']",
                selectorKind = "NAME",
                tagName = "input",
                value = "123456"
            )
            assertThat(MacroSanitizer.sanitizeStep(step).value).isNull()
        }
    }

    @Test
    fun `codec round-trips a full definition`() {
        val def = MacroDefinition(
            id = "abc",
            name = "Login flow",
            description = "opens the site and submits the form",
            category = "Work",
            icon = "🤖",
            steps = listOf(
                MacroStep(action = MacroStep.NAVIGATE, url = "https://example.com/", delayMs = 250),
                MacroStep(
                    action = MacroStep.CLICK, selector = "#submit", selectorKind = "ID",
                    tagName = "button", text = "Sign in", x = 120f, y = 640f,
                    delayMs = 400, timeoutMs = 3000, retries = 2
                ),
                MacroStep(action = MacroStep.SCROLL, dx = 0f, dy = -400f),
                MacroStep(action = MacroStep.WAIT_ELEMENT, selector = ".dash", timeoutMs = 6000)
            ),
            variables = listOf(MacroVariable(name = "site", defaultValue = "example.com")),
            playback = MacroPlayback(speed = 1.5f, repeat = 3, onError = "skip", defaultTimeoutMs = 4000)
        )
        val decoded = MacroCodec.decode(MacroCodec.encode(def))
        assertThat(decoded).isEqualTo(def)
    }

    @Test
    fun `codec tolerates unknown keys and missing fields`() {
        val raw = """
            {"name":"Imported","steps":[{"action":"CLICK","x":10}],
             "playback":{"speed":2.0},"futureField":{"x":1}}
        """.trimIndent()
        val decoded = MacroCodec.decode(raw)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.name).isEqualTo("Imported")
        assertThat(decoded.steps).hasSize(1)
        assertThat(decoded.steps[0].x).isEqualTo(10f)
        assertThat(decoded.playback.speed).isEqualTo(2.0f)
        assertThat(decoded.playback.repeat).isEqualTo(1) // default kept
    }

    @Test
    fun `codec rejects garbage as null`() {
        assertThat(MacroCodec.decode("not json at all")).isNull()
        assertThat(MacroCodec.decode("{\"steps\": 3}")).isNull()
    }

    @Test
    fun `variables resolve references and leave unknown names verbatim`() {
        val values = mapOf("site" to "example.com", "query" to "macro")
        assertThat(MacroVariables.resolve("https://{{site}}/?q={{query}}", values))
            .isEqualTo("https://example.com/?q=macro")
        assertThat(MacroVariables.resolve("https://{{unknown}}/x", values))
            .isEqualTo("https://{{unknown}}/x")
    }

    @Test
    fun `variables resolve into step value and url`() {
        val step = MacroStep(
            action = MacroStep.NAVIGATE,
            url = "https://{{site}}/",
            value = "{{user}}"
        )
        val resolved = MacroVariables.resolve(step, mapOf("site" to "a.io", "user" to "ada"))
        assertThat(resolved.url).isEqualTo("https://a.io/")
        assertThat(resolved.value).isEqualTo("ada")
    }

    @Test
    fun `variables report the names a string references`() {
        assertThat(MacroVariables.referencedIn("{{a}} and {{b}} and {{a}}"))
            .containsExactly("a", "b")
    }

    @Test
    fun `risk read flags submit-like clicks and ignores plain ones`() {
        val risky = MacroDefinition(
            steps = listOf(
                MacroStep(action = MacroStep.CLICK, text = "Submit payment", x = 1f, y = 1f)
            )
        )
        val calm = MacroDefinition(
            steps = listOf(
                MacroStep(action = MacroStep.CLICK, text = "Next page", x = 1f, y = 1f),
                MacroStep(action = MacroStep.SCROLL, dy = -100f)
            )
        )
        assertThat(MacroRisk.isRisky(risky)).isTrue()
        assertThat(MacroRisk.isRisky(calm)).isFalse()
    }

    @Test
    fun `sanitized definition keeps structure but drops every secret value`() {
        val def = MacroDefinition(
            steps = listOf(
                MacroStep(action = MacroStep.INPUT, selector = "input[name='password']", selectorKind = "NAME", tagName = "input", value = "s3cret"),
                MacroStep(action = MacroStep.INPUT, selector = "input[name='email']", selectorKind = "NAME", tagName = "input", value = "a@b.c")
            )
        )
        val clean = MacroSanitizer.sanitize(def)
        assertThat(clean.steps[0].value).isNull()
        assertThat(clean.steps[1].value).isEqualTo("a@b.c")
        assertThat(clean.steps).hasSize(2)
    }
}
