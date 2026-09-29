package com.roombrowser.theme

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.theme.RoomThemeSpec
import com.roombrowser.domain.theme.ThemeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Controller behind the Theme Studio activity (default process).
 *
 * Holds the WORKING spec (live-edited, never persisted until "Apply"), the
 * target profile, and the user's custom-theme gallery. Persistence always
 * writes a FULL snapshot onto the profile row (profiles.theme_json), which
 * is what makes themes per-profile isolated.
 */
class ThemeStudioController(
    application: Application,
    private val profileId: ProfileId?
) {

    private val graph = (application as RoomBrowserApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Target profile (null = generic studio without a profile target). */
    var profile by mutableStateOf<Profile?>(null)
        private set

    /** The spec being edited — the live preview renders this. */
    var working by mutableStateOf(BuiltInThemes.default())
        private set

    /** User-saved custom themes (gallery). */
    var gallery by mutableStateOf<List<RoomThemeSpec>>(emptyList())
        private set

    /** Transient UI message consumed by Compose. */
    var message by mutableStateOf<String?>(null)
        private set

    /** Clear the transient message (called after the snackbar showed it). */
    fun clearMessage() { message = null }

    /** Show a transient message via the snackbar. */
    fun toast(text: String) { message = text }

    private var initializedWorking = false

    fun start() {
        scope.launch {
            graph.themeRepo.observeGallery().collect { gallery = it }
        }
        if (profileId != null) {
            scope.launch {
                graph.profileRepo.observeProfile(profileId).collect { p ->
                    if (p != null) {
                        profile = p
                        if (!initializedWorking) {
                            initializedWorking = true
                            working = BuiltInThemes.resolveOrDefault(p.themeJson)
                        }
                    }
                }
            }
        } else {
            initializedWorking = true
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    /** Mutate the working spec (live preview follows immediately). */
    fun update(transform: (RoomThemeSpec) -> RoomThemeSpec) {
        working = transform(working).sanitized()
    }

    /** Load a theme (built-in or gallery) into the editor for previewing. */
    fun load(spec: RoomThemeSpec) {
        working = spec.sanitized()
    }

    /** Persist the working spec as THIS profile's theme snapshot. */
    fun applyToProfile() {
        val target = profile ?: return
        val spec = working
        scope.launch {
            graph.themeRepo.applyToProfile(target.id, spec)
            message = "Theme \"${spec.name}\" applied to ${target.name}"
        }
    }

    /** Save the working spec into the gallery as a NEW custom theme. */
    fun saveToGallery(name: String) {
        val spec = working.withIdentity(customId(), name.ifBlank { "${working.name} custom" })
        scope.launch {
            graph.themeRepo.saveToGallery(spec, System.currentTimeMillis())
            working = spec
            message = "\"${spec.name}\" saved to My themes"
        }
    }

    fun renameTheme(id: String, newName: String) {
        if (newName.isBlank()) return
        scope.launch {
            graph.themeRepo.rename(id, newName)
            if (working.id == id) working = working.copy(name = newName)
            message = "Theme renamed to \"$newName\""
        }
    }

    fun duplicateTheme(id: String) {
        scope.launch {
            graph.themeRepo.duplicate(id, System.currentTimeMillis())
            message = "Theme duplicated"
        }
    }

    fun deleteTheme(id: String) {
        scope.launch {
            graph.themeRepo.delete(id)
            message = "Theme deleted from My themes"
        }
    }

    /** Working spec back to the built-in default (also clears the profile). */
    fun resetToDefault() {
        working = BuiltInThemes.default()
        val target = profile
        scope.launch {
            if (target != null) graph.themeRepo.resetProfile(target.id)
            message = "Theme reset to the default (Obsidian)"
        }
    }

    fun exportJson(): String = ThemeJson.encode(working)

    /** Replace the working spec from pasted/imported JSON. */
    fun importJson(raw: String): Boolean {
        val decoded = ThemeJson.decode(raw) ?: return false
        working = decoded
        message = "Theme \"${decoded.name}\" imported — press Apply to use it"
        return true
    }

    private fun customId(): String =
        RoomThemeSpec.CUSTOM_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
}
