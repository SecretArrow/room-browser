/*
 * rb_filters.c — privacy filtering engine for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The keyword rules and the suffix-matching rule are a direct port of the
 * Android edition's FilterEngine.DEFAULT_KEYWORD_RULES / matchesSuffix, so
 * the two editions reach the same decision for the same request.
 */

#include "rb_filters.h"

#include "rb_filterlist.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* Keyword rules (port of FilterEngine.DEFAULT_KEYWORD_RULES) */

typedef struct {
    const char        *pattern;
    rb_filter_category category;
} rb_keyword_rule;

static const rb_keyword_rule RB_KEYWORD_RULES[] = {
    { "/ads/",                 RB_FILTER_AD },
    { "/adserver",             RB_FILTER_AD },
    { "/advert",               RB_FILTER_AD },
    { "doubleclick.net",       RB_FILTER_AD },
    { "googlesyndication",     RB_FILTER_AD },
    { "/analytics.js",         RB_FILTER_TRACKER },
    { "/gtag/js",              RB_FILTER_TRACKER },
    { "google-analytics.com",  RB_FILTER_TRACKER },
    { "connect.facebook.net",  RB_FILTER_TRACKER },
    { "scorecardresearch",     RB_FILTER_TRACKER },
    { "/telemetry",            RB_FILTER_TRACKER },
    { "/beacon.gif",           RB_FILTER_TRACKER },
    { "hotjar.com",            RB_FILTER_TRACKER },
    { "mixpanel.com",          RB_FILTER_TRACKER },
    { "segment.io",            RB_FILTER_TRACKER },
    { "amplitude.com",         RB_FILTER_TRACKER },
    { "fullstory.com",         RB_FILTER_TRACKER }
};

#define RB_KEYWORD_N \
    ((int)(sizeof(RB_KEYWORD_RULES) / sizeof(RB_KEYWORD_RULES[0])))

/* ------------------------------------------------------------------ */
/* Host sets */

typedef struct {
    char **items;
    int    count;
    int    cap;
} rb_hostset;

static void rb_hostset_free(rb_hostset *s)
{
    int i;

    for (i = 0; i < s->count; i++) {
        free(s->items[i]);
    }
    free(s->items);
    s->items = NULL;
    s->count = 0;
    s->cap = 0;
}

static void rb_hostset_add(rb_hostset *s, const char *host, size_t len)
{
    char *copy;

    if (len == 0) {
        return;
    }
    if (s->count == s->cap) {
        int ncap = (s->cap > 0) ? s->cap * 2 : 64;
        char **grown = (char **)realloc(s->items, (size_t)ncap * sizeof(char *));
        if (grown == NULL) {
            fprintf(stderr, "rb_filters: out of memory\n");
            exit(1);
        }
        s->items = grown;
        s->cap = ncap;
    }
    copy = (char *)malloc(len + 1);
    if (copy == NULL) {
        fprintf(stderr, "rb_filters: out of memory\n");
        exit(1);
    }
    memcpy(copy, host, len);
    copy[len] = '\0';
    s->items[s->count++] = copy;
}

/* Case-insensitively compares the host slice [h, h+len) with a NUL-terminated
 * set entry. */
static int rb_host_eq(const char *h, size_t len, const char *entry)
{
    size_t i;

    if (strlen(entry) != len) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        if (tolower((unsigned char)h[i]) != (unsigned char)entry[i]) {
            return 0;
        }
    }
    return 1;
}

/* Port of FilterEngine.matchesSuffix: matches the host itself and every
 * parent domain ("cdn.doubleclick.net" -> "doubleclick.net" -> "net"). */
static int rb_hostset_matches(const rb_hostset *s, const char *host, size_t len)
{
    const char *p = host;
    const char *end = host + len;

    if (len == 0) {
        return 0;
    }
    for (;;) {
        int i;
        size_t seg = (size_t)(end - p);

        for (i = 0; i < s->count; i++) {
            if (rb_host_eq(p, seg, s->items[i])) {
                return 1;
            }
        }
        {
            const char *dot = memchr(p, '.', seg);
            if (dot == NULL) {
                return 0;
            }
            p = dot + 1;
        }
    }
}

