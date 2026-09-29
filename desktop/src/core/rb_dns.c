/*
 * rb_dns.c — DNS configuration validation for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_dns.h"

#include "rb_json.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

static const char *const RB_DNS_MODE_NAMES[] = { "system", "auto", "doh", "dot" };
static const char *const RB_DNS_STATUS_NAMES[] = { "system", "protected_doh",
                                                   "protected_dot",
                                                   "misconfigured" };

const char *rb_dns_mode_name(rb_dns_mode mode)
{
    if ((int)mode < 0 || mode > RB_DNS_DOT) {
        return RB_DNS_MODE_NAMES[RB_DNS_SYSTEM];
    }
    return RB_DNS_MODE_NAMES[mode];
}

rb_dns_mode rb_dns_mode_parse(const char *name)
{
    int i;

    if (name == NULL) {
        return RB_DNS_SYSTEM;
    }
    while (*name == ' ' || *name == '\t') {
        name++;
    }
    for (i = 0; i <= RB_DNS_DOT; i++) {
        const char *a = name;
        const char *b = RB_DNS_MODE_NAMES[i];
        size_t n = strlen(b);

        if (strlen(a) != n) {
            continue;
        }
        {
            size_t k;
            int same = 1;
            for (k = 0; k < n; k++) {
                if (tolower((unsigned char)a[k]) != (unsigned char)b[k]) {
                    same = 0;
                    break;
                }
            }
            if (same) {
                return (rb_dns_mode)i;
            }
        }
    }
    return RB_DNS_SYSTEM;
}

const char *rb_dns_status_name(rb_dns_status status)
{
    if ((int)status < 0 || status > RB_DNS_STATUS_MISCONFIGURED) {
        return RB_DNS_STATUS_NAMES[RB_DNS_STATUS_SYSTEM];
    }
    return RB_DNS_STATUS_NAMES[status];
}

/* A value that is absent, empty or all whitespace is "not set". */
static const char *rb_dns_setting(const char *value)
{
    const char *p;

    if (value == NULL) {
        return NULL;
    }
    for (p = value; *p != '\0'; p++) {
        if (*p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') {
            return value;
        }
    }
    return NULL;
}

#define RB_DNS_URL_MAX 2048

int rb_dns_valid_doh_url(const char *url)
{
    char buf[RB_DNS_URL_MAX];
    const char *url_end;
    const char *p;
    const char *end;
    size_t len;
    int i;

    if (url == NULL) {
        return 0;
    }
    /* validateDohUrl() trims before it looks at anything, and a value read
     * back from the settings file can carry a trailing newline, so the trim
     * has to happen first — measuring the raw string would call a perfectly
     * good URL misconfigured.  Java's trim drops every byte at or below 0x20;
     * this is a byte-wise copy of that. */
    {
        const char *a = url;
        const char *b = url + strlen(url);

        while (a < b && (unsigned char)*a <= 0x20u) {
            a++;
        }
        while (b > a && (unsigned char)b[-1] <= 0x20u) {
            b--;
        }
        len = (size_t)(b - a);
        if (len == 0 || len >= sizeof(buf)) {
            return 0;
        }
        memcpy(buf, a, len);
        buf[len] = '\0';
    }
    if (len < 8) {
        return 0;
    }
    for (i = 0; i < 8; i++) {
        if (tolower((unsigned char)buf[i]) != "https://"[i]) {
            return 0;
        }
    }
    /* Java's URI rejects whitespace and control bytes outright, which is what
     * makes validateDohUrl() false for them; nothing below can rescue those,
     * so they are refused up front. */
    for (p = buf; *p != '\0'; p++) {
        unsigned char c = (unsigned char)*p;
        if (c <= 0x20u || c == 0x7fu) {
            return 0;
        }
    }
    /* The authority runs to the first '/', '?' or '#'; URI().host is null
     * (and the validation false) when there is no authority at all. */
    url_end = buf + len;
    p = buf + 8;
    end = p;
    while (end < url_end && *end != '/' && *end != '?' && *end != '#') {
        end++;
    }
    if (p == end) {
        return 0;
    }
    /* Strip any userinfo, then any port. */
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
        if (p >= end) {
            return 0;
        }
        if (*p != '[') {
            for (q = p; q < end; q++) {
                if (*q == ':') {
                    end = q;
                    break;
                }
            }
        }
    }
    return p < end;
}

