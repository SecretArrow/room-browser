package com.roombrowser.browser

import android.Manifest
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.repo.PermissionKind
import org.junit.Test

/**
 * The mapping a page's permission request goes through before the OS is asked.
 *
 * This is the half of the grant that is testable without a device, and it is
 * the half that decides whether the page is told the truth: `request.grant()`
 * for a resource the app does not hold gives the page a stream that fails to
 * open, which reaches it as a device error rather than as a refusal.
 */
class WebPermissionsTest {

    @Test
    fun `camera asks for the camera runtime permission`() {
        assertThat(WebPermissions.androidPermissionsFor(PermissionKind.CAMERA))
            .containsExactly(Manifest.permission.CAMERA)
    }

    @Test
    fun `microphone asks for record audio, not the camera`() {
        assertThat(WebPermissions.androidPermissionsFor(PermissionKind.MICROPHONE))
            .containsExactly(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun `location asks for coarse as well as fine`() {
        // Coarse is what lets a user on Android 12+ answer with approximate
        // location; asking for fine alone forces an all-or-nothing choice.
        assertThat(WebPermissions.androidPermissionsFor(PermissionKind.LOCATION))
            .containsExactly(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
    }

    @Test
    fun `a combined request unions its permissions without repeating them`() {
        val both = WebPermissions.androidPermissionsFor(
            setOf(PermissionKind.CAMERA, PermissionKind.MICROPHONE)
        )
        assertThat(both)
            .containsExactly(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun `kinds the webview cannot ask for need nothing from the OS`() {
        // Empty means "nothing to ask the platform", never "allowed": the
        // sheet still asks the user, it just has no second gate to clear.
        assertThat(WebPermissions.androidPermissionsFor(PermissionKind.CLIPBOARD)).isEmpty()
        assertThat(WebPermissions.androidPermissionsFor(PermissionKind.AUTOPLAY)).isEmpty()
    }

    @Test
    fun `every kind has a label of its own`() {
        val labels = PermissionKind.entries.map { WebPermissions.labelFor(it) }
        assertThat(labels).hasSize(PermissionKind.entries.size)
        assertThat(labels.toSet()).hasSize(PermissionKind.entries.size)
        assertThat(labels).doesNotContain("")
    }

    @Test
    fun `the camera and microphone explanations name what is shared`() {
        assertThat(WebPermissions.explanationFor(PermissionKind.CAMERA)).contains("camera")
        assertThat(WebPermissions.explanationFor(PermissionKind.MICROPHONE)).contains("microphone")
        assertThat(WebPermissions.explanationFor(PermissionKind.LOCATION)).contains("location")
    }
}
