/*
 * rb_url.c — omnibox URL heuristics for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_url.h"

#include "rb_search.h"
#include "rb_str.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Engine used by the legacy two-argument entry points (rb_url_decide,
 * rb_url_build_search) — the same default as the engine registry. */
#define RB_URL_DEFAULT_ENGINE "duckduckgo"

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
    /* Single source of truth: the engine registry (rb_search.c). The
     * DuckDuckGo template there is "https://duckduckgo.com/?q={query}",
     * which is exactly what this function has always produced. */
    return rb_search_url(RB_URL_DEFAULT_ENGINE, query);
}

char *rb_url_decide(const char *input)
{
    return rb_url_decide_engine(input, RB_URL_DEFAULT_ENGINE);
}

char *rb_url_decide_engine(const char *input, const char *engine_id)
{
    /* One decider for the whole browser: rb_url_classify is the port of
     * UrlIntelligence.classify, and the omnibox is the only thing that
     * decides anything.  Keeping a second set of heuristics here is how the
     * two would drift apart. */
    return rb_url_classify(input, engine_id, NULL, NULL);
}

/* ------------------------------------------------------------------ */
/* UrlIntelligence.classify */

/* Kotlin's \s, which is Java's [ \t\n\x0B\f\r]. */
static int rb_ws_char(char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\v' || c == '\f' ||
           c == '\r';
}

static int rb_has_ws(const char *s)
{
    for (; *s != '\0'; s++) {
        if (rb_ws_char(*s)) {
            return 1;
        }
    }
    return 0;
}

/* Case-insensitive "starts with". */
static int rb_ci_starts(const char *s, const char *prefix)
{
    size_t i;

    for (i = 0; prefix[i] != '\0'; i++) {
        if (s[i] == '\0' ||
            tolower((unsigned char)s[i]) != tolower((unsigned char)prefix[i])) {
            return 0;
        }
    }
    return 1;
}

static char *rb_url_trim_dup(const char *s)
{
    const char *a;
    const char *b;

    if (s == NULL) {
        return rb_url_dupn("", 0);
    }
    a = s;
    b = s + strlen(s);
    while (a < b && rb_ws_char(*a)) {
        a++;
    }
    while (b > a && rb_ws_char(b[-1])) {
        b--;
    }
    return rb_url_dupn(a, (size_t)(b - a));
}

/* Joins a prefix and a body into one malloc'd string. */
static char *rb_url_join(const char *a, const char *b, const char *c)
{
    rb_str out;

    rb_str_init(&out);
    rb_str_append(&out, a);
    rb_str_append(&out, b);
    rb_str_append(&out, c);
    return out.data;
}

/* ^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d+)?$ , octets <= 255.
 * Four dotted decimal groups, each 1-3 digits, with an optional port.  The
 * 3-digit cap is what keeps "1234.1.1.1" out (the regex cannot match it
 * either) and the octet check is UrlIntelligence's second pass. */
static int rb_looks_like_ipv4(const char *s)
{
    int part;

    for (part = 0; part < 4; part++) {
        int digits = 0;
        int value = 0;

        while (s[digits] >= '0' && s[digits] <= '9' && digits < 3) {
            value = value * 10 + (s[digits] - '0');
            digits++;
        }
        if (digits == 0 || value > 255) {
            return 0;
        }
        s += digits;
        if (part < 3) {
            if (*s != '.') {
                return 0;
            }
            s++;
        }
    }
    if (*s == ':') {
        s++;
        if (!isdigit((unsigned char)*s)) {
            return 0;
        }
        while (isdigit((unsigned char)*s)) {
            s++;
        }
    }
    return *s == '\0';
}

/* ^[0-9a-fA-F:]+:[0-9a-fA-F:.]+$ , for callers that already found a ':'.
 *
 * The pattern is matched by hand rather than with a regular expression: the
 * first class greedily takes the hex/colon run and the second class must then
 * reach the end of the string, which is a short backtrack to try. */
static int rb_looks_like_ipv6(const char *s)
{
    size_t run = 0;
    size_t j;

    while (isxdigit((unsigned char)s[run]) || s[run] == ':') {
        run++;
    }
    /* The split point is a ':' somewhere inside that run; everything left of
     * it is the first class, everything right of it the second. */
    for (j = run; j > 0; j--) {
        size_t k;

        if (s[j - 1] != ':') {
            continue;
        }
        for (k = j; s[k] != '\0'; k++) {
            if (!isxdigit((unsigned char)s[k]) && s[k] != ':' && s[k] != '.') {
                break;
            }
        }
        if (k > j && s[k] == '\0') {
            return 1; /* first class, ':', then a non-empty tail to the end */
        }
    }
    return 0;
}

