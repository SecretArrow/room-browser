/*
 * rb_bookmarks.h — bookmark list for Room Browser core.
 *
 * Bookmarks are kept in insertion order and de-duplicated by URL.
 * Persistence is JSON-lines: {"url":"...","title":"..."} with minimal
 * escaping (", \ and control characters); the parser skips malformed
 * lines.  All strings UTF-8.
 */

#ifndef RB_BOOKMARKS_H
#define RB_BOOKMARKS_H

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque, ordered, URL-unique bookmark list. */
typedef struct rb_bookmarks rb_bookmarks;

rb_bookmarks *rb_bookmarks_new(void);
void          rb_bookmarks_free(rb_bookmarks *b);

/* Adds a bookmark.  Returns 1 when added, 0 when the URL is already
 * present (or when url is NULL/empty). */
int           rb_bookmarks_add(rb_bookmarks *b, const char *url, const char *title);

/* Removes by URL.  Returns 1 when removed, 0 when not found. */
int           rb_bookmarks_remove(rb_bookmarks *b, const char *url);

int           rb_bookmarks_contains(const rb_bookmarks *b, const char *url);
int           rb_bookmarks_count(const rb_bookmarks *b);

/* Insertion-order accessors; NULL when index is out of range. */
const char   *rb_bookmarks_url_at(const rb_bookmarks *b, int index);
const char   *rb_bookmarks_title_at(const rb_bookmarks *b, int index);

/* Loads a JSON-lines file, appending (de-duplicated) entries.  A missing
 * file is fine (returns 0, keeps state).  0 on success, -1 on I/O error. */
int           rb_bookmarks_load(rb_bookmarks *b, const char *path);

/* Writes all bookmarks in order.  0 ok, -1 on error. */
int           rb_bookmarks_save(const rb_bookmarks *b, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_BOOKMARKS_H */
