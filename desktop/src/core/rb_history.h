/*
 * rb_history.h — browsing history for Room Browser core.
 *
 * Entries are stored most-recent-first.  Persistence is JSON-lines:
 *   {"url":"...","title":"...","visited_at":1234567890}
 * Strings are escaped minimally (", \ and control characters); the parser
 * skips malformed lines and lines longer than ~16 KiB.  All strings UTF-8.
 */

#ifndef RB_HISTORY_H
#define RB_HISTORY_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct { char *url; char *title; long long visited_at; } rb_hist_entry;

/* HistoryDao.search's LIMIT. */
#define RB_HISTORY_SEARCH_MAX 200

/* Opaque history log (bounded at 10000 entries; the oldest is dropped). */
typedef struct rb_history rb_history;

rb_history *rb_history_new(void);
void        rb_history_free(rb_history *h);

/* Appends an entry.  When the most recent entry has the same URL, that
 * entry is replaced instead (title updated, timestamp bumped). */
void        rb_history_append(rb_history *h, const char *url, const char *title);

int         rb_history_count(const rb_history *h);

/* Returns the n most recent entries as an internal array slice
 * (most-recent first).  DO NOT free or mutate it.  *out_n receives the
 * slice length (0 when there is nothing); returns NULL in that case. */
const rb_hist_entry *rb_history_recent(const rb_history *h, int n, int *out_n);

/* Substring search over url and title, most recent first, filling `out` with
 * up to `max` pointers into the log and returning how many were written.
 * A NULL/empty needle matches everything, which is what the history screen
 * shows before the user types.
 *
 * Matching is case-insensitive for ASCII only, the same as SQLite's LIKE —
 * so this behaves like HistoryDao.search, not like a Unicode-aware search. */
int         rb_history_search(const rb_history *h, const char *needle,
                              const rb_hist_entry **out, int max);

/* Removes the entry at `index` in the recent-first order.  Returns 1 when it
 * was there.  The index is only valid until the next append, which is why
 * there is no rb_history_remove(url): a history row is an occurrence, not a
 * unique key. */
int         rb_history_remove_at(rb_history *h, int index);

/* Removes every entry visited at or after `since` (HistoryDao.deleteSince:
 * "clear the last hour/day/...").  Returns how many went. */
int         rb_history_delete_since(rb_history *h, long long since);

/* Removes everything (HistoryDao.deleteAllFor).  Returns how many went. */
int         rb_history_clear(rb_history *h);

/* Distinct URLs visited at or after `since` (HistoryDao.distinctSites). */
int         rb_history_distinct_sites(const rb_history *h, long long since);

/* Loads a JSON-lines file, appending entries after (older than) any
 * existing ones.  A missing file is fine (returns 0, keeps state).
 * Returns 0 on success, -1 on I/O error. */
int         rb_history_load(rb_history *h, const char *path);

/* Writes the whole history (most recent first).  0 ok, -1 on error. */
int         rb_history_save(const rb_history *h, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_HISTORY_H */
