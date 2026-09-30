/*
 * rb_device.c - desktop device behaviour for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The catalogue itself is data and lives in rb_devices.c, which is
 * generated. This file is the logic around it: which device a profile
 * presents, what User-Agent that means, and the document-start script that
 * makes the two agree with the device the profile claims to be.
 */

#include "rb_devices.h"
#include "rb_str.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ------------------------------------------------------------------ */
/* Quoting
 *
 * Everything the catalogue holds is ASCII, so the only byte that could end
 * a JavaScript literal early is the quote itself - but a value that arrived
 * some other way must not be able to escape, so this escapes on the way in
 * rather than trusting the table. */

static void js_quote(rb_str *out, const char *value)
{
    const unsigned char *p = (const unsigned char *)((value != NULL) ? value : "");

    rb_str_append(out, "'");
    while (*p != '\0') {
        unsigned char c = *p;

        if (c == '\\') {
            rb_str_append(out, "\\\\");
            p++;
        } else if (c == '\'') {
            rb_str_append(out, "\\'");
            p++;
        } else if (c == '\n') {
            rb_str_append(out, "\\n");
            p++;
        } else if (c == '\r') {
            rb_str_append(out, "\\r");
            p++;
        } else if (c == '\t') {
            rb_str_append(out, "\\t");
            p++;
        } else if (c < 0x20 || c == 0x7f) {
            rb_str_appendf(out, "\\u%04x", (unsigned)c);
            p++;
        } else if (c == 0xe2 && p[1] == 0x80 && (p[2] == 0xa8 || p[2] == 0xa9)) {
            /* U+2028 and U+2029 are legal inside a C string literal but are
             * line terminators to JavaScript, so they end a statement there. */
            rb_str_appendf(out, "\\u%04x", (p[2] == 0xa8) ? 0x2028u : 0x2029u);
            p += 3;
        } else {
            char one[2];
            one[0] = (char)c;
            one[1] = '\0';
            rb_str_append(out, one);
            p++;
        }
    }
    rb_str_append(out, "'");
}

static char *rb_device_dup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        return NULL;
    }
    memcpy(p, s, n);
    return p;
}

/* The major version of a dotted version string, malloc'd. */
static char *rb_device_major(const char *version)
{
    size_t n = 0;
    char *p;

    if (version == NULL) {
        return NULL;
    }
    while (version[n] != '\0' && version[n] != '.') {
        n++;
    }
    p = (char *)malloc(n + 1);
    if (p == NULL) {
        return NULL;
    }
    memcpy(p, version, n);
    p[n] = '\0';
    return p;
}

/* ------------------------------------------------------------------ */
/* Choosing a device */

static int rb_device_taken(const char *const *ids, int n, const char *id)
{
    int i;

    if (ids == NULL || id == NULL) {
        return 0;
    }
    for (i = 0; i < n; i++) {
        if (ids[i] != NULL && strcmp(ids[i], id) == 0) {
            return 1;
        }
    }
    return 0;
}

static int rb_device_roll(int n)
{
    static int seeded = 0;

    if (n <= 1) {
        return 0;
    }
    if (!seeded) {
        /* rand() with a time seed is enough here: this only picks a
         * fingerprint-diversity default, it is not a security decision. */
        srand((unsigned)time(NULL));
        seeded = 1;
    }
    return (int)(rand() % n);
}

const rb_device *rb_device_random(const char *const *taken_ids, int taken_n)
{
    int total = rb_device_count();
    int i, free_n = 0, pick;

    if (total <= 0) {
        return NULL;
    }
    for (i = 0; i < total; i++) {
        const rb_device *d = rb_device_at(i);
        if (d != NULL && !rb_device_taken(taken_ids, taken_n, d->id)) {
            free_n++;
        }
    }
    if (free_n == 0) {
        /* Every device is spoken for. One shared device beats no assignment
         * at all, which is what the Android edition decides too. */
        return rb_device_at(rb_device_roll(total));
    }
    pick = rb_device_roll(free_n);
    for (i = 0; i < total; i++) {
        const rb_device *d = rb_device_at(i);
        if (d != NULL && !rb_device_taken(taken_ids, taken_n, d->id)) {
            if (pick == 0) {
                return d;
            }
            pick--;
        }
    }
    return rb_device_at(0); /* unreachable while free_n was counted correctly */
}

