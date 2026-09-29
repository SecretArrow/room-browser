/*
 * rb_filters.h — privacy filtering engine for Room Browser core.
 *
 * Port of the Android edition's FilterEngine (core/domain engine/
 * FilterEngine.kt): host-based ad / tracker / cross-site-tracker / malicious
 * blocking with the same suffix-matching rule and the same high-confidence
 * keyword rules, plus the same opt-in switches.
 *
 * The defaults are the compatibility-first ones the Android edition settled
 * on (2026-09 revision): ad, tracker, cross-site-tracker and popup blocking
 * are OFF out of the box — aggressive blocking breaks layouts, login flows
 * and embedded players on real sites — while malicious-site blocking stays
 * ON because it never breaks a legitimate page.
 *
 * Decisions are host-based only; full URLs are never logged.
 */

#ifndef RB_FILTERS_H
#define RB_FILTERS_H

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    RB_FILTER_NONE = 0,
    RB_FILTER_AD,
    RB_FILTER_TRACKER,
    RB_FILTER_CROSS_SITE_TRACKER,
    RB_FILTER_MALICIOUS,
    RB_FILTER_POPUP
} rb_filter_category;

/* Per-request policy switches (mirrors the Android per-profile settings). */
typedef struct {
    int block_ads;           /* default 0 */
    int block_trackers;      /* default 0 */
    int block_cross_site;    /* default 0 */
    int block_malicious;     /* default 1 — never breaks a legitimate site */
    int block_popups;        /* default 0 */
} rb_filter_options;

/* The compatibility-first defaults described above. */
rb_filter_options rb_filter_options_default(void);

typedef struct rb_filters rb_filters;

rb_filters *rb_filters_new(void);
void        rb_filters_free(rb_filters *f);

/* Loads the compiled-in list. Returns the number of hosts loaded. */
int         rb_filters_load_builtin(rb_filters *f);

/* Parses "<category>|<host>" lines from a NUL-terminated buffer; blank and
 * "#" lines are skipped, unknown categories ignored. Returns hosts added. */
int         rb_filters_load_text(rb_filters *f, const char *text);

/* Same, from a file. A missing file is not an error (returns 0). */
int         rb_filters_load_file(rb_filters *f, const char *path);

int         rb_filters_host_count(const rb_filters *f);

/* Decide what to do with a (sub)resource request.
 *
 *   request_host  host of the requested resource (required)
 *   page_host     host of the page that triggered it, NULL for a main frame
 *   path          path part, used by the keyword rules; NULL means "/"
 *   opts          policy switches; NULL uses the compatibility defaults
 *   out_reason    optional; receives a STATIC English reason string
 *
 * Returns RB_FILTER_NONE to allow, or the category to block. Cross-site
 * trackers are reported as RB_FILTER_CROSS_SITE_TRACKER so the privacy
 * dashboard can tell same-site from cross-site blocking. */
rb_filter_category rb_filters_decide(const rb_filters *f,
                                     const char *request_host,
                                     const char *page_host,
                                     const char *path,
                                     const rb_filter_options *opts,
                                     const char **out_reason);

/* Main-frame category for a host with no page context (used to warn about a
 * top-level navigation into a known-bad host). RB_FILTER_NONE when clean. */
rb_filter_category rb_filters_blocked_category(const rb_filters *f,
                                               const char *host);

/* --- privacy dashboard statistics ---
 * Every counter is advanced by real blocking events only; nothing here is
 * estimated or sampled. */
void rb_filters_reset_stats(rb_filters *f);
void rb_filters_count_block(rb_filters *f, rb_filter_category cat);
int  rb_filters_stat_blocked(const rb_filters *f, rb_filter_category cat);
int  rb_filters_stat_total(const rb_filters *f);

/* Stable, human-readable category name ("Ads", "Trackers", ...). */
const char *rb_filter_category_name(rb_filter_category cat);

/* --- suspicious-site signals ---
 *
 * Port of FilterEngine.suspiciousSignals: heuristics used when a host is on
 * no list at all, so the user is warned about a page rather than blocked from
 * it.  Returned as a bitmask because Kotlin returns a list the UI joins.
 *
 * The checks are deliberately the same three, with the same spelling and the
 * same ORDER, as the Android edition.  The IP-address rule is the same
 * deliberately loose one: it accepts 999.999.999.999, because tightening it
 * here would make the two editions disagree about which pages warn. */
typedef enum {
    RB_SUSPICIOUS_NONE          = 0,
    RB_SUSPICIOUS_INSECURE_HTTP = 1 << 0, /* "insecure http connection" */
    RB_SUSPICIOUS_IP_HOST       = 1 << 1, /* "IP address used instead of a domain name" */
    RB_SUSPICIOUS_PUNYCODE      = 1 << 2  /* "punycode domain (possible homograph)" */
} rb_suspicious_signal;

/* The signals `url` carries, as a bitmask of rb_suspicious_signal. */
unsigned int rb_filters_suspicious_signals(const char *url);

/* The Kotlin list element for one signal, or NULL when it is not one. */
const char *rb_suspicious_signal_name(rb_suspicious_signal signal);

/* All of them joined the way Kotlin's joinToString() does — ", " between,
 * nothing around — in the declaration order above.  malloc'd; "" when the
 * mask is empty.  Caller frees. */
char *rb_filters_suspicious_text(unsigned int signals);

#ifdef __cplusplus
}
#endif

#endif /* RB_FILTERS_H */
