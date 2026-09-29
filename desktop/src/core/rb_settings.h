/*
 * rb_settings.h — simple key=value store for Room Browser core.
 *
 * Persisted as plain "key=value\n" lines (split at the FIRST '=' so values
 * may contain '=').  Keys must not contain '=' or newlines; values may hold
 * anything: a backslash, LF and CR are written as "\\", "\n" and "\r" and
 * decoded again on load, so multi-line values (the homepage shortcut list)
 * survive a round trip instead of being truncated at the first newline.  All
 * strings UTF-8.  Defaults in rb_settings_new():
 *   home=https://duckduckgo.com  search_engine=duckduckgo  javascript=1
 * (javascript NEVER defaults to 0 — project-wide policy.)
 */

#ifndef RB_SETTINGS_H
#define RB_SETTINGS_H

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque ordered key=value store. */
typedef struct rb_settings rb_settings;

/* Fresh store with the defaults above. */
rb_settings *rb_settings_new(void);
void         rb_settings_free(rb_settings *s);

/* Looks the key up and returns its value; the fallback (which may be
 * NULL) is returned when the key is missing. */
const char  *rb_settings_get(const rb_settings *s, const char *key, const char *fallback);

/* Integer view: falls back when the key is missing or not a valid
 * integer (leading sign + digits; trailing junk after the digits is
 * ignored). */
int          rb_settings_get_int(const rb_settings *s, const char *key, int fallback);

/* Sets (or replaces) a key.  NULL value stores "".  Empty keys are
 * ignored. */
void         rb_settings_set(rb_settings *s, const char *key, const char *value);

void         rb_settings_set_int(rb_settings *s, const char *key, int value);

/* Ordered iteration over the stored pairs, for persistence layers that
 * write the whole store out (profile settings snapshots).  Index must be
 * in [0, rb_settings_count()).  Never returns NULL for a valid index. */
int          rb_settings_count(const rb_settings *s);
const char  *rb_settings_key_at(const rb_settings *s, int index);
const char  *rb_settings_value_at(const rb_settings *s, int index);

/* Loads "key=value\n" lines, updating existing keys and appending new
 * ones.  Malformed lines are skipped.  A missing file is fine (returns
 * 0 and keeps the defaults).  0 on success, -1 on I/O error. */
int          rb_settings_load(rb_settings *s, const char *path);

/* Writes all pairs in insertion order.  0 ok, -1 on error. */
int          rb_settings_save(const rb_settings *s, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_SETTINGS_H */
