/*
 * rb_prefs.h — the Android edition's ProfileSettings / BrowserGlobalSettings,
 * expressed as keys in an rb_settings store.
 *
 * Why a key=value store rather than a C struct: the Android settings model
 * grows a field every few releases, and a struct would force every stored
 * profile to be migrated each time (and would silently drop keys an older
 * binary does not know).  A store keyed by name round-trips forwards and
 * backwards: an unknown key written by a newer build is preserved verbatim
 * by an older one instead of being thrown away.
 *
 * The default values below are copied one-for-one from
 * android/core/domain/.../model/Profile.kt so the two editions ship the same
 * privacy posture.  In particular the compatibility-first 2026-09 revision:
 * the annoyance shields (ads, trackers, cross-site trackers, popups) are OFF,
 * while the security-grade protections (blockMalicious, httpsUpgrade) are ON.
 *
 * Keys are plain snake_case and stable; they are the on-disk contract.
 */

#ifndef RB_PREFS_H
#define RB_PREFS_H

#include "rb_settings.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---- per-profile keys (ProfileSettings) ---- */

/* Appearance */
#define RB_PREF_THEME                "theme"        /* system|light|dark|amoled */
#define RB_PREF_ACCENT_ARGB          "accent_argb"  /* 0xAARRGGBB as decimal   */
#define RB_PREF_FONT_SCALE           "font_scale"   /* percent, 100 = 1.0f     */
#define RB_PREF_REDUCED_MOTION       "reduced_motion"
#define RB_PREF_HIGH_CONTRAST        "high_contrast"
#define RB_PREF_TAB_LAYOUT           "tab_layout"   /* grid|list               */

/* Search & homepage */
#define RB_PREF_SEARCH_ENGINE        "search_engine"
#define RB_PREF_HOMEPAGE_ENABLED     "homepage_enabled"
#define RB_PREF_HOMEPAGE_SHORTCUTS   "homepage_shortcuts" /* '\n'-separated     */
#define RB_PREF_SHOW_PRIVACY_STATS   "show_privacy_stats"
#define RB_PREF_SHOW_RECENT_SITES    "show_recent_sites"
#define RB_PREF_SHOW_CLOCK           "show_clock"

/* The key the DESKTOP editions store their single homepage under.  It is a
 * desktop extension: the Android edition has no single home URL, it has
 * RB_PREF_HOMEPAGE_ENABLED plus a list of RB_PREF_HOMEPAGE_SHORTCUTS.  It
 * lives here rather than in each chrome so the two desktop editions cannot
 * drift onto different spellings of the same stored key — the file format is
 * shared even though the two GUIs are not. */
#define RB_PREF_HOME_LOCAL           "home"

/* Whether the bookmarks bar is shown.  A desktop extension like the key
 * above, and stored in the same profile settings: the bar is a property of
 * how a profile is browsed, not of the machine, so two profiles can disagree
 * about it exactly as they disagree about JavaScript. */
#define RB_PREF_BOOKMARKS_BAR_LOCAL  "bookmarks_bar"

/* User agent */
#define RB_PREF_UA_MODE              "ua_mode"      /* default|preset|custom   */
#define RB_PREF_UA_PRESET_ID         "ua_preset_id"
#define RB_PREF_CUSTOM_USER_AGENT    "custom_user_agent"

/* DNS */
#define RB_PREF_DNS_MODE             "dns_mode"     /* system|auto|doh|dot     */
#define RB_PREF_DOH_URL              "doh_url"
#define RB_PREF_DOT_HOSTNAME         "dot_hostname"

/* Privacy — see the compatibility-first note above */
#define RB_PREF_BLOCK_ADS              "block_ads"               /* default 0 */
#define RB_PREF_BLOCK_TRACKERS         "block_trackers"          /* default 0 */
#define RB_PREF_BLOCK_CROSS_SITE       "block_cross_site"        /* default 0 */
#define RB_PREF_BLOCK_POPUPS           "block_popups"            /* default 0 */
#define RB_PREF_BLOCK_MALICIOUS        "block_malicious"         /* default 1 */
#define RB_PREF_HTTPS_UPGRADE          "https_upgrade"           /* default 1 */
#define RB_PREF_BLOCK_THIRD_PARTY_COOKIES "block_third_party_cookies" /* 0 */
#define RB_PREF_BLOCK_MIXED_CONTENT   "block_mixed_content"     /* default 0 */
#define RB_PREF_JAVASCRIPT            "javascript"              /* default 1 */
#define RB_PREF_WEBRTC_POLICY         "webrtc_policy"  /* default|restrict_local_ip|disabled */
#define RB_PREF_SEARCH_SUGGESTIONS    "search_suggestions"       /* default 0 */
#define RB_PREF_DESKTOP_MODE_DEFAULT  "desktop_mode_default"     /* default 0 */

/* Language */
#define RB_PREF_LANGUAGE_TAG          "language_tag"
#define RB_PREF_TRANSLATE_TARGET      "translate_target_language"
#define RB_PREF_NEVER_TRANSLATE       "never_translate_sites" /* '\n'-separated */

/* Network protection */
#define RB_PREF_NET_PROTECT_GLOBAL    "network_protection_use_global" /* 1 */
#define RB_PREF_NET_PROTECT_ENABLED   "network_protection_enabled"    /* 1 */

/* Downloads */
#define RB_PREF_DOWNLOAD_SUBFOLDER    "download_subfolder"

/* Autofill */
#define RB_PREF_AUTOFILL_ENABLED      "autofill"

/* ---- global (browser-wide) keys (BrowserGlobalSettings) ---- */

#define RB_GPREF_DNS_MODE             RB_PREF_DNS_MODE
#define RB_GPREF_DOH_URL              RB_PREF_DOH_URL
#define RB_GPREF_DOT_HOSTNAME         RB_PREF_DOT_HOSTNAME
#define RB_GPREF_NET_PROTECT_ENABLED  RB_PREF_NET_PROTECT_ENABLED
#define RB_GPREF_SHOW_PREV_NAME       "show_previous_profile_name"
#define RB_GPREF_SHOW_LAST_SEEN       "show_last_seen_time"
#define RB_GPREF_OFFER_NET_CHANGE     "offer_network_change_options"
#define RB_GPREF_OFFER_AIRPLANE       "offer_airplane_mode_shortcut"
#define RB_GPREF_RETENTION            "network_retention_days" /* 1|7|30|90|2147483647 */
#define RB_GPREF_WARNING_BEHAVIOR     "warning_behavior"
#define RB_GPREF_CONFLICT_SEVERITY    "conflict_severity"
#define RB_GPREF_TELEMETRY            "telemetry_enabled"     /* default 0 */
#define RB_GPREF_DIAGNOSTICS          "diagnostics_enabled"   /* default 0 */

/* Fills `s` with the ProfileSettings defaults above.  Existing keys are
 * overwritten, so call this only on a fresh store (it is what
 * rb_profile_new() does). */
void rb_prefs_profile_defaults(rb_settings *s);

/* Fills `s` with the BrowserGlobalSettings defaults above. */
void rb_prefs_global_defaults(rb_settings *s);

/* Files the default homepage shortcuts, '\n'-separated (the four the
 * Android home screen ships). */
#define RB_PREFS_DEFAULT_SHORTCUTS \
    "https://www.youtube.com\nhttps://github.com\n" \
    "https://www.google.com\nhttps://www.reddit.com"

#ifdef __cplusplus
}
#endif

#endif /* RB_PREFS_H */
