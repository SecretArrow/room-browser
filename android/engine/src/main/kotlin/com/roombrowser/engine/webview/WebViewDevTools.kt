package com.roombrowser.engine.webview

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.webkit.WebViewFeature
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineInspector
import com.roombrowser.engine.devtools.EngineNetworkSignal

/**
 * What the WebView edition can serve to Developer Tools, and the handle that
 * serves it.
 *
 * The declared set is the set this build ACTUALLY implements, and one source
 * feeds both the host's capability report and every session's inspector, so the
 * screen can never describe a capability the engine does not hand out. The one
 * input the DEVICE decides rather than this build is whether it supports
 * document-start scripts -- see [capabilities].
 */
internal object WebViewDevTools {

    /**
     * The armed flag inside [CONSOLE_PATCH], as text.
     *
     * Declared before the patch because a `const val` initialiser may only
     * reference a constant that is already initialised; [consolePatch] replaces
     * this marker with the state at install time.
     */
    private const val ARMED_PLACEHOLDER = "__RB_CONSOLE_ARMED__"

    /** What this build serves on a WebView that supports document-start scripts. */
    val CAPABILITIES: DeveloperToolsCapabilities = DeveloperToolsCapabilities(
        capabilities = setOf(
            DevToolsCapability.PAGE_SCRIPTING,
            DevToolsCapability.CONSOLE_CAPTURE,
            DevToolsCapability.ENGINE_CONSOLE,
            DevToolsCapability.NETWORK_REQUEST_LINE
        ),
        // The one absence that is a property of the ENGINE rather than of work
        // not yet done. A request is reported before it is sent and the reply
        // is never handed back, so the only way to a status would be to
        // intercept the body -- which this app deliberately does not do.
        notes = mapOf(
            DevToolsCapability.NETWORK_RESPONSE_HEADERS to
                "WebView reports a request to shouldInterceptRequest before it is sent and never hands back the response, so status and response headers are not observable without intercepting the body — which this app deliberately does not do because it would break streaming."
        )
    )

    /**
     * The declared set, given the one capability input the device decides.
     *
     * The page-world console patch is installed with
     * [androidx.webkit.WebViewCompat.addDocumentStartJavaScript], so on a WebView
     * too old for [androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT] the
     * patch never runs and [DevToolsCapability.CONSOLE_CAPTURE] would describe a
     * feed that can never fill. The engine's own message channel does not depend
     * on that feature, so it stays.
     *
     * Pure, and takes the answer rather than asking for it, so both branches are
     * testable off-device -- the device query itself cannot run in a JVM test.
     */
    fun capabilities(documentStartScripts: Boolean): DeveloperToolsCapabilities =
        if (documentStartScripts) {
            CAPABILITIES
        } else {
            CAPABILITIES.copy(
                capabilities = CAPABILITIES.capabilities - DevToolsCapability.CONSOLE_CAPTURE,
                notes = CAPABILITIES.notes + (
                    DevToolsCapability.CONSOLE_CAPTURE to
                        "This WebView is too old for document-start scripts, which are how the page's own console is wrapped, so page console output cannot be captured on this device. The engine's own messages still reach this feed."
                    )
            )
        }

