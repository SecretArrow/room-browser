package com.roombrowser.browser.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme

/**
 * Device picker — its OWN full-screen activity (default process, no WebView),
 * replacing the old in-screen picker dialog.
 *
 * The bundled catalogue is 1000+ real handsets, so the picker gets a real
 * window: a search field over the whole list, a mark for the devices other
 * profiles are already presenting as (they stay selectable — a repeat is told,
 * not forbidden), the current choice highlighted, and "No device" as an
 * honest first row. Tapping a device returns it to the caller via
 * [setResult]; the caller applies it through the same profile-manager path
 * the old dialog used.
 *
 * The "in use" marks come from the shared profile store — AppGraph works in
 * every process, and the Room database keeps both processes consistent.
 */
class DevicePickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the status bar, display cutouts or the nav buttons.
        enableEdgeToEdge()
        val graph = (application as RoomBrowserApp).graph
        // The launching profile is excluded from the "in use" marks: its own
        // device is its current choice, not a conflict with itself.
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { ProfileId(it) }
        val currentDeviceId = intent.getStringExtra(EXTRA_CURRENT_DEVICE_ID)
        setContent {
            RoomBrowserTheme {
                DevicePickerRoot(
                    inUseLoader = { graph.profileManager.devicesInUse(except = profileId) },
                    currentDeviceId = currentDeviceId,
                    onPick = { deviceId ->
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(RESULT_DEVICE_ID, deviceId.orEmpty())
                        )
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_CURRENT_DEVICE_ID = "current_device_id"

        /**
         * The picked device id. Empty string = "No device" was picked.
         * Absent (with RESULT_CANCELED) = the user backed out, nothing changes.
         */
        const val RESULT_DEVICE_ID = "result_device_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerRoot(
    inUseLoader: suspend () -> Set<String>,
    currentDeviceId: String?,
    onPick: (String?) -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    var query by remember { mutableStateOf("") }
    var inUse by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        inUse = runCatching { inUseLoader() }.getOrDefault(emptySet())
    }

    // 1000+ entries: the filtered list is derived once per query change, never
    // inside a row's own composition.
    val results = remember(query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            Devices.all
        } else {
            Devices.all.filter {
                it.brand.lowercase().contains(needle) ||
                    it.model.lowercase().contains(needle) ||
                    it.code.lowercase().contains(needle) ||
                    it.year.toString() == needle
            }
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics).
        modifier = Modifier.imePadding(),
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic, nothing overlaps the nav buttons.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Select Device") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Above the navigation bar (3-button Back/Home/Recents or
                // gesture hint) and beside display cutouts in landscape.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search brand, model, code or year") },
                singleLine = true,
                shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                trailingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = extras.icon)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "${results.size} of ${Devices.all.size} devices",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 6.dp)
            )
            LazyColumn(Modifier.weight(1f)) {
                item(key = KEY_NO_DEVICE) {
                    // "No device" is a real choice: the profile then sends
                    // whatever the User-Agent setting says.
                    DeviceOptionRow(
                        title = "No device (use the User-Agent setting)",
                        subtitle = "The User-Agent setting decides what sites see",
                        current = currentDeviceId == null,
                        emphasize = currentDeviceId == null,
                        onClick = { onPick(null) }
                    )
                }
                items(results, key = { it.id }) { device ->
                    val taken = device.id in inUse
                    val current = device.id == currentDeviceId
                    DeviceOptionRow(
                        title = "${device.brand} ${device.model}",
                        subtitle = deviceMarks(device, taken, current),
                        current = current,
                        emphasize = taken || current,
                        onClick = { onPick(device.id) }
                    )
                }
            }
        }
    }
}

private const val KEY_NO_DEVICE = "no-device"

/** The quiet second line of a device row: identity, Android version, marks. */
private fun deviceMarks(device: Device, taken: Boolean, current: Boolean): String =
    buildString {
        append(device.code)
        append(" · ")
        append(device.year)
        append(" · Android ")
        append(device.androidVersion)
        if (device.formFactor == "tablet") append(" · tablet")
        if (taken) append(" · used by another profile")
        if (current) append(" · Current")
    }

/** One device row: full-width touch target, current choice highlighted. */
@Composable
private fun DeviceOptionRow(
    title: String,
    subtitle: String,
    current: Boolean,
    emphasize: Boolean,
    onClick: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (current) Modifier.background(extras.primary.copy(alpha = 0.08f))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = extras.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasize) extras.primary else extras.textSecondary
        )
    }
}
