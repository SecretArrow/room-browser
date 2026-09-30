/*
 * rb_profile.c — browser profiles for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * See rb_profile.h for the invariants this file exists to enforce.  The
 * short version: the UUID is the identity, the name is a label, and neither
 * may be allowed to drift into the other.
 */

#include "rb_profile.h"

#include "rb_devices.h"
#include "rb_json.h"
#include "rb_paths.h"
#include "rb_prefs.h"
#include "rb_str.h"
#include "rb_theme.h"
#include "rb_ua.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* U+1F464 "bust in silhouette" (Android's default profile icon), written as
 * octal escapes rather than \x so no escape can swallow the next byte. */
#define RB_PROFILE_DEFAULT_ICON "\360\237\221\244"
#define RB_PROFILE_DEFAULT_COLOR 0xFF6750A4u

#define RB_PROFILE_LINE 65536

struct rb_profile_registry {
    rb_profile *items; /* creation order */
    int count;
    int cap;
};

/* ------------------------------ lifecycle ------------------------------ */

long long rb_profile_now_ms(void)
{
    struct timespec ts;

    if (timespec_get(&ts, TIME_UTC) != TIME_UTC) {
        return (long long)time(NULL) * 1000LL;
    }
    return (long long)ts.tv_sec * 1000LL + (long long)(ts.tv_nsec / 1000000L);
}

static void rb_profile_clear(rb_profile *p)
{
    free(p->name);
    free(p->icon);
    free(p->theme_json);
    rb_settings_free(p->settings);
    p->name = NULL;
    p->icon = NULL;
    p->theme_json = NULL;
    p->settings = NULL;
}

/* ---------------------------- identity helpers ---------------------------- */

/* SplitMix64: small, fast, no state to seed badly.  Not cryptographic; see
 * the note on rb_profile_new_id() in the header for why that is fine. */