/* ------------------------------------------------------------------ */
/* Engine */

struct rb_filters {
    rb_hostset ads;
    rb_hostset trackers;
    rb_hostset malicious;
    int        stats[6]; /* indexed by rb_filter_category */
};

rb_filter_options rb_filter_options_default(void)
{
    rb_filter_options o;
    o.block_ads        = 0;
    o.block_trackers   = 0;
    o.block_cross_site = 0;
    o.block_malicious  = 1; /* security-grade: stays on */
    o.block_popups     = 0;
    return o;
}

rb_filters *rb_filters_new(void)
{
    rb_filters *f = (rb_filters *)calloc(1, sizeof(*f));
    if (f == NULL) {
        fprintf(stderr, "rb_filters: out of memory\n");
        exit(1);
    }
    return f;
}

void rb_filters_free(rb_filters *f)
{
    if (f == NULL) {
        return;
    }
    rb_hostset_free(&f->ads);
    rb_hostset_free(&f->trackers);
    rb_hostset_free(&f->malicious);
    free(f);
}

/* Lowercases and trims in place at both ends; also drops trailing dots, the
 * same normalisation the Android engine applies. */
static size_t rb_norm_host(char *buf, size_t len)
{
    size_t start = 0;
    size_t i;

    while (len > 0 && (buf[len - 1] == '.' || buf[len - 1] == ' ' ||
                       buf[len - 1] == '\t')) {
        len--;
    }
    while (start < len && (buf[start] == ' ' || buf[start] == '\t')) {
        start++;
    }
    if (start > 0) {
        memmove(buf, buf + start, len - start);
        len -= start;
    }
    for (i = 0; i < len; i++) {
        buf[i] = (char)tolower((unsigned char)buf[i]);
    }
    return len;
}

/* Parses one "<category>|<host>" line. Comment, blank and unknown-category
 * lines are ignored. Returns 1 when a host was added. */
static int rb_filters_add_line(rb_filters *f, const char *p, size_t len)
{
    char cat[16];
    char hostbuf[512];
    const char *host;
    size_t cat_end = 0;
    size_t host_len;
    size_t k;

    while (cat_end < len && p[cat_end] != '|') {
        if (p[cat_end] == '#') {
            return 0; /* comment line */
        }
        cat_end++;
    }
    if (cat_end == 0 || cat_end >= len || cat_end >= sizeof(cat)) {
        return 0;
    }
    for (k = 0; k < cat_end; k++) {
        cat[k] = (char)tolower((unsigned char)p[k]);
    }
    cat[cat_end] = '\0';

    host = p + cat_end + 1;
    host_len = len - cat_end - 1;
    if (host_len == 0 || host_len >= sizeof(hostbuf)) {
        return 0;
    }
    memcpy(hostbuf, host, host_len);
    host_len = rb_norm_host(hostbuf, host_len);
    if (host_len == 0) {
        return 0;
    }

    if (strcmp(cat, "ad") == 0) {
        rb_hostset_add(&f->ads, hostbuf, host_len);
    } else if (strcmp(cat, "tracker") == 0) {
        rb_hostset_add(&f->trackers, hostbuf, host_len);
    } else if (strcmp(cat, "malicious") == 0) {
        rb_hostset_add(&f->malicious, hostbuf, host_len);
    } else {
        return 0;
    }
    return 1;
}

int rb_filters_load_text(rb_filters *f, const char *text)
{
    int added = 0;
    const char *p;

    if (f == NULL || text == NULL) {
        return 0;
    }
    p = text;
    while (*p != '\0') {
        const char *nl = strchr(p, '\n');
        size_t len = (nl != NULL) ? (size_t)(nl - p) : strlen(p);
        added += rb_filters_add_line(f, p, len);
        if (nl == NULL) {
            break;
        }
        p = nl + 1;
    }
    return added;
}

