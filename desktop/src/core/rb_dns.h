/*
 * rb_dns.h — DNS configuration validation for Room Browser core.
 *
 * Ports DnsValidator: DoH URL / DoT hostname validation and the resolution of
 * an effective DNS mode for a profile (profile setting overriding the global
 * one).
 *
 * HONEST LIMITATION, taken from the Android edition: neither WebKitGTK nor
 * WebView2 lets the embedder force a custom resolver for page loads, and the
 * Android app documents the same thing ("a normal third-party app cannot
 * force WebView to use a custom resolver").  What this decides is the app's
 * OWN connections, and the status a settings screen shows.  The desktop
 * edition has no HTTP client of its own yet, so today this is status and
 * validation only — it never claims page loads are protected.
 */

#ifndef RB_DNS_H
#define RB_DNS_H

#ifdef __cplusplus
extern "C" {
#endif

/* DnsMode, in declaration order — the names are the on-disk contract. */
typedef enum {
    RB_DNS_SYSTEM = 0,
    RB_DNS_AUTO = 1,
    RB_DNS_DOH = 2,
    RB_DNS_DOT = 3
} rb_dns_mode;

/* DnsValidator.DnsStatus, in declaration order. */
typedef enum {
    RB_DNS_STATUS_SYSTEM = 0,
    RB_DNS_STATUS_PROTECTED_DOH = 1,
    RB_DNS_STATUS_PROTECTED_DOT = 2,
    RB_DNS_STATUS_MISCONFIGURED = 3
} rb_dns_status;

/* "system" / "auto" / "doh" / "dot", and the reverse.  An unknown or NULL
 * name parses as RB_DNS_SYSTEM, the ProfileSettings default. */
const char *rb_dns_mode_name(rb_dns_mode mode);
rb_dns_mode rb_dns_mode_parse(const char *name);

const char *rb_dns_status_name(rb_dns_status status);

/* 1 when `url` is an https URL with a host — DnsValidator.validateDohUrl.
 * "https://" alone, a non-https scheme, and anything Java's URI refuses
 * (whitespace, control bytes) are all invalid. */
int rb_dns_valid_doh_url(const char *url);

/* 1 when `host` is a non-empty host optionally followed by ":port", at most
 * 253 bytes, with every dot-separated label at most 63 bytes of
 * [A-Za-z0-9_-] — DnsValidator.validateDotHostname.  A bare label with no
 * dot is valid ("dns" is a hostname). */
int rb_dns_valid_dot_hostname(const char *host);

/* The resolution result.  `doh_url` / `dot_hostname` are malloc'd copies of
 * the values that WOULD be used, or NULL when the mode does not use one.
 * Free with rb_dns_effective_free(). */
typedef struct {
    rb_dns_mode mode;
    char *doh_url;
    char *dot_hostname;
    rb_dns_status status;
} rb_dns_effective;

/* DnsValidator.effective.  Each mode is the stored string ("system", ...);
 * a NULL/empty URL counts as "not set" — the desktop's spelling of Kotlin's
 * null, since rb_settings has no null.
 *
 * The rules, which are the Kotlin ones:
 *  - a profile mode of SYSTEM is final: system resolver, no URL, status
 *    SYSTEM
 *  - a profile mode of AUTO means "use the global mode"
 *  - otherwise the profile's own mode wins
 *  - the DoH URL comes from the profile only when the PROFILE's mode is DOH;
 *    otherwise it comes from the global setting
 *  - likewise for the DoT hostname
 *  - DOH/DOT are PROTECTED_* only when the chosen value validates, and
 *    MISCONFIGURED when it does not */
rb_dns_effective rb_dns_resolve(const char *profile_mode, const char *profile_doh,
                                const char *profile_dot, const char *global_mode,
                                const char *global_doh, const char *global_dot);

void rb_dns_effective_free(rb_dns_effective *e);

#ifdef __cplusplus
}
#endif

#endif /* RB_DNS_H */