/* ^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(/.*)?$ , case-insensitive. */
static int rb_looks_like_localhost(const char *s)
{
    static const char *const NAMES[] = { "localhost", "127.0.0.1", "[::1]" };
    size_t i;

    for (i = 0; i < sizeof(NAMES) / sizeof(NAMES[0]); i++) {
        const char *p;
        size_t n = strlen(NAMES[i]);

        if (!rb_ci_eq(s, n, NAMES[i])) {
            continue;
        }
        p = s + n;
        if (*p == ':') {
            p++;
            if (!isdigit((unsigned char)*p)) {
                return 0;
            }
            while (isdigit((unsigned char)*p)) {
                p++;
            }
        }
        if (*p == '/') {
            return 1; /* the path is unconstrained, including empty */
        }
        return *p == '\0';
    }
    return 0;
}

/* ^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(:\d+)?(/.*)?$ — a dotted name with at
 * least one dot, so "example" alone is not one. */
static int rb_looks_like_domain(const char *s)
{
    int dots = 0;

    for (;;) {
        size_t n = 0;

        while (isalnum((unsigned char)*s) || *s == '-') {
            s++;
            n++;
        }
        if (n == 0) {
            return 0;
        }
        if (*s != '.') {
            break;
        }
        s++;
        dots++;
    }
    if (dots == 0) {
        return 0; /* no dot: a single word is search text */
    }
    if (*s == ':') {
        s++;
        if (!isdigit((unsigned char)*s)) {
            return 0;
        }
        while (isdigit((unsigned char)*s)) {
            s++;
        }
    }
    if (*s == '/') {
        return 1;
    }
    return *s == '\0';
}

char *rb_url_classify(const char *input, const char *engine_id, int *out_kind,
                      int *out_upgraded)
{
    char *raw;
    char *url;

    if (out_kind != NULL) {
        *out_kind = RB_URL_INPUT_SEARCH;
    }
    if (out_upgraded != NULL) {
        *out_upgraded = 0;
    }
    raw = rb_url_trim_dup(input);
    if (raw[0] == '\0') {
        return raw; /* blank input stays blank; the caller decides */
    }

    /* file:// is loaded as-is: the desktop editions enable file access per
     * the profile setting, exactly like the Android WebView does. */
    if (rb_ci_starts(raw, "file://")) {
        if (out_kind != NULL) {
            *out_kind = RB_URL_INPUT_WEB;
        }
        return raw;
    }

    /* Anything with whitespace in it is a query, whatever else it looks
     * like: no URL contains a space, and "how to boil water" must not be
     * treated as a host. */
    if (rb_has_ws(raw)) {
        url = rb_search_url(engine_id, raw);
        free(raw);
        return url;
    }

    /* An address literal or localhost goes to http, never https: they are
     * spelled out precisely because the endpoint is a local or non-standard
     * one, and a TLS attempt against it fails — often slowly. */
    if (rb_looks_like_ipv4(raw)) {
        if (out_kind != NULL) {
            *out_kind = RB_URL_INPUT_WEB;
        }
        url = rb_url_join("http://", raw, "");
        free(raw);
        return url;
    }
    if (strchr(raw, ':') != NULL && rb_looks_like_ipv6(raw)) {
        if (out_kind != NULL) {
            *out_kind = RB_URL_INPUT_WEB;
        }
        url = rb_url_join("http://[", raw, "]");
        free(raw);
        return url;
    }
    if (rb_looks_like_localhost(raw)) {
        if (out_kind != NULL) {
            *out_kind = RB_URL_INPUT_WEB;
        }
        url = rb_url_join("http://", raw, "");
        free(raw);
        return url;
    }

    if (rb_scheme_prefix_len(raw) > 0) {
        if (rb_ci_starts(raw, "http://") || rb_ci_starts(raw, "https://")) {
            /* An explicitly typed scheme is EXPLICIT USER INTENT: an http://
             * URL the user typed is loaded as http, because http-only sites
             * must stay reachable.  Automatic upgrades happen only for link
             * navigations inside pages, and those carry a fallback. */
            if (out_kind != NULL) {
                *out_kind = RB_URL_INPUT_WEB;
            }
            return raw;
        }
        /* about:, data:, blob:, javascript:, ... are searched for rather
         * than loaded: typing one should not be a way to run it. */
        url = rb_search_url(engine_id, raw);
        free(raw);
        return url;
    }

    if (rb_looks_like_domain(raw)) { /* already implies a dot */
        char *https = rb_url_join("https://", raw, "");
        char *final_url = rb_url_upgrade_to_https(https, NULL);

        free(https);
        free(raw);
        if (out_kind != NULL) {
            *out_kind = RB_URL_INPUT_WEB;
        }
        if (out_upgraded != NULL) {
            /* "The browser chose https for a bare domain."  Android's
             * Input.Web.upgradedToHttps is false on this path — upgrade()
             * is handed a URL that is already https, so it never fires, and
             * nothing in the Android app reads the flag anyway.  Reporting
             * the useful answer here costs nothing and gives the desktop
             * HTTPS-first wiring a real signal; the URL is the same either
             * way. */
            *out_upgraded = rb_url_is_https(final_url) ? 1 : 0;
        }
        return final_url;
    }

    url = rb_search_url(engine_id, raw);
    free(raw);
    return url;
}