static unsigned long long rb_mix64(unsigned long long *state)
{
    unsigned long long z;

    *state += 0x9E3779B97F4A7C15ull;
    z = *state;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

void rb_profile_new_id(char *out)
{
    static unsigned long long state;
    static int seeded;
    unsigned char b[16];
    int i;
    int filled = 0;

#ifndef _WIN32
    {
        FILE *f = fopen("/dev/urandom", "rb");
        if (f != NULL) {
            filled = (fread(b, 1, sizeof(b), f) == sizeof(b));
            fclose(f);
        }
    }
#endif
    if (!seeded) {
        /* Clock + a stack address + the counter: enough that two processes
         * started in the same millisecond still diverge. */
        int local = 0;
        state = (unsigned long long)time(NULL) * 1000003ull;
        state ^= (unsigned long long)(size_t)&local;
        state ^= (unsigned long long)rb_profile_now_ms();
        seeded = 1;
    }
    if (!filled) {
        for (i = 0; i < 16; i += 8) {
            unsigned long long r = rb_mix64(&state);
            int k;
            for (k = 0; k < 8; k++) {
                b[i + k] = (unsigned char)((r >> (8 * k)) & 0xFFu);
            }
        }
    }
    b[6] = (unsigned char)((b[6] & 0x0Fu) | 0x40u); /* version 4 */
    b[8] = (unsigned char)((b[8] & 0x3Fu) | 0x80u); /* RFC 4122 variant */

    snprintf(out, RB_PROFILE_ID_LEN + 1,
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
             b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
}

void rb_profile_safe_suffix(const char *id, char *out)
{
    size_t n = 0;
    const char *p;

    if (out == NULL) {
        return;
    }
    out[0] = '\0';
    if (id == NULL) {
        return;
    }
    for (p = id; *p != '\0' && n < RB_PROFILE_SUFFIX_LEN; p++) {
        char c = *p;
        if (c == '-') {
            continue;
        }
        if (c >= 'A' && c <= 'Z') {
            c = (char)(c - 'A' + 'a');
        }
        out[n++] = c;
    }
    out[n] = '\0';
}

/* ------------------------------ registry ------------------------------ */

rb_profile_registry *rb_profile_registry_new(void)
{
    rb_profile_registry *r = (rb_profile_registry *)calloc(1, sizeof(*r));
    if (r == NULL) {
        fprintf(stderr, "rb_profile: out of memory\n");
        exit(1);
    }
    return r;
}

void rb_profile_registry_free(rb_profile_registry *r)
{
    int i;

    if (r == NULL) {
        return;
    }
    for (i = 0; i < r->count; i++) {
        rb_profile_clear(&r->items[i]);
    }
    free(r->items);
    free(r);
}

static void rb_registry_grow(rb_profile_registry *r)
{
    int ncap;
    rb_profile *grown;

    if (r->count < r->cap) {
        return;
    }
    ncap = (r->cap > 0) ? r->cap * 2 : 4;
    grown = (rb_profile *)realloc(r->items, (size_t)ncap * sizeof(rb_profile));
    if (grown == NULL) {
        fprintf(stderr, "rb_profile: out of memory\n");
        exit(1);
    }
    r->items = grown;
    r->cap = ncap;
}

int rb_profile_count(const rb_profile_registry *r)
{
    return (r != NULL) ? r->count : 0;
}

const rb_profile *rb_profile_at(const rb_profile_registry *r, int index)
{
    if (r == NULL || index < 0 || index >= r->count) {
        return NULL;
    }
    return &r->items[index];
}

int rb_profile_index_of(const rb_profile_registry *r, const char *id)
{
    int i;

    if (r == NULL || id == NULL) {
        return -1;
    }
    for (i = 0; i < r->count; i++) {
        if (strcmp(r->items[i].id, id) == 0) {
            return i;
        }
    }
    return -1;
}

const rb_profile *rb_profile_by_id(const rb_profile_registry *r, const char *id)
{
    return rb_profile_at(r, rb_profile_index_of(r, id));
}

const rb_profile *rb_profile_default(const rb_profile_registry *r)
{
    int i;

    if (r == NULL) {
        return NULL;
    }
    for (i = 0; i < r->count; i++) {
        if (r->items[i].is_default) {
            return &r->items[i];
        }
    }
    return (r->count > 0) ? &r->items[0] : NULL;
}

/* ------------------------------ name rules ------------------------------ */

/* ASCII-space/tab-trimmed copy, or NULL when `s` is NULL. */
static char *rb_trim_dup(const char *s)
{
    size_t len;
    size_t start = 0;

    if (s == NULL) {
        return NULL;
    }
    len = strlen(s);
    while (len > 0 && (s[len - 1] == ' ' || s[len - 1] == '\t')) {
        len--;
    }
    while (start < len && (s[start] == ' ' || s[start] == '\t')) {
        start++;
    }
    {
        char *out = (char *)malloc(len - start + 1);
        if (out == NULL) {
            fprintf(stderr, "rb_profile: out of memory\n");
            exit(1);
        }
        memcpy(out, s + start, len - start);
        out[len - start] = '\0';
        return out;
    }
}

/* Case-insensitive ASCII comparison (profile names are user labels, not
 * identifiers, so folding ASCII is the right amount of care). */
static int rb_name_eq(const char *a, const char *b)
{
    while (*a != '\0' && *b != '\0') {
        char ca = *a;
        char cb = *b;
        if (ca >= 'A' && ca <= 'Z') {
            ca = (char)(ca - 'A' + 'a');
        }
        if (cb >= 'A' && cb <= 'Z') {
            cb = (char)(cb - 'A' + 'a');
        }
        if (ca != cb) {
            return 0;
        }
        a++;
        b++;
    }
    return *a == *b;
}

/* 1 when `name` is already used by a profile other than `except_id`. */
static int rb_name_taken(const rb_profile_registry *r, const char *name,
                         const char *except_id)
{
    int i;

    for (i = 0; i < r->count; i++) {
        if (except_id != NULL && strcmp(r->items[i].id, except_id) == 0) {
            continue;
        }
        if (rb_name_eq(r->items[i].name, name)) {
            return 1;
        }
    }
    return 0;
}

/* Appends a blank, registry-owned profile carrying `name`; returns its index.
 * The name must already have been validated and found unique. */
static int rb_registry_push(rb_profile_registry *r, const char *name,
                            const char *icon, unsigned int color)
{
    rb_profile *p;

    rb_registry_grow(r);
    p = &r->items[r->count];
    memset(p, 0, sizeof(*p));
    rb_profile_new_id(p->id);
    p->name = rb_json_strdup(name);
    p->icon = rb_json_strdup((icon != NULL && icon[0] != '\0')
                                 ? icon
                                 : RB_PROFILE_DEFAULT_ICON);
    p->color_argb = (color != 0u) ? color : RB_PROFILE_DEFAULT_COLOR;
    p->created_at = rb_profile_now_ms();
    p->last_active_at = p->created_at;
    p->theme_json = rb_json_strdup("");
    p->settings = rb_settings_new();
    rb_prefs_profile_defaults(p->settings);
    p->is_default = (r->count == 0);
    r->count++;
    return r->count - 1;
}

/* The device ids every other profile already presents, so a new profile can
 * be given a machine nobody else claims.  Returns a malloc'd array of
 * borrowed pointers (the ids belong to the profiles) plus their count; free
 * the array, never the strings.  NULL means "none taken". */
static const char **device_ids_taken(const rb_profile_registry *r, int *out_n)
{
    const char **ids;
    int i, n = 0;

    *out_n = 0;
    ids = (const char **)malloc(sizeof(*ids) * (size_t)(r->count + 1));
    if (ids == NULL) {
        return NULL;
    }
    for (i = 0; i < r->count; i++) {
        const char *id = rb_settings_get(r->items[i].settings,
                                         RB_PREF_DEVICE_ID, NULL);
        if (id != NULL && id[0] != '\0') {
            ids[n++] = id;
        }
    }
    ids[n] = NULL;
    *out_n = n;
    return ids;
}

int rb_profile_create(rb_profile_registry *r, const char *name, const char *icon,
                      unsigned int color_argb, int randomize_device)
{
    char *trimmed;
    int idx;

    if (r == NULL) {
        return -1;
    }
    trimmed = rb_trim_dup(name);
    if (trimmed == NULL || trimmed[0] == '\0' ||
        strlen(trimmed) > (size_t)RB_PROFILE_MAX_NAME ||
        rb_name_taken(r, trimmed, NULL)) {
        free(trimmed);
        return -1;
    }
    idx = rb_registry_push(r, trimmed, icon, color_argb);
    free(trimmed);

    /* Newly created profiles identify as a randomly picked real machine, so
     * two fresh profiles do not look identical to the sites they visit.  The
     * device decides the User-Agent as well as everything else a page can ask
     * about the machine, so the UA keys are cleared rather than left behind
     * as a second, contradicting answer.  An explicit identity always wins:
     * callers that set ua_mode themselves are respected, and import/restore
     * pass 0 so the payload's settings survive verbatim (the same contract,
     * and the same reason, as ProfileManager.create()). */
    if (randomize_device) {
        rb_profile *p = &r->items[idx];
        const char *mode = rb_settings_get(p->settings, RB_PREF_UA_MODE, NULL);
        if (mode == NULL || strcmp(mode, "default") == 0) {
            int taken_n = 0;
            const char **taken = device_ids_taken(r, &taken_n);
            /* The new profile is already in the registry but has no device
             * yet, so it cannot appear in its own taken list. */
            const rb_device *d = rb_device_random(taken, taken_n);
            free((void *)taken); /* the ids are borrowed; only the array is ours */
            if (d != NULL) {
                rb_settings_set(p->settings, RB_PREF_DEVICE_ID, d->id);
                rb_settings_set(p->settings, RB_PREF_UA_PRESET_ID, "");
                rb_settings_set(p->settings, RB_PREF_CUSTOM_USER_AGENT, "");
            } else {
                /* No machine left to hand out: fall back to the UA preset
                 * rather than leaving the profile with no identity at all. */
                rb_settings_set(p->settings, RB_PREF_UA_MODE, "preset");
                rb_settings_set(p->settings, RB_PREF_UA_PRESET_ID,
                                rb_ua_random_preset_id());
            }
        }
    }
    return idx;
}

int rb_profile_rename(rb_profile_registry *r, const char *id, const char *name)
{
    char *trimmed;
    int i;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }
    trimmed = rb_trim_dup(name);
    if (trimmed == NULL || trimmed[0] == '\0' ||
        strlen(trimmed) > (size_t)RB_PROFILE_MAX_NAME ||
        rb_name_taken(r, trimmed, id)) {
        free(trimmed);
        return 0;
    }
    free(r->items[i].name);
    r->items[i].name = trimmed;
    return 1;
}

int rb_profile_restyle(rb_profile_registry *r, const char *id,
                       const char *icon, unsigned int color_argb)
{
    int i;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }
    if (icon != NULL && icon[0] != '\0') {
        free(r->items[i].icon);
        r->items[i].icon = rb_json_strdup(icon);
    }
    if (color_argb != 0u) {
        r->items[i].color_argb = color_argb;
    }
    return 1;
}

