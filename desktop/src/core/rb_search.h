/*
 * rb_search.h — search-engine registry for Room Browser core.
 *
 * Mirrors the Android edition's SearchEngines table (core/domain
 * model/SearchEngines.kt): the same six engines, the same URL templates and
 * the same {query} placeholder contract, so a profile moved between editions
 * keeps behaving identically.
 *
 * All returned strings are freshly malloc'd unless stated otherwise; the
 * rb_search_engine records themselves are static and must not be freed.
 */

#ifndef RB_SEARCH_H
#define RB_SEARCH_H

#ifdef __cplusplus
extern "C" {
#endif

/* One engine. search_template always contains "{query}"; suggest_template
 * is NULL when the engine has no suggestion endpoint. */
typedef struct {
    const char *id;
    const char *label;
    const char *search_template;
    const char *suggest_template; /* NULL when unsupported */
} rb_search_engine;

/* Number of built-in engines. */
int                        rb_search_count(void);

/* Engine by index (insertion order); NULL when out of range. */
const rb_search_engine    *rb_search_at(int index);

/* Engine by id (case-insensitive); NULL when unknown. */
const rb_search_engine    *rb_search_by_id(const char *id);

/* The fallback engine ("duckduckgo") — never NULL. */
const rb_search_engine    *rb_search_default(void);

/* Engine for an id, falling back to the default when the id is unknown or
 * NULL. Never NULL — the safe accessor every caller should use. */
const rb_search_engine    *rb_search_resolve(const char *id);

/* Percent-encodes a query as UTF-8 (space -> %20; A-Z a-z 0-9 - . _ ~ stay
 * literal). Surrounding whitespace is trimmed. NULL encodes as "". */
char                      *rb_search_encode(const char *query);

/* Replaces every "{query}" in tmpl with the encoded query. A NULL/absent
 * placeholder yields a copy of tmpl. */
char                      *rb_search_expand(const char *tmpl, const char *query);

/* Search URL for an engine id (unknown -> default engine). */
char                      *rb_search_url(const char *engine_id, const char *query);

/* Suggestion URL for an engine id, or NULL when the engine has no
 * suggestion endpoint (or the query is empty). */
char                      *rb_search_suggest_url(const char *engine_id,
                                                 const char *query);

#ifdef __cplusplus
}
#endif

#endif /* RB_SEARCH_H */
