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
