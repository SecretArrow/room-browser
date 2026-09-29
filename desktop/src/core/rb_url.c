/*
 * rb_url.c — omnibox URL heuristics for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_url.h"

#include "rb_str.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *const RB_URL_SCHEMES[] = {
    "about",     "blob",  "brave",      "chrome", "data", "file",
    "ftp",       "http",  "https",      "javascript", "mailto",
    "tel",  "view-source", "ws",    "wss"
};

static void rb_url_die(void)
{
    fprintf(stderr, "rb_url: out of memory\n");
    exit(1);
}

/* malloc n bytes + NUL, copying from s (may be NULL/empty). */
static char *rb_url_dupn(const char *s, size_t n)
{
    char *p = (char *)malloc(n + 1);
    if (p == NULL) {
        rb_url_die();
    }
    if (n > 0) {
        memcpy(p, s, n);
    }
    p[n] = '\0';
    return p;
}

static int rb_ci_eq(const char *a, size_t alen, const char *b)
{
    size_t i;
    if (strlen(b) != alen) {
        return 0;
    }
    for (i = 0; i < alen; i++) {
        if (tolower((unsigned char)a[i]) != tolower((unsigned char)b[i])) {
            return 0;
        }
    }
    return 1;
}

/* Length of a leading "scheme:" prefix (including the colon), 0 if none. */
static size_t rb_scheme_prefix_len(const char *s)
{
    size_t i;
    if (s == NULL || !isalpha((unsigned char)s[0])) {
        return 0;
    }
    for (i = 1; s[i] != '\0'; i++) {
        if (s[i] == ':') {
            return i + 1;
        }
        if (!isalnum((unsigned char)s[i]) && s[i] != '+' && s[i] != '-' &&
            s[i] != '.') {
            return 0;
        }
    }
    return 0;
}

static int rb_known_scheme(const char *s, size_t len)
{
    size_t i;
    size_t n = sizeof(RB_URL_SCHEMES) / sizeof(RB_URL_SCHEMES[0]);
    for (i = 0; i < n; i++) {
        if (rb_ci_eq(s, len, RB_URL_SCHEMES[i])) {
            return 1;
        }
    }
    return 0;
}

/* Any "<scheme>://" URL keeps its scheme; well-known opaque schemes
 * ("about:", "mailto:", ...) count as schemed too. */
static int rb_has_scheme(const char *s)
{
    size_t n = rb_scheme_prefix_len(s);
    if (n == 0) {
        return 0;
    }
    if (s[n] == '/' && s[n + 1] == '/') {
        return 1;
    }
    return rb_known_scheme(s, n - 1);
}

/* Length of the authority component in s (up to '/', '?', '#' or the end). */
static size_t rb_auth_len(const char *s)
{
    size_t i = 0;
    while (s[i] != '\0' && s[i] != '/' && s[i] != '?' && s[i] != '#') {
        i++;
    }
    return i;
}

/* Host-like check on the authority slice [s, s+n). */
static int rb_is_host_like(const char *s, size_t n)
{
    size_t host_end = n;
    size_t last_dot = 0;
    int have_dot = 0;
    size_t i;

    if (n == 0) {
        return 0;
    }

    /* Host part: up to ']' for bracketed IPv6, else up to ':' (port). */
    if (s[0] == '[') {
        for (i = 1; i < n; i++) {
            if (s[i] == ']') {
                host_end = i;
                break;
            }
        }
    } else {
        for (i = 0; i < n; i++) {
            if (s[i] == ':') {
                host_end = i;
                break;
            }
        }
    }

    /* "example.com" style: dot inside the host with an alphabetic tail. */
    for (i = 0; i < host_end; i++) {
        if (s[i] == '.') {
            last_dot = i;
            have_dot = 1;
        }
    }
    if (have_dot && last_dot > 0 && last_dot + 1 < host_end) {
        size_t tlen = host_end - (last_dot + 1);
        int alpha = 1;
        for (i = 0; i < tlen; i++) {
            if (!isalpha((unsigned char)s[last_dot + 1 + i])) {
                alpha = 0;
            }
        }
        if (tlen >= 2 && alpha) {
            return 1;
        }
    }

    /* Numeric/IPv6-ish literal: only digits, dots, colons, hex letters and
     * brackets; needs enough dots/colons to look like an address (this keeps
     * "1.5" and "12:30" as search text). */
    {
        int only_addr = 1;
        size_t dots = 0;
        size_t colons = 0;
        for (i = 0; i < n; i++) {
            char c = s[i];
            if (c == '.') {
                dots++;
            } else if (c == ':') {
                colons++;
            } else if (c == '[' || c == ']') {
                /* bracket */
            } else if (!isxdigit((unsigned char)c)) {
                only_addr = 0;
                break;
            }
        }
        if (only_addr &&
            (dots >= 2 || colons >= 2 || (dots >= 1 && colons >= 1))) {
            return 1;
        }
    }
    return 0;
}

