package com.roombrowser.browser

import android.Manifest
import com.roombrowser.data.repo.PermissionKind

/**
 * What a page's permission request needs from the OPERATING SYSTEM, as opposed
 * to what it needs from the user.
 *
 * A WebView `PermissionRequest` is answered by the app, but the app can only
 * truthfully answer yes when Android itself has granted the matching runtime
 * permission. `request.grant()` for a resource the app does not hold does not
 * hand the page a working camera — it hands it a stream that fails to open,
 * which reaches the page as a device error rather than as a refusal. So the
 * sheet asks the OS first and only then answers the page.
 *
 * Pure on purpose: `Manifest.permission.*` are compile-time String constants
 * and [PermissionKind] is a plain enum, so this is unit-testable on the JVM
 * with no Robolectric and no device.
 */
object WebPermissions {

    /**
     * The runtime permissions Android must have granted before [kind] can be
     * granted to a page. Empty for the kinds the WebView cannot ask for
     * through `onPermissionRequest` (clipboard, notifications, autoplay and
     * the rest are handled by the browser, not by this sheet) — an empty
     * answer means "nothing to ask the OS for", not "allowed".
     */
    fun androidPermissionsFor(kind: PermissionKind): List<String> = when (kind) {
        PermissionKind.CAMERA -> listOf(Manifest.permission.CAMERA)
        PermissionKind.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        // Coarse alongside fine: Android 12+ lets the user answer with
        // approximate location, and a site denied outright because it asked
        // for fine is worse than one given a usable position.
        PermissionKind.LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        else -> emptyList()
    }

    /** The union over [kinds], de-duplicated, order-stable. */
    fun androidPermissionsFor(kinds: Set<PermissionKind>): List<String> =
        kinds.flatMap { androidPermissionsFor(it) }.distinct()

    /** The sheet's own word for a request: "Camera", "Microphone", … */
    fun labelFor(kind: PermissionKind): String = when (kind) {
        PermissionKind.CAMERA -> "Camera"
        PermissionKind.MICROPHONE -> "Microphone"
        PermissionKind.LOCATION -> "Location"
        PermissionKind.NOTIFICATIONS -> "Notifications"
        PermissionKind.CLIPBOARD -> "Clipboard"
        PermissionKind.BLUETOOTH -> "Bluetooth"
        PermissionKind.USB -> "USB"
        PermissionKind.POPUPS -> "Pop-ups"
        PermissionKind.DOWNLOADS -> "Downloads"
        PermissionKind.SENSORS -> "Sensors"
        PermissionKind.AUTOPLAY -> "Autoplay"
    }

    /**
     * The sentence under the buttons. Named per kind rather than generic
     * because "the site wants access" tells the user nothing about what it
     * gets: a live camera is not the same concession as one reading of a
     * position.
     */
    fun explanationFor(kind: PermissionKind): String = when (kind) {
        PermissionKind.CAMERA -> "Use your camera — the site sees live video while this tab is open."
        PermissionKind.MICROPHONE -> "Use your microphone — the site hears audio while this tab is open."
        PermissionKind.LOCATION -> "Know your location — the site is told where you are."
        else -> "Access ${labelFor(kind).lowercase()}."
    }
}
