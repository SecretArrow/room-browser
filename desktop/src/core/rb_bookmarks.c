/*
 * rb_bookmarks.c — bookmarks for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * INVARIANT: `items[0 .. count-1]` are in display order — folder IS NULL,
 * folder, position, created_at, then id (see rb_bm_before).  Every mutation
 * below restores it, which is what keeps rb_bookmarks_at() an array read.
 *
 * The JSON-lines reader/writer uses the shared primitives in rb_json.h, so
 * the escaping and unescaping rules are the same ones every other store in
 * the browser applies.
 */

#include "rb_bookmarks.h"

#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RB_BOOKMARKS_LINE 16384

struct rb_bookmarks {
    rb_bookmark *items; /* display order */
    int count;
    int cap;
    long next_id;
};

/* ------------------------------- helpers -------------------------------- */

static void rb_bm_release(rb_bookmark *bm)
{
    free(bm->url);
    free(bm->title);
    free(bm->folder);
    memset(bm, 0, sizeof(*bm));
}

static void rb_bookmarks_grow(rb_bookmarks *b)
{
    int ncap;
    rb_bookmark *grown;

    if (b->count < b->cap) {
        return;
    }
    ncap = (b->cap > 0) ? b->cap * 2 : 8;
    grown = (rb_bookmark *)realloc(b->items,
                                   (size_t)ncap * sizeof(rb_bookmark));
    if (grown == NULL) {
        fprintf(stderr, "rb_bookmarks: out of memory\n");
        exit(1);
    }
    b->items = grown;
    b->cap = ncap;
}

/* 1 when `a` sorts before `b`.  This is the DAO's ORDER BY, with the id as a
 * final tie-break: SQLite would leave four-way ties in whatever order it
 * happened to read, and an order that depends on storage layout is not
 * something a UI or a test can rely on. */
static int rb_bm_before(const rb_bookmark *a, const rb_bookmark *b)
{
    const int a_folder = (a->folder != NULL);
    const int b_folder = (b->folder != NULL);

    if (a_folder != b_folder) {
        return a_folder; /* the one WITH a folder sorts first */
    }
    if (a_folder) {
        int c = strcmp(a->folder, b->folder);
        if (c != 0) {
            return c < 0;
        }
    }
    if (a->position != b->position) {
        return a->position < b->position;
    }
    if (a->created_at != b->created_at) {
        return a->created_at < b->created_at;
    }
    return a->id < b->id;
}

/* Stable insertion sort.  Bookmark lists are short and nearly sorted between
 * mutations, which is the case this is built for. */
static void rb_bookmarks_sort(rb_bookmarks *b)
{
    int i;

    for (i = 1; i < b->count; i++) {
        rb_bookmark key = b->items[i];
        int j = i - 1;

        while (j >= 0 && rb_bm_before(&key, &b->items[j])) {
            b->items[j + 1] = b->items[j];
            j--;
        }
        b->items[j + 1] = key;
    }
}

static int rb_bookmarks_index_of_id(const rb_bookmarks *b, long id)
{
    int i;

    if (b == NULL) {
        return -1;
    }
    for (i = 0; i < b->count; i++) {
        if (b->items[i].id == id) {
            return i;
        }
    }
    return -1;
}

static int rb_bookmarks_index_of_url(const rb_bookmarks *b, const char *url)
{
    int i;

    if (b == NULL || url == NULL) {
        return -1;
    }
    for (i = 0; i < b->count; i++) {
        if (strcmp(b->items[i].url, url) == 0) {
            return i;
        }
    }
    return -1;
}

/* Drops the row at `i`, keeping the display order of everything else. */
static void rb_bookmarks_drop(rb_bookmarks *b, int i)
{
    rb_bm_release(&b->items[i]);
    if (i + 1 < b->count) {
        memmove(&b->items[i], &b->items[i + 1],
                (size_t)(b->count - i - 1) * sizeof(rb_bookmark));
    }
    b->count--;
}

/* ------------------------------- parsing -------------------------------- */

/* Reads a string field; NULL when the key is absent or explicitly null. */
static char *rb_bm_field_str(const char *line, const char *key)
{
    size_t pos;
    char *out = NULL;

    if (!rb_json_find_key(line, key, &pos)) {
        return NULL;
    }
    if (line[pos] != '"' || !rb_json_parse_string(line, &pos, &out)) {
        return NULL; /* null, a number, or malformed: not a folder name */
    }
    return out;
}

