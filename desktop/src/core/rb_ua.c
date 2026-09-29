/*
 * rb_ua.c — User-Agent presets for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The table is a verbatim port of the Android edition's UserAgents.all.
 */

#include "rb_ua.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

static const rb_ua_preset RB_UA_PRESETS[] = {
    /* --- mobile --- */
    { "chrome_android", "Chrome Android", 0,
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Mobile Safari/537.36" },
    { "firefox_android", "Firefox Android", 0,
      "Mozilla/5.0 (Android 14; Mobile; rv:127.0) Gecko/127.0 "
      "Gecko/20100101 Firefox/127.0" },
    { "edge_android", "Edge Android", 0,
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Mobile Safari/537.36 EdgA/124.0.0.0" },
    { "samsung_android", "Samsung Internet", 0,
      "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 "
      "(KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 "
      "Mobile Safari/537.36" },
    { "webview", "Engine default", 0, "" },

    /* --- desktop --- */
    { "chrome_windows", "Chrome Windows", 1,
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" },
    { "chrome_macos", "Chrome macOS", 1,
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" },
    { "chrome_linux", "Chrome Linux", 1,
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Safari/537.36" },
    { "firefox_windows", "Firefox Windows", 1,
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) "
      "Gecko/20100101 Firefox/127.0" },
    { "firefox_macos", "Firefox macOS", 1,
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) "
      "Gecko/20100101 Firefox/127.0" },
    { "firefox_linux", "Firefox Linux", 1,
      "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0" },
    { "edge_windows", "Edge Windows", 1,
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0" },
    { "safari_macos", "Safari macOS", 1,
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
      "(KHTML, like Gecko) Version/17.4 Safari/605.1.15" }
};

#define RB_UA_N ((int)(sizeof(RB_UA_PRESETS) / sizeof(RB_UA_PRESETS[0])))

int rb_ua_count(void)
{
    return RB_UA_N;
}

const rb_ua_preset *rb_ua_at(int index)
{
    if (index < 0 || index >= RB_UA_N) {
        return NULL;
    }
    return &RB_UA_PRESETS[index];
}

const rb_ua_preset *rb_ua_by_id(const char *id)
{
    int i;

    if (id == NULL || id[0] == '\0') {
        return NULL;
    }
    for (i = 0; i < RB_UA_N; i++) {
        if (strcmp(RB_UA_PRESETS[i].id, id) == 0) {
            return &RB_UA_PRESETS[i];
        }
    }
    return NULL;
}

/* Presets eligible for random assignment to a NEW profile.
 *
 * Desktop-shaped on purpose: a desktop browser announcing itself as a phone
 * would be handed mobile layouts, the mirror image of Android excluding its
 * desktop presets. "Engine default" is excluded because picking it would be
 * a no-op override. */
static const char *const RB_UA_RANDOM_IDS[] = {
    "chrome_windows", "chrome_macos", "chrome_linux",
    "firefox_windows", "firefox_macos", "firefox_linux",
    "edge_windows", "safari_macos"
};

#define RB_UA_RANDOM_N \
    ((int)(sizeof(RB_UA_RANDOM_IDS) / sizeof(RB_UA_RANDOM_IDS[0])))

const char *rb_ua_random_preset_id(void)
{
    static int seeded = 0;

    if (RB_UA_RANDOM_N <= 0) {
        return NULL;
    }
    if (!seeded) {
        /* rand() with a time seed is enough here: this only picks a
         * fingerprint-diversity default, it is not a security decision. */
        srand((unsigned)time(NULL));
        seeded = 1;
    }
    return RB_UA_RANDOM_IDS[(int)(rand() % RB_UA_RANDOM_N) ];
}

static char *rb_ua_dup(const char *s)
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

/* 1 when s is NULL or only ASCII whitespace. */
static int rb_ua_blank(const char *s)
{
    if (s == NULL) {
        return 1;
    }
    while (*s != '\0') {
        if (*s != ' ' && *s != '\t' && *s != '\r' && *s != '\n') {
            return 0;
        }
        s++;
    }
    return 1;
}

char *rb_ua_effective(rb_ua_mode mode, const char *preset_id, const char *custom)
{
    switch (mode) {
    case RB_UA_MODE_PRESET: {
        const rb_ua_preset *p = rb_ua_by_id(preset_id);
        if (p == NULL || p->value[0] == '\0') {
            return NULL; /* unknown id, or "engine default" */
        }
        return rb_ua_dup(p->value);
    }
    case RB_UA_MODE_CUSTOM:
        if (rb_ua_blank(custom)) {
            return NULL;
        }
        return rb_ua_dup(custom);
    case RB_UA_MODE_DEFAULT:
    default:
        return NULL;
    }
}
