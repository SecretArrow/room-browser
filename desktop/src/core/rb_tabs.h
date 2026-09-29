/*
 * rb_tabs.h — tab table for Room Browser core.
 *
 * Ports the tab half of the Android edition: TabEntity, TabDao and
 * TabManager.  Room Browser keeps ONE of these per profile, which is exactly
 * what Android's TabManager is ("tab session manager for ONE profile").  On a
 * profile switch the platform layer saves and frees the old table and loads
 * the new profile's — the tabs of two profiles never share a table, so a
 * switch cannot leak one profile's tabs into another's window.
 *
 * Two rules shape everything below, and both are the Android DAO's:
 *
 *   - A tab is CLOSED, not forgotten.  rb_tabs_close() stamps closed_at and
 *     the row stays, which is what makes "reopen closed tab" possible.  Only
 *     rb_tabs_forget_closed() and rb_tabs_purge_closed_before() remove rows.
 *   - Open tabs are presented PINNED FIRST, then by position
 *     (ORDER BY is_pinned DESC, position ASC).  rb_tabs_count() and
 *     rb_tabs_at() are that order, and nothing else.
 *
 * Ids are strictly increasing from 1 and are never reused, so a stale id is
 * always detectable rather than silently addressing a different tab.
 * The clock is passed in rather than read here, which keeps every rule below
 * testable with fixed timestamps.
 */

#ifndef RB_TABS_H
#define RB_TABS_H

#ifdef __cplusplus
extern "C" {
#endif

/* Repo default for the "recently closed" list (TabDao.recentlyClosed's
 * LIMIT), and the retention the Android cleanup worker applies to closed
 * rows (RetentionCleanupWorker: purgeOldClosedTabs(days = 30)). */
#define RB_TABS_RECENTLY_CLOSED 10
#define RB_TABS_CLOSED_RETENTION_DAYS 30

/* close_others modes, matching BrowserViewModel.closeOtherTabs(onlyLeft). */
enum {
    RB_TABS_KEEP_OTHERS = 0, /* null  — close every tab but this one */
    RB_TABS_KEEP_LEFT = 1,   /* true  — close the ones before it */
    RB_TABS_KEEP_RIGHT = 2   /* false — close the ones after it */
};

typedef struct {
    long id;
    char *title;
    char *url;
    int position;          /* ordering within its pin group */
    int is_private;        /* never written to the session file */
    int is_pinned;
    char *group_name;      /* "" = ungrouped (Android stores NULL) */
    long long created_at;  /* ms since the epoch */
    long long last_viewed_at;
    long long closed_at;   /* 0 = open */
} rb_tab;

/* Opaque table of tabs for one profile. */
typedef struct rb_tabs rb_tabs;

rb_tabs *rb_tabs_new(void);
void     rb_tabs_free(rb_tabs *t);

/* ---- open tabs ---- */

/* Number of OPEN tabs. */
int      rb_tabs_count(const rb_tabs *t);

/* The index'th OPEN tab, pinned first then by position.  NULL out of range. */
const rb_tab *rb_tabs_at(const rb_tabs *t, int index);

/* Adds a tab at the end of the strip — after the highest position in use,
 * like BrowserRepository.newTab — and returns its id (0 when t is NULL).
 * A NULL title/url is stored as "".  created_at and last_viewed_at are set
 * to now_ms. */
long     rb_tabs_add(rb_tabs *t, const char *title, const char *url,
                     long long now_ms);

/* Mutable slot for the id, open or closed; NULL when there is no such tab.
 *
 * The module owns title, url and group_name and frees them with the tab, so a
 * caller that replaces one must hand over a malloc'd string (or NULL, which
 * reads as "").  Everything else may be written freely — the callers that do
 * (the address bar updating a title, the renderer updating a URL) are the
 * reason this returns a mutable slot at all. */
rb_tab  *rb_tabs_get(rb_tabs *t, long id);

/* ---- lifecycle ---- */

/* Closes a tab: stamps closed_at, keeps the row for reopen.  Returns 1 when
 * the tab was open.  A tab that is already closed returns 0 rather than
 * restamping, so the closed list cannot be reordered by a double close. */
int      rb_tabs_close(rb_tabs *t, long id, long long now_ms);

/* Closes several tabs at once.  `mode` is one of RB_TABS_KEEP_*.
 *
 * The comparison is on the raw position, not on the display index, because
 * that is what BrowserViewModel.closeOtherTabs does — so a pinned tab that
 * sorts to the front does not count as being "to the left" of anything.
 * Returns how many tabs were closed. */
int      rb_tabs_close_others(rb_tabs *t, long id, int mode, long long now_ms);

/* Reopens a closed tab, returning it to the open strip (re-sorted by pin and
 * position) and clearing closed_at.  Returns 1 when the tab was closed. */
int      rb_tabs_reopen(rb_tabs *t, long id);

/* Records that the tab was just used.  Returns 1 when the id exists. */
int      rb_tabs_touch(rb_tabs *t, long id, long long now_ms);

/* ---- closed tabs (the "reopen closed tab" stack) ---- */

/* Fills `out` with up to `max` of the most recently closed tabs, newest
 * first, and returns how many were written.  The pointers alias the store, so
 * they die at the next mutation — copy an id out before reopening. */
int      rb_tabs_recently_closed(const rb_tabs *t, const rb_tab **out, int max);

int      rb_tabs_closed_count(const rb_tabs *t);

/* Forgets closed tabs older than the cutoff.  Returns how many rows went. */
int      rb_tabs_purge_closed_before(rb_tabs *t, long long cutoff);

/* The retention policy the Android cleanup worker applies, as one call:
 * purges closed tabs older than RB_TABS_CLOSED_RETENTION_DAYS. */
int      rb_tabs_purge_closed(rb_tabs *t, long long now_ms);

/* Forgets every closed tab. */
int      rb_tabs_forget_closed(rb_tabs *t);

/* ---- arrangement ---- */

/* Moves an open tab to `position` (TabDao.setposition: a plain assignment,
 * so a position already in use simply produces a tie broken by id). */
int      rb_tabs_move(rb_tabs *t, long id, int position);

/* Pins or unpins an open tab.  Pinned tabs sort to the front of the strip. */
int      rb_tabs_pin(rb_tabs *t, long id, int pinned);

/* Adds the tab to, or removes it from, a named group ("" or NULL ungroups). */
int      rb_tabs_group(rb_tabs *t, long id, const char *group);

int      rb_tabs_set_private(rb_tabs *t, long id, int is_private);

/* Number of OPEN private tabs.  The platform layer clears the engine's
 * session artifacts when this drops to zero (BrowserViewModel.closeTab). */
int      rb_tabs_private_count(const rb_tabs *t);

/* ---- persistence ---- */

/* Session file: JSON lines, one row per tab, open and closed alike.
 *
 * PRIVATE TABS ARE NOT WRITTEN.  Android's tab table holds them, but a
 * desktop session file is a plain file in the profile directory that outlives
 * the process; writing an incognito tab's URL there would defeat the point of
 * incognito.  The cost is that private tabs do not survive a restart, which
 * is the behaviour a user expects from them anyway.
 *
 * 0 on success, -1 on I/O error. */
int      rb_tabs_save(const rb_tabs *t, const char *path);

/* Replaces the table's contents from `path`.  A missing file leaves it
 * empty and returns 0.  Ids are preserved, and the id counter advances past
 * the highest id seen so a later rb_tabs_add cannot collide with a restored
 * tab.  0 on success, -1 on I/O error. */
int      rb_tabs_load(rb_tabs *t, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_TABS_H */