char *rb_device_ua_for(const char *device_id)
{
    const rb_device *d = rb_device_by_id(device_id);

    if (d == NULL || d->ua == NULL || d->ua[0] == '\0') {
        return NULL;
    }
    return rb_device_dup(d->ua);
}

/* The OS as a person would write it. A Windows UA token has said
 * "Windows NT 10.0" since Windows 10 and still does on 11, so the reported
 * version cannot tell the two apart - which is exactly why this says
 * "Windows 11" for every Windows machine from 2022 on and does not pretend
 * to know more than the platform token does. */
const char *rb_device_os_name(const char *os)
{
    if (os == NULL) {
        return "";
    }
    if (strcmp(os, "windows") == 0) {
        return "Windows 11";
    }
    if (strcmp(os, "macos") == 0) {
        return "macOS";
    }
    if (strcmp(os, "linux") == 0) {
        return "Linux";
    }
    return os;
}

/* ------------------------------------------------------------------ */
/* The document-start script
 *
 * Scope matches the Android edition's DeviceShim deliberately: the
 * properties a MACHINE determines, and nothing geometric. Screen size,
 * viewport, devicePixelRatio and the layout that follows from them are left
 * alone because the page really is laid out on this screen - a claimed
 * viewport would render it wrongly and be contradicted by the viewport
 * itself. See SECURITY.md.
 *
 * The desktop-specific parts are the ones that differ from a phone: mobile
 * is always false, the form factor hint is "Desktop", the platform strings
 * follow the machine's OS, architecture follows its CPU, and `model` is the
 * empty string - Chrome reports no model on a desktop, so a profile that
 * invented one would be the only desktop in the world with a model name. */

