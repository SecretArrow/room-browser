package com.roombrowser.browser.macro

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.roombrowser.domain.macro.MacroDefinition
import com.roombrowser.domain.macro.MacroStep
import com.roombrowser.domain.macro.MacroVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt

/** One line of the execution log. */
data class MacroLogEntry(
    val timeMs: Long,
    val stepIndex: Int,
    val message: String,
    val level: Int // 0 = info, 1 = warn, 2 = error
)

/** What the runner needs from its host to act on the browser. */
interface MacroRunnerHost {

    /** Run a page script on the active tab's engine; the answer is the script's JSON string (or null). */
    fun macroEval(script: String, onResult: (String?) -> Unit)

    /** The engine view of the active tab, for native gesture dispatch. Null on the homepage. */
    fun macroEngineView(): android.view.View?

    /** Load a URL in the active tab (the app's own classification runs first). */
    fun macroLoadUrl(url: String)

    fun macroGoBack()
    fun macroGoForward()
    fun macroReload()

    /** Switch to the N-th (0-based) open tab of this profile. */
    fun macroSelectTabIndex(index: Int): Boolean

    /** The index of the open tab whose title starts with [label], if any. */
    fun macroTabIndexFor(label: String): Int?

    /** Open a new tab at [url]. */
    fun macroNewTab(url: String)

    /** Capture the page's pixels (a screenshot checkpoint). */
    fun macroCapturePixels(onResult: (Bitmap?) -> Unit)

    /** Persist a screenshot checkpoint; answers the file's display name. */
    fun macroSaveCheckpoint(name: String, bitmap: Bitmap): String
}

/**
 * The Macro playback engine.
 *
 * A macro runs on the ACTIVE tab — the one the user sees — and only there:
 * page steps go through the active engine, and a TAB_SWITCH step is the only
 * way the run ever changes tabs. The run is fully cancellable at every await
 * (delay, page settle, element wait, gesture): [stop] lands within one
 * interval, and the system Back handler can always reach it.
 *
 * Speed multiplies every delay: 1.0 replays as recorded, 2.0 runs half
 * speed, 0.5 double speed. Repeat: 1 = once, N = N times, 0 = loop until
 * stopped. On a failed step the runner spends the step's retries, then
 * follows the macro's onError policy (stop or skip).
 */