    /**
     * The page-world console patch, installed at document start by the session
     * itself.
     *
     * It is NOT one of the app's `EnginePageScripts`: those three are profile
     * policy, and this is an inspection concern the engine owns. It wraps the
     * console at document start and buffers into a bounded ring, but forwards
     * over the `RoomConsole` bridge only while [WebViewInspector] has armed it,
     * so a page that logs before DevTools opens is still visible once it does.
     *
     * Everything crossing the bridge is a single JSON STRING: `addJavascriptInterface`
     * does not carry a complex JS value, so the patch stringifies and the native
     * side parses. The wrapper must never throw into the page's own call -- the
     * original method is always invoked, in a `finally`-equivalent position.
     */
    const val CONSOLE_PATCH = """
(function () {
  if (window.__rbConsole && window.__rbConsole.rb) return;
  var BUFFER_MAX = 200;
  var TEXT_MAX = 4000;
  var buffer = [];
  var armed = $ARMED_PLACEHOLDER;

  function format(value) {
    try {
      if (typeof value === 'string') return value;
      if (value === null) return 'null';
      if (value === undefined) return 'undefined';
      if (typeof value === 'object') return JSON.stringify(value);
      return String(value);
    } catch (e) {
      return '[unserialisable]';
    }
  }

  function send(entry) {
    try {
      var bridge = window.RoomConsole;
      if (bridge && typeof bridge.entry === 'function') {
        bridge.entry(JSON.stringify(entry));
      }
    } catch (e) {}
  }

  function record(level, source, line, values) {
    try {
      var parts = [];
      for (var i = 0; i < values.length; i++) parts.push(format(values[i]));
      var body = parts.join(' ');
      if (body.length > TEXT_MAX) body = body.slice(0, TEXT_MAX);
      var entry = { level: level, text: body, source: source || null, line: line || 0, ts: Date.now() };
      buffer.push(entry);
      while (buffer.length > BUFFER_MAX) buffer.shift();
      if (armed) send(entry);
    } catch (e) {}
  }

  var LEVELS = ['log', 'info', 'warn', 'error', 'debug', 'assert'];
  for (var i = 0; i < LEVELS.length; i++) {
    (function (level) {
      var original = console[level];
      console[level] = function () {
        try {
          var args = Array.prototype.slice.call(arguments);
          if (level === 'assert') {
            if (!args[0]) record(level, location.href, 0, args.slice(1));
          } else {
            record(level, location.href, 0, args);
          }
        } catch (e) {}
        if (original) return original.apply(console, arguments);
      };
    })(LEVELS[i]);
  }

  window.addEventListener('error', function (event) {
    record('error', event.filename || location.href, event.lineno || 0, [String(event.message || 'error')]);
  });
  window.addEventListener('unhandledrejection', function (event) {
    record('error', location.href, 0, ['Unhandled rejection: ' + format(event.reason)]);
  });

  window.__rbConsole = {
    rb: true,
    arm: function () {
      if (armed) return;
      armed = true;
      var pending = buffer;
      buffer = [];
      for (var j = 0; j < pending.length; j++) send(pending[j]);
    },
    disarm: function () { armed = false; },
    buffered: function () { return buffer.length; }
  };
})();
"""

    fun inspector(session: WebViewEngineSession): WebViewInspector = WebViewInspector(session)

    /**
     * The patch with its armed state already in it.
     *
     * The state has to be part of the SCRIPT rather than only of the page world,
     * because a page-world flag dies with its document: on the next navigation a
     * patch that had been armed the old way started disarmed, so a panel left
     * open went quiet on every page after the first. Installed per arm state, the
     * new document is right from its very first line, with no page-world flag to
     * re-set and no buffered flush arriving after the engine's copy of the same
     * line.
     */
    fun consolePatch(armed: Boolean): String =
        CONSOLE_PATCH.replace(ARMED_PLACEHOLDER, armed.toString())
}

/**
 * The inspection handle for ONE session.
 *
 * A capture sink belongs to whoever opened the panel, not to the engine, so the
 * handle is per session even though the capability set is shared: the sink and
 * the arm state are the only per-session things there are.
 *
 * The console funnel carries BOTH origins, told apart by
 * [EngineConsoleMessage.fromEngine]: the engine's own `onConsoleMessage` reports
 * true, the page patch's bridge reports false.
 *
 * THREADING. [onConsole] and [onNetwork] are called from the engine's own
 * threads -- the chrome client on the UI thread, the page bridge on WebView's
 * JavaBridge thread, `shouldInterceptRequest` on a background thread -- and the
 * facade requires the sink on the main thread, so every delivery is posted.
 */
