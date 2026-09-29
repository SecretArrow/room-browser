/*
 * rb_ipconflict.c — profile network conflict detection for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_ipconflict.h"

#include "rb_json.h"
#include "rb_prefs.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RB_IPCONFLICT_LINE 16384

struct rb_ip_history {
    rb_ip_assoc *items;
    int count;
    int cap;
};

/* ------------------------------- helpers -------------------------------- */

static void rb_ip_assoc_release(rb_ip_assoc *a)
{
    free(a->profile_id);
    free(a->ip);
    memset(a, 0, sizeof(*a));
}

static void rb_ip_history_grow(rb_ip_history *h)
{
    int ncap;
    rb_ip_assoc *grown;

    if (h->count < h->cap) {
        return;
    }
    ncap = (h->cap > 0) ? h->cap * 2 : 8;
    grown = (rb_ip_assoc *)realloc(h->items,
                                   (size_t)ncap * sizeof(rb_ip_assoc));
    if (grown == NULL) {
        fprintf(stderr, "rb_ipconflict: out of memory\n");
        exit(1);
    }
    h->items = grown;
    h->cap = ncap;
}

static int rb_ip_history_index_of(const rb_ip_history *h, const char *profile_id,
                                  const char *ip)
{
    int i;

    if (h == NULL || profile_id == NULL || ip == NULL) {
        return -1;
    }
    for (i = 0; i < h->count; i++) {
        if (strcmp(h->items[i].profile_id, profile_id) == 0 &&
            strcmp(h->items[i].ip, ip) == 0) {
            return i;
        }
    }
    return -1;
}

/* 1 when the '\n'-separated list contains `value` as a whole item. */
static int rb_ip_list_has(const char *list, const char *value)
{
    const char *p;
    size_t vlen;

    if (list == NULL || value == NULL) {
        return 0;
    }
    vlen = strlen(value);
    p = list;
    while (*p != '\0') {
        const char *nl = strchr(p, '\n');
        size_t len = (nl != NULL) ? (size_t)(nl - p) : strlen(p);

        if (len == vlen && strncmp(p, value, vlen) == 0) {
            return 1;
        }
        if (nl == NULL) {
            break;
        }
        p = nl + 1;
    }
    return 0;
}

/* ---------------------------- IP validation ----------------------------- */

/* The loose Android IPv6 shape: hex-and-colon groups, at most four hex digits
 * each, plus an optional tail of at most two ".ddd" groups.  See the header
 * for why this is deliberately not a strict RFC 4291 parser. */
static int rb_ip_valid_v6(const char *s)
{
    const char *p = s;
    int groups = 0;
    int colons = 0;
    const char *q;

    for (q = s; *q != '\0'; q++) {
        if (*q == ':') {
            colons++;
        }
    }
    if (colons < 2) {
        return 0;
    }
    /* ([0-9a-fA-F]{0,4}:){1,7} — four digits is the cap, so a five-digit
     * group cannot be split to make the pattern fit. */
    while (groups < 8) {
        int d = 0;

        while (isxdigit((unsigned char)p[d])) {
            d++;
        }
        if (d > 4 || p[d] != ':') {
            break;
        }
        p += d + 1;
        groups++;
    }
    if (groups < 1 || groups > 7) {
        return 0;
    }
    /* ([0-9a-fA-F]{0,4})? */
    {
        int d = 0;

        while (isxdigit((unsigned char)p[d])) {
            d++;
        }
        if (d > 4) {
            return 0;
        }
        p += d;
    }
    /* (\.[0-9]{1,3}){0,2} */
    {
        int k = 0;

        while (k < 2 && *p == '.') {
            int d = 0;

            p++;
            while (isdigit((unsigned char)*p)) {
                p++;
                d++;
            }
            if (d < 1 || d > 3) {
                return 0;
            }
            k++;
        }
    }
    return *p == '\0';
}

