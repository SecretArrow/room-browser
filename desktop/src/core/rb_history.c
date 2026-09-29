/*
 * rb_history.c — browsing history for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The record format is one flat JSON object per line; the primitives that
 * read and write it live in rb_json so every store shares one behaviour.
 */

#include "rb_history.h"

#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define RB_HISTORY_MAX 10000
#define RB_HISTORY_LINE 16384

struct rb_history {
    rb_hist_entry *items; /* items[0] = most recent */
    int count;
    int cap;
};

static void rb_history_grow(rb_history *h)
{
    int ncap;
    rb_hist_entry *grown;

    if (h->count < h->cap) {
        return;
    }
    ncap = (h->cap > 0) ? h->cap * 2 : 16;
    grown = (rb_hist_entry *)realloc(h->items,
                                     (size_t)ncap * sizeof(rb_hist_entry));
    if (grown == NULL) {
        fprintf(stderr, "rb_history: out of memory\n");
        exit(1);
    }
    h->items = grown;
    h->cap = ncap;
}

/* Parses one history JSON object; requires the "url" key. */
static int rb_parse_history_line(const char *line, char **out_url,
                                 char **out_title, long long *out_ts)
{
    size_t i = 0;
    char *url = NULL;
    char *title = NULL;
    long long ts = 0;
    int have_url = 0;

    *out_url = NULL;
    *out_title = NULL;
    *out_ts = 0;

    rb_json_skip_ws(line, &i);
    if (line[i] != '{') {
        return 0;
    }
    i++;
    for (;;) {
        char *key = NULL;
        rb_json_skip_ws(line, &i);
        if (line[i] == '}') {
            i++;
            rb_json_skip_ws(line, &i);
            if (line[i] != '\0') {
                goto fail;
            }
            break;
        }
        if (line[i] != '"') {
            goto fail;
        }
        if (!rb_json_parse_string(line, &i, &key)) {
            goto fail;
        }
        rb_json_skip_ws(line, &i);
        if (line[i] != ':') {
            free(key);
            goto fail;
        }
        i++;
        rb_json_skip_ws(line, &i);
        if (line[i] == '"') {
            char *val = NULL;
            if (!rb_json_parse_string(line, &i, &val)) {
                free(key);
                goto fail;
            }
            if (strcmp(key, "url") == 0) {
                free(url);
                url = val;
                have_url = 1;
            } else if (strcmp(key, "title") == 0) {
                free(title);
                title = val;
            } else {
                free(val); /* unknown key: tolerated */
            }
        } else {
            long long num = 0;
            if (!rb_json_parse_number(line, &i, &num)) {
                free(key);
                goto fail;
            }
            if (strcmp(key, "visited_at") == 0) {
                ts = num;
            }
        }
        free(key);
        rb_json_skip_ws(line, &i);
        if (line[i] == ',') {
            i++;
            continue;
        }
        if (line[i] == '}') {
            i++;
            rb_json_skip_ws(line, &i);
            if (line[i] != '\0') {
                goto fail;
            }
            break;
        }
        goto fail;
    }
    if (!have_url) {
        goto fail;
    }
    *out_url = url;
    *out_title = (title != NULL) ? title : rb_json_strdup("");
    *out_ts = ts;
    return 1;
fail:
    free(url);
    free(title);
    return 0;
}

/* ------------------------------ public API ------------------------------ */

rb_history *rb_history_new(void)
{
    rb_history *h = (rb_history *)calloc(1, sizeof(*h));
    if (h == NULL) {
        fprintf(stderr, "rb_history: out of memory\n");
        exit(1);
    }
    return h;
}

void rb_history_free(rb_history *h)
{
    int i;

    if (h == NULL) {
        return;
    }
    for (i = 0; i < h->count; i++) {
        free(h->items[i].url);
        free(h->items[i].title);
    }
    free(h->items);
    free(h);
}

void rb_history_append(rb_history *h, const char *url, const char *title)
{
    char *nu;
    char *nt;

    if (h == NULL) {
        return;
    }
    nu = rb_json_strdup(url);
    nt = rb_json_strdup(title);
    if (h->count > 0 && strcmp(h->items[0].url, nu) == 0) {
        /* consecutive same-URL: replace the entry, bump the timestamp */
        free(h->items[0].title);
        h->items[0].title = nt;
        h->items[0].visited_at = (long long)time(NULL);
        free(nu);
        return;
    }
    if (h->count >= RB_HISTORY_MAX) {
        free(h->items[h->count - 1].url);
        free(h->items[h->count - 1].title);
        h->count--;
    }
    rb_history_grow(h);
    if (h->count > 0) {
        memmove(&h->items[1], &h->items[0],
                (size_t)h->count * sizeof(rb_hist_entry));
    }
    h->items[0].url = nu;
    h->items[0].title = nt;
    h->items[0].visited_at = (long long)time(NULL);
    h->count++;
}

int rb_history_count(const rb_history *h)
{
    return (h != NULL) ? h->count : 0;
}

const rb_hist_entry *rb_history_recent(const rb_history *h, int n, int *out_n)
{
    int k;

    if (out_n != NULL) {
        *out_n = 0;
    }
    if (h == NULL || h->items == NULL || h->count <= 0 || n <= 0) {
        return NULL;
    }
    k = (n < h->count) ? n : h->count;
    if (out_n != NULL) {
        *out_n = k;
    }
    return h->items;
}

