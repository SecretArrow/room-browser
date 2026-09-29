/*
 * rb_paths.c — data-directory helper for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_paths.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>

#ifdef _WIN32
#include <windows.h> /* CreateDirectoryA */
#else
#include <unistd.h>
#endif

static char *rb_paths_strdup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_paths: out of memory\n");
        exit(1);
    }
    memcpy(p, s, n);
    return p;
}

/* Creates one path component; succeeds silently when it already exists
 * as a directory (stat-first keeps us off errno, which is not in the
 * allowed include set). */
static int rb_mkdir_one(const char *path)
{
    struct stat st;

    if (stat(path, &st) == 0) {
        return S_ISDIR(st.st_mode) ? 0 : -1;
    }
#ifdef _WIN32
    if (CreateDirectoryA(path, NULL) == 0) {
        return -1;
    }
    return 0;
#else
    if (mkdir(path, 0755) != 0) {
        return -1;
    }
    return 0;
#endif
}

/* Creates every component of `path` (temporarily mutated in place). */
static int rb_mkdirs(char *path)
{
    char *p;

    for (p = path + 1; *p != '\0'; p++) {
        if (*p == '/' || *p == '\\') {
            char saved = *p;
            *p = '\0';
            if (rb_mkdir_one(path) != 0) {
                *p = saved;
                return -1;
            }
            *p = saved;
        }
    }
    return rb_mkdir_one(path);
}

char *rb_paths_data_dir(void)
{
#ifdef _WIN32
    const char *base = getenv("APPDATA");
#else
    const char *base = getenv("HOME");
#endif
    char path[1024];
    int n;

    if (base == NULL || base[0] == '\0') {
        return NULL;
    }
#ifdef _WIN32
    n = snprintf(path, sizeof(path), "%s\\RoomBrowser", base);
#else
    n = snprintf(path, sizeof(path), "%s/.config/RoomBrowser", base);
#endif
    if (n < 0 || (size_t)n >= sizeof(path)) {
        return NULL;
    }
    if (rb_mkdirs(path) != 0) {
        return NULL;
    }
    return rb_paths_strdup(path);
}

void rb_paths_free(char *p)
{
    free(p);
}
