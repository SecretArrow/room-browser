/*
 * rb_tabs.h — tab table for Room Browser core.
 *
 * Tabs are kept in insertion order; ids are strictly increasing starting
 * from 1 and are never reused.  All strings are UTF-8 and owned by the
 * module (freed on rb_tabs_close/rb_tabs_free).  rb_tabs_get() hands out a
 * mutable slot, so the UI may mutate title/url in place (or assign its own
 * malloc'd pointer) — the module frees whatever the fields hold when the
 * tab goes away.
 */

#ifndef RB_TABS_H
#define RB_TABS_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct { long id; char *title; char *url; } rb_tab;

/* Opaque table of open tabs in insertion order. */
typedef struct rb_tabs rb_tabs;

rb_tabs *rb_tabs_new(void);
void     rb_tabs_free(rb_tabs *t);

/* Adds a tab (NULL title/url are stored as "") and returns its new id. */
long     rb_tabs_add(rb_tabs *t, const char *title, const char *url);

int      rb_tabs_count(const rb_tabs *t);

/* Removes the tab with the given id.  Returns 1 if closed, 0 if not found. */
int      rb_tabs_close(rb_tabs *t, long id);

/* Mutable slot for the id, NULL if the tab is gone. */
rb_tab  *rb_tabs_get(rb_tabs *t, long id);

/* Read-only slot by insertion-order index, NULL when out of range. */
const rb_tab *rb_tabs_at(const rb_tabs *t, int index);

#ifdef __cplusplus
}
#endif

#endif /* RB_TABS_H */