/* The snapshot a theme ID is stored as: {"id":"<theme>"}, which is the shape
 * rb_theme_current() reads in both desktop editions and the one the Android
 * edition's themeJson holds.  Empty for a NULL/empty id, which reads back as
 * the default theme.  malloc'd, never NULL. */
static char *theme_snapshot_json(const char *theme_id)
{
    char *esc;
    char *json;
    size_t need;

    if (theme_id == NULL || theme_id[0] == '\0') {
        return rb_json_strdup("");
    }
    /* Escaped even though a theme id comes from the core's own registry: the
     * snapshot is a JSON document that rb_profile_registry_save() writes
     * verbatim, so anything reaching it has to be escaped here or it is a
     * malformed profile file. */
    esc = rb_json_escape(theme_id);
    need = strlen(esc) + sizeof("{\"id\":\"\"}");
    json = (char *)malloc(need);
    if (json != NULL) {
        snprintf(json, need, "{\"id\":\"%s\"}", esc);
    }
    free(esc);
    return (json != NULL) ? json : rb_json_strdup("");
}

/* The theme ID, into the profile's theme_json snapshot.  See the header for
 * why this is not a setting: the "theme" setting is the MODE, and the two are
 * separate choices that a single combo used to overwrite each other with. */
int rb_profile_set_theme(rb_profile_registry *r, const char *id,
                         const char *theme_id)
{
    int i;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }

    free(r->items[i].theme_json);
    r->items[i].theme_json = theme_snapshot_json(theme_id);
    return 1;
}

