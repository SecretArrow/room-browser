/*
 * rb_paths.h — data-directory helper for Room Browser core.
 *
 * rb_paths_data_dir() returns a malloc'd path to the per-user data
 * directory, creating it recursively when needed:
 *   Windows: %APPDATA%\RoomBrowser
 *   Unix:    $HOME/.config/RoomBrowser
 * Returns NULL when the environment variable is missing, the path would
 * overflow, or the directory cannot be created.
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

#ifdef __cplusplus
}
#endif

#endif /* RB_PATHS_H */
