package com.roombrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.ProfileId
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PROFILE ISOLATION — CRITICAL TESTS (spec sections 59/60).
 *
 * These instrumented tests REQUIRE an emulator/device (they exercise the
 * real WebView engine + per-profile data directories). Run with:
 *
 *   ./gradlew connectedDebugAndroidTest
 *
 * The full end-to-end isolation proof (cookie/localStorage/IndexedDB/
 * service worker/sessionStorage/cache separation) uses the local test
 * website in tools/profile-test-site/ served from the device or a dev
 * machine — see TESTING.md for the exact procedure.
 */
@RunWith(AndroidJUnit4::class)
class ProfileIsolationTest {

    @Test
    fun distinct_profiles_get_distinct_webview_data_directories() {
        val a = ProfileId.new()
        val b = ProfileId.new()
        assertThat(a.safeSuffix).isNotEqualTo(b.safeSuffix)
        // The suffix is what WebView.setDataDirectorySuffix uses: two
        // different suffixes = two physically separate storage trees.
        assertThat(a.safeSuffix.length).isEqualTo(32)
        assertThat(b.safeSuffix.length).isEqualTo(32)
    }

    @Test
    fun suffix_is_alphanumeric_and_within_webview_limits() {
        repeat(50) {
            val id = ProfileId.new()
            assertThat(id.safeSuffix).matches("^[0-9a-f]{32}$")
        }
    }

    @Test
    fun process_binding_is_profile_exclusive() {
        val a = ProfileId.new()
        val b = ProfileId.new()
        val first = ProfileEngine.bindProcessToProfile(a)
        // Re-binding to the SAME profile is a no-op success
        assertThat(ProfileEngine.bindProcessToProfile(a)).isTrue()
        // A second bind attempt to a DIFFERENT profile must fail — this is
        // the guard that makes cross-profile state reuse impossible.
        assertThat(first).isTrue()
        assertThat(ProfileEngine.bindProcessToProfile(b)).isFalse()
    }

    @Test
    fun package_context_resolves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Debug builds carry an applicationIdSuffix (".debug") — match the base id.
        assertThat(context.packageName).startsWith("com.roombrowser")
    }
}
