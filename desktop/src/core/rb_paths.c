/*
 * rb_paths.c — data-directory helper for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The POSIX directory calls used by the tree walkers (lstat, opendir, rmdir,
 * unlink) are hidden by a strict -std=c11 translation unit, so ask for them
 * explicitly.  Must precede every system header.
 */
#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#endif

#include "rb_paths.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>

#ifdef _WIN32
#include <windows.h> /* CreateDirectoryA */
#else
#include <dirent.h>
#include <unistd.h>
#endif

/* MSVC's CRT has no S_ISDIR (POSIX-only; mingw has it, MSVC does not —
 * LNK2019 CI-proven). Test the _S_IFDIR bit directly on Windows. */
#ifdef _WIN32
#define RB_S_ISDIR(m) (((m) & _S_IFDIR) != 0)
#else
#define RB_S_ISDIR(m) S_ISDIR(m)
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
        return RB_S_ISDIR(st.st_mode) ? 0 : -1;
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

char *rb_paths_join(const char *dir, const char *name)
{
    size_t dlen, nlen;
    char *p;

    if (dir == NULL || name == NULL || dir[0] == '\0' || name[0] == '\0') {
        return NULL;
    }
    dlen = strlen(dir);
    nlen = strlen(name);
    p = (char *)malloc(dlen + nlen + 2);
    if (p == NULL) {
        return NULL;
    }
    memcpy(p, dir, dlen);
    p[dlen] = '/';
    memcpy(p + dlen + 1, name, nlen + 1);
    return p;
}

char *rb_paths_join3(const char *a, const char *b, const char *c)
{
    char *first = rb_paths_join(a, b);
    char *both;

    if (first == NULL) {
        return NULL;
    }
    both = rb_paths_join(first, c);
    free(first);
    return both;
}

int rb_paths_mkdirs(const char *path)
{
    char *copy;
    int rc;

    if (path == NULL || path[0] == '\0') {
        return -1;
    }
    copy = rb_paths_strdup(path);
    rc = rb_mkdirs(copy);
    free(copy);
    return rc;
}

int rb_paths_is_dir(const char *path)
{
    struct stat st;

    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    return stat(path, &st) == 0 && RB_S_ISDIR(st.st_mode);
}

int rb_paths_is_file(const char *path)
{
    struct stat st;

    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    return stat(path, &st) == 0 && !RB_S_ISDIR(st.st_mode);
}

char *rb_paths_dirname(const char *path)
{
    const char *slash;
    size_t len;

    if (path == NULL || path[0] == '\0') {
        return rb_paths_strdup(".");
    }
    slash = strrchr(path, '/');
#ifdef _WIN32
    {
        const char *back = strrchr(path, '\\');
        if (back != NULL && (slash == NULL || back > slash)) {
            slash = back;
        }
    }
#endif
    if (slash == NULL) {
        return rb_paths_strdup(".");
    }
    len = (size_t)(slash - path);
    if (len == 0) {
        return rb_paths_strdup("/");
    }
    {
        char *out = (char *)malloc(len + 1);
        if (out == NULL) {
            return NULL;
        }
        memcpy(out, path, len);
        out[len] = '\0';
        return out;
    }
}

void rb_paths_free(char *p)
{
    free(p);
}

/* ------------------------- recursive tree operations ------------------------- */

#ifdef _WIN32

int rb_paths_remove_tree(const char *path)
{
    DWORD attrs;
    WIN32_FIND_DATAA fd;
    char *pattern;
    HANDLE h;

    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    attrs = GetFileAttributesA(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) {
        return 1; /* already gone */
    }
    if ((attrs & FILE_ATTRIBUTE_DIRECTORY) == 0) {
        /* A read-only file cannot be deleted until the bit is cleared. */
        SetFileAttributesA(path, FILE_ATTRIBUTE_NORMAL);
        return DeleteFileA(path) != 0;
    }
    pattern = rb_paths_join(path, "*");
    if (pattern != NULL) {
        h = FindFirstFileA(pattern, &fd);
        free(pattern);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                char *child;
                if (strcmp(fd.cFileName, ".") == 0 ||
                    strcmp(fd.cFileName, "..") == 0) {
                    continue;
                }
                child = rb_paths_join(path, fd.cFileName);
                if (child != NULL) {
                    (void)rb_paths_remove_tree(child);
                    free(child);
                }
            } while (FindNextFileA(h, &fd) != 0);
            FindClose(h);
        }
    }
    return RemoveDirectoryA(path) != 0;
}