/* One-time repair of a profile file written by the build whose theme combo
 * stored the theme ID in the MODE setting.
 *
 * That build left two marks: the mode key holds a theme name, and theme_json
 * is still empty, so the profile renders the default theme while its settings
 * claim a choice nobody can see.  Any profile whose "theme" setting names a
 * theme is therefore moved back where it belongs — the ID into the snapshot,
 * the setting reset to "system" — which is what the user picked, restored.
 *
 * A value the theme registry does not know is left alone: "light", "dark",
 * "amoled" and "system" are modes, and anything else is not ours to guess at.
 * Returns how many profiles were repaired. */
int rb_profile_repair_theme_setting(rb_profile_registry *r)
{
    int i;
    int fixed = 0;

    if (r == NULL) {
        return 0;
    }

    for (i = 0; i < r->count; i++) {
        rb_profile *p = &r->items[i];
        const char *v;

        if (p->settings == NULL) {
            continue;
        }
        v = rb_settings_get(p->settings, RB_PREF_THEME, NULL);
        if (v == NULL || v[0] == '\0') {
            continue;
        }
        if (rb_theme_by_id(v) == NULL) {
            continue;   /* a mode, or nothing we recognise */
        }
        if (p->theme_json == NULL || p->theme_json[0] == '\0') {
            free(p->theme_json);
            p->theme_json = theme_snapshot_json(v);
        }
        rb_settings_set(p->settings, RB_PREF_THEME, "system");
        fixed++;
    }
    return fixed;
}

int rb_profile_set_locked(rb_profile_registry *r, const char *id, int locked)
{
    int i;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }
    r->items[i].is_locked = locked ? 1 : 0;
    return 1;
}

int rb_profile_set_default(rb_profile_registry *r, const char *id)
{
    int i;
    int target;

    if (r == NULL || id == NULL) {
        return 0;
    }
    target = rb_profile_index_of(r, id);
    if (target < 0) {
        return 0;
    }
    for (i = 0; i < r->count; i++) {
        r->items[i].is_default = (i == target);
    }
    return 1;
}

int rb_profile_touch(rb_profile_registry *r, const char *id)
{
    int i;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }
    r->items[i].last_active_at = rb_profile_now_ms();
    return 1;
}