int rb_filters_load_builtin(rb_filters *f)
{
    int added = 0;
    int i;

    if (f == NULL) {
        return 0;
    }
    for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
        const char *line = RB_FILTERLIST_LINES[i];
        added += rb_filters_add_line(f, line, strlen(line));
    }
    return added;
}

int rb_filters_load_file(rb_filters *f, const char *path)
{
    FILE *fh;
    long size;
    char *buf;
    int added;

    if (f == NULL || path == NULL) {
        return 0;
    }
    fh = fopen(path, "rb");
    if (fh == NULL) {
        return 0; /* missing file is fine */
    }
    if (fseek(fh, 0, SEEK_END) != 0) {
        fclose(fh);
        return 0;
    }
    size = ftell(fh);
    if (size <= 0 || fseek(fh, 0, SEEK_SET) != 0) {
        fclose(fh);
        return 0;
    }
    buf = (char *)malloc((size_t)size + 1);
    if (buf == NULL) {
        fclose(fh);
        return 0;
    }
    {
        size_t got = fread(buf, 1, (size_t)size, fh);
        buf[got] = '\0';
    }
    fclose(fh);
    added = rb_filters_load_text(f, buf);
    free(buf);
    return added;
}

int rb_filters_host_count(const rb_filters *f)
{
    if (f == NULL) {
        return 0;
    }
    return f->ads.count + f->trackers.count + f->malicious.count;
}

/* Lowercase [host, host+len) into buf, dropping trailing dots. Returns the
 * normalised length; 0 when the result would be empty or too long. */
static size_t rb_prep_host(const char *host, char *buf, size_t cap)
{
    size_t len;

    if (host == NULL) {
        return 0;
    }
    len = strlen(host);
    if (len == 0 || len >= cap) {
        return 0;
    }
    memcpy(buf, host, len);
    return rb_norm_host(buf, len);
}

rb_filter_category rb_filters_decide(const rb_filters *f,
                                     const char *request_host,
                                     const char *page_host,
                                     const char *path,
                                     const rb_filter_options *opts,
                                     const char **out_reason)
{
    rb_filter_options o = (opts != NULL) ? *opts : rb_filter_options_default();
    char req[512];
    size_t req_len;

    if (out_reason != NULL) {
        *out_reason = NULL;
    }
    if (f == NULL) {
        return RB_FILTER_NONE;
    }
    req_len = rb_prep_host(request_host, req, sizeof(req));
    if (req_len == 0) {
        return RB_FILTER_NONE;
    }

    if (o.block_malicious && rb_hostset_matches(&f->malicious, req, req_len)) {
        if (out_reason != NULL) {
            *out_reason = "host on malicious-site blocklist";
        }
        return RB_FILTER_MALICIOUS;
    }
    if (o.block_ads && rb_hostset_matches(&f->ads, req, req_len)) {
        if (out_reason != NULL) {
            *out_reason = "host on ad blocklist";
        }
        return RB_FILTER_AD;
    }
    if (o.block_trackers && rb_hostset_matches(&f->trackers, req, req_len)) {
        char page[512];
        size_t page_len = rb_prep_host(page_host, page, sizeof(page));
        int cross_site = (page_len > 0 &&
                          !(page_len == req_len && memcmp(page, req, req_len) == 0));
        if (o.block_cross_site || !cross_site) {
            if (out_reason != NULL) {
                *out_reason = "host on tracker blocklist";
            }
            return cross_site ? RB_FILTER_CROSS_SITE_TRACKER : RB_FILTER_TRACKER;
        }
    }

    if (o.block_ads || o.block_trackers) {
        char lowered[1024];
        const char *p = (path != NULL && path[0] != '\0') ? path : "/";
        size_t n = req_len;
        size_t i;

        /* Rule matching is done on "host + path", lowercased (Android builds
         * the same "$host${path.lowercase()}" string). */
        memcpy(lowered, req, req_len);
        for (i = 0; p[i] != '\0' && n + 1 < sizeof(lowered); i++) {
            lowered[n++] = (char)tolower((unsigned char)p[i]);
        }
        lowered[n] = '\0';

        for (i = 0; i < (size_t)RB_KEYWORD_N; i++) {
            const rb_keyword_rule *r = &RB_KEYWORD_RULES[i];
            int relevant;

            if (strstr(lowered, r->pattern) == NULL) {
                continue;
            }
            switch (r->category) {
            case RB_FILTER_AD:
                relevant = o.block_ads;
                break;
            case RB_FILTER_TRACKER:
            case RB_FILTER_CROSS_SITE_TRACKER:
                relevant = o.block_trackers;
                break;
            default:
                relevant = 1;
                break;
            }
            if (relevant) {
                if (out_reason != NULL) {
                    *out_reason = "keyword rule";
                }
                return r->category;
            }
        }
    }
    return RB_FILTER_NONE;
}