int rb_dns_valid_dot_hostname(const char *host)
{
    const char *a;
    const char *b;
    const char *p;
    size_t len;
    size_t label = 0;

    if (host == NULL) {
        return 0;
    }
    /* Kotlin trims first, then measures and rejects spaces and slashes, so a
     * trailing newline from a settings file must not be fatal. */
    a = host;
    b = host + strlen(host);
    while (a < b && (*a == ' ' || *a == '\t' || *a == '\r' || *a == '\n')) {
        a++;
    }
    while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                     b[-1] == '\n')) {
        b--;
    }
    len = (size_t)(b - a);
    if (len == 0 || len > 253) {
        return 0;
    }
    for (p = a; p < b; p++) {
        if (*p == ' ' || *p == '\t' || *p == '/') {
            return 0;
        }
    }
    /* The host part is everything before the first ':', which is what
     * substringBefore(':') gives.  Its dot-separated labels are 1..63 bytes
     * of [A-Za-z0-9_-]; a trailing dot therefore fails on its empty label,
     * as it does on Android. */
    for (p = a; p < b && *p != ':'; p++) {
        if (*p == '.') {
            if (label == 0 || label > 63) {
                return 0;
            }
            label = 0;
            continue;
        }
        if (!isalnum((unsigned char)*p) && *p != '_' && *p != '-') {
            return 0;
        }
        label++;
    }
    return label > 0 && label <= 63;
}

rb_dns_effective rb_dns_resolve(const char *profile_mode, const char *profile_doh,
                                const char *profile_dot, const char *global_mode,
                                const char *global_doh, const char *global_dot)
{
    rb_dns_effective out;
    rb_dns_mode p_mode = rb_dns_mode_parse(profile_mode);
    rb_dns_mode mode;

    out.mode = RB_DNS_SYSTEM;
    out.doh_url = NULL;
    out.dot_hostname = NULL;
    out.status = RB_DNS_STATUS_SYSTEM;

    if (p_mode == RB_DNS_SYSTEM) {
        return out; /* a profile pinned to system never consults the global */
    }
    mode = (p_mode == RB_DNS_AUTO) ? rb_dns_mode_parse(global_mode) : p_mode;
    out.mode = mode;

    if (mode == RB_DNS_DOH) {
        /* The profile's own URL counts only when the PROFILE chose DoH; an
         * AUTO profile riding a global DoH uses the global URL. */
        const char *url = (p_mode == RB_DNS_DOH) ? rb_dns_setting(profile_doh)
                                                 : NULL;
        if (url == NULL) {
            url = rb_dns_setting(global_doh);
        }
        out.doh_url = (url != NULL) ? rb_json_strdup(url) : NULL;
        out.status = rb_dns_valid_doh_url(out.doh_url) ? RB_DNS_STATUS_PROTECTED_DOH
                                                       : RB_DNS_STATUS_MISCONFIGURED;
        return out;
    }
    if (mode == RB_DNS_DOT) {
        const char *host = (p_mode == RB_DNS_DOT) ? rb_dns_setting(profile_dot)
                                                  : NULL;
        if (host == NULL) {
            host = rb_dns_setting(global_dot);
        }
        out.dot_hostname = (host != NULL) ? rb_json_strdup(host) : NULL;
        out.status = rb_dns_valid_dot_hostname(out.dot_hostname)
                         ? RB_DNS_STATUS_PROTECTED_DOT
                         : RB_DNS_STATUS_MISCONFIGURED;
        return out;
    }
    /* AUTO resolving to AUTO (a global setting left on auto) is the system
     * resolver, which is what the Kotlin when-branch produces too. */
    out.status = RB_DNS_STATUS_SYSTEM;
    return out;
}

void rb_dns_effective_free(rb_dns_effective *e)
{
    if (e == NULL) {
        return;
    }
    free(e->doh_url);
    free(e->dot_hostname);
    e->doh_url = NULL;
    e->dot_hostname = NULL;
}
