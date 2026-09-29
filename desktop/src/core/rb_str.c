/*
 * rb_str.c — minimal growable UTF-8 string buffer for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_str.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void rb_str_die(void)
{
    fprintf(stderr, "rb_str: out of memory\n");
    exit(1);
}

/* Make room for `extra` more content bytes (plus the NUL terminator). */
static void rb_str_reserve(rb_str *s, size_t extra)
{
    size_t need;
    size_t ncap;
    char *grown;

    if (extra == 0) {
        return;
    }
    if (extra > (size_t)-1 - (s->len + 1)) {
        rb_str_die(); /* size_t overflow */
    }
    need = s->len + extra + 1;
    if (s->cap >= need) {
        return;
    }
    ncap = (s->cap > 0) ? s->cap : 16;
    while (ncap < need) {
        if (ncap > (size_t)-1 / 2) {
            ncap = need; /* doubling would overflow; clamp */
            break;
        }
        ncap *= 2;
    }
    grown = (char *)realloc(s->data, ncap + 1);
    if (grown == NULL) {
        rb_str_die();
    }
    s->data = grown;
    s->cap = ncap;
    s->data[s->len] = '\0';
}

void rb_str_init(rb_str *s)
{
    if (s == NULL) {
        return;
    }
    s->data = NULL;
    s->len = 0;
    s->cap = 0;
}

void rb_str_free(rb_str *s)
{
    if (s == NULL) {
        return;
    }
    free(s->data);
    s->data = NULL;
    s->len = 0;
    s->cap = 0;
}

void rb_str_clear(rb_str *s)
{
    if (s == NULL) {
        return;
    }
    s->len = 0;
    if (s->data != NULL) {
        s->data[0] = '\0';
    }
}

void rb_str_append(rb_str *s, const char *rhs)
{
    size_t n;

    if (s == NULL || rhs == NULL) {
        return;
    }
    n = strlen(rhs);
    if (n == 0) {
        return;
    }
    rb_str_reserve(s, n);
    memcpy(s->data + s->len, rhs, n);
    s->len += n;
    s->data[s->len] = '\0';
}

void rb_str_appendf(rb_str *s, const char *fmt, ...)
{
    va_list ap;
    int need;

    if (s == NULL || fmt == NULL) {
        return;
    }
    va_start(ap, fmt);
    need = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (need <= 0) {
        return; /* empty output or format error */
    }
    rb_str_reserve(s, (size_t)need);
    va_start(ap, fmt);
    (void)vsnprintf(s->data + s->len, (size_t)need + 1, fmt, ap);
    va_end(ap);
    s->len += (size_t)need;
}

const char *rb_str_c(const rb_str *s)
{
    return (s != NULL && s->data != NULL) ? s->data : "";
}