int rb_profile_delete(rb_profile_registry *r, const char *id)
{
    int i;
    int was_default;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }
    was_default = r->items[i].is_default;
    rb_profile_clear(&r->items[i]);
    if (i + 1 < r->count) {
        memmove(&r->items[i], &r->items[i + 1],
                (size_t)(r->count - i - 1) * sizeof(rb_profile));
    }
    r->count--;
    if (was_default && r->count > 0) {
        r->items[0].is_default = 1;
    }
    return 1;
}

int rb_profile_set_settings(rb_profile_registry *r, const char *id,
                            rb_settings *settings)
{
    int i;

    if (r == NULL || id == NULL) {
        return 0;
    }
    i = rb_profile_index_of(r, id);
    if (i < 0) {
        return 0;
    }
    rb_settings_free(r->items[i].settings);
    if (settings == NULL) {
        r->items[i].settings = rb_settings_new();
        rb_prefs_profile_defaults(r->items[i].settings);
    } else {
        r->items[i].settings = settings;
    }
    return 1;
}

/* ------------------------------ duplication ------------------------------ */

/* Copies every pair from `src` into `dst` (dst is expected to be empty).
 * Uses the ordered iterator so no rb_settings internals leak out here. */
static void rb_settings_copy_into(rb_settings *dst, const rb_settings *src)
{
    int i;
    int n = rb_settings_count(src);

    for (i = 0; i < n; i++) {
        rb_settings_set(dst, rb_settings_key_at(src, i),
                        rb_settings_value_at(src, i));
    }
}

/* Truncates `s` in place to at most `max` bytes without splitting a UTF-8
 * sequence (backs off any trailing continuation bytes). */
static void rb_utf8_truncate(char *s, size_t max)
{
    size_t len = strlen(s);

    if (len <= max) {
        return;
    }
    while (max > 0 && ((unsigned char)s[max] & 0xC0u) == 0x80u) {
        max--;
    }
    s[max] = '\0';
}

/* Builds "base+suffix" or "base+suffix N", keeping the whole thing inside
 * RB_PROFILE_MAX_NAME bytes.  The suffix and counter are the part that makes
 * the name unique, so when the result is too long it is the BASE that gets
 * cut, never the tail. */
static void rb_dup_name(const char *base, const char *suffix, int n, rb_str *out)
{
    rb_str_clear(out);
    if (n > 1) {
        rb_str_appendf(out, "%s%s %d", base, suffix, n);
    } else {
        rb_str_appendf(out, "%s%s", base, suffix);
    }
    if (out->len > (size_t)RB_PROFILE_MAX_NAME) {
        rb_str tail;
        size_t keep;

        /* Rebuild as "<truncated base><tail>", where <tail> is the suffix and
         * counter, so the unique part survives. */
        rb_str_init(&tail);
        if (n > 1) {
            rb_str_appendf(&tail, "%s %d", suffix, n);
        } else {
            rb_str_append(&tail, suffix);
        }
        if (tail.len > (size_t)RB_PROFILE_MAX_NAME) {
            rb_utf8_truncate(tail.data, (size_t)RB_PROFILE_MAX_NAME);
            rb_str_clear(out);
            rb_str_append(out, rb_str_c(&tail));
        } else {
            keep = (size_t)RB_PROFILE_MAX_NAME - tail.len;
            rb_str_clear(out);
            {
                char *head = rb_json_strdup(base);
                rb_utf8_truncate(head, keep);
                rb_str_append(out, head);
                free(head);
            }
            rb_str_append(out, rb_str_c(&tail));
        }
        rb_str_free(&tail);
    }
}

