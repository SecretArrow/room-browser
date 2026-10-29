package com.roombrowser.engine.webview

/**
 * The device-identity half of a device profile, as page-world JavaScript:
 * the claimed RAM, core count and GPU strings, and nothing else.
 *
 * WHY THIS EXISTS AGAIN, AND WHY SO SMALL. The pre-191 shim carried the whole
 * device identity in JavaScript -- navigator.userAgent, the brand list, a
 * hand-built `userAgentData` with a `getHighEntropyValues` that answered on a
 * microtask -- and every one of those JS surfaces was a tell the engine's own
 * answers are not: a spoofed accessor has a `.get` function where the platform
 * ships a native one, and a synthesized object has the shape of a thing
 * assembled by hand. The wire identity work since then moved everything the
 * ENGINE can answer natively down to the engine: `settings.userAgentString`
 * drives `navigator.userAgent` natively, and the client-hint headers are
 * derived from that same string, so the UA a page reads back is the UA the
 * request carried with zero JavaScript involved. What the engine cannot
 * derive from the wire is exactly what a device profile still needs to claim
 * in the page world -- the RAM, the core count and the SoC strings, the three
 * numbers a tracker cross-references against the UA to tell two "devices" on
 * one physical handset apart. This object claims those, and ONLY those.
 *
 * THE CONSISTENCY CONTRACT. The caller passes the values of the SAME Device
 * the wire UA was tuned from (`UserAgents.device(settings)` on both sides),
 * so a page comparing `navigator.deviceMemory` against `Sec-CH-UA-Model`'s
 * handset finds one device, not two. A caller that passes values from a
 * different device than the wire claims rebuilds the split-identity bug this
 * whole subsystem exists to keep dead.
 *
 * THE GUARD. The registration wraps this through [SignInHostGuard.wrap] like
 * every other document-start script, so a Google-owned host runs none of it:
 * the bare-WebView bar on Google's surfaces is absolute, device profile or
 * not.
 */
object DeviceIdentityScript {

    /**
     * The identity script for one device's claims. Parameters are plain
     * values, not a `Device`, so the engine module keeps its dependency
     * surface at primitives -- the caller above the facade resolves the
     * catalogue entry.
     */
    fun scriptFor(
        deviceMemoryGb: Int,
        hardwareConcurrency: Int,
        gpuVendor: String,
        gpuRenderer: String,
    ): String = IDENTITY
        .replace("__MEMORY__", deviceMemoryGb.toString())
        .replace("__CORES__", hardwareConcurrency.toString())
        .replace("__GPU_VENDOR__", jsString(gpuVendor))
        .replace("__GPU_RENDERER__", jsString(gpuRenderer))

    // No template literals and no "$" anywhere: the script is spliced into a
    // Kotlin string, and a stray dollar would be read as interpolation.
    private val IDENTITY = """
(function () {
  'use strict';
  try {
    var MEMORY = __MEMORY__;
    var CORES = __CORES__;
    var GPU_VENDOR = __GPU_VENDOR__;
    var GPU_RENDERER = __GPU_RENDERER__;

    function define(target, prop, value) {
      try {
        Object.defineProperty(target, prop, {
          get: function () { return value; },
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    define(Navigator.prototype, 'deviceMemory', MEMORY);
    define(Navigator.prototype, 'hardwareConcurrency', CORES);

    // WebGL reports the SoC. An Adreno string on a handset the identity says
    // ships an Exynos is exactly the contradiction this exists to avoid.
    function patchGL(ctx) {
      if (!ctx || !ctx.prototype || !ctx.prototype.getParameter) return;
      var proto = ctx.prototype;
      var original = proto.getParameter;
      var patched = function (pname) {
        if (pname === 37445) return GPU_VENDOR;
        if (pname === 37446) return GPU_RENDERER;
        return original.call(this, pname);
      };
      // Keep the patched accessor reporting as native to a casual toString().
      try {
        patched.toString = function () { return original.toString(); };
      } catch (e) {}
      try {
        Object.defineProperty(proto, 'getParameter', {
          value: patched,
          configurable: true,
          writable: true
        });
      } catch (e) {}
    }
    if (typeof WebGLRenderingContext !== 'undefined') patchGL(WebGLRenderingContext);
    if (typeof WebGL2RenderingContext !== 'undefined') patchGL(WebGL2RenderingContext);
  } catch (e) {
    // A page must still load even if the platform refuses one of these.
  }
})();
"""

    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
        return "'$escaped'"
    }
}
