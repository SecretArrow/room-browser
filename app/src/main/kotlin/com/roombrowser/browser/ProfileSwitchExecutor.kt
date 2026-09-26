package com.roombrowser.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.profile.ProfileSwitchStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes the profile-switch security protocol (spec section 48) against
 * the domain state machine:
 *
 *   1. stop navigation          5. release profile resources
 *   2. save tab state           6. load new profile context
 *   3. destroy browser context  7. restore new profile tabs
 *   4. flush profile state
 *
 * Because WebView.setDataDirectorySuffix() is process-wide, steps 6-7 are
 * performed by RESTARTING the ':browser' process with the new profile id:
 * the current WebViews are destroyed, tabs are persisted, a restart intent
 * is scheduled via AlarmManager, and the process terminates itself.
 */
class ProfileSwitchExecutor(
    private val context: Context,
    private val stateMachine: ProfileSwitchStateMachine = ProfileSwitchStateMachine()
) {

    /** Steps the executor performs in-process before the restart. */
    interface Host {
        /** Step 1 — stop current navigation. */
        fun stopNavigation()
        /** Step 2 — persist all open tab state to Room. */
        suspend fun saveTabState()
        /** Step 3 — destroy every WebView in the browser context. */
        fun destroyBrowserContext()
        /** Step 4 — flush cookies/storage state to disk. */
        fun flushProfileState()
        /** Step 5 — release in-memory caches, thumbnails, pooled objects. */
        fun releaseProfileResources()
        /** Private-tab cleanup hook (session cookies). */
        fun cleanupPrivateTabs()
        /** Target activity class used for the restart intent. */
        val restartActivityClass: Class<*>
    }

    fun switch(from: ProfileId, to: ProfileId, restartIntentExtras: Intent.() -> Unit, host: Host) {
        check(stateMachine.snapshot().state == ProfileSwitchStateMachine.State.IDLE) {
            "A profile switch is already in progress"
        }
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        scope.launch {
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.Begin(from, to))

            runStep(ProfileSwitchStateMachine.Step.STOP_NAVIGATION) { host.stopNavigation() }
            runStep(ProfileSwitchStateMachine.Step.SAVE_TAB_STATE) { host.saveTabState() }
            runStep(ProfileSwitchStateMachine.Step.DESTROY_BROWSER_CONTEXT) { host.destroyBrowserContext() }
            runStep(ProfileSwitchStateMachine.Step.FLUSH_PROFILE_STATE) { host.flushProfileState() }
            runStep(ProfileSwitchStateMachine.Step.RELEASE_PROFILE_RESOURCES) { host.releaseProfileResources() }

            // Steps 6-7 (load new profile + restore its tabs) happen AFTER the restart
            scheduleRestart(host, to, restartIntentExtras)
            terminateProcess()
        }
    }

    private suspend fun runStep(step: ProfileSwitchStateMachine.Step, block: suspend () -> Unit) {
        try {
            block()
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.StepCompleted)
        } catch (t: Throwable) {
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.Error(step, t.message ?: "unknown error"))
            // Recover to a safe state: abort the switch, keep the old profile.
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.Abort)
            throw IllegalStateException("Profile switch failed at $step", t)
        }
    }

    /** Fire-and-forget cleanup of a private-tab session. */
    fun cleanupPrivateSession(host: Host) {
        host.cleanupPrivateTabs()
    }

    private fun scheduleRestart(host: Host, to: ProfileId, extras: Intent.() -> Unit) {
        val intent = Intent(context, host.restartActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_PROFILE_ID, to.value)
            extras()
        }
        val pending = PendingIntent.getActivity(
            context,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        runCatching {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pending)
        }.onFailure {
            // Fallback for devices that restrict exact alarms
            alarm.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pending)
        }
    }

    /**
     * Hard-terminate ONLY the ':browser' process. The Android system treats
     * this as a normal process death; the scheduled restart relaunches the
     * browser activity with the NEW profile id, whose WebView data-directory
     * suffix differs → genuinely separate storage context.
     */
    private fun terminateProcess() {
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            Runtime.getRuntime().exit(0)
        }, KILL_DELAY_MS)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.roombrowser.extra.PROFILE_ID"
        const val EXTRA_RESTART = "com.roombrowser.extra.RESTART"
        private const val RESTART_REQUEST_CODE = 4242
        private const val RESTART_DELAY_MS = 350L
        private const val KILL_DELAY_MS = 250L
    }
}
