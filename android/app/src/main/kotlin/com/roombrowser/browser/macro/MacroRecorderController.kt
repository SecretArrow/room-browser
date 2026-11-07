package com.roombrowser.browser.macro

import android.util.Log
import com.roombrowser.domain.macro.MacroDefinition
import com.roombrowser.domain.macro.MacroStep
import com.roombrowser.domain.macro.MacroSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the recorder is doing — the UI binds an indicator to this. */
enum class MacroRecordState { IDLE, RECORDING, PAUSED }

/** What the recorder needs from its host to see the browser's chrome actions. */
interface MacroRecorderHost {

    /** Run a page script on the ACTIVE tab's engine and hand back its JSON answer. */
    fun macroEval(script: String, onResult: (String?) -> Unit)

    /** The id of the active tab, or null on the homepage (no engine). */
    fun macroActiveTabId(): String?

    /** True when [tabId] is still open. */
    fun macroTabExists(tabId: String): Boolean
}

/**
 * The native half of the Macro Recorder.
 *
 * The page probe (see [MacroRecorderScripts]) sees what happens INSIDE the
 * document; this controller owns what the app does AROUND it — tab switches,
 * back/forward/reload, omnibox navigations — and merges both into one step
 * list. The merge is pull-based: every [PULL_INTERVAL_MS] the controller
 * pulls the active tab's buffer and merges any steps newer than the last
 * one it saw (by timestamp), so a same-site full page load cannot lose
 * steps (they survive in `sessionStorage`) and a pull that races the page's
 * own write cannot duplicate them.
 *
 * The recording is SCOPED to the tab it started on: switching tabs pauses
 * capture on the tab left behind (its listeners are uninstalled, its buffer
 * kept) and resumes on return. A macro never secretly records a page the
 * user is not looking at.
 *
 * Everything runs on the MAIN scope the host provides; evaluateJs is
 * main-thread by contract and the controller owns no threads.
 */