static long long rb_bm_field_num(const char *line, const char *key,
                                 long long fallback)
{
    size_t pos;
    long long v = fallback;

    if (!rb_json_find_key(line, key, &pos)) {
        return fallback;
    }
    if (!rb_json_parse_number(line, &pos, &v)) {
        return fallback;
    }
    return v;
}

/* ------------------------------ public API ------------------------------ */

rb_bookmarks *rb_bookmarks_new(void)
{
    rb_bookmarks *b = (rb_bookmarks *)calloc(1, sizeof(*b));

    if (b == NULL) {
        fprintf(stderr, "rb_bookmarks: out of memory\n");
        exit(1);
    }
    b->next_id = 1;
    return b;
}

void rb_bookmarks_free(rb_bookmarks *b)
{
    int i;

    if (b == NULL) {
        return;
    }
    for (i = 0; i < b->count; i++) {
        rb_bm_release(&b->items[i]);
    }
    free(b->items);
    free(b);
}

int rb_bookmarks_count(const rb_bookmarks *b)
{
    return (b != NULL) ? b->count : 0;
}

const rb_bookmark *rb_bookmarks_at(const rb_bookmarks *b, int index)
{
    if (b == NULL || index < 0 || index >= b->count) {
        return NULL;
    }
    return &b->items[index];
}

const rb_bookmark *rb_bookmarks_find(const rb_bookmarks *b, const char *url)
{
    int i = rb_bookmarks_index_of_url(b, url);

    return (i >= 0) ? &b->items[i] : NULL;
}

int rb_bookmarks_contains(const rb_bookmarks *b, const char *url)
{
    return (rb_bookmarks_index_of_url(b, url) >= 0) ? 1 : 0;
}

rb_bookmark *rb_bookmarks_by_id(rb_bookmarks *b, long id)
{
    int i = rb_bookmarks_index_of_id(b, id);

    return (i >= 0) ? &b->items[i] : NULL;
}

int rb_bookmarks_max_position(const rb_bookmarks *b)
{
    int i;
    int best = -1;

    if (b == NULL) {
        return -1;
    }
    for (i = 0; i < b->count; i++) {
        if (b->items[i].position > best) {
            best = b->items[i].position;
        }
    }
    return best;
}

long rb_bookmarks_add(rb_bookmarks *b, const char *url, const char *title,
                      const char *folder, long long now_ms)
{
    rb_bookmark row;
    long id;

    if (b == NULL || url == NULL || url[0] == '\0') {
        return -1;
    }
    if (rb_bookmarks_index_of_url(b, url) >= 0) {
        return -1; /* already bookmarked; addBookmark returns -1 here too */
    }
    memset(&row, 0, sizeof(row));
    id = b->next_id++;
    row.id = id;
    row.url = rb_json_strdup(url);
    row.title = rb_json_strdup(title);
    /* An empty folder name is the no-folder case: Android's callers pass null
     * or a real name, so "" would otherwise create a group with no header. */
    row.folder = (folder != NULL && folder[0] != '\0')
                     ? rb_json_strdup(folder)
                     : NULL;
    row.position = rb_bookmarks_max_position(b) + 1;
    row.created_at = now_ms;

    rb_bookmarks_grow(b);
    b->items[b->count++] = row;
    /* The new row's position is the highest in use, so it can only need to
     * travel within its own group — but a new folder group can sort anywhere,
     * so sort rather than insert. */
    rb_bookmarks_sort(b);
    return id;
}

int rb_bookmarks_update_meta(rb_bookmarks *b, long id, const char *title,
                             const char *folder)
{
    int i = rb_bookmarks_index_of_id(b, id);
    rb_bookmark *bm;

    if (i < 0) {
        return 0;
    }
    bm = &b->items[i];
    free(bm->title);
    bm->title = rb_json_strdup(title);
    free(bm->folder);
    bm->folder = (folder != NULL && folder[0] != '\0')
                     ? rb_json_strdup(folder)
                     : NULL;
    /* folder is part of the sort key, so this can move the row. */
    rb_bookmarks_sort(b);
    return 1;
}

int rb_bookmarks_delete(rb_bookmarks *b, long id)
{
    int i = rb_bookmarks_index_of_id(b, id);

    if (i < 0) {
        return 0;
    }
    rb_bookmarks_drop(b, i);
    return 1;
}

int rb_bookmarks_delete_url(rb_bookmarks *b, const char *url)
{
    int i = rb_bookmarks_index_of_url(b, url);

    if (i < 0) {
        return 0;
    }
    rb_bookmarks_drop(b, i);
    return 1;
}