/* ------------------------------------------------------------------ */
/* URL inspection */

int rb_url_is_http(const char *url)
{
    return url != NULL && rb_ci_eq(url, 7, "http://");
}

int rb_url_is_https(const char *url)
{
    return url != NULL && rb_ci_eq(url, 8, "https://");
}

/* Offset of the authority inside a "scheme://..." URL, or 0 when there is
 * no "://" at all. */
static size_t rb_url_authority_offset(const char *url)
{
    const char *sep = (url != NULL) ? strstr(url, "://") : NULL;
    return (sep != NULL) ? (size_t)(sep - url) + 3 : 0;
}

char *rb_url_host_of(const char *url)
{
    const char *p;
    const char *end;
    size_t off;
    size_t len;
    char *host;

    if (url == NULL) {
        return NULL;
    }
    off = rb_url_authority_offset(url);
    if (off == 0) {
        return NULL; /* no authority: about:blank, mailto:, ... */
    }
    p = url + off;
    end = p + rb_auth_len(p);
    if (p == end) {
        return NULL;
    }

    /* Drop any userinfo ("user:pass@host"). */
    {
        const char *at = NULL;
        const char *q;
        for (q = p; q < end; q++) {
            if (*q == '@') {
                at = q;
            }
        }
        if (at != NULL) {
            p = at + 1;
        }
    }

    /* Bracketed IPv6 literal: keep the address, drop the brackets. */
    if (p < end && *p == '[') {
        const char *close = NULL;
        const char *q;
        for (q = p + 1; q < end; q++) {
            if (*q == ']') {
                close = q;
                break;
            }
        }
        if (close != NULL) {
            p++;
            end = close;
        }
    } else {
        const char *colon = NULL;
        const char *q;
        for (q = p; q < end; q++) {
            if (*q == ':') {
                colon = q;
                break; /* first ':' starts the port */
            }
        }
        if (colon != NULL) {
            end = colon;
        }
    }
    if (p >= end) {
        return NULL;
    }
    len = (size_t)(end - p);
    host = rb_url_dupn(p, len);
    {
        size_t i;
        for (i = 0; i < len; i++) {
            host[i] = (char)tolower((unsigned char)host[i]);
        }
    }
    return host;
}

char *rb_url_path_of(const char *url)
{
    const char *p;
    size_t off;

    if (url == NULL) {
        return NULL;
    }
    off = rb_url_authority_offset(url);
    if (off == 0) {
        return rb_url_dupn("/", 1);
    }
    p = url + off + rb_auth_len(url + off);
    if (*p == '\0') {
        return rb_url_dupn("/", 1);
    }
    if (*p == '?' || *p == '#') {
        /* No path, but a query/fragment is present. */
        return rb_url_dupn("/", 1);
    }
    /* Trim any fragment so filtering keyword rules see the path only. */
    {
        const char *hash = strchr(p, '#');
        size_t len = (hash != NULL) ? (size_t)(hash - p) : strlen(p);
        return rb_url_dupn(p, len);
    }
}

