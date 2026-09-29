package com.roombrowser.domain.profile

import com.roombrowser.domain.model.ProfileId

/**
 * Profile switch security protocol (spec section 48).
 *
 * The switch sequence is modelled as an explicit state machine so it can be
 * unit-tested: no step may be skipped and no stale WebView/context may be
 * reused for another profile.
 */
class ProfileSwitchStateMachine {

    enum class Step {
        STOP_NAVIGATION,
        SAVE_TAB_STATE,
        DESTROY_BROWSER_CONTEXT,
        FLUSH_PROFILE_STATE,
        RELEASE_PROFILE_RESOURCES,
        LOAD_NEW_PROFILE_CONTEXT,
        RESTORE_NEW_PROFILE_TABS;

        val order: Int get() = ordinal
    }

    enum class State { IDLE, SWITCHING, COMPLETE, FAILED }

    sealed interface Event {
        data class Begin(val fromProfile: ProfileId, val toProfile: ProfileId) : Event
        data object StepCompleted : Event
        data class Error(val step: Step, val reason: String) : Event
        data object Abort : Event
    }

    data class Snapshot(
        val state: State,
        val currentStep: Step?,
        val completedSteps: List<Step>,
        val fromProfile: ProfileId? = null,
        val toProfile: ProfileId? = null,
        val error: String? = null
    )

    private val completed = mutableListOf<Step>()
    private var state: State = State.IDLE
    private var currentStep: Step? = null
    private var from: ProfileId? = null
    private var to: ProfileId? = null
    private var error: String? = null

    fun snapshot(): Snapshot = Snapshot(state, currentStep, completed.toList(), from, to, error)

    /** True when [profile] is involved in the in-flight switch. */
    fun involves(profile: ProfileId): Boolean =
        state == State.SWITCHING && (from == profile || to == profile)

    fun onEvent(event: Event): Snapshot {
        when (event) {
            is Event.Begin -> {
                check(state == State.IDLE) { "A profile switch is already in progress" }
                require(event.fromProfile != event.toProfile) { "Cannot switch to the same profile" }
                from = event.fromProfile
                to = event.toProfile
                state = State.SWITCHING
                currentStep = Step.STOP_NAVIGATION
            }
            Event.StepCompleted -> {
                check(state == State.SWITCHING) { "No switch in progress" }
                currentStep?.let { completed += it }
                val next = Step.entries.firstOrNull { it.order == (completed.lastOrNull()?.order ?: -1) + 1 }
                currentStep = next
                if (next == null) state = State.COMPLETE
            }
            is Event.Error -> {
                check(state == State.SWITCHING) { "No switch in progress" }
                error = "step ${event.step}: ${event.reason}"
                state = State.FAILED
            }
            Event.Abort -> {
                state = State.IDLE
                currentStep = null
            }
        }
        return snapshot()
    }

    fun reset() {
        completed.clear()
        state = State.IDLE
        currentStep = null
        from = null
        to = null
        error = null
    }

    /** Expected order for documentation and tests. */
    val expectedOrder: List<Step> = Step.entries.toList()
}
