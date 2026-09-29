/*
 * rb_https.c — HTTPS-First fallback policy for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_https.h"

#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    char *upgraded;
    char *original;
} rb_https_entry;

struct rb_https_pending {
    rb_https_entry *items;
    int count;
    int cap;
};

int rb_https_is_recoverable(int error_code)
{
    switch (error_code) {
    case RB_HTTPS_ERR_UNKNOWN:
    case RB_HTTPS_ERR_CONNECT:
    case RB_HTTPS_ERR_TIMEOUT:
    case RB_HTTPS_ERR_SSL_HANDSHAKE:
        return 1;
    default:
        return 0;
    }
}

rb_https_pending *rb_https_pending_new(void)
{
    rb_https_pending *p = (rb_https_pending *)calloc(1, sizeof(*p));
    if (p == NULL) {
        fprintf(stderr, "rb_https: out of memory\n");
        exit(1);
    }
    return p;
}

void rb_https_pending_free(rb_https_pending *p)
{
    int i;

    if (p == NULL) {
        return;
    }
    for (i = 0; i < p->count; i++) {
        free(p->items[i].upgraded);
        free(p->items[i].original);
    }
    free(p->items);
    free(p);
}

/* Trimmed copy, or NULL when the input is NULL/blank. */
static char *rb_https_trim_dup(const char *s)
{
    size_t len;
    size_t start = 0;

    if (s == NULL) {
        return NULL;
    }
    len = strlen(s);
    while (len > 0 && (s[len - 1] == ' ' || s[len - 1] == '\t' ||
                       s[len - 1] == '\r' || s[len - 1] == '\n')) {
        len--;
    }
    while (start < len && (s[start] == ' ' || s[start] == '\t' ||
                           s[start] == '\r' || s[start] == '\n')) {
        start++;
    }
    if (start == len) {
        return NULL; /* blank */
    }
    {
        char *out = (char *)malloc(len - start + 1);
        if (out == NULL) {
            fprintf(stderr, "rb_https: out of memory\n");
            exit(1);
        }
        memcpy(out, s + start, len - start);
        out[len - start] = '\0';
        return out;
    }
}

static int rb_https_index_of(const rb_https_pending *p, const char *upgraded)
{
    int i;

    for (i = 0; i < p->count; i++) {
        if (strcmp(p->items[i].upgraded, upgraded) == 0) {
            return i;
        }
    }
    return -1;
}

void rb_https_register(rb_https_pending *p, const char *upgraded_url,
                       const char *original_url)
{
    char *up;
    char *orig;
    int i;

    if (p == NULL) {
        return;
    }
    up = rb_https_trim_dup(upgraded_url);
    orig = rb_https_trim_dup(original_url);
    if (up == NULL || orig == NULL) {
        free(up);
        free(orig);
        return;
    }

    i = rb_https_index_of(p, up);
    if (i >= 0) {
        /* Re-registering the same upgraded URL (a reload) replaces the
         * original rather than growing the table without bound. */
        free(p->items[i].original);
        p->items[i].original = orig;
        free(up);
        return;
    }

    if (p->count == p->cap) {
        int ncap = (p->cap > 0) ? p->cap * 2 : 8;
        rb_https_entry *grown = (rb_https_entry *)realloc(
            p->items, (size_t)ncap * sizeof(rb_https_entry));
        if (grown == NULL) {
            fprintf(stderr, "rb_https: out of memory\n");
            exit(1);
        }
        p->items = grown;
        p->cap = ncap;
    }
    p->items[p->count].upgraded = up;
    p->items[p->count].original = orig;
    p->count++;
}

char *rb_https_consume(rb_https_pending *p, const char *url)
{
    char *key;
    int i;
    char *out;

    if (p == NULL) {
        return NULL;
    }
    key = rb_https_trim_dup(url);
    if (key == NULL) {
        return NULL; /* a NULL/blank URL was never one of ours */
    }
    i = rb_https_index_of(p, key);
    free(key);
    if (i < 0) {
        return NULL;
    }
    out = p->items[i].original;
    free(p->items[i].upgraded);
    if (i + 1 < p->count) {
        memmove(&p->items[i], &p->items[i + 1],
                (size_t)(p->count - i - 1) * sizeof(rb_https_entry));
    }
    p->count--;
    return out;
}

void rb_https_clear(rb_https_pending *p)
{
    int i;

    if (p == NULL) {
        return;
    }
    for (i = 0; i < p->count; i++) {
        free(p->items[i].upgraded);
        free(p->items[i].original);
    }
    p->count = 0;
}

int rb_https_pending_count(const rb_https_pending *p)
{
    return (p != NULL) ? p->count : 0;
}

char *rb_https_retry_url(rb_https_pending *p, const char *url, int error_code)
{
    if (!rb_https_is_recoverable(error_code)) {
        /* The secure page loaded but is broken in a way http would share
         * (out of memory, too many redirects, ...): drop the entry so it is
         * not consumed by a later, unrelated failure of the same URL. */
        char *dropped = rb_https_consume(p, url);
        free(dropped);
        return NULL;
    }
    return rb_https_consume(p, url);
}