char *rb_url_display(const char *url)
{
    const char *p;
    rb_str out;
    size_t len;

    if (url == NULL) {
        return rb_url_dupn("", 0);
    }
    p = url;
    if (rb_url_is_https(p)) {
        p += 8;
    } else if (rb_url_is_http(p)) {
        p += 7;
    }
    rb_str_init(&out);
    rb_str_append(&out, p);
    len = out.len;
    if (len > 1 && out.data[len - 1] == '/') {
        out.data[len - 1] = '\0';
        out.len = len - 1;
    }
    return out.data;
}

char *rb_url_upgrade_to_https(const char *url, int *out_upgraded)
{
    const char *rest;
    const char *authority_end;
    const char *colon = NULL;
    size_t tlen;
    size_t authority_len;
    size_t port_len;
    rb_str out;

    if (out_upgraded != NULL) {
        *out_upgraded = 0;
    }
    if (url == NULL) {
        return rb_url_dupn("", 0);
    }
    /* Trim surrounding whitespace into (url, tlen). */
    {
        const char *a = url;
        const char *b = url + strlen(url);
        while (a < b && (*a == ' ' || *a == '\t')) {
            a++;
        }
        while (b > a && (b[-1] == ' ' || b[-1] == '\t')) {
            b--;
        }
        url = a;
        tlen = (size_t)(b - a);
    }
    if (!rb_url_is_http(url)) {
        return rb_url_dupn(url, tlen); /* nothing to upgrade */
    }

    rest = url + 7;
    {   /* authority runs to the first '/', '?' or '#' (rb_auth_len does this) */
        size_t n = rb_auth_len(rest);
        authority_end = rest + n;
        authority_len = n;
    }

    /* An explicit port other than 80 means the endpoint is deliberately
     * non-standard — leave it on http (see the header comment). */
    {
        const char *q = rest;
        if (authority_len > 0 && *q == '[') {
            /* Bracketed IPv6 literal: its interior ':' are not a port. */
            const char *close = memchr(q, ']', authority_len);
            if (close != NULL) {
                q = close + 1;
            }
        }
        for (; q < authority_end; q++) {
            if (*q == ':') {
                colon = q;
            }
        }
    }
    if (colon != NULL) {
        port_len = (size_t)(authority_end - (colon + 1));
        if (!(port_len == 2 && colon[1] == '8' && colon[2] == '0')) {
            return rb_url_dupn(url, tlen); /* keep http, *out_upgraded stays 0 */
        }
    }

    /* Upgrade: "https://" + host (with a trailing ":80" dropped) + rest. */
    rb_str_init(&out);
    rb_str_append(&out, "https://");
    if (colon != NULL) {
        rb_str_appendf(&out, "%.*s", (int)(colon - rest), rest);
    } else {
        rb_str_appendf(&out, "%.*s", (int)authority_len, rest);
    }
    rb_str_append(&out, authority_end);

    if (out_upgraded != NULL) {
        *out_upgraded = 1;
    }
    return out.data;
}

/* ------------------------------------------------------------------ */
/* Percent-encoding, and the Google Translate wrapper.
 *
 * rb_search_encode() is the same encoding with one extra rule — it trims the
 * query's leading and trailing whitespace, which is what a search box wants
 * and exactly what a value embedded in another URL's query string must not
 * get.  The unreserved set and the %XX spelling are shared; the trimming is
 * why this is a separate function rather than a call to that one. */
char *rb_url_encode_component(const char *s)
{
    rb_str out;
    const char *p;

    rb_str_init(&out);
    if (s != NULL) {
        for (p = s; *p != '\0'; p++) {
            unsigned char c = (unsigned char)*p;
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
    }
    /* The contract is a string, never NULL: "" encodes to "", and a caller
     * concatenating the result must not have to guard it.  rb_str leaves the
     * buffer unallocated when nothing was appended. */
    if (out.data == NULL) {
        return rb_url_dupn("", 0);
    }
    return out.data;
}

char *rb_url_translate_wrapper(const char *url, const char *target)
{
    rb_str out;
    char *encoded;

    /* Android's TranslateDialog refuses to build the wrapper for its own
     * about: page; the same guard, spelled for every scheme the browser owns
     * rather than for the one page Android happens to have. */
    if (url == NULL || url[0] == '\0' || rb_ci_eq(url, 6, "about:")) {
        return NULL;
    }
    encoded = rb_url_encode_component(url);
    rb_str_init(&out);
    rb_str_append(&out, "https://translate.google.com/translate?sl=auto&tl=");
    rb_str_append(&out, (target != NULL) ? target : "");
    rb_str_append(&out, "&u=");
    rb_str_append(&out, encoded);
    free(encoded);
    return out.data;
}
