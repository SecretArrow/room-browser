/*
 * rb_ipconflict.h — profile network conflict detection for Room Browser core.
 *
 * Ports IpConflictDetector: the record of which public IP each profile has
 * been seen from, and the decision whether opening a profile from an address
 * another profile has used deserves a warning.
 *
 * IMPORTANT (from the product spec, and reproduced here so it is not lost):
 * a shared public IP does NOT prove that two profiles belong to the same
 * person.  This is an informational, fully configurable warning.  It never
 * blocks access silently, and nothing here can refuse a navigation.
 *
 * The decisions read their configuration out of an rb_settings store, using
 * the global keys in rb_prefs.h (RB_GPREF_WARNING_BEHAVIOR,
 * RB_GPREF_CONFLICT_SEVERITY, RB_GPREF_RETENTION, RB_GPREF_NET_PROTECT_ENABLED)
 * and the per-profile RB_PREF_NET_PROTECT_ENABLED.  The three "already dealt
 * with" sets are '\n'-separated lists, the same encoding rb_prefs uses for
 * every other set.
 *
 * The clock is passed in rather than read here, so retention is testable.
 */

#ifndef RB_IPCONFLICT_H
#define RB_IPCONFLICT_H

#ifdef __cplusplus
extern "C" {
#endif

#include "rb_settings.h"

/* NetworkRetention.FOREVER, in days. */
#define RB_IPCONFLICT_RETENTION_FOREVER 2147483647

/* One observed (profile, ip) association — IpAssociation. */
typedef struct {
    char *profile_id;
    char *ip;
    long long first_seen_at; /* ms since the epoch */
    long long last_seen_at;
} rb_ip_assoc;

/* Opaque, unordered table keyed by (profile_id, ip). */
typedef struct rb_ip_history rb_ip_history;

rb_ip_history *rb_ip_history_new(void);
void           rb_ip_history_free(rb_ip_history *h);

int            rb_ip_history_count(const rb_ip_history *h);
const rb_ip_assoc *rb_ip_history_at(const rb_ip_history *h, int index);

/* The row for this (profile, ip) pair, or NULL. */
const rb_ip_assoc *rb_ip_history_find(const rb_ip_history *h,
                                      const char *profile_id, const char *ip);

/* IpConflictDetector.isValidIp: an IPv4 dotted quad with every octet 0-255,
 * or a loose IPv6 literal (hex-and-colon groups, at most four hex digits each,
 * at least two colons, optional up-to-two dotted-decimal tail groups — the
 * shape the Android regex accepts, warts included).  A NULL/blank input, and
 * anything with surrounding spaces inside it, is invalid. */
int            rb_ip_is_valid(const char *ip);

/* IpConflictDetector.record: upserts the association for (profile_id, ip),
 * preserving first_seen_at and setting last_seen_at to now_ms.  Returns 1
 * when the row is new, 0 when an existing row was updated, and 0 without
 * touching anything when the profile id or ip is blank or invalid. */
int            rb_ip_history_record(rb_ip_history *h, const char *profile_id,
                                    const char *ip, long long now_ms);

/* IpConflictDetector.purgeExpired: removes every association last seen before
 * the retention cutoff (RB_IPCONFLICT_RETENTION_FOREVER never purges) and
 * returns how many were removed. */
int            rb_ip_history_purge(rb_ip_history *h, int retention_days,
                                   long long now_ms);

/* The retention cutoff as an absolute timestamp, or 0 for "no cutoff" (FOREVER). */
long long      rb_ip_retention_cutoff(int retention_days, long long now_ms);

/* IpConflictDetector.CheckResult.  has_conflict is 0 for every "do not warn"
 * outcome, in which case the other fields are 0/NULL.  Free the strings with
 * rb_ip_check_free(). */
typedef struct {
    int has_conflict;
    int should_warn;
    int requires_confirmation;
    char *current_profile_id;
    char *current_ip;
    char *previous_profile_id;
    long long previous_last_seen_at;
} rb_ip_check;

/* IpConflictDetector.check.
 *
 * `global` supplies networkProtectionEnabled, warningBehavior,
 * conflictSeverity and retention (the browser-wide settings).
 * `profile_protection_enabled` is the per-profile switch.
 * `suppressed_ips` and `warned_networks` are '\n'-separated ip lists;
 * `suppressed_profiles` is a '\n'-separated profile-id list.
 *
 * There is no previous_profile_name in the result: the Android domain layer
 * leaves that field empty and lets the UI resolve it, and the desktop does
 * the same, so the caller looks the id up in its profile registry.
 *
 * The order of the checks is the Kotlin order and is load-bearing — the
 * global switch, then the per-profile switch, then the warning behaviour,
 * then IP validity, then the per-IP suppression, and only then the history.
 */
rb_ip_check    rb_ip_check_run(const rb_ip_history *h,
                               const char *current_profile_id,
                               const char *current_ip,
                               const rb_settings *global,
                               int profile_protection_enabled,
                               const char *suppressed_ips,
                               const char *suppressed_profiles,
                               const char *warned_networks, long long now_ms);

void           rb_ip_check_free(rb_ip_check *c);

/* JSON lines, one association per row:
 *   {"profile_id":"...","ip":"...","first_seen_at":N,"last_seen_at":N}
 * 0 on success, -1 on I/O error; a missing file loads as empty. */
int            rb_ip_history_save(const rb_ip_history *h, const char *path);
int            rb_ip_history_load(rb_ip_history *h, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_IPCONFLICT_H */
