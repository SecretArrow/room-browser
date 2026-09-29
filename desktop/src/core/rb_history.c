/*
 * rb_history.c — browsing history for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The JSON-lines reader/writer is intentionally small and duplicated in
 * rb_bookmarks.c so each module stays self-contained (the public header
 * contract is shared, the helpers are not).
 */

#include "rb_history.h"

#include "rb_str.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define RB_HISTORY_MAX 10000
#define RB_HISTORY_LINE 16384

static char *rb_hist_strdup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_history: out of memory\n");
        exit(1);
    }
    memcpy(p, s, n);
    return p;
}

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

/* ------------------------- minimal JSON helpers ------------------------- */

static void rb_js_skip_ws(const char *s, size_t *i)
{
    while (s[*i] == ' ' || s[*i] == '\t') {
        (*i)++;
    }
}

static int rb_hex_val(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

static void rb_append_utf8(rb_str *b, unsigned cp)
{
    char one[2];

    if (cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) {
        cp = 0xFFFDu; /* out of range / lone surrogate -> replacement char */
    }
    one[1] = '\0';
    if (cp < 0x80u) {
        one[0] = (char)cp;
        rb_str_append(b, one);
    } else if (cp < 0x800u) {
        one[0] = (char)(0xC0u | (cp >> 6));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | (cp & 0x3Fu));
        rb_str_append(b, one);
    } else if (cp < 0x10000u) {
        one[0] = (char)(0xE0u | (cp >> 12));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | ((cp >> 6) & 0x3Fu));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | (cp & 0x3Fu));
        rb_str_append(b, one);
    } else {
        one[0] = (char)(0xF0u | (cp >> 18));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | ((cp >> 12) & 0x3Fu));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | ((cp >> 6) & 0x3Fu));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | (cp & 0x3Fu));
        rb_str_append(b, one);
    }
}

/* Parses a JSON string starting at s[*i] (must be '"').  On success
 * advances *i past the closing quote and stores a malloc'd UTF-8 string. */
static int rb_parse_json_string(const char *s, size_t *i, char **out)
{
    rb_str b;

    *out = NULL;
    if (s[*i] != '"') {
        return 0;
    }
    (*i)++;
    rb_str_init(&b);
    while (s[*i] != '"' && s[*i] != '\0') {
        char c = s[*i];
        if (c == '\\') {
            char e = s[*i + 1];
            switch (e) {
            case '"':
                rb_str_append(&b, "\"");
                break;
            case '\\':
                rb_str_append(&b, "\\");
                break;
            case '/':
                rb_str_append(&b, "/");
                break;
            case 'b':
                rb_str_append(&b, "\b");
                break;
            case 'f':
                rb_str_append(&b, "\f");
                break;
            case 'n':
                rb_str_append(&b, "\n");
                break;
            case 'r':
                rb_str_append(&b, "\r");
                break;
            case 't':
                rb_str_append(&b, "\t");
                break;
            case 'u': {
                unsigned cp = 0;
                int k;
                for (k = 1; k <= 4; k++) {
                    int hv = rb_hex_val(s[*i + (size_t)k]);
                    if (hv < 0) {
                        goto fail;
                    }
                    cp = cp * 16u + (unsigned)hv;
                }
                rb_append_utf8(&b, cp);
                (*i) += 6;
                continue;
            }
            default:
                goto fail;
            }
            (*i) += 2;
        } else {
            char one[2];
            one[0] = c;
            one[1] = '\0';
            rb_str_append(&b, one);
            (*i)++;
        }
    }
    if (s[*i] != '"') {
        goto fail; /* unterminated */
    }
    (*i)++;
    *out = (b.data != NULL) ? b.data : rb_hist_strdup("");
    return 1;
fail:
    rb_str_free(&b);
    return 0;
}

static int rb_parse_json_number(const char *s, size_t *i, long long *out)
{
    char *end = NULL;
    long long v;

    if (s[*i] == '\0') {
        return 0;
    }
    v = strtoll(s + *i, &end, 10);
    if (end == s + *i) {
        return 0;
    }
    *i = (size_t)(end - s);
    *out = v;
    return 1;
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

    rb_js_skip_ws(line, &i);
    if (line[i] != '{') {
        return 0;
    }
    i++;
    for (;;) {
        char *key = NULL;
        rb_js_skip_ws(line, &i);
        if (line[i] == '}') {
            i++;
            rb_js_skip_ws(line, &i);
            if (line[i] != '\0') {
                goto fail;
            }
            break;
        }
        if (line[i] != '"') {
            goto fail;
        }
        if (!rb_parse_json_string(line, &i, &key)) {
            goto fail;
        }
        rb_js_skip_ws(line, &i);
        if (line[i] != ':') {
            free(key);
            goto fail;
        }
        i++;
        rb_js_skip_ws(line, &i);
        if (line[i] == '"') {
            char *val = NULL;
            if (!rb_parse_json_string(line, &i, &val)) {
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
            if (!rb_parse_json_number(line, &i, &num)) {
                free(key);
                goto fail;
            }
            if (strcmp(key, "visited_at") == 0) {
                ts = num;
            }
        }
        free(key);
        rb_js_skip_ws(line, &i);
        if (line[i] == ',') {
            i++;
            continue;
        }
        if (line[i] == '}') {
            i++;
            rb_js_skip_ws(line, &i);
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
    *out_title = (title != NULL) ? title : rb_hist_strdup("");
    *out_ts = ts;
    return 1;
fail:
    free(url);
    free(title);
    return 0;
}

/* Escapes ", \ and control characters; returns a malloc'd string. */
static char *rb_json_escape(const char *s)
{
    rb_str b;
    size_t i;

    rb_str_init(&b);
    if (s != NULL) {
        for (i = 0; s[i] != '\0'; i++) {
            unsigned char c = (unsigned char)s[i];
            switch (c) {
            case '"':
                rb_str_append(&b, "\\\"");
                break;
            case '\\':
                rb_str_append(&b, "\\\\");
                break;
            case '\n':
                rb_str_append(&b, "\\n");
                break;
            case '\r':
                rb_str_append(&b, "\\r");
                break;
            case '\t':
                rb_str_append(&b, "\\t");
                break;
            case '\b':
                rb_str_append(&b, "\\b");
                break;
            case '\f':
                rb_str_append(&b, "\\f");
                break;
            default:
                if (c < 0x20u) {
                    rb_str_appendf(&b, "\\u%04x", (unsigned)c);
                } else {
                    char one[2];
                    one[0] = (char)c;
                    one[1] = '\0';
                    rb_str_append(&b, one);
                }
            }
        }
    }
    return (b.data != NULL) ? b.data : rb_hist_strdup("");
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
    nu = rb_hist_strdup(url);
    nt = rb_hist_strdup(title);
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

/* Appends at the tail (oldest side); used by load() to preserve file order. */
static void rb_history_push_tail(rb_history *h, const char *url,
                                 const char *title, long long ts)
{
    if (h->count >= RB_HISTORY_MAX) {
        return;
    }
    rb_history_grow(h);
    h->items[h->count].url = rb_hist_strdup(url);
    h->items[h->count].title = rb_hist_strdup(title);
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