int rb_paths_copy_tree(const char *from, const char *to)
{
    DWORD attrs;
    WIN32_FIND_DATAA fd;
    char *pattern;
    HANDLE h;
    int rc = 0;

    if (from == NULL || to == NULL) {
        return -1;
    }
    attrs = GetFileAttributesA(from);
    if (attrs == INVALID_FILE_ATTRIBUTES) {
        return -1;
    }
    if ((attrs & FILE_ATTRIBUTE_DIRECTORY) == 0) {
        FILE *in = fopen(from, "rb");
        FILE *out;
        char buf[16384];
        size_t n;
        if (in == NULL) {
            return -1;
        }
        out = fopen(to, "wb");
        if (out == NULL) {
            fclose(in);
            return -1;
        }
        while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
            if (fwrite(buf, 1, n, out) != n) {
                rc = -1;
                break;
            }
        }
        if (ferror(in)) {
            rc = -1;
        }
        fclose(out);
        fclose(in);
        return rc;
    }
    if (rb_paths_mkdirs(to) != 0) {
        return -1;
    }
    pattern = rb_paths_join(from, "*");
    if (pattern == NULL) {
        return -1;
    }
    h = FindFirstFileA(pattern, &fd);
    free(pattern);
    if (h == INVALID_HANDLE_VALUE) {
        return 0; /* empty directory */
    }
    do {
        char *src;
        char *dst;
        if (strcmp(fd.cFileName, ".") == 0 ||
            strcmp(fd.cFileName, "..") == 0) {
            continue;
        }
        src = rb_paths_join(from, fd.cFileName);
        dst = rb_paths_join(to, fd.cFileName);
        if (src == NULL || dst == NULL) {
            rc = -1;
        } else if (rb_paths_copy_tree(src, dst) != 0) {
            rc = -1;
        }
        free(src);
        free(dst);
    } while (FindNextFileA(h, &fd) != 0);
    FindClose(h);
    return rc;
}

#else /* POSIX */

int rb_paths_remove_tree(const char *path)
{
    struct stat st;
    DIR *d;
    struct dirent *e;

    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    /* lstat, not stat: a symlink inside a profile tree must be removed, not
     * followed out of it. */
    if (lstat(path, &st) != 0) {
        return 1; /* already gone */
    }
    if (!S_ISDIR(st.st_mode)) {
        return unlink(path) == 0;
    }
    d = opendir(path);
    if (d != NULL) {
        while ((e = readdir(d)) != NULL) {
            char *child;
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) {
                continue;
            }
            child = rb_paths_join(path, e->d_name);
            if (child != NULL) {
                (void)rb_paths_remove_tree(child);
                free(child);
            }
        }
        closedir(d);
    }
    return rmdir(path) == 0;
}

int rb_paths_copy_tree(const char *from, const char *to)
{
    struct stat st;
    DIR *d;
    struct dirent *e;

    if (from == NULL || to == NULL) {
        return -1;
    }
    if (lstat(from, &st) != 0) {
        return -1;
    }
    if (!S_ISDIR(st.st_mode)) {
        FILE *in = fopen(from, "rb");
        FILE *out;
        char buf[16384];
        size_t n;
        int rc = 0;

        if (in == NULL) {
            return -1;
        }
        out = fopen(to, "wb");
        if (out == NULL) {
            fclose(in);
            return -1;
        }
        while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
            if (fwrite(buf, 1, n, out) != n) {
                rc = -1;
                break;
            }
        }
        if (ferror(in)) {
            rc = -1;
        }
        fclose(out);
        fclose(in);
        return rc;
    }
    if (rb_paths_mkdirs(to) != 0) {
        return -1;
    }
    d = opendir(from);
    if (d == NULL) {
        return -1; /* unreadable directory: do not report a silent success */
    }
    {
        int rc = 0;
        while ((e = readdir(d)) != NULL) {
            char *src;
            char *dst;
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) {
                continue;
            }
            src = rb_paths_join(from, e->d_name);
            dst = rb_paths_join(to, e->d_name);
            if (src == NULL || dst == NULL) {
                rc = -1;
            } else if (rb_paths_copy_tree(src, dst) != 0) {
                rc = -1;
            }
            free(src);
            free(dst);
        }
        closedir(d);
        return rc;
    }
}

#endif /* _WIN32 */
