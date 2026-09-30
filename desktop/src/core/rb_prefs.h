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

/* The range the desktop editions render a font scale within, and the value
 * that means "leave the system font alone".  Shared so the two GUIs cannot
 * disagree about what a stored scale means. */
#define RB_FONT_SCALE_MIN            50
#define RB_FONT_SCALE_MAX            250
#define RB_FONT_SCALE_DEFAULT        100
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

/* The real machine this profile presents. When set it decides the User-Agent
 * and the device shim, and the three keys above are not consulted - one
 * control, so the UA can never disagree with the machine behind it. Empty or
 * absent means the profile presents nothing and the keys above decide. */
#define RB_PREF_DEVICE_ID            "device_id"

/* Screen size.  What the profile tells a page the display is, in CSS pixels.
 *
 * The Android edition's Screen size row, stored with the same spellings so one
 * profile means the same thing on both.  "real" is the default and the only
 * mode in which nothing is claimed.
 *
 * A desktop window can be any size, so screen.width disagreeing with the
 * viewport is ordinary here in a way it is not on a phone.  The screen is
 * still a fingerprinting signal, though, and a profile presenting a 4K
 * workstation while reporting a laptop panel is the same unclaimed
 * contradiction the Android edition now offers a way out of.  See
 * SECURITY.md. */
#define RB_PREF_SCREEN_SIZE          "screen_size"   /* real|manual */
#define RB_PREF_SCREEN_WIDTH         "screen_width"  /* CSS px, manual only */
#define RB_PREF_SCREEN_HEIGHT        "screen_height"

/* The range a stored screen size can mean: narrower than any display Chrome
 * runs on, and wider than any it runs on.  Shared so the two GUIs cannot
 * disagree about what a stored size is. */
#define RB_SCREEN_PX_MIN             240
#define RB_SCREEN_PX_MAX             4320

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

/* The profile's font scale as a percentage, ready to render with.
 *
 * The key is shared with the Android edition, but on the desktop nothing ever
 * read it, so a scale the user chose did nothing at all.  A stored value can
 * be anything — a hand-edited settings file, or a number written by a build
 * whose range differed — so the renderers ask through here rather than
 * trusting it.  Values outside [RB_FONT_SCALE_MIN, RB_FONT_SCALE_MAX] are
 * clamped; zero or less is not a scale but the shape an unset or corrupt key
 * takes, so it reads as RB_FONT_SCALE_DEFAULT rather than being clamped up to
 * the smallest one.  100 must mean "unchanged" so a profile that never
 * touched this renders exactly as it did before. */
int rb_font_scale_percent(const rb_settings *s);

/* The screen size `s` claims, in CSS pixels.  Returns 1 and writes both
 * outputs when the profile claims one; returns 0 and writes nothing when it
 * claims none, which is the default.
 *
 * Both spellings of "no claim" land here: a profile not in manual mode, and a
 * profile in manual mode whose stored numbers are outside
 * [RB_SCREEN_PX_MIN, RB_SCREEN_PX_MAX].  A size no screen has is a corrupt
 * entry, and the truthful reading of a corrupt entry is the real display
 * rather than a page laid out for a display that cannot exist.  An
 * unrecognised mode is likewise no claim — a value that cannot be understood
 * must not switch a claim on. */
int rb_screen_claim_of(const rb_settings *s, int *w, int *h);

/* The WebRTC policy a profile's stored value means.  The values are the
 * Android edition's WebRtcPolicy enum (Profile.kt) lowercased, and the strings
 * are what RB_PREF_WEBRTC_POLICY holds.
 *
 * Parsed here rather than in each GUI because the three editions can express
 * different amounts of it, and the one thing they must not do is disagree
 * about which of the three a stored value IS.  Each then renders the nearest
 * thing its engine can actually do, and says so where the user chose it. */
typedef enum {
    RB_WEBRTC_DEFAULT = 0,        /* the engine's own behaviour            */
    RB_WEBRTC_RESTRICT_LOCAL_IP,  /* no host candidates for anyone listening */
    RB_WEBRTC_DISABLED            /* no real-time media at all             */
} rb_webrtc_policy;

/* The policy `s` asks for.  An absent or unrecognised value reads as
 * RB_WEBRTC_RESTRICT_LOCAL_IP, which is both what a fresh profile gets and the
 * protective end of the range: a privacy switch whose value cannot be
 * understood must not fall back to the permissive setting. */
rb_webrtc_policy rb_webrtc_policy_of(const rb_settings *s);

/* The string to store for a policy: the inverse of rb_webrtc_policy_of(), so
 * a combo box can be built from the enum without repeating the spellings. */
const char *rb_webrtc_policy_name(rb_webrtc_policy p);

/* Files the default homepage shortcuts, '\n'-separated (the four the
 * Android home screen ships). */
#define RB_PREFS_DEFAULT_SHORTCUTS \
    "https://www.youtube.com\nhttps://github.com\n" \
    "https://www.google.com\nhttps://www.reddit.com"

#ifdef __cplusplus
}
#endif

#endif /* RB_PREFS_H */
