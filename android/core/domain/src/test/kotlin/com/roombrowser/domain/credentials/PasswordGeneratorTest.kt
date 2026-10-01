package com.roombrowser.domain.credentials

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.PasswordGenerator.Strength
import org.junit.Test

/**
 * Tests for the CSPRNG password generator. The contract is statistical by
 * nature — "at least one character of each enabled class" and "no repeats"
 * cannot be proven from a single sample — so the property tests below run
 * hundreds of generations. Every seed draw comes from the real
 * [java.security.SecureRandom]; the tests assert properties that hold for
 * EVERY draw, never a specific output.
 */
class PasswordGeneratorTest {

    private val allClasses = PasswordGenerator.Options()

    @Test
    fun `generates exactly the requested length`() {
        // 4 classes are enabled, so any length >= 4 is legal.
        for (length in listOf(4, 8, 12, 20, 32, 64, 128)) {
            val options = allClasses.copy(length = length)
            repeat(20) {
                assertThat(PasswordGenerator.generate(options)).hasLength(length)
            }
        }
    }

    @Test
    fun `every enabled class contributes at least one character`() {
        val options = allClasses.copy(length = 12)
        repeat(300) {
            val generated = PasswordGenerator.generate(options)
            assertThat(generated.any { it in PasswordGenerator.LOWER }).isTrue()
            assertThat(generated.any { it in PasswordGenerator.UPPER }).isTrue()
            assertThat(generated.any { it in PasswordGenerator.DIGITS }).isTrue()
            assertThat(generated.any { it in PasswordGenerator.SYMBOLS }).isTrue()
        }
    }

    @Test
    fun `only the enabled classes appear`() {
        // Symbols alone: no letter or digit may leak in from the union.
        val symbolsOnly = PasswordGenerator.Options(
            length = 24, upper = false, lower = false, digits = false, symbols = true
        )
        repeat(50) {
            val generated = PasswordGenerator.generate(symbolsOnly)
            assertThat(generated.all { it in PasswordGenerator.SYMBOLS }).isTrue()
        }

        // Digits + lowercase: the excluded classes never show up.
        val noUpperNoSymbols = PasswordGenerator.Options(
            length = 24, upper = false, lower = true, digits = true, symbols = false
        )
        repeat(50) {
            val generated = PasswordGenerator.generate(noUpperNoSymbols)
            val allowed = PasswordGenerator.LOWER + PasswordGenerator.DIGITS
            assertThat(generated.all { it in allowed }).isTrue()
        }
    }

    @Test
    fun `a length equal to the class count is exactly one character per class`() {
        val generated = PasswordGenerator.generate(allClasses.copy(length = 4))
        assertThat(generated).hasLength(4)
        assertThat(generated.any { it in PasswordGenerator.LOWER }).isTrue()
        assertThat(generated.any { it in PasswordGenerator.UPPER }).isTrue()
        assertThat(generated.any { it in PasswordGenerator.DIGITS }).isTrue()
        assertThat(generated.any { it in PasswordGenerator.SYMBOLS }).isTrue()
    }

    @Test
    fun `two calls never return the same password`() {
        // Not a randomness proof, but it is the observable regression guard:
        // a fixed pool, a broken seed or a constant return would collapse the
        // distinct set. 500 draws from a >= 62-character pool make a real
        // collision vanishingly unlikely.
        val seen = HashSet<String>()
        repeat(500) {
            seen += PasswordGenerator.generate(allClasses.copy(length = 24))
        }
        assertThat(seen).hasSize(500)
    }

    @Test
    fun `avoidAmbiguous keeps look-alike characters out`() {
        val options = allClasses.copy(length = 40, avoidAmbiguous = true)
        repeat(200) {
            val generated = PasswordGenerator.generate(options)
            for (ambiguous in PasswordGenerator.AMBIGUOUS) {
                assertThat(generated).doesNotContain(ambiguous.toString())
            }
        }
    }

    @Test
    fun `ambiguous characters are allowed when they are not avoided`() {
        // Statistical, and deliberately so: the guard is that the filter is
        // opt-in, not that it is off. Over 4000 characters from a pool that
        // contains 7 of them, never drawing one is not a realistic outcome.
        val options = allClasses.copy(length = 40, avoidAmbiguous = false)
        val found = (0 until 100).any { _ ->
            val generated = PasswordGenerator.generate(options)
            generated.any { it in PasswordGenerator.AMBIGUOUS }
        }
        assertThat(found).isTrue()
    }

    @Test
    fun `a length below the class count is rejected`() {
        assertThrowsIllegalArgument { PasswordGenerator.generate(allClasses.copy(length = 3)) }
        assertThrowsIllegalArgument { PasswordGenerator.generate(allClasses.copy(length = 0)) }
        assertThrowsIllegalArgument { PasswordGenerator.generate(allClasses.copy(length = -5)) }
    }

    @Test
    fun `a length below the enabled class count is rejected for smaller selections too`() {
        val twoClasses = PasswordGenerator.Options(
            length = 1, upper = true, lower = true, digits = false, symbols = false
        )
        assertThrowsIllegalArgument { PasswordGenerator.generate(twoClasses) }
        // One class, length 1 is legal.
        val oneClass = PasswordGenerator.Options(
            length = 1, upper = false, lower = true, digits = false, symbols = false
        )
        assertThat(PasswordGenerator.generate(oneClass)).hasLength(1)
    }

    @Test
    fun `no enabled class is rejected`() {
        val none = PasswordGenerator.Options(
            length = 20, upper = false, lower = false, digits = false, symbols = false
        )
        assertThrowsIllegalArgument { PasswordGenerator.generate(none) }
    }

    @Test
    fun `strength rates a generated password strong and a weak one weak`() {
        assertThat(PasswordGenerator.strength("")).isEqualTo(Strength.WEAK)
        // 10 lowercase chars: 10 * log2(26) = 47 bits.
        assertThat(PasswordGenerator.strength("abcdefghij")).isEqualTo(Strength.WEAK)
        // 12 lowercase chars: 12 * log2(26) = 56 bits.
        assertThat(PasswordGenerator.strength("abcdefghijkl")).isEqualTo(Strength.FAIR)
        // 20 chars over all four classes: 20 * log2(88) = 129 bits.
        assertThat(PasswordGenerator.strength(PasswordGenerator.generate(allClasses)))
            .isEqualTo(Strength.STRONG)
    }

    @Test
    fun `strength counts only the classes actually present`() {
        // 20 lowercase chars (94 bits) must not be credited with the entropy
        // of the full alphabet, and a short password stays weak however many
        // classes it touches: "!a1" is 3 chars over 62 symbols = 18 bits.
        assertThat(PasswordGenerator.strength("abcdefghijklmnopqrst")).isEqualTo(Strength.STRONG)
        assertThat(PasswordGenerator.strength("abcdefghij")).isEqualTo(Strength.WEAK)
        assertThat(PasswordGenerator.strength("!a1")).isEqualTo(Strength.WEAK)
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException but nothing was thrown")
    }
}