int rb_profile_duplicate(rb_profile_registry *r, const char *id,
                         const char *suffix)
{
    const rb_profile *src;
    const char *sfx;
    rb_str cand;
    int n = 1;
    int idx;

    if (r == NULL || id == NULL) {
        return -1;
    }
    src = rb_profile_by_id(r, id);
    if (src == NULL) {
        return -1;
    }
    sfx = (suffix != NULL) ? suffix : " Copy";

    rb_str_init(&cand);
    rb_dup_name(src->name, sfx, 1, &cand);
    while (rb_name_taken(r, rb_str_c(&cand), NULL)) {
        n++;
        if (n > 10000) { /* runaway guard: give up rather than spin */
            rb_str_free(&cand);
            return -1;
        }
        rb_dup_name(src->name, sfx, n, &cand);
    }

    idx = rb_registry_push(r, rb_str_c(&cand),
                           src->icon, src->color_argb);
    rb_str_free(&cand);

    /* The copy inherits the source's configuration, not its identity: the new
     * row is never the default, and it does not go on presenting the source's
     * machine — two profiles sharing one fingerprint is exactly what the
     * catalogue exists to prevent (the Android edition's duplicate() makes
     * the same choice).  Everything else is inherited verbatim. */
    {
        rb_profile *dst = &r->items[idx];
        const char *src_device;

        rb_settings_copy_into(dst->settings, src->settings);
        free(dst->theme_json);
        dst->theme_json = rb_json_strdup(src->theme_json);
        dst->is_default = 0;
        dst->is_locked = 0;

        /* Only a profile that presented a machine gets a new one: a copy of a
         * profile that presented nothing (imported, or using an explicit UA)
         * must not silently acquire one. */
        src_device = rb_settings_get(dst->settings, RB_PREF_DEVICE_ID, NULL);
        if (src_device != NULL && rb_device_by_id(src_device) != NULL) {
            int taken_n = 0;
            const char **taken = device_ids_taken(r, &taken_n);
            /* The copy is already in the registry carrying the source's
             * device id, so that machine counts as taken and cannot come
             * back. */
            const rb_device *d = rb_device_random(taken, taken_n);
            free((void *)taken); /* the ids are borrowed; only the array is ours */
            if (d != NULL) {
                rb_settings_set(dst->settings, RB_PREF_DEVICE_ID, d->id);
            }
        }
    }
    return idx;
}

/* ------------------------------ persistence ------------------------------ */

static void rb_write_settings_obj(rb_str *b, const rb_settings *s)
{
    int i;
    int n = rb_settings_count(s);

    rb_str_append(b, "{");
    for (i = 0; i < n; i++) {
        char *ek = rb_json_escape(rb_settings_key_at(s, i));
        char *ev = rb_json_escape(rb_settings_value_at(s, i));
        if (i > 0) {
            rb_str_append(b, ",");
        }
        rb_str_appendf(b, "\"%s\":\"%s\"", ek, ev);
        free(ek);
        free(ev);
    }
    rb_str_append(b, "}");
}

static void rb_write_profile_line(rb_str *b, const rb_profile *p)
{
    char *n = rb_json_escape(p->name);
    char *ic = rb_json_escape(p->icon);
    char *tj = rb_json_escape(p->theme_json);

    rb_str_append(b, "{\"id\":\"");
    rb_str_append(b, p->id); /* a generated UUID: never needs escaping */
    rb_str_appendf(b, "\",\"name\":\"%s\",\"icon\":\"%s\"", n, ic);
    rb_str_appendf(b,
                   ",\"color_argb\":%u,\"is_locked\":%d,\"is_default\":%d"
                   ",\"created_at\":%lld,\"last_active_at\":%lld",
                   p->color_argb, p->is_locked ? 1 : 0, p->is_default ? 1 : 0,
                   p->created_at, p->last_active_at);
    rb_str_appendf(b, ",\"theme_json\":\"%s\",\"settings\":", tj);
    rb_write_settings_obj(b, p->settings);
    rb_str_append(b, "}");
    free(n);
    free(ic);
    free(tj);
}

/* Parses {"key":"value", ...} into `out`; advances *i past the '}'. */
static int rb_parse_settings_obj(const char *s, size_t *i, rb_settings *out)
{
    if (s[*i] != '{') {
        return 0;
    }
    (*i)++;
    for (;;) {
        char *key = NULL;
        char *val = NULL;

        rb_json_skip_ws(s, i);
        if (s[*i] == '}') {
            (*i)++;
            return 1;
        }
        if (!rb_json_parse_string(s, i, &key)) {
            return 0;
        }
        rb_json_skip_ws(s, i);
        if (s[*i] != ':') {
            free(key);
            return 0;
        }
        (*i)++;
        rb_json_skip_ws(s, i);
        if (!rb_json_parse_string(s, i, &val)) {
            free(key);
            return 0;
        }
        rb_settings_set(out, key, val);
        free(key);
        free(val);
        rb_json_skip_ws(s, i);
        if (s[*i] == ',') {
            (*i)++;
            continue;
        }
        if (s[*i] == '}') {
            (*i)++;
            return 1;
        }
        return 0;
    }
}

/* Parses one profile line into `out`, which must be zeroed by the caller.
 * Requires "id" and "name"; everything else falls back to the defaults. */