int rb_ip_is_valid(const char *ip)
{
    const char *a;
    const char *b;
    size_t len;
    char buf[128];

    if (ip == NULL) {
        return 0;
    }
    a = ip;
    b = ip + strlen(ip);
    while (a < b && (*a == ' ' || *a == '\t' || *a == '\r' || *a == '\n')) {
        a++;
    }
    while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                     b[-1] == '\n')) {
        b--;
    }
    len = (size_t)(b - a);
    if (len == 0 || len >= sizeof(buf)) {
        return 0;
    }
    memcpy(buf, a, len);
    buf[len] = '\0';

    if (strchr(buf, ':') != NULL) {
        return rb_ip_valid_v6(buf);
    }
    {
        int part;
        const char *p = buf;

        for (part = 0; part < 4; part++) {
            int digits = 0;
            int value = 0;

            while (isdigit((unsigned char)p[digits]) && digits < 3) {
                value = value * 10 + (p[digits] - '0');
                digits++;
            }
            if (digits == 0 || value > 255) {
                return 0;
            }
            p += digits;
            if (part < 3) {
                if (*p != '.') {
                    return 0;
                }
                p++;
            }
        }
        return *p == '\0';
    }
}

/* ------------------------------- history -------------------------------- */

rb_ip_history *rb_ip_history_new(void)
{
    rb_ip_history *h = (rb_ip_history *)calloc(1, sizeof(*h));

    if (h == NULL) {
        fprintf(stderr, "rb_ipconflict: out of memory\n");
        exit(1);
    }
    return h;
}

void rb_ip_history_free(rb_ip_history *h)
{
    int i;

    if (h == NULL) {
        return;
    }
    for (i = 0; i < h->count; i++) {
        rb_ip_assoc_release(&h->items[i]);
    }
    free(h->items);
    free(h);
}

int rb_ip_history_count(const rb_ip_history *h)
{
    return (h != NULL) ? h->count : 0;
}

const rb_ip_assoc *rb_ip_history_at(const rb_ip_history *h, int index)
{
    if (h == NULL || index < 0 || index >= h->count) {
        return NULL;
    }
    return &h->items[index];
}

const rb_ip_assoc *rb_ip_history_find(const rb_ip_history *h,
                                      const char *profile_id, const char *ip)
{
    int i = rb_ip_history_index_of(h, profile_id, ip);

    return (i >= 0) ? &h->items[i] : NULL;
}

int rb_ip_history_record(rb_ip_history *h, const char *profile_id,
                         const char *ip, long long now_ms)
{
    int i;
    char *clean;

    if (h == NULL || profile_id == NULL || profile_id[0] == '\0') {
        return 0;
    }
    if (!rb_ip_is_valid(ip)) {
        return 0; /* an address we cannot reason about is not recorded */
    }
    /* Store the trimmed form, so " 1.2.3.4 " and "1.2.3.4" are one row. */
    {
        const char *a = ip;
        const char *b = ip + strlen(ip);

        while (a < b && (*a == ' ' || *a == '\t')) {
            a++;
        }
        while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                         b[-1] == '\n')) {
            b--;
        }
        clean = rb_json_strdup_n(a, (size_t)(b - a));
    }

    i = rb_ip_history_index_of(h, profile_id, clean);
    if (i >= 0) {
        h->items[i].last_seen_at = now_ms;
        free(clean);
        return 0;
    }
    rb_ip_history_grow(h);
    h->items[h->count].profile_id = rb_json_strdup(profile_id);
    h->items[h->count].ip = clean;
    h->items[h->count].first_seen_at = now_ms;
    h->items[h->count].last_seen_at = now_ms;
    h->count++;
    return 1;
}

long long rb_ip_retention_cutoff(int retention_days, long long now_ms)
{
    if (retention_days == RB_IPCONFLICT_RETENTION_FOREVER) {
        return 0; /* NetworkRetention.FOREVER.cutoff() is null */
    }
    if (retention_days < 0) {
        retention_days = 0;
    }
    return now_ms - (long long)retention_days * 86400000LL;
}