rb_filter_category rb_filters_blocked_category(const rb_filters *f,
                                               const char *host)
{
    char req[512];
    size_t len;

    if (f == NULL) {
        return RB_FILTER_NONE;
    }
    len = rb_prep_host(host, req, sizeof(req));
    if (len == 0) {
        return RB_FILTER_NONE;
    }
    if (rb_hostset_matches(&f->malicious, req, len)) {
        return RB_FILTER_MALICIOUS;
    }
    if (rb_hostset_matches(&f->ads, req, len)) {
        return RB_FILTER_AD;
    }
    if (rb_hostset_matches(&f->trackers, req, len)) {
        return RB_FILTER_TRACKER;
    }
    return RB_FILTER_NONE;
}

void rb_filters_reset_stats(rb_filters *f)
{
    int i;

    if (f == NULL) {
        return;
    }
    for (i = 0; i < 6; i++) {
        f->stats[i] = 0;
    }
}

void rb_filters_count_block(rb_filters *f, rb_filter_category cat)
{
    if (f == NULL || cat < 0 || cat > 5) {
        return;
    }
    f->stats[cat]++;
}

int rb_filters_stat_blocked(const rb_filters *f, rb_filter_category cat)
{
    if (f == NULL || cat < 0 || cat > 5) {
        return 0;
    }
    return f->stats[cat];
}

int rb_filters_stat_total(const rb_filters *f)
{
    int i;
    int total = 0;

    if (f == NULL) {
        return 0;
    }
    for (i = 1; i < 6; i++) { /* skip RB_FILTER_NONE */
        total += f->stats[i];
    }
    return total;
}

const char *rb_filter_category_name(rb_filter_category cat)
{
    switch (cat) {
    case RB_FILTER_AD:                  return "Ads";
    case RB_FILTER_TRACKER:             return "Trackers";
    case RB_FILTER_CROSS_SITE_TRACKER:  return "Cross-site trackers";
    case RB_FILTER_MALICIOUS:           return "Malicious sites";
    case RB_FILTER_POPUP:               return "Popups";
    case RB_FILTER_NONE:
    default:                            return "None";
    }
}

/* ------------------------------------------------------------------ */
/* Suspicious-site signals (FilterEngine.suspiciousSignals) */

/* Kotlin's Regex("^https?://[0-9]{1,3}(\\.[0-9]{1,3}){3}") — `containsMatchIn`
 * with a leading ^ anchor, so it is a prefix test.  Written out rather than
 * pulled from a regex library: the core has no regex dependency, the pattern
 * is fixed, and spelling it out is what makes it reviewable.
 *
 * It is intentionally as loose as the Kotlin one: each octet is 1-3 digits
 * and is NOT range-checked, so "999.999.999.999" counts.  That is a signal
 * ("looks like a bare address"), not a validation, and mirroring it exactly
 * keeps the two editions warning about the same pages. */