class MacroRunner(
    private val scope: CoroutineScope,
    private val host: MacroRunnerHost
) {

    companion object {
        private const val TAG = "MacroRunner"
        private const val SETTLE_MS = 350L
        private const val ELEMENT_POLL_MS = 200L
        private const val GESTURE_STEPS = 12
    }

    enum class State { IDLE, RUNNING, PAUSED }

    var state: State = State.IDLE
        private set

    /** The run's live progress — the HUD binds to this. */
    data class Progress(
        val state: State = State.IDLE,
        val macroId: String = "",
        val macroName: String = "",
        val stepIndex: Int = -1,
        val stepCount: Int = 0,
        val iteration: Int = 0,
        val totalIterations: Int = 1,
        val currentAction: String = "",
        val etaMs: Long = 0L
    )

    var progress: Progress = Progress()
        private set

    /** The execution log of the current/last run, newest last. */
    val log = mutableListOf<MacroLogEntry>()

    private val listeners = mutableListOf<(Progress) -> Unit>()

    fun addListener(listener: (Progress) -> Unit) {
        listeners.add(listener)
        listener(progress)
    }

    fun removeListener(listener: (Progress) -> Unit) {
        listeners.remove(listener)
    }

    private var runJob: Job? = null
    private var startedAt = 0L
    private var currentMacro: MacroDefinition? = null

    /** True while a run (even a paused one) is under way. */
    fun isBusy(): Boolean = state != State.IDLE

    // ---------- lifecycle ----------

    /**
     * Starts [def]. Returns false when a run is already under way — the UI
     * routes to the running HUD instead, it does not stack runs.
     */
    fun start(def: MacroDefinition, variables: Map<String, String>): Boolean {
        if (state != State.IDLE) return false
        currentMacro = def
        log.clear()
        logLine(0, "Run started: ${def.name} (${def.steps.size} steps)")
        runJob = scope.launch {
            runLoop(def, variables)
        }
        return true
    }

    /** Restarts the current macro from step 0 (iteration 1). */
    fun restart(variables: Map<String, String>) {
        val def = currentMacro ?: return
        stopInternal(logMessage = "Restart requested")
        logLine(0, "Restarting: ${def.name}")
        start(def, variables)
    }

    fun pause() {
        if (state != State.RUNNING) return
        state = State.PAUSED
        publish(action = "paused")
        logLine(0, "Paused")
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.RUNNING
        publish(action = "resumed")
        logLine(0, "Resumed")
    }

    /** Stops the run and keeps the log + progress — the HUD's Stop. */
    fun stop() = stopInternal(logMessage = "Stopped by user")

    private fun stopInternal(logMessage: String) {
        runJob?.cancel()
        runJob = null
        if (state != State.IDLE) {
            logLine(0, logMessage)
            state = State.IDLE
            publish(action = "stopped")
        }
    }

    /** Clears the log and progress after the HUD is dismissed. */
    fun reset() {
        if (state != State.IDLE) return
        log.clear()
        progress = Progress()
        publish(action = "")
        currentMacro = null
    }

    // ---------- the loop ----------

    private suspend fun runLoop(def: MacroDefinition, variables: Map<String, String>) {
        state = State.RUNNING
        startedAt = SystemClock.elapsedRealtime()
        val repeat = def.playback.repeat
        var iteration = 0
        try {
            while (true) {
                iteration++
                publish(
                    def = def, stepIndex = -1, iteration = iteration,
                    action = "iteration $iteration"
                )
                logLine(0, "Iteration $iteration started")
                val outcome = runOnce(def, variables, iteration)
                if (outcome == RunOutcome.STOPPED) return
                if (repeat > 0 && iteration >= repeat) break
                // loop mode (repeat == 0): run until stopped
            }
            state = State.IDLE
            logLine(0, "Run finished: ${def.name}")
            publish(def = def, stepIndex = def.steps.size, iteration = iteration, action = "done")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = State.IDLE
            logLine(2, "Run failed: ${e.message ?: e.javaClass.simpleName}")
            publish(def = def, stepIndex = progress.stepIndex, iteration = iteration, action = "failed")
        }
    }

    private enum class RunOutcome { COMPLETED, STOPPED }

    private suspend fun runOnce(
        def: MacroDefinition,
        variables: Map<String, String>,
        iteration: Int
    ): RunOutcome {
        val steps = def.steps
        var i = 0
        while (i < steps.size) {
            if (state == State.IDLE) return RunOutcome.STOPPED
            val raw = steps[i]
            val step = MacroVariables.resolve(raw, variables)
            awaitWhilePaused()
            if (step.delayMs > 0) {
                val scaled = (step.delayMs * def.playback.speed).toLong().coerceAtLeast(0)
                interruptibleDelay(scaled)
                awaitWhilePaused()
            }
            publish(
                def = def, stepIndex = i, iteration = iteration,
                action = step.action, stepCount = steps.size
            )
            val attempts = 1 + (step.retries ?: 0)
            var lastError: String? = null
            var ok = false
            for (attempt in 1..attempts) {
                if (state == State.IDLE) return RunOutcome.STOPPED
                if (attempt > 1) {
                    logLine(1, "Step ${i + 1} (${step.action}) retry $attempt/$attempts")
                    interruptibleDelay(400)
                    awaitWhilePaused()
                }
                lastError = execute(step, def)
                if (lastError == null) {
                    ok = true
                    break
                }
            }
            if (!ok) {
                val msg = "Step ${i + 1} (${step.action}) failed: $lastError"
                if (def.playback.onError == "skip") {
                    logLine(1, "$msg — skipped")
                    i++
                    continue
                }
                logLine(2, msg)
                state = State.IDLE
                publish(def = def, stepIndex = i, iteration = iteration, action = "failed")
                return RunOutcome.STOPPED
            }
            i++
        }
        return RunOutcome.COMPLETED
    }

    /** Returns null on success, the failure reason otherwise. */
    private suspend fun execute(step: MacroStep, def: MacroDefinition): String? {
        return try {
            when (step.action) {
                MacroStep.CLICK, MacroStep.DBLCLICK, MacroStep.LONG_PRESS ->
                    clickStep(step, def)
                MacroStep.DRAG -> dragStep(step)
                MacroStep.SCROLL -> scrollStep(step)
                MacroStep.INPUT -> jsStep(fillScript(step), "input")
                MacroStep.CHANGE -> jsStep(fillScript(step), "select")
                MacroStep.KEY -> keyStep(step)
                MacroStep.NAVIGATE -> {
                    val url = step.url ?: step.value
                        ?: return "no url recorded"
                    host.macroLoadUrl(url)
                    awaitSettle()
                    null
                }
                MacroStep.BACK -> { host.macroGoBack(); awaitSettle(); null }
                MacroStep.FORWARD -> { host.macroGoForward(); awaitSettle(); null }
                MacroStep.RELOAD -> { host.macroReload(); awaitSettle(); null }
                MacroStep.TAB_SWITCH -> tabSwitchStep(step)
                MacroStep.NEW_TAB -> {
                    host.macroNewTab(step.url ?: "about:home")
                    awaitSettle()
                    null
                }
                MacroStep.WAIT -> {
                    interruptibleDelay((step.timeoutMs ?: 1_000L).coerceAtLeast(0))
                    null
                }
                MacroStep.WAIT_ELEMENT -> waitElementStep(step, def)
                MacroStep.SCREENSHOT -> screenshotStep(step)
                else -> "unknown action ${step.action}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ---------- step executors ----------

    private suspend fun clickStep(step: MacroStep, def: MacroDefinition): String? {
        // Selector first — it survives layout changes; coordinates are the
        // fallback the recorder captured for exactly this moment.
        if (!step.selector.isNullOrBlank()) {
            val err = jsStep(clickScript(step), "click")
            if (err == null) return null
            logLine(1, "selector click missed ($err); falling back to coordinates")
        }
        val x = step.x ?: return "no selector and no coordinates"
        val y = step.y ?: return "no selector and no coordinates"
        val view = host.macroEngineView() ?: return "no engine view (homepage tab?)"
        val err = dispatchTap(view, x, y, step.action)
        if (err != null) return err
        if (step.action == MacroStep.DBLCLICK) {
            interruptibleDelay(80)
            dispatchTap(view, x, y, MacroStep.CLICK)
        }
        awaitSettle()
        return null
    }

    private suspend fun dispatchTap(
        view: android.view.View,
        cssX: Float,
        cssY: Float,
        action: String
    ): String? {
        // CSS px → view px. The page's own layout viewport is the scale the
        // coordinates were captured in; when it cannot be read (a page that
        // never answered), the view's own size is the honest fallback.
        val vw = readViewportWidth(view) ?: view.width.toFloat().coerceAtLeast(1f)
        val sx = view.width.toFloat() / vw
        val vx = (cssX * sx).roundToInt().coerceIn(1, view.width - 1)
        val vy = (cssY * sx).roundToInt().coerceIn(1, view.height - 1)
        val downAt = SystemClock.uptimeMillis()
        val hold = if (action == MacroStep.LONG_PRESS) 650L else 48L
        return try {
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, vx.toFloat(), vy.toFloat(), 0)
            view.dispatchTouchEvent(down)
            down.recycle()
            // The hold is a real wait in this coroutine (the runner rides
            // the main dispatcher): the UI thread stays alive, the press
            // genuinely lasts, and stop() can still cancel mid-hold.
            interruptibleDelay(hold)
            val upAt = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(upAt, upAt, MotionEvent.ACTION_UP, vx.toFloat(), vy.toFloat(), 0)
            view.dispatchTouchEvent(up)
            up.recycle()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message
        }
    }

    /** The page's layout viewport width in CSS px, or null when the page never answered. */
    private suspend fun readViewportWidth(view: android.view.View): Float? {
        val script = "(function () { return String(document.documentElement.clientWidth || 0); })()"
        var answer: String? = null
        evalAwait(script) { answer = it }
        return answer?.let { MacroRecorderCodec.jsonStringOf(it) }?.toFloatOrNull()?.takeIf { it > 0f }
    }

    /**
     * Runs [script] through the host and suspends until the engine's answer
     * lands (or a 2s bound passes — a page that never answers must not hang
     * a run). The answer is handed to [onResult] as the raw JSON-encoded
     * payload, exactly as evaluateJs delivered it.
     */
    private suspend fun evalAwait(script: String, onResult: (String?) -> Unit) {
        var answered = false
        suspendCancellableCoroutine { cont ->
            host.macroEval(script) { payload ->
                onResult(payload)
                answered = true
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            cont.invokeOnCancellation { }
        }
        if (!answered) return
    }

    private suspend fun dragStep(step: MacroStep): String? {
        val view = host.macroEngineView() ?: return "no engine view"
        val x = step.x ?: return "no start"
        val y = step.y ?: return "no start"
        val ex = step.endX ?: x
        val ey = step.endY ?: y
        val vw = readViewportWidth(view) ?: view.width.toFloat().coerceAtLeast(1f)
        val s = view.width.toFloat() / vw
        val vx = (x * s).roundToInt().coerceIn(1, view.width - 1)
        val vy = (y * s).roundToInt().coerceIn(1, view.height - 1)
        val vex = (ex * s).roundToInt().coerceIn(1, view.width - 1)
        val vey = (ey * s).roundToInt().coerceIn(1, view.height - 1)
        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, vx.toFloat(), vy.toFloat(), 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        for (i in 1..GESTURE_STEPS) {
            interruptibleDelay(16)
            val t = SystemClock.uptimeMillis()
            val fx = vx + (vex - vx) * i / GESTURE_STEPS
            val fy = vy + (vey - vy) * i / GESTURE_STEPS
            val move = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, fx.toFloat(), fy.toFloat(), 0)
            view.dispatchTouchEvent(move)
            move.recycle()
        }
        val t1 = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(t1, t1, MotionEvent.ACTION_UP, vex.toFloat(), vey.toFloat(), 0)
        view.dispatchTouchEvent(up)
        up.recycle()
        awaitSettle()
        return null
    }

    private fun scrollStep(step: MacroStep): String? {
        val dx = step.dx ?: 0f
        val dy = step.dy ?: 0f
        val script = """
            (function () {
              try {
                window.scrollBy(${dx.toInt()}, ${dy.toInt()});
                return 'ok';
              } catch (e) { return String(e); }
            })()
        """.trimIndent()
        var failed = true
        host.macroEval(script) { failed = it == null }
        return if (failed) "scroll not delivered" else null
    }

    private fun clickScript(step: MacroStep): String {
        val sel = (step.selector ?: "").replace("\\", "\\\\").replace("'", "\\'")
        val dbl = step.action == MacroStep.DBLCLICK
        return """
            (function () {
              try {
                var el = document.querySelector('$sel');
                if (!el) return 'element not found';
                el.scrollIntoView({ block: 'center', inline: 'nearest' });
                var evs = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                for (var i = 0; i < evs.length; i++) {
                  el.dispatchEvent(new MouseEvent(evs[i], { bubbles: true, cancelable: true, view: window }));
                }
                ${if (dbl) "el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));" else ""}
                return 'ok';
              } catch (e) { return String(e); }
            })()
        """.trimIndent()
    }

    private fun fillScript(step: MacroStep): String {
        val sel = (step.selector ?: "").replace("\\", "\\\\").replace("'", "\\'")
        val value = (step.value ?: "").replace("\\", "\\\\").replace("'", "\\'")
        val isSelect = step.tagName?.equals("select", ignoreCase = true) == true
        val setValue = if (isSelect) {
            """
              var want = '$value';
              var hit = false;
              for (var i = 0; i < el.options.length; i++) {
                if (el.options[i].value === want || el.options[i].text === want) {
                  el.selectedIndex = i; hit = true; break;
                }
              }
              if (!hit) return 'option not found';
            """
        } else {
            """
              try {
                var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(el, '$value');
              } catch (e) { el.value = '$value'; }
            """
        }
        val events = if (isSelect) {
            "el.dispatchEvent(new Event('change', { bubbles: true }));"
        } else {
            "el.dispatchEvent(new Event('input', { bubbles: true }));" +
                " el.dispatchEvent(new Event('change', { bubbles: true }));"
        }
        return """
            (function () {
              try {
                var el = document.querySelector('$sel');
                if (!el) return 'element not found';
                el.scrollIntoView({ block: 'center', inline: 'nearest' });
                el.focus();
                $setValue
                $events
                return 'ok';
              } catch (e) { return String(e); }
            })()
        """.trimIndent()
    }

    private fun keyStep(step: MacroStep): String? {
        val name = (step.value ?: "").uppercase()
        val keyCode = when (name) {
            "ENTER" -> KeyEvent.KEYCODE_ENTER
            "TAB" -> KeyEvent.KEYCODE_TAB
            "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
            "BACKSPACE", "DEL" -> KeyEvent.KEYCODE_DEL
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            else -> return "unsupported key $name"
        }
        val view = host.macroEngineView() ?: return "no engine view"
        val t = SystemClock.uptimeMillis()
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        view.dispatchKeyEvent(down)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        view.dispatchKeyEvent(up)
        awaitSettleQuiet()
        return null
    }

    private suspend fun tabSwitchStep(step: MacroStep): String? {
        val label = step.value ?: return "no tab label"
        // The recorded label is the tab's title at record time; the host
        // resolves it (prefix match over the open tabs, position fallback).
        val idx = host.macroTabIndexFor(label)
        if (idx != null && host.macroSelectTabIndex(idx)) {
            awaitSettle()
            return null
        }
        return "tab not found: $label"
    }

    private suspend fun waitElementStep(step: MacroStep, def: MacroDefinition): String? {
        val sel = step.selector ?: return "no selector"
        val escaped = sel.replace("\\", "\\\\").replace("'", "\\'")
        val timeout = step.timeoutMs ?: def.playback.defaultTimeoutMs
        val script = """
            (function () {
              try { return document.querySelector('$escaped') ? 'found' : 'absent'; }
              catch (e) { return 'bad selector'; }
            })()
        """.trimIndent()
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            awaitWhilePaused()
            var answer: String? = null
            suspendCancellableCoroutine { cont ->
                host.macroEval(script) {
                    answer = it
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                cont.invokeOnCancellation { }
            }
            when (answer?.let { MacroRecorderCodec.jsonStringOf(it) }) {
                "found" -> return null
                "bad selector" -> return "bad selector"
            }
            interruptibleDelay(ELEMENT_POLL_MS)
        }
        return "element not found within ${timeout}ms"
    }

    private suspend fun screenshotStep(step: MacroStep): String? {
        val bitmap = suspendCancellableCoroutine { cont ->
            var done = false
            host.macroCapturePixels {
                if (!done) {
                    done = true
                    if (cont.isActive) cont.resumeWith(Result.success(it))
                }
            }
        }
        if (bitmap == null) return "capture failed"
        val name = host.macroSaveCheckpoint(step.note ?: "checkpoint", bitmap)
        logLine(0, "Checkpoint saved: $name")
        return null
    }

    private suspend fun jsStep(script: String, what: String): String? {
        var failure: String? = null
        evalAwait(script) { payload ->
            val answer = payload?.let { MacroRecorderCodec.jsonStringOf(it) }
            if (answer != "ok") failure = answer ?: "$what not delivered"
        }
        return failure
    }

    // ---------- plumbing ----------

    /**
     * Parks the loop while paused. A spin would freeze the main thread;
     * instead the loop sleeps in short slices and re-reads the state, so a
     * resume (or a stop) lands within one slice.
     */
    private suspend fun awaitWhilePaused() {
        while (state == State.PAUSED) {
            delay(150)
        }
    }

    private suspend fun interruptibleDelay(ms: Long) {
        if (ms <= 0) return
        delay(ms)
    }

    private suspend fun awaitSettle() {
        interruptibleDelay(SETTLE_MS)
    }

    private fun awaitSettleQuiet() {
        // A key step's settle is short and synchronous enough not to need one.
    }

    private fun publish(
        def: MacroDefinition? = null,
        stepIndex: Int = progress.stepIndex,
        iteration: Int = progress.iteration,
        action: String = progress.currentAction,
        stepCount: Int = progress.stepCount
    ) {
        val d = def ?: currentMacro ?: return
        val total = if (d.playback.repeat > 0) d.playback.repeat else 0
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val stepsDone = (iteration - 1) * d.steps.size + (stepIndex + 1)
        val totalSteps = if (total > 0) total * d.steps.size else stepsDone.coerceAtLeast(1)
        val eta = if (stepsDone > 0 && elapsed > 0) {
            elapsed * (totalSteps - stepsDone) / stepsDone
        } else 0L
        progress = Progress(
            state = state,
            macroId = d.id,
            macroName = d.name,
            stepIndex = stepIndex,
            stepCount = d.steps.size,
            iteration = iteration,
            totalIterations = total,
            currentAction = action,
            etaMs = eta
        )
        val snapshot = listeners.toList()
        for (l in snapshot) l(progress)
    }

    private fun logLine(level: Int, message: String) {
        log.add(MacroLogEntry(SystemClock.elapsedRealtime() - startedAt, progress.stepIndex, message, level))
        if (log.size > 500) log.removeAt(0)
        Log.d(TAG, message)
    }
}