static int rb_parse_profile_line(const char *line, rb_profile *out)
{
    size_t i = 0;
    int have_id = 0;
    int have_name = 0;
    long long num = 0;

    memset(out, 0, sizeof(*out));
    rb_json_skip_ws(line, &i);
    if (line[i] != '{') {
        return 0;
    }
    i++;

    out->color_argb = RB_PROFILE_DEFAULT_COLOR;
    out->settings = rb_settings_new();
    rb_prefs_profile_defaults(out->settings);
    out->icon = rb_json_strdup(RB_PROFILE_DEFAULT_ICON);
    out->theme_json = rb_json_strdup("");

    for (;;) {
        char *key = NULL;

        rb_json_skip_ws(line, &i);
        if (line[i] == '}') {
            i++;
            break;
        }
        if (line[i] != '"') {
            goto fail;
        }
        if (!rb_json_parse_string(line, &i, &key)) {
            goto fail;
        }
        rb_json_skip_ws(line, &i);
        if (line[i] != ':') {
            free(key);
            goto fail;
        }
        i++;
        rb_json_skip_ws(line, &i);

        if (line[i] == '"') {
            char *val = NULL;
            if (!rb_json_parse_string(line, &i, &val)) {
                free(key);
                goto fail;
            }
            if (strcmp(key, "id") == 0 && val[0] != '\0' &&
                strlen(val) <= (size_t)RB_PROFILE_ID_LEN) {
                memcpy(out->id, val, strlen(val) + 1);
                have_id = 1;
            } else if (strcmp(key, "name") == 0) {
                free(out->name);
                out->name = val;
                val = NULL;
                have_name = (out->name[0] != '\0');
            } else if (strcmp(key, "icon") == 0 && val[0] != '\0') {
                free(out->icon);
                out->icon = val;
                val = NULL;
            } else if (strcmp(key, "theme_json") == 0) {
                free(out->theme_json);
                out->theme_json = val;
                val = NULL;
            }
            free(val); /* unknown key: tolerated */
        } else if (line[i] == '{' && strcmp(key, "settings") == 0) {
            if (!rb_parse_settings_obj(line, &i, out->settings)) {
                free(key);
                goto fail;
            }
        } else if (rb_json_parse_number(line, &i, &num)) {
            if (strcmp(key, "color_argb") == 0) {
                out->color_argb = (unsigned int)num;
            } else if (strcmp(key, "is_locked") == 0) {
                out->is_locked = (num != 0);
            } else if (strcmp(key, "is_default") == 0) {
                out->is_default = (num != 0);
            } else if (strcmp(key, "created_at") == 0) {
                out->created_at = num;
            } else if (strcmp(key, "last_active_at") == 0) {
                out->last_active_at = num;
            }
        } else {
            free(key);
            goto fail; /* a value we cannot represent: reject the line */
        }
        free(key);

        rb_json_skip_ws(line, &i);
        if (line[i] == ',') {
            i++;
            continue;
        }
        if (line[i] == '}') {
            i++;
            break;
        }
        goto fail;
    }

    rb_json_skip_ws(line, &i);
    if (line[i] != '\0') {
        goto fail; /* trailing junk */
    }
    if (!have_id || !have_name) {
        goto fail;
    }
    if (out->last_active_at == 0) {
        out->last_active_at = out->created_at;
    }
    if (out->color_argb == 0u) {
        out->color_argb = RB_PROFILE_DEFAULT_COLOR;
    }
    return 1;
fail:
    rb_profile_clear(out);
    return 0;
}

int rb_profile_registry_save(const rb_profile_registry *r, const char *path)
{
    FILE *f;
    int i;
    int ok = 1;

    if (r == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < r->count; i++) {
        rb_str line;

        rb_str_init(&line);
        rb_write_profile_line(&line, &r->items[i]);
        if (fprintf(f, "%s\n", rb_str_c(&line)) < 0) {
            ok = 0;
        }
        rb_str_free(&line);
        if (!ok) {
            fclose(f);
            return -1;
        }
    }
    if (fflush(f) != 0) {
        fclose(f);
        return -1;
    }
    fclose(f);
    return 0;
}