int rb_ip_history_purge(rb_ip_history *h, int retention_days, long long now_ms)
{
    long long cutoff;
    int removed = 0;
    int i = 0;

    if (h == NULL) {
        return 0;
    }
    cutoff = rb_ip_retention_cutoff(retention_days, now_ms);
    if (cutoff == 0) {
        return 0; /* FOREVER purges nothing */
    }
    while (i < h->count) {
        if (h->items[i].last_seen_at >= cutoff) {
            i++;
            continue;
        }
        rb_ip_assoc_release(&h->items[i]);
        memmove(&h->items[i], &h->items[i + 1],
                (size_t)(h->count - i - 1) * sizeof(rb_ip_assoc));
        h->count--;
        removed++;
    }
    return removed;
}

/* -------------------------------- check --------------------------------- */

static rb_ip_check rb_ip_check_none(void)
{
    rb_ip_check c;

    memset(&c, 0, sizeof(c));
    return c;
}

rb_ip_check rb_ip_check_run(const rb_ip_history *h, const char *current_profile_id,
                            const char *current_ip, const rb_settings *global,
                            int profile_protection_enabled,
                            const char *suppressed_ips,
                            const char *suppressed_profiles,
                            const char *warned_networks, long long now_ms)
{
    rb_ip_check result = rb_ip_check_none();
    const char *behavior;
    const char *severity;
    long long cutoff;
    int retention;
    const rb_ip_assoc *latest = NULL;
    int i;
    char *clean;

    if (global == NULL || current_profile_id == NULL) {
        return result;
    }
    /* Both switches, then the warning behaviour — the Kotlin order. */
    if (rb_settings_get_int(global, RB_GPREF_NET_PROTECT_ENABLED, 0) == 0 ||
        !profile_protection_enabled) {
        return result;
    }
    behavior = rb_settings_get(global, RB_GPREF_WARNING_BEHAVIOR, "ask_every_time");
    if (strcmp(behavior, "dont_warn") == 0) {
        return result;
    }
    if (!rb_ip_is_valid(current_ip)) {
        return result;
    }
    {
        const char *a = current_ip;
        const char *b = current_ip + strlen(current_ip);

        while (a < b && (*a == ' ' || *a == '\t')) {
            a++;
        }
        while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                         b[-1] == '\n')) {
            b--;
        }
        clean = rb_json_strdup_n(a, (size_t)(b - a));
    }
    if (rb_ip_list_has(suppressed_ips, clean)) {
        free(clean);
        return result;
    }

    retention = rb_settings_get_int(global, RB_GPREF_RETENTION,
                                    RB_IPCONFLICT_RETENTION_FOREVER);
    cutoff = rb_ip_retention_cutoff(retention, now_ms);

    /* The latest association from ANOTHER profile at this address, within
     * the retention window and not suppressed for this session. */
    for (i = 0; i < rb_ip_history_count(h); i++) {
        const rb_ip_assoc *row = rb_ip_history_at(h, i);

        if (strcmp(row->profile_id, current_profile_id) == 0) {
            continue;
        }
        if (strcmp(row->ip, clean) != 0) {
            continue;
        }
        if (rb_ip_list_has(suppressed_profiles, row->profile_id)) {
            continue;
        }
        if (cutoff != 0 && row->last_seen_at < cutoff) {
            continue;
        }
        if (latest == NULL || row->last_seen_at > latest->last_seen_at) {
            latest = row;
        }
    }
    if (latest == NULL) {
        free(clean);
        return result;
    }

    if (strcmp(behavior, "once_per_network") == 0 &&
        rb_ip_list_has(warned_networks, clean)) {
        free(clean);
        return result;
    }

    severity = rb_settings_get(global, RB_GPREF_CONFLICT_SEVERITY, "informational");
    result.has_conflict = 1;
    result.should_warn = 1;
    result.requires_confirmation = (strcmp(severity, "require_confirmation") == 0);
    result.current_profile_id = rb_json_strdup(current_profile_id);
    result.current_ip = clean;
    result.previous_profile_id = rb_json_strdup(latest->profile_id);
    result.previous_last_seen_at = latest->last_seen_at;
    return result;
}

