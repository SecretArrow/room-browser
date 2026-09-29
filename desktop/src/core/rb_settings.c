/*
 * rb_settings.c — simple key=value store for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_settings.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RB_SETTINGS_LINE 8192

static char *rb_set_strdup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_settings: out of memory\n");
        exit(1);
    }
    memcpy(p, s, n);
    return p;
}

typedef struct {
    char *key;
    char *value;
} rb_kv;

struct rb_settings {
    rb_kv *items; /* insertion order */
    int count;
    int cap;
};

static void rb_settings_grow(rb_settings *s)
{
    int ncap;
    rb_kv *grown;

    if (s->count < s->cap) {
        return;
    }
    ncap = (s->cap > 0) ? s->cap * 2 : 8;
    grown = (rb_kv *)realloc(s->items, (size_t)ncap * sizeof(rb_kv));
    if (grown == NULL) {
        fprintf(stderr, "rb_settings: out of memory\n");
        exit(1);
    }
    s->items = grown;
    s->cap = ncap;
}

static int rb_settings_index_of(const rb_settings *s, const char *key)
{
    int i;

    for (i = 0; i < s->count; i++) {
        if (strcmp(s->items[i].key, key) == 0) {
            return i;
        }
    }
    return -1;
}

/* Trims ASCII spaces/tabs from both ends in place. */
static void rb_trim_in_place(char *s)
{
    size_t len;
    size_t start;

    if (s == NULL) {
        return;
    }
    len = strlen(s);
    while (len > 0 && (s[len - 1] == ' ' || s[len - 1] == '\t')) {
        s[--len] = '\0';
    }
    start = 0;
    while (s[start] == ' ' || s[start] == '\t') {
        start++;
    }
    if (start > 0) {
        memmove(s, s + start, len - start + 1);
    }
}

/* The file is line-based, so a value containing a newline would be written
 * as two lines and come back truncated (only the first would still have its
 * key, the rest would be skipped as malformed).  Homepage shortcut lists and
 * any pasted multi-line text hit this.  Escape the backslash and the two
 * line terminators so every byte sequence survives the round trip.  Keys
 * need no escaping: they are constrained to contain neither '=' nor a
 * newline. */
static void rb_settings_write_escaped(FILE *f, const char *value)
{
    const char *p;

    for (p = (value != NULL) ? value : ""; *p != '\0'; p++) {
        switch (*p) {
        case '\\':
            fputs("\\\\", f);
            break;
        case '\n':
            fputs("\\n", f);
            break;
        case '\r':
            fputs("\\r", f);
            break;
        default:
            fputc((unsigned char)*p, f);
            break;
        }
    }
}

/* Reverses rb_settings_write_escaped, in place.  An unrecognised escape is
 * left alone, so a value that predates this escaping (a Windows path such as
 * "C:\new") keeps every byte it had. */
static void rb_unescape_in_place(char *s)
{
    char *out = s;
    const char *in = s;

    while (*in != '\0') {
        if (*in == '\\' && (in[1] == '\\' || in[1] == 'n' || in[1] == 'r')) {
            *out++ = (in[1] == '\\') ? '\\' : (in[1] == 'n') ? '\n' : '\r';
            in += 2;
            continue;
        }
        *out++ = *in++;
    }
    *out = '\0';
}

rb_settings *rb_settings_new(void)
{
    rb_settings *s = (rb_settings *)calloc(1, sizeof(*s));
    if (s == NULL) {
        fprintf(stderr, "rb_settings: out of memory\n");
        exit(1);
    }
    /* defaults (javascript NEVER defaults to 0 — project-wide policy) */
    rb_settings_set(s, "home", "https://duckduckgo.com");
    rb_settings_set(s, "search_engine", "duckduckgo");
    rb_settings_set(s, "javascript", "1");
    return s;
}

void rb_settings_free(rb_settings *s)
{
    int i;

    if (s == NULL) {
        return;
    }
    for (i = 0; i < s->count; i++) {
        free(s->items[i].key);
        free(s->items[i].value);
    }
    free(s->items);
    free(s);
}

const char *rb_settings_get(const rb_settings *s, const char *key,
                            const char *fallback)
{
    int i;

    if (s == NULL || key == NULL) {
        return fallback;
    }
    i = rb_settings_index_of(s, key);
    return (i >= 0) ? s->items[i].value : fallback;
}

int rb_settings_get_int(const rb_settings *s, const char *key, int fallback)
{
    const char *v = rb_settings_get(s, key, NULL);
    char *end = NULL;
    long long num;

    if (v == NULL) {
        return fallback;
    }
    num = strtoll(v, &end, 10);
    if (end == v) {
        return fallback; /* not a number */
    }
    if (num > 2147483647LL || num < -2147483648LL) {
        return fallback; /* out of int range */
    }
    return (int)num;
}

void rb_settings_set(rb_settings *s, const char *key, const char *value)
{
    int i;

    if (s == NULL || key == NULL || key[0] == '\0') {
        return;
    }
    i = rb_settings_index_of(s, key);
    if (i >= 0) {
        free(s->items[i].value);
        s->items[i].value = rb_set_strdup(value);
        return;
    }
    rb_settings_grow(s);
    s->items[s->count].key = rb_set_strdup(key);
    s->items[s->count].value = rb_set_strdup(value);
    s->count++;
}

void rb_settings_set_int(rb_settings *s, const char *key, int value)
{
    char buf[32];

    snprintf(buf, sizeof(buf), "%d", value);
    rb_settings_set(s, key, buf);
}

int rb_settings_count(const rb_settings *s)
{
    return (s != NULL) ? s->count : 0;
}

const char *rb_settings_key_at(const rb_settings *s, int index)
{
    if (s == NULL || index < 0 || index >= s->count) {
        return NULL;
    }
    return s->items[index].key;
}

const char *rb_settings_value_at(const rb_settings *s, int index)
{
    if (s == NULL || index < 0 || index >= s->count) {
        return NULL;
    }
    return s->items[index].value;
}

int rb_settings_load(rb_settings *s, const char *path)
{
    char buf[RB_SETTINGS_LINE];
    FILE *f;

    if (s == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine, defaults stay */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *eq;

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
        eq = strchr(buf, '=');
        if (eq == NULL || eq == buf) {
            continue; /* no '=' or empty key */
        }
        *eq = '\0';
        rb_trim_in_place(buf); /* trim the key side only */
        if (buf[0] == '\0') {
            continue;
        }
        rb_unescape_in_place(eq + 1);
        rb_settings_set(s, buf, eq + 1);
    }
    if (ferror(f)) {
        fclose(f);
        return -1;
    }
    fclose(f);
    return 0;
}

int rb_settings_save(const rb_settings *s, const char *path)
{
    FILE *f;
    int i;

    if (s == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < s->count; i++) {
        if (fprintf(f, "%s=", s->items[i].key) < 0) {
            fclose(f);
            return -1;
        }
        rb_settings_write_escaped(f, s->items[i].value);
        if (fputc('\n', f) == EOF) {
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