int rb_url_is_probably_url(const char *input)
{
    char buf[4096];
    const char *a;
    const char *b;
    const char *p;
    size_t n;

    if (input == NULL) {
        return 0;
    }
    a = input;
    b = input + strlen(input);
    while (a < b && (*a == ' ' || *a == '\t' || *a == '\r' || *a == '\n')) {
        a++;
    }
    while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                     b[-1] == '\n')) {
        b--;
    }
    n = (size_t)(b - a);
    if (n == 0 || n >= sizeof(buf)) {
        return 0;
    }
    memcpy(buf, a, n);
    buf[n] = '\0';

    for (p = buf; *p != '\0'; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            return 0; /* interior whitespace -> search text */
        }
        if (c >= 0x80u) {
            return 0; /* non-ASCII input -> search text */
        }
    }
    if (strstr(buf, "://") != NULL) {
        return 1;
    }
    if (rb_has_scheme(buf)) {
        return 1;
    }
    if (strncmp(buf, "localhost", 9) == 0) {
        char next = buf[9];
        if (next == '\0' || next == ':' || next == '/') {
            return 1;
        }
    }
    return rb_is_host_like(buf, rb_auth_len(buf));
}

char *rb_url_normalize(const char *input)
{
    const char *a;
    const char *b;
    size_t n;
    char *t;
    rb_str out;

    /* trim surrounding whitespace into a working copy */
    if (input == NULL) {
        input = "";
    }
    a = input;
    b = input + strlen(input);
    while (a < b && (*a == ' ' || *a == '\t' || *a == '\r' || *a == '\n')) {
        a++;
    }
    while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                     b[-1] == '\n')) {
        b--;
    }
    n = (size_t)(b - a);
    t = rb_url_dupn(a, n);
    if (t[0] == '\0') {
        return t; /* empty input -> "" */
    }

    rb_str_init(&out);
    if (!rb_has_scheme(t)) {
        rb_str_append(&out, "https://");
    }
    rb_str_append(&out, t);
    free(t);

    {
        const char *c = rb_str_c(&out);
        const char *sep = strstr(c, "://");
        if (sep != NULL) {
            const char *p = sep + 3;
            while (*p != '\0' && *p != '/' && *p != '?' && *p != '#') {
                p++;
            }
            if (*p == '\0') {
                rb_str_append(&out, "/"); /* bare authority -> add the slash */
                return out.data;
            }
            if (*p == '?' || *p == '#') {
                /* insert "/" between the authority and the query/fragment */
                rb_str fixed;
                size_t pos = (size_t)(p - c);
                char saved = out.data[pos];
                out.data[pos] = '\0';
                rb_str_init(&fixed);
                rb_str_append(&fixed, out.data);
                rb_str_append(&fixed, "/");
                out.data[pos] = saved;
                rb_str_append(&fixed, out.data + pos);
                rb_str_free(&out);
                return fixed.data;
            }
        }
    }
    return out.data;
}

char *rb_url_build_search(const char *query)
{
    const char *a;
    const char *b;
    const char *q;
    size_t i;
    rb_str out;

    if (query == NULL) {
        query = "";
    }
    a = query;
    b = query + strlen(query);
    while (a < b && (*a == ' ' || *a == '\t' || *a == '\r' || *a == '\n')) {
        a++;
    }
    while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                     b[-1] == '\n')) {
        b--;
    }
    q = a;

    rb_str_init(&out);
    rb_str_append(&out, "https://duckduckgo.com/?q=");
    for (i = 0; q[i] != '\0' && &q[i] < b; i++) {
        unsigned char c = (unsigned char)q[i];
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' ||
            c == '~') {
            char one[2];
            one[0] = (char)c;
            one[1] = '\0';
            rb_str_append(&out, one);
        } else {
            rb_str_appendf(&out, "%%%02X", (unsigned)c);
        }
    }
    return out.data;
}

char *rb_url_decide(const char *input)
{
    if (rb_url_is_probably_url(input)) {
        return rb_url_normalize(input);
    }
    return rb_url_build_search(input);
}