class MacroRecorderController(
    private val scope: CoroutineScope,
    private val host: MacroRecorderHost
) {

    companion object {
        private const val TAG = "MacroRecorder"
        private const val PULL_INTERVAL_MS = 700L

        /** A gap longer than this between steps collapses to it (the editor trims dead air). */
        private const val MAX_DELAY_MS = 5_000L
    }

    var state: MacroRecordState = MacroRecordState.IDLE
        private set

    /** The tab the recording runs on — steps from any other tab are refused. */
    private var tabId: String? = null

    /** The merged step list, native truth — the page buffer is only ever a source. */
    private val steps = mutableListOf<MacroRecorderStep>()

    private var lastMergedTimestamp = 0L
    private var pullJob: Job? = null
    private var droppedByPage = 0

    /** Observers called on every state/step change (main thread). */
    private val listeners = mutableListOf<(MacroRecordState, Int) -> Unit>()

    fun addListener(listener: (MacroRecordState, Int) -> Unit) {
        listeners.add(listener)
        listener(state, steps.size)
    }

    fun removeListener(listener: (MacroRecordState, Int) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        val snapshot = listeners.toList()
        for (l in snapshot) l(state, steps.size)
    }

    /** True when a recording (or a paused one) is under way on this tab. */
    fun isActiveOn(activeTab: String?): Boolean =
        state != MacroRecordState.IDLE && tabId != null && tabId == activeTab

    // ---------- lifecycle ----------

    /**
     * Starts a fresh recording on the active tab. A recording already under
     * way is stopped first (the caller confirms; this method just does it).
     * The homepage cannot host a recording: there is no document to probe,
     * and the first honest step a macro needs is a navigation anyway.
     */
    fun start(activeTab: String?): Boolean {
        if (activeTab == null) return false
        stopInternal(clearPage = true)
        tabId = activeTab
        steps.clear()
        lastMergedTimestamp = 0L
        droppedByPage = 0
        state = MacroRecordState.RECORDING
        host.macroEval(MacroRecorderScripts.startJs()) { }
        startPulling()
        notifyChanged()
        Log.d(TAG, "recording started on tab=$activeTab")
        return true
    }

    /** Freezes capture everywhere. The indicator stays up — nothing is lost. */
    fun pause() {
        if (state != MacroRecordState.RECORDING) return
        state = MacroRecordState.PAUSED
        host.macroEval(MacroRecorderScripts.pausedJs(true)) { }
        notifyChanged()
    }

    /** Resumes capture on the recording's tab. */
    fun resume() {
        if (state != MacroRecordState.PAUSED) return
        state = MacroRecordState.RECORDING
        host.macroEval(MacroRecorderScripts.pausedJs(false)) { }
        // The document may have navigated while paused: reinstall so the
        // listeners exist wherever the page is now.
        host.macroEval(MacroRecorderScripts.startJs()) { }
        notifyChanged()
    }

    /**
     * Ends the recording and keeps the steps — what Stop runs. The returned
     * definition is the EDITOR's opening state; its id is empty (the editor
     * mints one when it saves).
     */
    fun stop(onDone: (MacroDefinition?) -> Unit) {
        if (state == MacroRecordState.IDLE) {
            onDone(null)
            return
        }
        val capturedTab = tabId
        stopInternal(clearPage = false)
        val recorded = steps.toList()
        val def = MacroDefinition(
            name = "Recorded ${recorded.size} steps",
            steps = recorded.mapIndexed { i, s -> s.toMacroStep(i, recorded) },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "recording stopped: ${recorded.size} steps, page dropped=$droppedByPage")
        onDone(MacroSanitizer.sanitize(def))
        // Keep the steps in memory until the editor saves; a cleared list
        // would break a "record again" flow that reuses this controller.
        if (capturedTab == null) {
            // unreachable; start() refuses a null tab — kept for honesty
        }
    }

    /** Discards everything — what Cancel runs. */
    fun cancel() {
        stopInternal(clearPage = true)
        steps.clear()
        notifyChanged()
    }

    private fun stopInternal(clearPage: Boolean) {
        pullJob?.cancel()
        pullJob = null
        val script = if (clearPage) {
            MacroRecorderScripts.stopJs()
        } else {
            MacroRecorderScripts.uninstallJs()
        }
        host.macroEval(script) { }
        state = MacroRecordState.IDLE
        notifyChanged()
    }

    /**
     * The active tab changed while recording: pause capture on the tab left
     * behind (its buffer stays in its sessionStorage) and warm the probe on
     * the tab entered, should it be the recording's own tab coming back.
     */
    fun onActiveTabChanged(newActive: String?) {
        if (state == MacroRecordState.IDLE) return
        if (newActive == tabId) {
            if (state == MacroRecordState.RECORDING) {
                host.macroEval(MacroRecorderScripts.startJs()) { }
            }
        } else {
            // Leaving the recording's tab: freeze its page-side capture so a
            // page the user is no longer looking at cannot add steps.
            host.macroEval(MacroRecorderScripts.pausedJs(true)) { }
        }
    }

    /** The recording's tab closed: keep what was recorded, stop capturing. */
    fun onTabClosed(closedTab: String?) {
        if (state != MacroRecordState.IDLE && closedTab == tabId) {
            state = MacroRecordState.PAUSED
            notifyChanged()
        }
    }

    // ---------- chrome actions the app records itself ----------

    /** Records a navigation the app itself performed (omnibox, bookmark, link intent). */
    fun recordNavigate(url: String) {
        if (state != MacroRecordState.RECORDING) return
        pushChrome(MacroRecorderStep(action = MacroStep.NAVIGATE, url = url))
    }

    /** Records a tab switch (stores the label the tab list shows, not a fragile id). */
    fun recordTabSwitch(tabLabel: String) {
        if (state != MacroRecordState.RECORDING) return
        pushChrome(MacroRecorderStep(action = MacroStep.TAB_SWITCH, value = tabLabel))
    }

    fun recordNewTab(url: String) {
        if (state != MacroRecordState.RECORDING) return
        pushChrome(MacroRecorderStep(action = MacroStep.NEW_TAB, url = url))
    }

    fun recordBack() {
        if (state != MacroRecordState.RECORDING) return
        pushChrome(MacroRecorderStep(action = MacroStep.BACK))
    }

    fun recordForward() {
        if (state != MacroRecordState.RECORDING) return
        pushChrome(MacroRecorderStep(action = MacroStep.FORWARD))
    }

    fun recordReload() {
        if (state != MacroRecordState.RECORDING) return
        pushChrome(MacroRecorderStep(action = MacroStep.RELOAD))
    }

    private fun pushChrome(step: MacroRecorderStep) {
        steps.add(step.copy(timestampMs = System.currentTimeMillis()))
        notifyChanged()
    }

    // ---------- the pull loop ----------

    private fun startPulling() {
        pullJob?.cancel()
        pullJob = scope.launch {
            while (state == MacroRecordState.RECORDING) {
                pullOnce()
                delay(PULL_INTERVAL_MS)
            }
        }
    }

    private fun pullOnce() {
        val active = host.macroActiveTabId() ?: return
        // Only the recording's own tab is pulled: another tab's buffer
        // belongs to that tab's user moment, not to this macro.
        if (active != tabId) return
        if (!host.macroTabExists(active)) return
        host.macroEval(MacroRecorderScripts.pullJs()) { payload ->
            mergeSnapshot(MacroRecorderCodec.decodeSnapshot(payload))
        }
    }

    @Synchronized
    private fun mergeSnapshot(snapshot: MacroRecorderSnapshot) {
        if (state != MacroRecordState.RECORDING) return
        if (snapshot.dropped > droppedByPage) droppedByPage = snapshot.dropped
        val fresh = snapshot.steps.filter { it.timestampMs > lastMergedTimestamp }
        if (fresh.isEmpty()) return
        steps.addAll(fresh)
        lastMergedTimestamp = fresh.maxOf { it.timestampMs }
        notifyChanged()
    }

    /** The number of steps recorded so far — the indicator badge. */
    fun stepCount(): Int = steps.size

    /** Number of steps the page itself dropped (the CAP tail). */
    fun droppedByPage(): Int = droppedByPage
}

/** The editor-friendly ceiling on a recorded gap (dead air is trimmed). */
private const val MAX_DELAY_MS = 5_000L

/** Page-step → macro-step: delay is the editor-friendly trim of the recorded gaps. */
private fun MacroRecorderStep.toMacroStep(index: Int, all: List<MacroRecorderStep>): MacroStep {
    val prev = all.getOrNull(index - 1)
    val rawGap = if (prev != null && timestampMs > prev.timestampMs) timestampMs - prev.timestampMs else 0L
    val delay = rawGap.coerceIn(0L, MAX_DELAY_MS)
    return MacroStep(
        action = action,
        selector = selector,
        selectorKind = selectorKind,
        tagName = tagName,
        text = text,
        x = x, y = y, endX = endX, endY = endY, dx = dx, dy = dy,
        value = value,
        url = url,
        delayMs = delay,
        timestampMs = timestampMs,
        note = note
    )
}
