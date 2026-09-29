/*
 * rb_bookmarks.h — bookmarks for Room Browser core.
 *
 * Ports BookmarkEntity and BookmarkDao.  A bookmark is a row with an id, a
 * URL, a title, an optional folder and a position — not just a URL/title
 * pair — because that is what the Android edition stores and what its
 * screens render.
 *
 * DISPLAY ORDER IS AN INVARIANT, like rb_tabs': `items[0 .. count-1]` are
 * always in the DAO's order (BookmarkDao.observeAll)
 *
 *     ORDER BY folder IS NULL, folder, position, created_at
 *
 * so rb_bookmarks_at() is an array read rather than a sort per call.  Note
 * what that order means: a row WITH a folder sorts before one without, so
 * the unfolded bookmarks land at the end of the list, exactly as they do on
 * Android where the folder-less rows form the last group of the screen.
 *
 * The clock is passed in rather than read here, so created_at is testable.
 * All strings are UTF-8.  Persistence is JSON lines, one row per bookmark:
 *   {"id":1,"url":"...","title":"...","folder":null,"position":0,"created_at":0}
 * "folder":null is the no-folder case, which is a different thing from a
 * folder whose name is "" — again matching BookmarkEntity.folder: String?.
 */

#ifndef RB_BOOKMARKS_H
#define RB_BOOKMARKS_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    long id;
    char *url;
    char *title;
    char *folder;         /* NULL = no folder (BookmarkEntity.folder == null) */
    int position;         /* within its folder group */
    long long created_at; /* ms since the epoch */
} rb_bookmark;

/* Opaque, ordered, URL-unique bookmark list. */
typedef struct rb_bookmarks rb_bookmarks;

rb_bookmarks *rb_bookmarks_new(void);
void          rb_bookmarks_free(rb_bookmarks *b);

int           rb_bookmarks_count(const rb_bookmarks *b);

/* The index'th bookmark in display order; NULL when out of range. */
const rb_bookmark *rb_bookmarks_at(const rb_bookmarks *b, int index);

/* BookmarkDao.find: the bookmark with this URL, or NULL.  There is at most
 * one, because add() refuses a URL that is already present. */
const rb_bookmark *rb_bookmarks_find(const rb_bookmarks *b, const char *url);
int           rb_bookmarks_contains(const rb_bookmarks *b, const char *url);

/* Mutable slot for the id, or NULL when there is no such bookmark.
 *
 * The module owns url, title and folder and frees them with the row, so a
 * caller that replaces one must hand over a malloc'd string (or NULL, which
 * reads as "no folder" for `folder` and "" for the others).  Moving a row
 * between folders goes through rb_bookmarks_update_meta() instead, which
 * also restores the display order. */
rb_bookmark  *rb_bookmarks_by_id(rb_bookmarks *b, long id);

/* BrowserRepository.addBookmark: appends at max_position()+1 with a fresh id.
 * Returns the new id (always > 0), or -1 when the URL is already bookmarked.
 * A NULL/empty url is refused with -1; a NULL title is stored as "". */
long          rb_bookmarks_add(rb_bookmarks *b, const char *url,
                               const char *title, const char *folder,
                               long long now_ms);

/* BookmarkDao.updateMeta: replaces the title and the folder.  A NULL folder
 * clears it — which moves the row to the end of the list, since folder is
 * part of the sort key.  Returns 1 when the id exists. */
int           rb_bookmarks_update_meta(rb_bookmarks *b, long id,
                                       const char *title, const char *folder);

/* BookmarkDao.delete.  Returns 1 when the bookmark was there. */
int           rb_bookmarks_delete(rb_bookmarks *b, long id);

/* Removes the bookmark for a URL.  Returns 1 when it was there.  This is the
 * bookmark button's "is it starred? then unstar it" step, which on Android is
 * a find() followed by a delete(id). */
int           rb_bookmarks_delete_url(rb_bookmarks *b, const char *url);

/* BrowserViewModel.toggleBookmark as one call, since both desktop UI layers
 * want exactly it: unbookmarks `url` when it is bookmarked, otherwise
 * bookmarks it with `title` (blank falling back to the URL).  Returns 1 when
 * the page is bookmarked afterwards.  A NULL/empty url is a no-op. */
int           rb_bookmarks_toggle(rb_bookmarks *b, const char *url,
                                  const char *title, long long now_ms);

/* BookmarkDao.deleteAllFor: removes everything.  Returns how many went. */
int           rb_bookmarks_clear(rb_bookmarks *b);

/* BookmarkDao.maxPosition, with Kotlin's `?: -1` folded in: the highest
 * position in use, or -1 when the list is empty. */
int           rb_bookmarks_max_position(const rb_bookmarks *b);

/* Writes every bookmark in display order.  0 ok, -1 on error. */
int           rb_bookmarks_save(const rb_bookmarks *b, const char *path);

/* Loads a JSON-lines file, appending (de-duplicated) rows after any already
 * held.  A missing file is fine (returns 0, keeps state).  Rows written by an
 * older build carry no id/position/created_at; they arrive at position 0 and
 * keep their file order.  0 on success, -1 on I/O error. */
int           rb_bookmarks_load(rb_bookmarks *b, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_BOOKMARKS_H */