static int rb_suspicious_is_ip_host(const char *lowered)
{
    const char *p = lowered;
    int octet;

    if (strncmp(p, "http://", 7) == 0) {
        p += 7;
    } else if (strncmp(p, "https://", 8) == 0) {
        p += 8;
    } else {
        return 0;
    }

    for (octet = 0; octet < 4; octet++) {
        int digits = 0;
        while (digits < 3 && p[digits] >= '0' && p[digits] <= '9') {
            digits++;
        }
        if (digits == 0) {
            return 0;
        }
        p += digits;
        if (octet < 3) {
            if (*p != '.') {
                return 0;
            }
            p++;
        }
    }
    return 1;   /* the four octets are there; whatever follows is not this
                 * check's business — Kotlin does not anchor the end either */
}

unsigned int rb_filters_suspicious_signals(const char *url)
{
    unsigned int signals = RB_SUSPICIOUS_NONE;
    char *lowered;
    size_t n;
    size_t i;

    if (url == NULL || url[0] == '\0') {
        return RB_SUSPICIOUS_NONE;
    }
    /* Kotlin lowercases the whole URL once and tests that; the same string is
     * built here so the three checks see exactly what the Kotlin ones see. */
    n = strlen(url);
    lowered = (char *)malloc(n + 1);
    if (lowered == NULL) {
        return RB_SUSPICIOUS_NONE;
    }
    for (i = 0; i < n; i++) {
        lowered[i] = (char)tolower((unsigned char)url[i]);
    }
    lowered[n] = '\0';

    if (strncmp(lowered, "http://", 7) == 0) {
        signals |= RB_SUSPICIOUS_INSECURE_HTTP;
    }
    if (rb_suspicious_is_ip_host(lowered)) {
        signals |= RB_SUSPICIOUS_IP_HOST;
    }
    /* strstr, not a token check: Kotlin's contains() is a plain substring
     * test, so "xn--" anywhere in the URL counts. */
    if (strstr(lowered, "xn--") != NULL) {
        signals |= RB_SUSPICIOUS_PUNYCODE;
    }

    free(lowered);
    return signals;
}

const char *rb_suspicious_signal_name(rb_suspicious_signal signal)
{
    switch (signal) {
    case RB_SUSPICIOUS_INSECURE_HTTP: return "insecure http connection";
    case RB_SUSPICIOUS_IP_HOST:       return "IP address used instead of a domain name";
    case RB_SUSPICIOUS_PUNYCODE:      return "punycode domain (possible homograph)";
    case RB_SUSPICIOUS_NONE:
    default:                          return NULL;
    }
}

char *rb_filters_suspicious_text(unsigned int signals)
{
    /* The three names, in the order Kotlin appends them, so the joined string
     * reads the same on both editions. */
    static const rb_suspicious_signal ORDER[] = {
        RB_SUSPICIOUS_INSECURE_HTTP,
        RB_SUSPICIOUS_IP_HOST,
        RB_SUSPICIOUS_PUNYCODE
    };
    size_t cap = 1;
    size_t i;
    char *out;
    char *w;

    for (i = 0; i < sizeof ORDER / sizeof ORDER[0]; i++) {
        if (signals & (unsigned int)ORDER[i]) {
            cap += strlen(rb_suspicious_signal_name(ORDER[i])) + 2;
        }
    }
    out = (char *)malloc(cap);
    if (out == NULL) {
        return NULL;
    }
    w = out;
    *w = '\0';
    for (i = 0; i < sizeof ORDER / sizeof ORDER[0]; i++) {
        const char *name;
        if (!(signals & (unsigned int)ORDER[i])) {
            continue;
        }
        name = rb_suspicious_signal_name(ORDER[i]);
        if (w != out) {
            *w++ = ',';
            *w++ = ' ';
        }
        memcpy(w, name, strlen(name));
        w += strlen(name);
        *w = '\0';
    }
    return out;
}