int rb_profile_registry_load(rb_profile_registry *r, const char *path)
{
    char buf[RB_PROFILE_LINE];
    FILE *f;
    int i;

    if (r == NULL || path == NULL) {
        return -1;
    }
    /* Loading replaces the registry, so release what it held. */
    for (i = 0; i < r->count; i++) {
        rb_profile_clear(&r->items[i]);
    }
    r->count = 0;

    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine: caller starts from empty */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        rb_profile parsed;

        if (len > 0 && buf[len - 1] != '\n' && !feof(f)) {
            int ch; /* line longer than the buffer: skip it entirely */
            while ((ch = fgetc(f)) != EOF && ch != '\n') {
                /* drain */
            }
            continue;
        }
        while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
            buf[--len] = '\0';
        }
        if (len == 0) {
            continue;
        }
        if (!rb_parse_profile_line(buf, &parsed)) {
            continue; /* malformed line: skip, keep the rest */
        }
        if (rb_profile_index_of(r, parsed.id) >= 0 ||
            rb_name_taken(r, parsed.name, NULL)) {
            rb_profile_clear(&parsed); /* duplicate id or name: drop it */
            continue;
        }
        rb_registry_grow(r);
        r->items[r->count] = parsed;
        r->count++;
    }
    if (ferror(f)) {
        fclose(f);
        return -1;
    }
    fclose(f);

    /* Repair the "exactly one default" invariant a hand-edited or truncated
     * file can break. */
    {
        int defaults = 0;
        for (i = 0; i < r->count; i++) {
            if (r->items[i].is_default) {
                defaults++;
            }
        }
        if (defaults == 0 && r->count > 0) {
            r->items[0].is_default = 1;
        } else if (defaults > 1) {
            int seen = 0;
            for (i = 0; i < r->count; i++) {
                if (r->items[i].is_default) {
                    seen++;
                    if (seen > 1) {
                        r->items[i].is_default = 0;
                    }
                }
            }
        }
    }
    return 0;
}

/* ---------------------------- directory layout ---------------------------- */

static const char *rb_dir_component(int which)
{
    switch (which) {
    case RB_PROFILE_DIR_METADATA:
        return "metadata";
    case RB_PROFILE_DIR_BROWSER_DATA:
        return "browser_data";
    case RB_PROFILE_DIR_CACHE:
        return "cache";
    case RB_PROFILE_DIR_DOWNLOADS:
        return "downloads";
    case RB_PROFILE_DIR_SETTINGS:
        return "settings";
    default:
        return NULL;
    }
}

char *rb_profile_root(const char *data_dir)
{
    return rb_paths_join(data_dir, "profiles");
}

char *rb_profile_dir(const char *data_dir, const char *id)
{
    char *root;
    char *name;
    char *out;
    size_t len;

    if (data_dir == NULL || id == NULL || id[0] == '\0') {
        return NULL;
    }
    root = rb_profile_root(data_dir);
    if (root == NULL) {
        return NULL;
    }
    /* "profile_<id>" — the id, never the name, so a rename never moves data. */
    len = strlen("profile_") + strlen(id) + 1;
    name = (char *)malloc(len);
    if (name == NULL) {
        free(root);
        return NULL;
    }
    snprintf(name, len, "profile_%s", id);
    out = rb_paths_join(root, name);
    free(root);
    free(name);
    return out;
}

char *rb_profile_subdir(const char *data_dir, const char *id, int which)
{
    const char *component = rb_dir_component(which);
    char *dir;
    char *out;

    if (component == NULL) {
        return NULL;
    }
    dir = rb_profile_dir(data_dir, id);
    if (dir == NULL) {
        return NULL;
    }
    out = rb_paths_join(dir, component);
    free(dir);
    return out;
}

int rb_profile_ensure_dirs(const char *data_dir, const char *id)
{
    int which;
    int rc = 0;

    for (which = 0; which < RB_PROFILE_DIR_COUNT; which++) {
        char *dir = rb_profile_subdir(data_dir, id, which);
        if (dir == NULL || rb_paths_mkdirs(dir) != 0) {
            rc = -1;
        }
        free(dir);
    }
    return rc;
}

int rb_profile_remove_data(const char *data_dir, const char *id)
{
    char *dir = rb_profile_dir(data_dir, id);
    int rc;

    if (dir == NULL) {
        return 0;
    }
    rc = rb_paths_remove_tree(dir);
    free(dir);
    return rc;
}
