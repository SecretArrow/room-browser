package com.roombrowser.browser.engine

import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.Device

/**
 * The JavaScript a profile runs before any page script, so the properties a
 * page reads agree with what the profile claims to be.
 *
 * Two independent parts, and each is installed only when the profile has
 * actually asked for it:
 *
 *  - the **identity** shim, for a profile presenting a device: the UA, the
 *    client hints, `platform`, `deviceMemory`, `hardwareConcurrency` and the
 *    WebGL vendor/renderer strings. These are the things a handset determines
 *    and that the real hardware therefore cannot contradict.
 *
 *  - the **screen** shim, for a profile whose screen size is set by hand:
 *    `screen.width/height/availWidth/availHeight` and `screen.orientation`.
 *
 * Screen geometry is left alone unless the profile asks for it. The default is
 * the phone's own screen, because the page really is laid out here. A profile
 * that sets a size by hand is making a choice the settings row states plainly,
 * and the cost is in what this deliberately does *not* do: the layout viewport
 * cannot follow the claim, so `innerWidth`, `innerHeight` and
 * `devicePixelRatio` stay the display's own — they are what the compositor
 * actually renders at, and moving them means re-laying the page out, which is
 * the breakage this file exists to avoid. A claimed screen that differs from
 * the phone's is therefore a disagreement a script can find.
 *
 * It is offered anyway because the alternative is worse. Without it, a profile
 * presenting a Galaxy S24 Ultra reports a screen that handset never had — the
 * same contradiction, except that nobody chose it and the settings screen said
 * nothing about it. This way the mismatch is explicit, bounded to the screen
 * family, and stated where the choice is made. See SECURITY.md.
 *
 * Honest limits: a page that inspects `Function.prototype.toString` on the
 * patched accessors, compares dozens of unrelated signals, or fingerprints
 * the GPU by timing a draw call can still tell. This raises the cost of the
 * cheap checks; it is not and does not claim to be undetectable.
 */
object DeviceShim {

    /**
     * The document-start script for a profile. Blank when the profile claims
     * neither a device nor a screen size, which is the default state and the
     * one every profile starts in.
     */
    fun scriptFor(device: Device?, screen: ClaimedScreen? = null): String = buildString {
        if (device != null) append(identityScript(device))
        if (screen != null) append(screenScript(screen))
    }

    /** The identity shim: what the profile presents itself as. */
    private fun identityScript(device: Device): String {
        val chromeMajor = device.chromeVersion.substringBefore('.')
        val mobile = device.formFactor != "tablet"
        return IDENTITY
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

    /** The screen shim: what a page is told the display is. */
    private fun screenScript(screen: ClaimedScreen): String = SCREEN
        .replace("__SCREEN_W__", screen.widthPx.toString())
        .replace("__SCREEN_H__", screen.heightPx.toString())
        .replace("__SCREEN_LANDSCAPE__", screen.isLandscape.toString())
        .replace(
            "__SCREEN_TYPE__",
            jsString(if (screen.isLandscape) "landscape-primary" else "portrait-primary")
        )
        .replace("__SCREEN_ANGLE__", if (screen.isLandscape) "90" else "0")

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
    private val IDENTITY = """
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

    // Installed only for a profile that set a screen size by hand, so the
    // numbers below are always the claimed ones and never a "real" default.
    private val SCREEN = """
(function () {
  'use strict';
  try {
    var SCREEN_W = __SCREEN_W__;
    var SCREEN_H = __SCREEN_H__;
    var LANDSCAPE = __SCREEN_LANDSCAPE__;
    var TYPE = __SCREEN_TYPE__;
    var ANGLE = __SCREEN_ANGLE__;

    function define(target, prop, value) {
      try {
        Object.defineProperty(target, prop, {
          get: function () { return value; },
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    // What the page is told the screen is. Chrome on Android reports the whole
    // display as the available rectangle too — there is no persistent chrome to
    // subtract — so both pairs carry the same numbers rather than inventing a
    // difference a real handset does not have.
    define(Screen.prototype, 'width', SCREEN_W);
    define(Screen.prototype, 'height', SCREEN_H);
    define(Screen.prototype, 'availWidth', SCREEN_W);
    define(Screen.prototype, 'availHeight', SCREEN_H);

    // The layout viewport and the pixel ratio are NOT touched here, on purpose.
    // The viewport is the page's real width and height on this display and the
    // ratio is what the compositor actually renders at; overriding either would
    // re-lay the page out at a size the screen does not have, which is the
    // breakage this file exists to avoid. The consequence is real and is stated
    // in settings and in SECURITY.md: a claimed screen that differs from the
    // phone's is a disagreement with the viewport, and a script can find it.
    // The unit test pins those names out of this script so the trade cannot be
    // undone by a later edit without the test saying so.

    // Orientation follows the shape that was claimed, not the hinge. A profile
    // claiming a landscape screen must not also answer "portrait-primary" —
    // that pairing is the contradiction this shim exists to remove.
    try {
      if (typeof ScreenOrientation !== 'undefined' && ScreenOrientation.prototype) {
        define(ScreenOrientation.prototype, 'type', TYPE);
        define(ScreenOrientation.prototype, 'angle', ANGLE);
      }
    } catch (e) {}
    try {
      // The pre-standard spelling, still present in Chromium. Guarded because
      // it is on its way out and a missing property is not an error: 0 is
      // portrait, 90 is landscape, which is what the legacy value always was.
      if ('orientation' in window) {
        Object.defineProperty(window, 'orientation', {
          get: function () { return ANGLE; },
          configurable: true,
          enumerable: true
        });
      }
    } catch (e) {}
  } catch (e) {
    // A page must still load even if the platform refuses one of these.
  }
})();
"""
}
