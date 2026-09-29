/*
 * rb_bookmarks.c — bookmark list for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The JSON-lines reader/writer mirrors rb_history.c on purpose: each
 * module stays self-contained (shared public contract, private helpers).
 */

#include "rb_bookmarks.h"

#include "rb_str.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RB_BOOKMARKS_LINE 16384

static char *rb_bm_strdup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_bookmarks: out of memory\n");
        exit(1);
    }
    memcpy(p, s, n);
    return p;
}

typedef struct {
    char *url;
    char *title;
} rb_bm;

struct rb_bookmarks {
    rb_bm *items; /* insertion order */
    int count;
    int cap;
};

static void rb_bookmarks_grow(rb_bookmarks *b)
{
    int ncap;
    rb_bm *grown;

    if (b->count < b->cap) {
        return;
    }
    ncap = (b->cap > 0) ? b->cap * 2 : 8;
    grown = (rb_bm *)realloc(b->items, (size_t)ncap * sizeof(rb_bm));
    if (grown == NULL) {
        fprintf(stderr, "rb_bookmarks: out of memory\n");
        exit(1);
    }
    b->items = grown;
    b->cap = ncap;
}

static int rb_bookmarks_index_of(const rb_bookmarks *b, const char *url)
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
    *out = (b.data != NULL) ? b.data : rb_bm_strdup("");
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

/* Parses one bookmark JSON object; requires the "url" key. */
static int rb_parse_bookmark_line(const char *line, char **out_url,
                                  char **out_title)
{
    size_t i = 0;
    char *url = NULL;
    char *title = NULL;
    int have_url = 0;

    *out_url = NULL;
    *out_title = NULL;

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
            /* numbers (e.g. stray "visited_at") are tolerated and ignored */
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
    *out_title = (title != NULL) ? title : rb_bm_strdup("");
    return 1;
fail:
    free(url);
    free(title);
    return 0;
}

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
    return (b.data != NULL) ? b.data : rb_bm_strdup("");
}

/* ------------------------------ public API ------------------------------ */

rb_bookmarks *rb_bookmarks_new(void)
{
    rb_bookmarks *b = (rb_bookmarks *)calloc(1, sizeof(*b));
    if (b == NULL) {
        fprintf(stderr, "rb_bookmarks: out of memory\n");
        exit(1);
    }
    return b;
}

void rb_bookmarks_free(rb_bookmarks *b)
{
    int i;

    if (b == NULL) {
        return;
    }
    for (i = 0; i < b->count; i++) {
        free(b->items[i].url);
        free(b->items[i].title);
    }
    free(b->items);
    free(b);
}

int rb_bookmarks_add(rb_bookmarks *b, const char *url, const char *title)
{
    rb_bm *slot;

    if (b == NULL || url == NULL || url[0] == '\0') {
        return 0;
    }
    if (rb_bookmarks_index_of(b, url) >= 0) {
        return 0; /* already present */
    }
    rb_bookmarks_grow(b);
    slot = &b->items[b->count];
    slot->url = rb_bm_strdup(url);
    slot->title = rb_bm_strdup(title);
    b->count++;
    return 1;
}

int rb_bookmarks_remove(rb_bookmarks *b, const char *url)
{
    int i;

    if (b == NULL) {
        return 0;
    }
    i = rb_bookmarks_index_of(b, url);
    if (i < 0) {
        return 0;
    }
    free(b->items[i].url);
    free(b->items[i].title);
    if (i + 1 < b->count) {
        memmove(&b->items[i], &b->items[i + 1],
                (size_t)(b->count - i - 1) * sizeof(rb_bm));
    }
    b->count--;
    return 1;
}

int rb_bookmarks_contains(const rb_bookmarks *b, const char *url)
{
    return (b != NULL && url != NULL && rb_bookmarks_index_of(b, url) >= 0)
               ? 1
               : 0;
}

int rb_bookmarks_count(const rb_bookmarks *b)
{
    return (b != NULL) ? b->count : 0;
}

const char *rb_bookmarks_url_at(const rb_bookmarks *b, int index)
{
    if (b == NULL || index < 0 || index >= b->count) {
        return NULL;
    }
    return b->items[index].url;
}

const char *rb_bookmarks_title_at(const rb_bookmarks *b, int index)
{
    if (b == NULL || index < 0 || index >= b->count) {
        return NULL;
    }
    return b->items[index].title;
}

int rb_bookmarks_load(rb_bookmarks *b, const char *path)
{
    char buf[RB_BOOKMARKS_LINE];
    FILE *f;

    if (b == NULL || path == NULL) {
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
        if (rb_parse_bookmark_line(buf, &url, &title)) {
            (void)rb_bookmarks_add(b, url, title); /* keeps de-duplication */
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

int rb_bookmarks_save(const rb_bookmarks *b, const char *path)
{
    FILE *f;
    int i;

    if (b == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < b->count; i++) {
        char *eu = rb_json_escape(b->items[i].url);
        char *et = rb_json_escape(b->items[i].title);
        int ok = fprintf(f, "{\"url\":\"%s\",\"title\":\"%s\"}\n", eu, et) >= 0;
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