/* ------------------------------- queries -------------------------------- */

static char rb_hist_lower(char c)
{
    return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
}

/* SQLite's LIKE, for the ASCII subset: a case-insensitive substring test. */
static int rb_hist_contains_ci(const char *hay, const char *needle)
{
    size_t nlen = strlen(needle);
    size_t i;

    if (nlen == 0) {
        return 1;
    }
    for (i = 0; hay[i] != '\0'; i++) {
        size_t k = 0;
        while (k < nlen && hay[i + k] != '\0' &&
               rb_hist_lower(hay[i + k]) == rb_hist_lower(needle[k])) {
            k++;
        }
        if (k == nlen) {
            return 1;
        }
    }
    return 0;
}

int rb_history_search(const rb_history *h, const char *needle,
                      const rb_hist_entry **out, int max)
{
    int filled = 0;
    int i;

    if (h == NULL || out == NULL || max <= 0) {
        return 0;
    }
    if (needle == NULL) {
        needle = "";
    }
    for (i = 0; i < h->count && filled < max; i++) {
        if (rb_hist_contains_ci(h->items[i].url, needle) ||
            rb_hist_contains_ci(h->items[i].title, needle)) {
            out[filled++] = &h->items[i];
        }
    }
    return filled;
}

static void rb_hist_release(rb_hist_entry *e)
{
    free(e->url);
    free(e->title);
    memset(e, 0, sizeof(*e));
}

/* Drops the entry at `i`, preserving the order of everything after it. */
static void rb_hist_drop(rb_history *h, int i)
{
    rb_hist_release(&h->items[i]);
    if (i + 1 < h->count) {
        memmove(&h->items[i], &h->items[i + 1],
                (size_t)(h->count - i - 1) * sizeof(rb_hist_entry));
    }
    h->count--;
}

int rb_history_remove_at(rb_history *h, int index)
{
    if (h == NULL || index < 0 || index >= h->count) {
        return 0;
    }
    rb_hist_drop(h, index);
    return 1;
}

int rb_history_delete_since(rb_history *h, long long since)
{
    int removed = 0;
    int i = 0;

    if (h == NULL) {
        return 0;
    }
    while (i < h->count) {
        if (h->items[i].visited_at < since) {
            i++;
            continue;
        }
        rb_hist_drop(h, i); /* the next entry slides into i */
        removed++;
    }
    return removed;
}

int rb_history_clear(rb_history *h)
{
    int removed;
    int i;

    if (h == NULL) {
        return 0;
    }
    removed = h->count;
    for (i = 0; i < h->count; i++) {
        rb_hist_release(&h->items[i]);
    }
    h->count = 0;
    return removed;
}

int rb_history_distinct_sites(const rb_history *h, long long since)
{
    int distinct = 0;
    int i;

    if (h == NULL) {
        return 0;
    }
    for (i = 0; i < h->count; i++) {
        int seen = 0;
        int k;

        if (h->items[i].visited_at < since) {
            continue;
        }
        /* O(n^2) over an already-narrowed window; the log is capped at 10000
         * and this runs once per statistics refresh, not per navigation. */
        for (k = 0; k < i && !seen; k++) {
            if (h->items[k].visited_at >= since &&
                strcmp(h->items[k].url, h->items[i].url) == 0) {
                seen = 1;
            }
        }
        if (!seen) {
            distinct++;
        }
    }
    return distinct;
}

/* Appends at the tail (oldest side); used by load() to preserve file order. */static void rb_history_push_tail(rb_history *h, const char *url,
                                 const char *title, long long ts)
{
    if (h->count >= RB_HISTORY_MAX) {
        return;
    }
    rb_history_grow(h);
    h->items[h->count].url = rb_json_strdup(url);
    h->items[h->count].title = rb_json_strdup(title);
    h->items[h->count].visited_at = ts;
    h->count++;
}

int rb_history_load(rb_history *h, const char *path)
{
    char buf[RB_HISTORY_LINE];
    FILE *f;

    if (h == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *url = NULL;
        char *title = NULL;
        long long ts = 0;

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
        if (rb_parse_history_line(buf, &url, &title, &ts)) {
            rb_history_push_tail(h, url, title, ts);
            free(url);
            free(title);
        }
    }
    if (ferror(f)) {
        fclose(f);
        return -1;
    }
    fclose(f);
    return 0;
}

int rb_history_save(const rb_history *h, const char *path)
{
    FILE *f;
    int i;

    if (h == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < h->count; i++) {
        char *eu = rb_json_escape(h->items[i].url);
        char *et = rb_json_escape(h->items[i].title);
        int ok = fprintf(f, "{\"url\":\"%s\",\"title\":\"%s\",\"visited_at\":%lld}\n",
                         eu, et, h->items[i].visited_at) >= 0;
        free(eu);
        free(et);
        if (!ok) {
            fclose(f);
            return -1;
        }
    }
    if (fflush(f) != 0) {
        fclose(f);
        return -1;
    }
    fclose(f);
    return 0;
}