char *rb_device_shim_js(const rb_device *device)
{
    rb_str out;
    char *major;
    char *result;

    if (device == NULL) {
        return NULL;
    }
    major = rb_device_major(device->chrome);

    rb_str_init(&out);
    rb_str_append(&out, "(function () {\n  'use strict';\n  try {\n");

    rb_str_append(&out, "    var UA = ");
    js_quote(&out, device->ua);
    rb_str_append(&out, ";\n    var CHROME = ");
    js_quote(&out, device->chrome);
    rb_str_append(&out, ";\n    var CHROME_MAJOR = ");
    js_quote(&out, (major != NULL) ? major : "");
    rb_str_append(&out, ";\n    var PLATFORM = ");
    js_quote(&out, device->platform);
    rb_str_append(&out, ";\n    var UA_PLATFORM = ");
    js_quote(&out, device->ua_platform);
    rb_str_append(&out, ";\n    var PLATFORM_VERSION = ");
    js_quote(&out, device->platform_version);
    rb_str_append(&out, ";\n    var ARCH = ");
    js_quote(&out, device->arch);
    rb_str_appendf(&out, ";\n    var MEMORY = %d;", device->memory);
    rb_str_appendf(&out, "\n    var CORES = %d;", device->cores);
    rb_str_append(&out, "\n    var GPU_VENDOR = ");
    js_quote(&out, device->gpu_vendor);
    rb_str_append(&out, ";\n    var GPU_RENDERER = ");
    js_quote(&out, device->gpu_renderer);
    rb_str_append(&out, ";\n\n");

    rb_str_append(&out,
        "    function define(target, prop, value) {\n"
        "      try {\n"
        "        Object.defineProperty(target, prop, {\n"
        "          get: function () { return value; },\n"
        "          configurable: true,\n"
        "          enumerable: true\n"
        "        });\n"
        "      } catch (e) {}\n"
        "    }\n"
        "\n"
        "    define(Navigator.prototype, 'userAgent', UA);\n"
        "    define(Navigator.prototype, 'platform', PLATFORM);\n"
        "    define(Navigator.prototype, 'deviceMemory', MEMORY);\n"
        "    define(Navigator.prototype, 'hardwareConcurrency', CORES);\n"
        "\n"
        "    // Client hints. These are the ones that name the machine, so a\n"
        "    // profile that shims the UA but not these is not presenting one.\n"
        "    function brands(full) {\n"
        "      var v = full ? CHROME : CHROME_MAJOR;\n"
        "      return [\n"
        "        { brand: 'Chromium', version: v },\n"
        "        { brand: 'Google Chrome', version: v },\n"
        "        { brand: 'Not?A_Brand', version: '24' }\n"
        "      ];\n"
        "    }\n"
        "    var uaData = {\n"
        "      brands: brands(false),\n"
        "      mobile: false,\n"
        "      platform: UA_PLATFORM,\n"
        "      getHighEntropyValues: function (hints) {\n"
        "        var wanted = hints || [];\n"
        "        var out = {};\n"
        "        for (var i = 0; i < wanted.length; i++) {\n"
        "          switch (wanted[i]) {\n"
        "            case 'architecture': out.architecture = ARCH; break;\n"
        "            case 'bitness': out.bitness = '64'; break;\n"
        "            case 'formFactor': out.formFactor = 'Desktop'; break;\n"
        "            case 'model': out.model = ''; break;\n"
        "            case 'platform': out.platform = UA_PLATFORM; break;\n"
        "            case 'platformVersion':\n"
        "              out.platformVersion = PLATFORM_VERSION; break;\n"
        "            case 'uaFullVersion': out.uaFullVersion = CHROME; break;\n"
        "            case 'fullVersionList':\n"
        "              out.fullVersionList = brands(true); break;\n"
        "            case 'wow64': out.wow64 = false; break;\n"
        "            default: break;\n"
        "          }\n"
        "        }\n"
        "        return Promise.resolve(out);\n"
        "      },\n"
        "      toJSON: function () {\n"
        "        return { brands: brands(false), mobile: false,\n"
        "                 platform: UA_PLATFORM };\n"
        "      }\n"
        "    };\n"
        "    try {\n"
        "      Object.defineProperty(Navigator.prototype, 'userAgentData', {\n"
        "        get: function () { return uaData; },\n"
        "        configurable: true,\n"
        "        enumerable: true\n"
        "      });\n"
        "    } catch (e) {}\n"
        "\n"
        "    // WebGL reports the GPU. An Adreno string on a machine that\n"
        "    // ships an RTX is exactly the contradiction this exists to avoid.\n"
        "    function patchGL(ctx) {\n"
        "      if (!ctx || !ctx.prototype || !ctx.prototype.getParameter) return;\n"
        "      var proto = ctx.prototype;\n"
        "      var original = proto.getParameter;\n"
        "      var patched = function (pname) {\n"
        "        if (pname === 37445) return GPU_VENDOR;\n"
        "        if (pname === 37446) return GPU_RENDERER;\n"
        "        return original.call(this, pname);\n"
        "      };\n"
        "      try {\n"
        "        patched.toString = function () { return original.toString(); };\n"
        "      } catch (e) {}\n"
        "      try {\n"
        "        Object.defineProperty(proto, 'getParameter', {\n"
        "          value: patched,\n"
        "          configurable: true,\n"
        "          writable: true\n"
        "        });\n"
        "      } catch (e) {}\n"
        "    }\n"
        "    if (typeof WebGLRenderingContext !== 'undefined') {\n"
        "      patchGL(WebGLRenderingContext);\n"
        "    }\n"
        "    if (typeof WebGL2RenderingContext !== 'undefined') {\n"
        "      patchGL(WebGL2RenderingContext);\n"
        "    }\n"
        "  } catch (e) {\n"
        "    // A page must still load even if the platform refuses one of these.\n"
        "  }\n"
        "})();\n");

    free(major);
    result = rb_device_dup(rb_str_c(&out));
    rb_str_free(&out);
    return result;
}
