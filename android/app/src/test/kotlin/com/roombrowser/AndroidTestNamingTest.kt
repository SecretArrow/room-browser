package com.roombrowser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the ONE rule that androidTest method names have to obey and that
 * nothing else in the build enforces.
 *
 * WHY IT IS A PROBLEM AT ALL. A backticked Kotlin name may contain spaces, but
 * a JVM METHOD name and a JVM CLASS name are not governed by the same rule: the
 * method name tolerates anything but `. ; [ /`, while D8 refuses to write a
 * class whose simple name is not an identifier. Kotlin puts the method name
 * inside the class name of every synthetic class it generates for it, so
 *
 *     fun `the same tab id in two profiles is two different conversations`() =
 *         runBlocking<Unit> { ... }
 *
 * compiles fine and then dies in dexBuilderDebugAndroidTest with "Space
 * characters in SimpleName '...Test$the same tab id ...$1' are not allowed
 * prior to DEX version 040" — because minSdk is 28, every build emits a DEX
 * below 040, and `runBlocking` guarantees the suspend lambda that produces the
 * `$1` class in the first place.
 *
 * WHY IT NEEDS A GUARD RATHER THAN A NOTE. androidTest is compiled by exactly
 * one CI job (the emulator one, `assembleDebugAndroidTest`), so this failure is
 * invisible to `quality`: lint, unit tests and the debug build all stay green
 * while the E2E job dies at its first step, twenty minutes in, on a message
 * about SimpleNames that says nothing about the test that caused it. That is
 * the same masking that makes androidTest typos expensive, and this test is
 * the cheap half of the fix — it runs in the fast job, in seconds, and names
 * the offending method.
 *
 * It reads the source tree instead of reflecting over compiled classes because
 * the classes only exist in the job that already fails; the point is to catch
 * the name BEFORE anything compiles.
 */
class AndroidTestNamingTest {

    @Test
    fun instrumented_test_names_are_usable_as_dex_simple_names() {
        val offenders = mutableListOf<String>()
        for (file in androidTestSources().walkTopDown()) {
            if (!file.isFile || file.extension != "kt") continue
            for (match in BACKTICKED_NAME.findAll(file.readText())) {
                val name = match.groupValues[1]
                if (!DEX_SAFE.matches(name)) {
                    offenders += "${file.name}: `$name` — rename to snake_case " +
                        "(D8 rejects it as a class simple name below DEX 040)"
                }
            }
        }

        assertThat(offenders).isEmpty()
    }

    /**
     * The instrumented source root, from wherever this test happens to run.
     *
     * A Gradle test's working directory is its module directory, so the first
     * candidate is the one that matches — but the answer is checked rather than
     * assumed, because a guard that silently scans nothing is worse than none.
     */
    private fun androidTestSources(): File =
        listOf("src/androidTest/kotlin", "app/src/androidTest/kotlin")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error(
                "androidTest sources not found from ${File(".").absolutePath}; " +
                    "this guard would otherwise pass without reading anything"
            )

    private companion object {
        /** A backticked function name, as in: fun `some name`( */
        val BACKTICKED_NAME = Regex("fun\\s+`([^`]*)`")

        /** What D8 accepts as a simple name below DEX version 040. */
        val DEX_SAFE = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    }
}
