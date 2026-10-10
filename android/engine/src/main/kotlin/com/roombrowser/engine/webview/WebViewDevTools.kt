package com.roombrowser.engine.webview

import android.os.Handler
import android.os.Looper
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
 * screen can never describe a capability the engine does not hand out.
 */
internal object WebViewDevTools {

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
  var armed = false;

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

    override val capabilities: DeveloperToolsCapabilities = WebViewDevTools.CAPABILITIES

    fun onConsole(message: EngineConsoleMessage) {
        mainHandler.post { consoleSink?.invoke(message) }
    }

    fun onNetwork(signal: EngineNetworkSignal) {
        mainHandler.post { networkSink?.invoke(signal) }
    }

    override fun startConsoleCapture(sink: (EngineConsoleMessage) -> Unit) {
        consoleSink = sink
        session.armPageConsole(true)
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
        session.armPageConsole(false)
    }
}
