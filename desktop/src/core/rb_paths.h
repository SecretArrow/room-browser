/*
 * rb_paths.h — data-directory helper for Room Browser core.
 *
 * rb_paths_data_dir() returns a malloc'd path to the per-user data
 * directory, creating it recursively when needed:
 *   Windows: %APPDATA%\RoomBrowser
 *   Unix:    $HOME/.config/RoomBrowser
 * Returns NULL when the environment variable is missing, the path would
 * overflow, or the directory cannot be created.
 *
 * Inside that directory every profile owns a self-contained subtree laid
 * out exactly like the Android edition's ProfileDirectoryLayout:
 *
 *   profiles/profile_<id>/metadata
 *   profiles/profile_<id>/browser_data    (web-engine profile)
 *   profiles/profile_<id>/cache
 *   profiles/profile_<id>/downloads
 *   profiles/profile_<id>/settings        (settings.txt, history.jsonl, ...)
 *
 * and profiles.jsonl at the root is the profile registry.
 */

#ifndef RB_PATHS_H
#define RB_PATHS_H

#ifdef __cplusplus
extern "C" {
#endif

/* malloc'd data dir path (caller frees with rb_paths_free or free()). */
char *rb_paths_data_dir(void);

/* free() wrapper; NULL-safe. */
void  rb_paths_free(char *p);

/* Joins dir and name with the platform separator ('/' everywhere; Windows
 * accepts '/', and keeping one separator keeps the two editions' stored
 * paths interchangeable). malloc'd; NULL on allocation failure or when dir
 * or name is NULL/empty. */
char *rb_paths_join(const char *dir, const char *name);

/* Same, for three components. */
char *rb_paths_join3(const char *a, const char *b, const char *c);

/* Creates every component of path recursively. Returns 0 on success (also
 * when it already exists as a directory), -1 otherwise. */
int   rb_paths_mkdirs(const char *path);

/* 1 when path exists as a directory, 0 otherwise. */
int   rb_paths_is_dir(const char *path);

/* 1 when path exists as a regular file, 0 otherwise. */
int   rb_paths_is_file(const char *path);

/* Copies the directory part of path (everything before the last separator)
 * into a malloc'd string; "." when there is no separator. */
char *rb_paths_dirname(const char *path);

/* Recursively deletes `path` (file or directory).  Returns 1 when the path
 * is gone afterwards — also when it never existed — and 0 when something is
 * still there.  Symlinks are removed, never followed. */
int   rb_paths_remove_tree(const char *path);

/* Recursively copies the tree at `from` into `to`, creating `to`.  Regular
 * files and directories only; anything else in the tree is skipped.  Returns
 * 0 on success, -1 on the first error (leaving whatever was copied so far).
 * Used by profile duplication and profile backup/restore. */
int   rb_paths_copy_tree(const char *from, const char *to);

#ifdef __cplusplus
}
#endif

#endif /* RB_PATHS_H */
