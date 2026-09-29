/*
 * rb_json.h — minimal JSON helpers for Room Browser core.
 *
 * Room Browser persists its small records as JSON lines. Every store
 * (bookmarks, history, profiles, session, downloads) needs exactly the same
 * three primitives — parse a string, parse a number, escape a string — so
 * they live here once instead of being copied into each module.
 *
 * This is deliberately NOT a general JSON library: it parses the flat,
 * one-object-per-line records Room Browser writes, and nothing else. All
 * functions are total — malformed input yields 0 (failure), never a crash.
 */

#ifndef RB_JSON_H
#define RB_JSON_H

#include <sys/types.h> /* size_t */

#ifdef __cplusplus
extern "C" {
#endif

/* Skips ASCII spaces/tabs at *i. */
void rb_json_skip_ws(const char *s, size_t *i);

/* Parses a JSON string starting at s[*i] (which must be '"'). On success
 * stores a malloc'd, unescaped UTF-8 result in *out ("" for an empty
 * string, never NULL), advances *i past the closing quote and returns 1.
 * On failure *out is set to NULL and *i is left unspecified. */
int  rb_json_parse_string(const char *s, size_t *i, char **out);

/* Parses a decimal integer at s[*i]; advances *i and returns 1 on success. */
int  rb_json_parse_number(const char *s, size_t *i, long long *out);

/* Parses a JSON boolean or a bare 0/1; advances *i and returns 1 on
 * success. */
int  rb_json_parse_bool(const char *s, size_t *i, int *out);

/* Locates the value of a top-level "key" in one JSON-line object. On
 * success leaves *value_pos just past the ':' and any whitespace, and
 * returns 1. Returns 0 when the key is absent. */
int  rb_json_find_key(const char *line, const char *key, size_t *value_pos);

/* Escapes s for inclusion in a JSON string: '"', '\\' and control
 * characters become escapes; other bytes (including UTF-8) pass through.
 * Always returns a malloc'd string ("" for NULL). */
char *rb_json_escape(const char *s);

/* strdup for core modules that do not want to depend on rb_str. Dies on
 * allocation failure (the stores are not built to run out of memory). */
char *rb_json_strdup(const char *s);

/* The first n bytes of s as a malloc'd, NUL-terminated string.  NULL reads
 * as "".  Same allocation policy as rb_json_strdup. */
char *rb_json_strdup_n(const char *s, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* RB_JSON_H */
