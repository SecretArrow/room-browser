package com.roombrowser.agent

import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.domain.macro.MacroDefinition
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.runBlocking

/**
 * The macro surface the agent's create_macro / run_macro / macro_list tools
 * drive — the same seam shape as [AgentProfileData] and [AgentSkillsAccess]:
 * an interface the executor holds optionally, an implementation that goes
 * through the real repositories, and a null in the runs that have no honest
 * way to honor it (a headless or scheduled turn has no tab the user is
 * looking at, and a macro RUNS on that tab).
 */
interface AgentMacroAccess {

    /** Every macro of this profile: id, name, step count, category. */
    fun list(): List<MacroSummary>

    /** One macro's full definition, or null when the id is unknown or another profile's. */
    fun load(id: String): MacroDefinition?

    /** Persists [definition] into this profile's library; answers the stored id. */
    fun save(definition: MacroDefinition): String

    /**
     * Starts [id] on the visible tab; answers null on success or a
     * human-readable reason. Non-blocking: the playback HUD is the progress
     * surface, the agent polls nothing.
     */
    fun startRun(id: String, variables: Map<String, String>): String?

    /** Whether a playback is already under way on this profile's browser. */
    fun isBusy(): Boolean
}

data class MacroSummary(
    val id: String,
    val name: String,
    val steps: Int,
    val category: String
)

/**
 * The real implementation: [BrowserRepository] for the library rows (the
 * profile wall and the sanitizer live inside it) and [BrowserViewModel] for
 * the runner (the playback belongs to the live browser process, on the tab
 * the user sees).
 */
class GraphAgentMacroAccess(
    private val app: RoomBrowserApp,
    private val profileId: ProfileId,
    private val vm: BrowserViewModel
) : AgentMacroAccess {

    private val repo: BrowserRepository get() = app.graph.browserRepo

    override fun list(): List<MacroSummary> = runBlocking {
        runCatching { repo.macros(profileId) }.getOrDefault(emptyList()).map { row ->
            MacroSummary(
                id = row.id,
                name = row.name,
                steps = MacroCodecHelper.stepCount(row.definitionJson),
                category = row.category
            )
        }
    }

    override fun load(id: String): MacroDefinition? = runBlocking {
        runCatching { repo.macro(profileId, id) }.getOrNull()
    }

    override fun save(definition: MacroDefinition): String = runBlocking {
        repo.saveMacro(profileId, definition)
    }

    override fun startRun(id: String, variables: Map<String, String>): String? {
        val def = load(id) ?: return "no macro with id $id"
        if (vm.macroRunner.isBusy()) {
            return "a macro is already running on the visible tab — stop it first (the user can, from the HUD)"
        }
        return if (vm.macroRun(def, variables)) {
            null
        } else {
            "the macro could not be started"
        }
    }

    override fun isBusy(): Boolean = vm.macroRunner.isBusy()
}

/** Tiny decode helper so the summary never holds a definition it doesn't need. */
private object MacroCodecHelper {
    fun stepCount(definitionJson: String): Int =
        runCatching {
            com.roombrowser.domain.macro.MacroCodec.decode(definitionJson)?.steps?.size ?: 0
        }.getOrDefault(0)
}
