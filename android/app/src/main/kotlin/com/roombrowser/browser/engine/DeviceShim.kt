package com.roombrowser.browser.engine

import com.roombrowser.domain.model.Device

/**
 * The JavaScript a profile runs before any page script, so the properties a
 * page reads agree with the device the profile claims to be.
 *
 * Scope, deliberately: this covers the *non-geometric* surface — the things a
 * handset determines and that the real hardware therefore cannot contradict.
 * Screen size, viewport size, `devicePixelRatio`, `screen.orientation` and the
 * layout that follows from them are NOT touched, because the page is really
 * laid out on this phone's screen and a claim that disagreed with the viewport
 * would render the page wrong *and* be the cheapest spoofing signal there is.
 * See SECURITY.md.
 *
 * What is presented:
 *  - `navigator.userAgent` (the device's real Chrome string)
 *  - `navigator.userAgentData` — brands, mobile, platform, and the
 *    high-entropy values (`model`, `platformVersion`, `uaFullVersion`,
 *    `fullVersionList`, `formFactor`), which are the client hints that would
 *    otherwise name the actual handset
 *  - `navigator.deviceMemory`, `navigator.hardwareConcurrency`,
 *    `navigator.platform`
 *  - the WebGL `UNMASKED_VENDOR_WEBGL` / `UNMASKED_RENDERER_WEBGL` strings
 *
 * Honest limits: a page that inspects `Function.prototype.toString` on the
 * patched accessors, compares dozens of unrelated signals, or fingerprints
 * the GPU by timing a draw call can still tell. This raises the cost of the
 * cheap checks; it is not and does not claim to be undetectable.
 */
object DeviceShim {

    /** The document-start script for [device]. */
    fun scriptFor(device: Device): String {
        val chromeMajor = device.chromeVersion.substringBefore('.')
        val mobile = device.formFactor != "tablet"
        return TEMPLATE
            .replace("__UA__", jsString(device.userAgent))
            .replace("__CHROME__", jsString(device.chromeVersion))
            .replace("__CHROME_MAJOR__", jsString(chromeMajor))
            .replace("__ANDROID__", jsString(device.androidVersion))
            .replace("__MODEL__", jsString(device.code))
            .replace("__MOBILE__", mobile.toString())
            .replace("__FORM__", jsString(if (mobile) "Mobile" else "Tablet"))
            .replace("__MEMORY__", device.deviceMemoryGb.toString())
            .replace("__CORES__", device.hardwareConcurrency.toString())
            .replace("__GPU_VENDOR__", jsString(device.gpuVendor))
            .replace("__GPU_RENDERER__", jsString(device.gpuRenderer))
    }

    /** A JS string literal, so a model code can never break out of the script. */
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

    // No template literals and no "$" anywhere: the script is spliced into a
    // Kotlin string, and a stray dollar would be read as interpolation.
    private val TEMPLATE = """
(function () {
  'use strict';
  try {
    var UA = __UA__;
    var CHROME = __CHROME__;
    var CHROME_MAJOR = __CHROME_MAJOR__;
    var ANDROID = __ANDROID__;
    var MODEL = __MODEL__;
    var MOBILE = __MOBILE__;
    var FORM = __FORM__;
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

    define(Navigator.prototype, 'userAgent', UA);
    define(Navigator.prototype, 'platform', 'Linux armv8l');
    define(Navigator.prototype, 'deviceMemory', MEMORY);
    define(Navigator.prototype, 'hardwareConcurrency', CORES);

    // Client hints. These are the ones that name the handset outright, so a
    // profile that shims the UA but not these is not presenting a device.
    function brands(full) {
      var chromium = full ? CHROME : CHROME_MAJOR;
      return [
        { brand: 'Chromium', version: chromium },
        { brand: 'Google Chrome', version: chromium },
        { brand: 'Not?A_Brand', version: '24' }
      ];
    }
    var uaData = {
      brands: brands(false),
      mobile: MOBILE,
      platform: 'Android',
      getHighEntropyValues: function (hints) {
        var wanted = hints || [];
        var out = {};
        for (var i = 0; i < wanted.length; i++) {
          switch (wanted[i]) {
            case 'architecture': out.architecture = ''; break;
            case 'bitness': out.bitness = ''; break;
            case 'formFactor': out.formFactor = FORM; break;
            case 'model': out.model = MODEL; break;
            case 'platform': out.platform = 'Android'; break;
            case 'platformVersion': out.platformVersion = ANDROID + '.0.0'; break;
            case 'uaFullVersion': out.uaFullVersion = CHROME; break;
            case 'fullVersionList': out.fullVersionList = brands(true); break;
            case 'wow64': out.wow64 = false; break;
            default: break;
          }
        }
        return Promise.resolve(out);
      },
      toJSON: function () {
        return { brands: brands(false), mobile: MOBILE, platform: 'Android' };
      }
    };
    try {
      Object.defineProperty(Navigator.prototype, 'userAgentData', {
        get: function () { return uaData; },
        configurable: true,
        enumerable: true
      });
    } catch (e) {}

    // WebGL reports the SoC. An Adreno string on a handset that ships an
    // Exynos is exactly the kind of contradiction this exists to avoid.
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
}