void rb_ip_check_free(rb_ip_check *c)
{
    if (c == NULL) {
        return;
    }
    free(c->current_profile_id);
    free(c->current_ip);
    free(c->previous_profile_id);
    memset(c, 0, sizeof(*c));
}

/* ------------------------------ persistence ----------------------------- */

static int rb_ip_write_str(FILE *f, const char *value)
{
    char *escaped = rb_json_escape(value);
    int rc = 0;

    if (fputc('"', f) == EOF || fputs(escaped, f) == EOF || fputc('"', f) == EOF) {
        rc = -1;
    }
    free(escaped);
    return rc;
}

int rb_ip_history_save(const rb_ip_history *h, const char *path)
{
    FILE *f;
    int i;
    int rc = 0;

    if (h == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < h->count && rc == 0; i++) {
        if (fprintf(f, "{\"first_seen_at\":%lld,\"last_seen_at\":%lld,"
                       "\"profile_id\":",
                    h->items[i].first_seen_at, h->items[i].last_seen_at) < 0) {
            rc = -1;
            break;
        }
        rc = rb_ip_write_str(f, h->items[i].profile_id);
        if (rc == 0 && fputs(",\"ip\":", f) == EOF) {
            rc = -1;
        }
        if (rc == 0) {
            rc = rb_ip_write_str(f, h->items[i].ip);
        }
        if (rc == 0 && fputs("}\n", f) == EOF) {
            rc = -1;
        }
    }
    if (rc == 0 && ferror(f)) {
        rc = -1;
    }
    if (fclose(f) != 0) {
        rc = -1;
    }
    return rc;
}

static char *rb_ip_field_str(const char *line, const char *key)
{
    size_t pos;
    char *out = NULL;

    if (!rb_json_find_key(line, key, &pos)) {
        return NULL;
    }
    if (line[pos] != '"' || !rb_json_parse_string(line, &pos, &out)) {
        return NULL;
    }
    return out;
}

static long long rb_ip_field_num(const char *line, const char *key,
                                 long long fallback)
{
    size_t pos;
    long long v = fallback;

    if (!rb_json_find_key(line, key, &pos)) {
        return fallback;
    }
    if (!rb_json_parse_number(line, &pos, &v)) {
        return fallback;
    }
    return v;
}

int rb_ip_history_load(rb_ip_history *h, const char *path)
{
    char buf[RB_IPCONFLICT_LINE];
    FILE *f;
    int rc = 0;

    if (h == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *profile_id;
        char *ip;

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
        profile_id = rb_ip_field_str(buf, "profile_id");
        ip = rb_ip_field_str(buf, "ip");
        if (profile_id == NULL || profile_id[0] == '\0' || ip == NULL) {
            free(profile_id);
            free(ip);
            continue; /* not one of our rows */
        }
        if (rb_ip_history_index_of(h, profile_id, ip) >= 0) {
            free(profile_id);
            free(ip);
            continue; /* first row wins */
        }
        rb_ip_history_grow(h);
        h->items[h->count].profile_id = profile_id;
        h->items[h->count].ip = ip;
        h->items[h->count].first_seen_at =
            rb_ip_field_num(buf, "first_seen_at", 0);
        h->items[h->count].last_seen_at =
            rb_ip_field_num(buf, "last_seen_at", 0);
        h->count++;
    }
    if (ferror(f)) {
        rc = -1;
    }
    fclose(f);
    return rc;
}