internal class WebViewInspector(
    private val session: WebViewEngineSession
) : EngineInspector {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var consoleSink: ((EngineConsoleMessage) -> Unit)? = null

    @Volatile
    private var networkSink: ((EngineNetworkSignal) -> Unit)? = null

    /**
     * Console lines the page patch reported, keyed by text, so the engine's copy
     * of the same line can be recognised and dropped.
     *
     * WebView calls `onConsoleMessage` for EVERY console message, the page's own
     * `console.log` included, so one line arrives here twice: once decoded from
     * the patch's bridge payload and once from the engine's own callback. The
     * patch's copy is the authoritative one -- it is the capture this edition
     * declares, and it carries the patch's level and location -- so the engine's
     * copy is dropped *when it is a copy*. A message the patch never saw, a CSP
     * violation or a deprecation the engine raised itself, has no counterpart
     * here and is still delivered.
     *
     * KEYED ON TEXT ALONE, because the two paths agree on nothing else: their
     * level vocabularies differ (`assert` and WebView's `TIP` map differently)
     * and the patch reports line 0 where the engine reports the real line.
     *
     * Main-thread only -- see [onConsole].
     */
    private val pageConsoleKeys = LinkedHashMap<String, Long>()

    /**
     * Engine copies that arrived with nothing listening.
     *
     * The engine reports a page's console from document start, which is long
     * before Developer Tools is opened, and an engine copy with no sink was
     * simply dropped -- so on a WebView too old for the page-world patch, where
     * this callback is the only source there is, the panel opened on an empty
     * feed and the page's own errors were gone for good. Bounded, oldest first.
     *
     * Main-thread only, like [pageConsoleKeys].
     */
    private val engineBacklog = EngineConsoleBacklog(ENGINE_BACKLOG_MAX)

    override val capabilities: DeveloperToolsCapabilities =
        WebViewDevTools.capabilities(
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        )

    fun onConsole(message: EngineConsoleMessage) {
        // Every delivery lands on the main thread first, so [pageConsoleKeys]
        // and [engineBacklog] are written and read by one thread only.
        if (message.fromEngine) {
            mainHandler.post { holdEngineCopy(message) }
        } else {
            mainHandler.post {
                notePageConsole(message.text)
                consoleSink?.invoke(message)
            }
        }
    }

    private fun holdEngineCopy(message: EngineConsoleMessage) {
        val sink = consoleSink ?: return engineBacklog.add(message)
        mainHandler.postDelayed({ if (!echoesPageConsole(message.text)) sink.invoke(message) }, ECHO_HOLD_MS)
    }

    fun onNetwork(signal: EngineNetworkSignal) {
        mainHandler.post { networkSink?.invoke(signal) }
    }

    /**
     * Records that the page patch reported [text], so the engine's copy of the
     * same line can be told from a line the patch never saw.
     */
    private fun notePageConsole(text: String) {
        val now = SystemClock.uptimeMillis()
        pageConsoleKeys[text] = now
        while (pageConsoleKeys.size > PAGE_CONSOLE_KEYS_MAX) {
            val oldest = pageConsoleKeys.minByOrNull { it.value }?.key ?: return
            pageConsoleKeys.remove(oldest)
        }
    }

    /** Whether the page patch reported [text] recently enough to be the origin of an engine copy. */
    private fun echoesPageConsole(text: String): Boolean {
        val seen = pageConsoleKeys[text] ?: return false
        return SystemClock.uptimeMillis() - seen <= ECHO_WINDOW_MS
    }

    override fun startConsoleCapture(sink: (EngineConsoleMessage) -> Unit) {
        // Posted so the replay cannot interleave with a delivery already queued,
        // and so the backlog stays main-thread-only.
        mainHandler.post {
            consoleSink = sink
            engineBacklog.drain().forEach(sink)
            session.armPageConsole(true)
        }
    }

    override fun stopConsoleCapture() {
        consoleSink = null
        session.armPageConsole(false)
    }

    override fun startNetworkCapture(sink: (EngineNetworkSignal) -> Unit) {
        networkSink = sink
    }

    override fun stopNetworkCapture() {
        networkSink = null
    }

    override fun close() {
        consoleSink = null
        networkSink = null
        engineBacklog.clear()
        session.armPageConsole(false)
    }

    private companion object {
        /**
         * How long an engine-origin message is held before it is delivered.
         *
         * The patch's copy and the engine's copy of one line race for the main
         * thread, and the hold is what makes the outcome the same either way: by
         * the time it elapses the patch's copy has landed and been recorded, so
         * a duplicate is recognised. Short enough that a genuine engine message
         * is not visibly late.
         */
        const val ECHO_HOLD_MS = 250L

        /** How recent a patch line must be for an engine message to count as its copy. */
        const val ECHO_WINDOW_MS = 2_000L

        /** Texts remembered for [ECHO_WINDOW_MS]. A page logging faster than this loses only the engine's copy of the oldest lines. */
        const val PAGE_CONSOLE_KEYS_MAX = 512

        /** Engine copies kept while no panel is listening. Bounded, so an uninspected page cannot grow one forever. */
        const val ENGINE_BACKLOG_MAX = 512
    }
}

/**
 * The engine's console copies, held until a panel asks for them.
 *
 * Separate from the inspector, and free of Android, so the drop-oldest and
 * drain behaviour is pinned by a JVM test rather than by a device.
 */
internal class EngineConsoleBacklog(private val capacity: Int) {

    private val entries = ArrayDeque<EngineConsoleMessage>()

    fun add(message: EngineConsoleMessage) {
        entries.addLast(message)
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Everything held, oldest first. Empties the backlog. */
    fun drain(): List<EngineConsoleMessage> = entries.toList().also { entries.clear() }

    fun clear() = entries.clear()
}
