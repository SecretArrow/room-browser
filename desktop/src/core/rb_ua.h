/*
 * rb_ua.h — User-Agent presets for Room Browser core.
 *
 * Direct port of the Android edition's UserAgents table
 * (core/domain model/UserAgents.kt): the same ids, labels and UA strings,
 * so a profile that moves between editions sends the same User-Agent.
 *
 * The strings are deliberately version-pinned snapshots, not a promise of
 * platform capability: changing the UA does NOT change rendering engine
 * features, screen size or available web APIs. The UI and SECURITY.md say
 * so explicitly on both editions.
 */

#ifndef RB_UA_H
#define RB_UA_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *id;
    const char *label;
    int         is_desktop;  /* 1 = desktop preset, 0 = mobile preset */
    const char *value;       /* "" means "use the engine default" */
} rb_ua_preset;

/* How a profile picks its User-Agent (mirrors Android's UaMode). */
typedef enum {
    RB_UA_MODE_DEFAULT = 0, /* engine default — send no override */
    RB_UA_MODE_PRESET  = 1, /* one of the presets below */
    RB_UA_MODE_CUSTOM  = 2  /* a free-form string */
} rb_ua_mode;

int                  rb_ua_count(void);
const rb_ua_preset  *rb_ua_at(int index);
const rb_ua_preset  *rb_ua_by_id(const char *id);   /* NULL when unknown */

/* The preset id a NEW profile is assigned at random. Mobile-shaped on
 * Android, desktop-shaped here — a desktop browser should not introduce
 * itself as a phone. Returns a static string; NULL only if the table were
 * empty. */
const char          *rb_ua_random_preset_id(void);

/* Resolve the effective User-Agent string for a profile's UA configuration.
 *
 * Returns a malloc'd string when an override applies, or NULL when the
 * engine default should be used untouched (mode DEFAULT, a PRESET whose
 * value is empty, or a CUSTOM string that is blank). Mirrors Android's
 * UserAgents.effectiveUserAgent(). */
char                *rb_ua_effective(rb_ua_mode mode, const char *preset_id,
                                     const char *custom);

#ifdef __cplusplus
}
#endif

#endif /* RB_UA_H */
