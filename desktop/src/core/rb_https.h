/*
 * rb_https.h — HTTPS-First fallback policy for Room Browser core.
 *
 * Port of android/core/domain/.../engine/HttpsUpgradeFallback.kt: the
 * compatibility half of HTTPS upgrades.
 *
 * Room Browser upgrades an http navigation to https (rb_url_upgrade_to_https).
 * When the secure version turns out to be unavailable — connection refused,
 * timeout, TLS handshake failure — the ORIGINAL http URL is retried once
 * automatically instead of leaving the user on a dead error page.  Upgrades
 * can therefore stay on by default without making http-only sites
 * unreachable, which is what "HTTPS-First" means in the major browsers.
 *
 * The upgrade is recorded per URL, so the retry can only ever fire for a URL
 * this browser upgraded itself.  A plain http navigation the user typed, or a
 * link into an http-only site, is never silently turned into https.
 */

#ifndef RB_HTTPS_H
#define RB_HTTPS_H

#ifdef __cplusplus
extern "C" {
#endif

/* Engine error codes that are worth an automatic http retry.
 *
 * Deliberately conservative: a code that indicates a problem the http
 * version would share (out of memory, too many redirects, a file error) is
 * excluded, because retrying would just fail twice.  The values are the
 * WebKitGTK / WebView2 error codes the two platform layers already report;
 * see rb_https_is_recoverable() for the mapping. */
typedef enum {
    RB_HTTPS_ERR_UNKNOWN        = -1,  /* generic connect failure */
    RB_HTTPS_ERR_CONNECT        = -6,  /* nothing listening on 443 */
    RB_HTTPS_ERR_TIMEOUT        = -8,  /* connection timed out */
    RB_HTTPS_ERR_SSL_HANDSHAKE  = -11  /* TLS transport failed */
} rb_https_error;

/* 1 when a failed load with this error code should be retried over http.
 * Everything else — including codes that mean "the page loaded but is
 * broken" — returns 0. */
int rb_https_is_recoverable(int error_code);

typedef struct rb_https_pending rb_https_pending;

rb_https_pending *rb_https_pending_new(void);
void              rb_https_pending_free(rb_https_pending *p);

/* Records that `upgraded_url` replaced `original_url`, so a later failure of
 * the former retries the latter.  Both are trimmed; a NULL/empty argument is
 * ignored.  Registering the same upgraded URL twice replaces the original. */
void rb_https_register(rb_https_pending *p, const char *upgraded_url,
                       const char *original_url);

/* Takes (once) the original URL for a failed `url`.  Returns a malloc'd
 * string, or NULL when this failure was not preceded by one of our upgrades
 * — which is the common case and must not be treated as an error.  Consuming
 * removes the entry, so a retry that fails again cannot loop. */
char *rb_https_consume(rb_https_pending *p, const char *url);

/* Drops every pending upgrade; used on a profile switch, where the tabs that
 * were mid-upgrade no longer exist. */
void rb_https_clear(rb_https_pending *p);

/* Number of pending upgrades (0 for a NULL registry). */
int  rb_https_pending_count(const rb_https_pending *p);

/* Convenience wrapper for the whole decision, so both platform layers do the
 * same thing in the same order:
 *
 *   - the load succeeded, or the error is not recoverable  -> NULL
 *   - the URL was one of ours and the error is recoverable  -> original URL
 *
 * `url` is the URL that failed, `error_code` the engine's code.
 *
 * Either way the entry is dropped.  A non-recoverable failure retires the
 * upgrade too, so that a *later*, unrelated failure of the same URL cannot
 * be mistaken for this upgrade having failed and silently redirect the user
 * to http. */
char *rb_https_retry_url(rb_https_pending *p, const char *url,
                         int error_code);

#ifdef __cplusplus
}
#endif

#endif /* RB_HTTPS_H */
