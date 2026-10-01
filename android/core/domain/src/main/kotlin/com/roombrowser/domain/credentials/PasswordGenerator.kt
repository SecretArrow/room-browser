package com.roombrowser.domain.credentials

import java.security.SecureRandom
import kotlin.math.ln

/**
 * Cryptographically secure password generator for the per-profile vault.
 *
 * WHY this lives in core/domain: generation is pure computation with a
 * precise contract (length, class coverage, ambiguity), so it belongs where
 * it can be unit-tested exhaustively without Android. The UI only picks
 * [Options], calls [generate] and renders the result.
 *
 * SECURITY — the source of randomness is the whole product here:
 *  - every draw comes from [java.security.SecureRandom], the platform CSPRNG.
 *    `kotlin.random.Random` is deliberately NOT used: its default source is
 *    not guaranteed to be cryptographically strong, and a predictable
 *    password is worse than a short one.
 *  - [generate] reuses the caller's [SecureRandom] instance rather than
 *    constructing one per call: SecureRandom construction is expensive and
 *    the platform instance is already seeded from the OS entropy pool.
 *
 * GUARANTEES (each one is asserted by tests):
 *  - the result is exactly [Options.length] characters long;
 *  - at least one character from EVERY selected class appears, so a site's
 *    "must contain a symbol/number" rule cannot reject a generated password;
 *  - the remaining characters are drawn uniformly from the union of the
 *    selected classes;
 *  - the per-class guarantee characters are shuffled into the result, so
 *    their positions are not predictable (every character is positionally
 *    uniform over the class it was drawn from);
 *  - with [Options.avoidAmbiguous] no character from [AMBIGUOUS] appears.
 */
object PasswordGenerator {

    /** Lowercase letters, in ASCII order. */
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"

    /** Uppercase letters, in ASCII order. */
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Digit characters. */
    const val DIGITS = "0123456789"

    /**
     * Symbol / punctuation characters. Escaped `\$` is only Kotlin string
     * syntax — the character set itself is `!@#$%^&*()-_=+[]{};:,.<>/?`.
     */
    const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.<>/?"

    /**
     * Visually confusable characters: letter/digit look-alikes (O/0, o,
     * I/l/1) plus the pipe. Excluded when [Options.avoidAmbiguous] is set —
     * a password that cannot be read off a screen or transcribed reliably
     * ends up written down on a sticky note, which costs far more than the
     * ~0.3 bits per character it saves here. Only characters that are
     * actually in an enabled class can be removed; the rest are a no-op.
     */
    const val AMBIGUOUS = "O0oIl1|"

    /** Coarse strength buckets for the UI (see [strength]). */
    enum class Strength { WEAK, FAIR, STRONG }

    /**
     * Generator inputs.
     *
     * @param length total number of characters; must be at least the number
     * of enabled classes (each class must be able to contribute its one
     * guaranteed character).
     * @param upper include [UPPER].
     * @param lower include [LOWER].
     * @param digits include [DIGITS].
     * @param symbols include [SYMBOLS].
     * @param avoidAmbiguous exclude [AMBIGUOUS] from the pool.
     */
    data class Options(
        val length: Int = 20,
        val upper: Boolean = true,
        val lower: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val avoidAmbiguous: Boolean = false
    )

    /**
     * Generates one password for [options].
     *
     * Every random choice — which classes, which characters, the final
     * shuffle — goes through [random].
     *
     * @throws IllegalArgumentException when no class is enabled, when
     * [Options.length] is smaller than the number of enabled classes (the
     * one-character-per-class guarantee would be impossible), or when
     * [Options.avoidAmbiguous] would empty an enabled class.
     */
    fun generate(options: Options, random: SecureRandom = SecureRandom()): String {
        val sets = selectedSets(options)
        require(sets.isNotEmpty()) { "Enable at least one character class" }
        require(options.length >= sets.size) {
            "Length ${options.length} cannot hold one character from each of " +
                "the ${sets.size} enabled classes"
        }
        val union = sets.joinToString(separator = "")
        val chars = CharArray(options.length)
        var index = 0
        // Seed one character from EVERY class first, so the coverage promise
        // holds no matter what the fill pass below happens to draw. Any
        // class's own characters are still uniform over that class.
        for (set in sets) {
            chars[index++] = set[random.nextInt(set.length)]
        }
        while (index < options.length) {
            chars[index++] = union[random.nextInt(union.length)]
        }
        shuffle(chars, random)
        return String(chars)
    }

    /**
     * Strength bucket for the UI. DELIBERATELY a coarse heuristic, not a
     * crack-time claim: it estimates the entropy of [password] as
     * `length * log2(charset)`, where `charset` is the sum of the class sizes
     * actually PRESENT in the string, and maps bits to a bucket ([FAIR_BITS],
     * [STRONG_BITS]). It therefore punishes short and single-class passwords
     * (the failure modes that matter) while not pretending to detect
     * dictionary words — "correcthorsebatterystaple" reads as STRONG, which
     * is honest for a random generator and conservative for a human.
     *
     * An empty password is always [Strength.WEAK].
     */
    fun strength(password: String): Strength {
        if (password.isEmpty()) return Strength.WEAK
        var charset = 0
        if (password.any { it in LOWER }) charset += LOWER.length
        if (password.any { it in UPPER }) charset += UPPER.length
        if (password.any { it in DIGITS }) charset += DIGITS.length
        if (password.any { it in SYMBOLS }) charset += SYMBOLS.length
        // Only characters outside every known class (e.g. a non-ASCII pass
        // phrase): treat the pool as 1 to avoid log(0) — the length alone
        // then decides, which is the honest reading.
        if (charset == 0) charset = 1
        val bits = password.length * (ln(charset.toDouble()) / LN_2)
        return when {
            bits < FAIR_BITS -> Strength.WEAK
            bits < STRONG_BITS -> Strength.FAIR
            else -> Strength.STRONG
        }
    }

    /** The enabled classes, each already ambiguity-filtered. */
    private fun selectedSets(options: Options): List<String> {
        val sets = mutableListOf<String>()
        if (options.lower) sets += pool(LOWER, options)
        if (options.upper) sets += pool(UPPER, options)
        if (options.digits) sets += pool(DIGITS, options)
        if (options.symbols) sets += pool(SYMBOLS, options)
        return sets
    }

    /** One class as it will actually be drawn from. */
    private fun pool(set: String, options: Options): String {
        val filtered =
            if (options.avoidAmbiguous) set.filterNot { it in AMBIGUOUS } else set
        require(filtered.isNotEmpty()) {
            "Character class is empty after removing ambiguous characters"
        }
        return filtered
    }

    /**
     * Fisher-Yates over the whole array, driven by [random] — the shuffle is
     * what makes the per-class guarantee characters indistinguishable from
     * the fill characters positionally.
     */
    private fun shuffle(chars: CharArray, random: SecureRandom) {
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val swap = chars[i]
            chars[i] = chars[j]
            chars[j] = swap
        }
    }

    /** Entropy floor (bits) for [Strength.FAIR]. */
    private const val FAIR_BITS = 50.0

    /** Entropy floor (bits) for [Strength.STRONG]. */
    private const val STRONG_BITS = 80.0

    private val LN_2 = ln(2.0)
}
