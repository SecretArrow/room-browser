/*
 * rb_prefs.c — defaults for the Android edition's settings model.
 * Pure C11; only the C standard library is used.
 *
 * Every value here is copied from
 * android/core/domain/src/main/kotlin/com/roombrowser/domain/model/Profile.kt
 * (ProfileSettings / BrowserGlobalSettings).  Keep the two in step: a
 * difference here is a difference in what the two editions protect.
 */

#include "rb_prefs.h"

#include <stddef.h> /* NULL */

void rb_prefs_profile_defaults(rb_settings *s)
{
    if (s == NULL) {
        return;
    }

    /* Appearance */
    rb_settings_set(s, RB_PREF_THEME, "system");
    rb_settings_set(s, RB_PREF_ACCENT_ARGB, "4285020836"); /* 0xFF6750A4 */
    rb_settings_set_int(s, RB_PREF_FONT_SCALE, 100);       /* 1.0f as percent */
    rb_settings_set_int(s, RB_PREF_REDUCED_MOTION, 0);
    rb_settings_set_int(s, RB_PREF_HIGH_CONTRAST, 0);
    rb_settings_set(s, RB_PREF_TAB_LAYOUT, "grid");

    /* Search & homepage */
    rb_settings_set(s, RB_PREF_SEARCH_ENGINE, "duckduckgo");
    rb_settings_set_int(s, RB_PREF_HOMEPAGE_ENABLED, 1);
    rb_settings_set(s, RB_PREF_HOMEPAGE_SHORTCUTS, RB_PREFS_DEFAULT_SHORTCUTS);
    rb_settings_set_int(s, RB_PREF_SHOW_PRIVACY_STATS, 1);
    rb_settings_set_int(s, RB_PREF_SHOW_RECENT_SITES, 1);
    rb_settings_set_int(s, RB_PREF_SHOW_CLOCK, 1);

    /* User agent */
    rb_settings_set(s, RB_PREF_UA_MODE, "default");
    rb_settings_set(s, RB_PREF_UA_PRESET_ID, "");
    rb_settings_set(s, RB_PREF_CUSTOM_USER_AGENT, "");

    /* DNS */
    rb_settings_set(s, RB_PREF_DNS_MODE, "system");
    rb_settings_set(s, RB_PREF_DOH_URL, "");
    rb_settings_set(s, RB_PREF_DOT_HOSTNAME, "");

    /* Privacy — compatibility-first (2026-09): annoyance shields OFF,
     * security-grade protections ON.  See rb_prefs.h. */
    rb_settings_set_int(s, RB_PREF_BLOCK_ADS, 0);
    rb_settings_set_int(s, RB_PREF_BLOCK_TRACKERS, 0);
    rb_settings_set_int(s, RB_PREF_BLOCK_CROSS_SITE, 0);
    rb_settings_set_int(s, RB_PREF_BLOCK_POPUPS, 0);
    rb_settings_set_int(s, RB_PREF_BLOCK_MALICIOUS, 1);
    rb_settings_set_int(s, RB_PREF_HTTPS_UPGRADE, 1);
    rb_settings_set_int(s, RB_PREF_BLOCK_THIRD_PARTY_COOKIES, 0);
    rb_settings_set_int(s, RB_PREF_BLOCK_MIXED_CONTENT, 0);
    /* JavaScript is NEVER disabled by default — project-wide policy. */
    rb_settings_set_int(s, RB_PREF_JAVASCRIPT, 1);
    rb_settings_set(s, RB_PREF_WEBRTC_POLICY, "restrict_local_ip");
    rb_settings_set_int(s, RB_PREF_SEARCH_SUGGESTIONS, 0);
    rb_settings_set_int(s, RB_PREF_DESKTOP_MODE_DEFAULT, 0);

    /* Language */
    rb_settings_set(s, RB_PREF_LANGUAGE_TAG, "");
    rb_settings_set(s, RB_PREF_TRANSLATE_TARGET, "id");
    rb_settings_set(s, RB_PREF_NEVER_TRANSLATE, "");

    /* Network protection */
    rb_settings_set_int(s, RB_PREF_NET_PROTECT_GLOBAL, 1);
    rb_settings_set_int(s, RB_PREF_NET_PROTECT_ENABLED, 1);

    /* Downloads */
    rb_settings_set(s, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser");

    /* Autofill */
    rb_settings_set_int(s, RB_PREF_AUTOFILL_ENABLED, 1);
}

void rb_prefs_global_defaults(rb_settings *s)
{
    if (s == NULL) {
        return;
    }
    rb_settings_set(s, RB_GPREF_DNS_MODE, "system");
    rb_settings_set(s, RB_GPREF_DOH_URL, "");
    rb_settings_set(s, RB_GPREF_DOT_HOSTNAME, "");
    rb_settings_set_int(s, RB_GPREF_NET_PROTECT_ENABLED, 1);
    rb_settings_set_int(s, RB_GPREF_SHOW_PREV_NAME, 1);
    rb_settings_set_int(s, RB_GPREF_SHOW_LAST_SEEN, 1);
    rb_settings_set_int(s, RB_GPREF_OFFER_NET_CHANGE, 1);
    rb_settings_set_int(s, RB_GPREF_OFFER_AIRPLANE, 1);
    rb_settings_set_int(s, RB_GPREF_RETENTION, 30);
    rb_settings_set(s, RB_GPREF_WARNING_BEHAVIOR, "ask_every_time");
    rb_settings_set(s, RB_GPREF_CONFLICT_SEVERITY, "informational");
    /* OFF by default; no data is collected anyway. */
    rb_settings_set_int(s, RB_GPREF_TELEMETRY, 0);
    rb_settings_set_int(s, RB_GPREF_DIAGNOSTICS, 0);
}

int rb_font_scale_percent(const rb_settings *s)
{
    int v;

    if (s == NULL) {
        return RB_FONT_SCALE_DEFAULT;
    }
    v = rb_settings_get_int(s, RB_PREF_FONT_SCALE, RB_FONT_SCALE_DEFAULT);
    /* Zero or less is what an unset or corrupt key looks like, not a request
     * for the smallest size, so it reads as the system size. */
    if (v <= 0) {
        return RB_FONT_SCALE_DEFAULT;
    }
    if (v < RB_FONT_SCALE_MIN) {
        return RB_FONT_SCALE_MIN;
    }
    if (v > RB_FONT_SCALE_MAX) {
        return RB_FONT_SCALE_MAX;
    }
    return v;
}