int rb_bookmarks_toggle(rb_bookmarks *b, const char *url, const char *title,
                        long long now_ms)
{
    if (rb_bookmarks_delete_url(b, url)) {
        return 0;
    }
    return (rb_bookmarks_add(b, url, title, NULL, now_ms) > 0) ? 1 : 0;
}

int rb_bookmarks_clear(rb_bookmarks *b)
{
    int removed;
    int i;

    if (b == NULL) {
        return 0;
    }
    removed = b->count;
    for (i = 0; i < b->count; i++) {
        rb_bm_release(&b->items[i]);
    }
    b->count = 0;
    return removed;
}

/* ------------------------------ persistence ----------------------------- */

static int rb_bm_write_str(FILE *f, const char *value)
{
    char *escaped = rb_json_escape(value);
    int rc = 0;

    if (fputc('"', f) == EOF || fputs(escaped, f) == EOF || fputc('"', f) == EOF) {
        rc = -1;
    }
    free(escaped);
    return rc;
}

static int rb_bm_write_row(FILE *f, const rb_bookmark *bm)
{
    int rc;

    if (fprintf(f, "{\"id\":%ld,\"position\":%d,\"created_at\":%lld,\"url\":",
                bm->id, bm->position, bm->created_at) < 0) {
        return -1;
    }
    rc = rb_bm_write_str(f, bm->url);
    if (rc == 0 && fputs(",\"title\":", f) == EOF) {
        rc = -1;
    }
    if (rc == 0) {
        rc = rb_bm_write_str(f, bm->title);
    }
    if (rc == 0 && fputs(",\"folder\":", f) == EOF) {
        rc = -1;
    }
    if (rc == 0) {
        /* An explicit null, not an omitted key: it is the difference between
         * "no folder" and a folder named "". */
        rc = (bm->folder != NULL) ? rb_bm_write_str(f, bm->folder)
                                  : (fputs("null", f) == EOF ? -1 : 0);
    }
    if (rc == 0 && fputs("}\n", f) == EOF) {
        rc = -1;
    }
    return rc;
}

int rb_bookmarks_save(const rb_bookmarks *b, const char *path)
{
    FILE *f;
    int i;
    int rc = 0;

    if (b == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < b->count && rc == 0; i++) {
        rc = rb_bm_write_row(f, &b->items[i]);
    }
    if (rc == 0 && ferror(f)) {
        rc = -1;
    }
    if (fclose(f) != 0) {
        rc = -1;
    }
    return rc;
}

int rb_bookmarks_load(rb_bookmarks *b, const char *path)
{
    char buf[RB_BOOKMARKS_LINE];
    FILE *f;
    int rc = 0;

    if (b == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *url;
        char *title;
        char *folder;
        rb_bookmark row;

        if (len > 0 && buf[len - 1] != '\n' && !feof(f)) {
            int ch; /* line longer than the buffer: skip it entirely */
            while ((ch = fgetc(f)) != EOF && ch != '\n') {
                /* drain */
            }
            continue;
        }
        while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
            buf[--len] = '\0';
        }
        if (len == 0) {
            continue;
        }
        url = rb_bm_field_str(buf, "url");
        if (url == NULL || url[0] == '\0') {
            free(url); /* not one of our rows */
            continue;
        }
        if (rb_bookmarks_index_of_url(b, url) >= 0) {
            free(url); /* already held: the first row wins */
            continue;
        }
        title = rb_bm_field_str(buf, "title");
        folder = rb_bm_field_str(buf, "folder");

        memset(&row, 0, sizeof(row));
        row.id = (long)rb_bm_field_num(buf, "id", 0);
        row.position = (int)rb_bm_field_num(buf, "position", 0);
        row.created_at = rb_bm_field_num(buf, "created_at", 0);
        row.url = url;
        row.title = (title != NULL) ? title : rb_json_strdup("");
        row.folder = (folder != NULL && folder[0] != '\0') ? folder : NULL;
        if (row.folder == NULL) {
            free(folder);
        }

        rb_bookmarks_grow(b);
        if (row.id <= 0) {
            row.id = b->next_id; /* a row from before ids existed */
        }
        if (row.id >= b->next_id) {
            b->next_id = row.id + 1;
        }
        b->items[b->count++] = row;
    }
    if (ferror(f)) {
        rc = -1;
    }
    fclose(f);

    /* The file's row order is not necessarily the display order — rows may
     * have been written by an older build with no positions at all. */
    rb_bookmarks_sort(b);
    return rc;
}
