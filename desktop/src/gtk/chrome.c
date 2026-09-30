/*
 * Room Browser (desktop) - GTK chrome implementation.
 *
 * GTK3 chrome in pure C11: GtkNotebook tab strip (one WebKitWebView per
 * tab), toolbar with omnibox, bookmark star and hamburger menu, keyboard
 * accelerators via GSimpleAction, plus the shared persistence glue.
 * Brave-inspired dark palette with purple accent (#A78BFA) loaded through
 * a GtkCssProvider (RB_CSS below).
 *
 * Design notes:
 *  - Array index of app->tabs == notebook page index == rb_tabs index;
 *    the switch-page handler keeps app->active_id in sync (suppressed via
 *    app->silent while the chrome itself mutates the notebook).
 *  - The per-tab close button resolves its tab by widget pointer
 *    (rb_tab_by_widget), so it keeps working after any reordering.
 *  - The hamburger menu is a classic GtkMenu attached to a GtkMenuButton
 *    (gtk_menu_button_set_popup is deprecated in favour of popovers but
 *    still fully functional on GTK 3.x; the two calls are wrapped in
 *    G_GNUC_BEGIN/END_IGNORE_DEPRECATIONS to stay warning-clean).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <gdk/gdkkeysyms.h>

#include "chrome.h"
#include "webview.h"
#include "rb_version.h"

/* Single global application state (declared extern in chrome.h). */
App g_app;

/* The JavaScript check menu item (single instance, module-static). */
static GtkWidget *g_js_item = NULL;

/* The "Show bookmarks bar" check menu item, rebuilt with the menu. */
static GtkWidget *g_bm_item = NULL;

/* Forward declarations (defined below, referenced by earlier functions). */
static void on_tab_close_clicked(GtkButton *button, gpointer user_data);
static void on_newtab_clicked(GtkButton *button, gpointer user_data);
static void rb_downloads_dir_init(App *app);
static void rb_bookmarks_bar_refresh(App *app);

/* ------------------------------------------------------------------ */
/* Chrome CSS, generated from the active profile's theme.
 *
 * The layout rules are fixed; every colour comes from rb_theme.h, so the
 * desktop wears the same 18 palettes the Android edition does and a profile
 * change repaints the chrome.  RGBA forms are emitted with GTK3's
 * rgba(r,g,b,a) syntax rather than #RRGGBBaa, which GTK 3.20 does not parse.
 *
 * The provider is kept so the stylesheet can be re-loaded in place when the
 * theme changes; the fallback (no theme yet) is the obsidian default. */

static GtkCssProvider *g_css = NULL;

/* The font the chrome would use unscaled: whatever GTK reports the first time
 * a scale is applied, captured then and kept.  rb_apply_font_scale() always
 * scales THIS rather than the name currently installed, so going 150% -> 100%
 * returns to the system size instead of compounding to 150% of 150%.
 *
 * Captured lazily rather than at start-up because GTK has to be initialised
 * first, and the first apply is the earliest point that is guaranteed. */
static char *g_base_font = NULL;

static void rb_capture_base_font(void)
{
    GtkSettings *s = gtk_settings_get_default();
    char *name = NULL;

    if (s == NULL || g_base_font != NULL) return;
    g_object_get(s, "gtk-font-name", &name, NULL);
    if (name != NULL && name[0] != '\0') {
        g_base_font = name;   /* g_object_get hands over the reference */
    } else {
        g_free(name);
    }
}

/* Applies the active profile's font scale to the UI font.
 *
 * A profile at RB_FONT_SCALE_DEFAULT gets the captured system font back
 * verbatim, so the common case leaves no trace on a setting that is
 * process-wide: the chrome renders exactly as it would with no scaling at
 * all.  Any other value scales the size the system font already had, which is
 * what makes this an accessibility control rather than a font override — a
 * user who already runs a large system font keeps it, scaled.
 *
 * gtk-font-name rather than a font-size rule in the stylesheet: it is the one
 * lever that reaches every widget the chrome builds, including the ones whose
 * text is set by the theme's CSS and the dialogs opened later. */
void rb_apply_font_scale(App *app)
{
    GtkSettings *s = gtk_settings_get_default();
    const rb_profile *p = rb_active_profile(app);
    const char *space;
    double base;
    int pct;
    int tenths;
    char *scaled;

    if (s == NULL) return;
    if (g_base_font == NULL) rb_capture_base_font();
    if (g_base_font == NULL) return;   /* nothing to scale from */

    pct = rb_font_scale_percent(p ? p->settings : NULL);
    if (pct == RB_FONT_SCALE_DEFAULT) {
        gtk_settings_set_string_property(s, "gtk-font-name", g_base_font, "rb");
        return;
    }

    /* "<family> <size>", as Pango writes it: the size follows the last space.
     * A name carrying no size at all is left alone rather than guessed at. */
    space = strrchr(g_base_font, ' ');
    if (space == NULL) return;
    base = g_ascii_strtod(space + 1, NULL);
    if (base <= 0.0) return;

    /* Point sizes are fractional (10.5, 11.5), so one decimal is kept.
     *
     * Both halves of this are deliberately locale-blind.  atof() and %f
     * follow LC_NUMERIC, and under a comma-decimal locale they would turn a
     * perfectly good "Cantarell 11" into "Cantarell 11,0" — which Pango
     * cannot parse, leaving the chrome with no font at all.  g_ascii_strtod()
     * always reads '.', and the result is built from integers so nothing
     * locale-dependent formats it. */
    tenths = (int)(base * (double)pct / 10.0 + 0.5);
    if (tenths < 1) tenths = 1;
    scaled = g_strdup_printf("%.*s %d.%d", (int)(space - g_base_font),
                             g_base_font, tenths / 10, tenths % 10);
    gtk_settings_set_string_property(s, "gtk-font-name", scaled, "rb");
    g_free(scaled);
}

static void rb_css_rgba(char out[48], unsigned int argb, double alpha_mult)
{
    int r = 0, g = 0, b = 0;
    double a = 1.0;
    rb_theme_rgb(argb, &r, &g, &b);
    a = ((double)((argb >> 24) & 0xffu) / 255.0) * alpha_mult;
    if (a > 1.0) a = 1.0;
    if (a < 0.0) a = 0.0;
    snprintf(out, 48, "rgba(%d,%d,%d,%.3f)", r, g, b, a);
}

static void rb_css_hex(char out[8], unsigned int argb)
{
    rb_theme_hex(argb, out);   /* "#RRGGBB", alpha dropped */
}

/* Builds the stylesheet for a resolved palette.  Caller frees. */
static char *rb_css_build(const rb_theme_colors *c, int radius)
{
    char bg[8], surf[8], alt[8], prim[8];
    char txt[8], dim[8], addr[8], tabbar[8], border[8], sel[8];
    char accent_faint[48], accent_soft[48], sel_soft[48];
    size_t cap = 6144;
    char *css = (char *)malloc(cap);

    if (!css) return NULL;

    rb_css_hex(bg, c->background);
    rb_css_hex(surf, c->surface);
    rb_css_hex(alt, c->surface_alt);
    rb_css_hex(prim, c->primary);
    rb_css_hex(txt, c->text_primary);
    rb_css_hex(dim, c->text_secondary);
    rb_css_hex(addr, c->address_bar);
    rb_css_hex(tabbar, c->tab_bar);
    rb_css_hex(border, c->border);
    rb_css_hex(sel, c->selection);
    rb_css_rgba(accent_faint, c->primary, 0.22);
    rb_css_rgba(accent_soft, c->primary, 0.25);
    rb_css_rgba(sel_soft, c->selection, 0.45);

    snprintf(css, cap,
        "window { background-color: %s; }\n"
        "notebook header { background-color: %s; border: none; }\n"
        "notebook header tabs tab { background-color: %s; color: %s;"
        " padding: 3px 10px 3px 12px; }\n"
        "notebook header tabs tab:checked { background-color: %s;"
        " color: %s; border-bottom: 2px solid %s; }\n"
        "notebook header tabs tab:hover { color: %s; }\n"
        ".rb-toolbar { background-color: %s; padding: 5px 7px;"
        " border-bottom: 1px solid %s; }\n"
        ".rb-btn { background-color: transparent; color: %s; border: none;"
        " padding: 2px 9px; border-radius: %dpx; }\n"
        ".rb-btn:hover { background-color: %s; color: %s; }\n"
        ".rb-btn:disabled { color: %s; }\n"
        ".rb-omni { background-color: %s; color: %s; border: 1px solid %s;"
        " border-radius: %dpx; padding: 3px 10px 5px 10px;"
        " caret-color: %s; }\n"
        ".rb-omni:focus { border-color: %s; }\n"
        ".rb-omni selection { background-color: %s; color: %s; }\n"
        ".rb-tab-close { background-color: transparent; color: %s;"
        " border: none; padding: 0 3px; border-radius: %dpx; }\n"
        ".rb-tab-close:hover { color: %s; background-color: %s; }\n"
        ".rb-dim { color: %s; }\n"
        "menu { background-color: %s; color: %s; border: 1px solid %s; }\n"
        "menu menuitem:hover { background-color: %s; color: %s; }\n",
        bg, tabbar, alt, dim, surf, txt, prim, txt,
        surf, border, txt, radius, accent_faint, txt, dim,
        addr, txt, border, radius, prim, prim, sel_soft, txt,
        dim, radius, txt, accent_soft, dim,
        surf, txt, border, accent_faint, txt);

    /* Shape and the link-target bubble, in a SECOND snprintf rather than more
     * arguments on the one above: that call's format and argument lists are
     * long and positional, and one miscounted %s there reads garbage
     * silently.
     *
     * The shape rules are overrides, so they must come after the base rules
     * — same specificity, later wins.  What they buy is Brave's silhouette:
     * a tab with rounded shoulders whose active state is flush with the
     * toolbar below it (its background already equals the toolbar's, so
     * removing the accent underline is what makes the two read as ONE
     * surface), and a pill-shaped omnibox. */
    {
        size_t used = strlen(css);
        if (used + 1200 < cap) {
            snprintf(css + used, cap - used,
                     "notebook header tabs tab { min-height: 28px;"
                     " margin: 2px 2px 0 0; border-radius: 9px 9px 0 0; }\n"
                     "notebook header tabs tab:checked { border-bottom: none; }\n"
                     ".rb-omni { border-radius: 999px; }\n"
                     ".rb-status { background-color: %s; color: %s;"
                     " border: 1px solid %s; border-radius: %dpx;"
                     " padding: 2px 8px; }\n"
                     ".rb-bmbar { background-color: %s; padding: 3px 6px;"
                     " border-bottom: 1px solid %s; }\n"
                     ".rb-bmbtn { background-color: transparent; color: %s;"
                     " border: none; padding: 2px 8px; border-radius: %dpx; }\n"
                     ".rb-bmbtn:hover { background-color: %s; color: %s; }\n",
                     surf, txt, border, radius,
                     surf, border, txt, radius, accent_faint, txt);
        }
    }
    return css;
}

/* The chrome's own dark/light preference.  GTK3 has no "is the desktop dark"
 * query, so AUTO reads gtk-application-prefer-dark-theme and falls back to
 * the theme name — the same two signals every GTK3 app uses, and an
 * approximation either way. */
static int rb_system_is_dark(void)
{
    GtkSettings *s = gtk_settings_get_default();
    gboolean prefer_dark = FALSE;
    gchar *name = NULL;
    int dark = 0;

    if (s != NULL) {
        g_object_get(s, "gtk-application-prefer-dark-theme", &prefer_dark, NULL);
        dark = prefer_dark ? 1 : 0;
        g_object_get(s, "gtk-theme-name", &name, NULL);
        if (!dark && name != NULL) {
            gchar *lower = g_ascii_strdown(name, -1);
            if (strstr(lower, "dark") != NULL || strstr(lower, "-black") != NULL) {
                dark = 1;
            }
            g_free(lower);
        }
        if (name != NULL) g_free(name);
    }
    return dark;
}

/* The theme the ACTIVE PROFILE selected.  The id lives in the profile's
 * theme_json as {"id":"<theme>"} — the same place, and the same shape, as the
 * Android edition's themeJson snapshot.  "" or a malformed snapshot means the
 * default theme, which is what rb_theme_resolve() already answers. */
const rb_theme *rb_theme_current(App *app)
{
    const rb_profile *p = rb_active_profile(app);
    const char *json = (p && p->theme_json) ? p->theme_json : "";
    size_t pos = 0;
    char *id = NULL;
    const rb_theme *t;

    if (json[0] != '\0' && rb_json_find_key(json, "id", &pos) &&
        rb_json_parse_string(json, &pos, &id) && id != NULL) {
        t = rb_theme_by_id(id);
        free(id);
        return t ? t : rb_theme_default();
    }
    return rb_theme_default();
}

/* The MODE comes from the profile's "theme" setting ("system" is the stored
 * spelling of rb_theme.h's AUTO). */
static rb_theme_mode rb_theme_mode_current(App *app)
{
    const char *m = rb_pref(app, RB_PREF_THEME, "system");
    if (m == NULL) return RB_THEME_AUTO;
    if (strcmp(m, "light") == 0)  return RB_THEME_LIGHT;
    if (strcmp(m, "dark") == 0)   return RB_THEME_DARK;
    if (strcmp(m, "amoled") == 0) return RB_THEME_AMOLED;
    return RB_THEME_AUTO;
}

/* (Re)loads the chrome stylesheet from the active profile's theme. */
void rb_css_load(App *app)
{
    const rb_theme *t = app ? rb_theme_current(app) : rb_theme_default();
    rb_theme_mode mode = app ? rb_theme_mode_current(app) : RB_THEME_DARK;
    rb_theme_colors c = rb_theme_palette(t, mode, rb_system_is_dark());
    char *css = rb_css_build(&c, t->corner_radius);

    if (css == NULL) return;
    if (g_css == NULL) {
        g_css = gtk_css_provider_new();
        {
            GdkScreen *screen = gdk_screen_get_default();
            if (screen) {
                gtk_style_context_add_provider_for_screen(screen,
                    GTK_STYLE_PROVIDER(g_css),
                    GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
            }
        }
    }
    /* GTK3 signature: (provider, data, length, GError**) — the 4-arg form;
     * the 3-arg variant is GTK4-only and fails to compile against gtk+-3.0.
     * Re-loading the same provider restyles every widget that uses it. */
    gtk_css_provider_load_from_data(g_css, css, -1, NULL);
    free(css);
}

/* ------------------------------------------------------------------ */
/* Small helpers */

char *rb_strdup(const char *s)
{
    size_t n;
    char *p;
    if (!s) return NULL;
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (!p) return NULL;
    memcpy(p, s, n);
    return p;
}

void rb_set_str(char **dst, char *owned)
{
    if (!dst) { free(owned); return; }
    free(*dst);
    *dst = owned;
}

static char *rb_path_join(const char *dir, const char *name)
{
    size_t n = strlen(dir) + strlen(name) + 2;
    char *p = (char *)malloc(n);
    if (!p) return NULL;
    snprintf(p, n, "%s/%s", dir, name);
    return p;
}

static void rb_add_class(GtkWidget *w, const char *cls)
{
    gtk_style_context_add_class(gtk_widget_get_style_context(w), cls);
}

/* ------------------------------------------------------------------ */
/* Persistence: load at startup, save on change (and a final save at quit). */

/* The name given to the profile created for a data directory that has none.
 * Android calls its first profile "Personal" too (ProfileManager.create()). */
#define RB_PROFILE_FIRST_NAME "Personal"

int rb_data_init(App *app)
{
    char *dir = rb_paths_data_dir();
    int fresh_profiles = 0;

    app->store = rb_tabs_new();
    app->history = rb_history_new();
    app->bookmarks = rb_bookmarks_new();
    app->settings = rb_settings_new();
    app->profiles = rb_profile_registry_new();
    app->switcher = rb_switch_new();
    app->https = rb_https_pending_new();
    app->downloads = rb_downloads_new();
    app->filters = rb_filters_new();
    if (!app->store || !app->history || !app->bookmarks || !app->settings ||
        !app->profiles || !app->switcher || !app->https || !app->downloads ||
        !app->filters) {
        return -1;
    }
    /* The bundled list, compiled in: no network, no update check, same hosts
     * the Android edition ships.  Loading it is what makes the filter engine
     * able to answer at all — the per-profile switches decide which of its
     * categories are consulted. */
    rb_filters_load_builtin(app->filters);
    if (!dir) return -1;

    app->path_history = rb_path_join(dir, "history.jsonl");
    app->path_bookmarks = rb_path_join(dir, "bookmarks.jsonl");
    app->path_settings = rb_path_join(dir, "settings.txt");
    app->path_profiles = rb_path_join(dir, "profiles.jsonl");
    app->path_downloads = rb_path_join(dir, "downloads.jsonl");
    if (!app->path_history || !app->path_bookmarks || !app->path_settings ||
        !app->path_profiles || !app->path_downloads) {
        rb_paths_free(dir);
        return -1;
    }

    /* The GLOBAL store is BrowserGlobalSettings: the handful of settings that
     * belong to the installation rather than to a profile (DNS overrides,
     * network-change retention, telemetry).  Everything a user toggles in the
     * menu now lives in the active profile — see rb_pref(). */
    rb_prefs_global_defaults(app->settings);
    rb_settings_load(app->settings, app->path_settings);

    rb_history_load(app->history, app->path_history);
    rb_bookmarks_load(app->bookmarks, app->path_bookmarks);
    rb_downloads_load(app->downloads, app->path_downloads);

    rb_profile_registry_load(app->profiles, app->path_profiles);
    if (rb_profile_count(app->profiles) == 0) {
        /* A data directory with no profiles.jsonl (a fresh install, or one
         * written by an older desktop build) gets the first profile here,
         * exactly as the Android edition does. */
        fresh_profiles = 1;
        if (rb_profile_create(app->profiles, RB_PROFILE_FIRST_NAME, NULL, 0, 1) < 0) {
            rb_paths_free(dir);
            return -1;
        }
    }

    {
        const rb_profile *first = rb_profile_default(app->profiles);
        if (first == NULL) { first = rb_profile_at(app->profiles, 0); }
        if (first == NULL) { rb_paths_free(dir); return -1; }
        app->active_profile_id = rb_strdup(first->id);
        if (!app->active_profile_id) { rb_paths_free(dir); return -1; }

        /* Settings written by a desktop build older than the profile registry
         * lived in settings.txt under bare keys ("home", "javascript").  Carry
         * them over rather than dropping the user's homepage and JavaScript
         * choice on the floor; the file itself is left where it is. */
        if (fresh_profiles) {
            rb_settings *ps = first->settings;
            if (ps) {
                if (rb_settings_get(app->settings, RB_PREF_HOME_LOCAL, NULL)) {
                    rb_settings_set(ps, RB_PREF_HOME_LOCAL,
                        rb_settings_get(app->settings, RB_PREF_HOME_LOCAL, ""));
                }
                if (rb_settings_get(app->settings, RB_PREF_JAVASCRIPT, NULL)) {
                    rb_settings_set_int(ps, RB_PREF_JAVASCRIPT,
                        rb_settings_get_int(app->settings, RB_PREF_JAVASCRIPT, 1));
                }
            }
        }
    }

    /* Project policy: JavaScript is NEVER disabled by default. */
    app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
    app->home_url = rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                      "https://duckduckgo.com"));
    rb_downloads_dir_init(app);

    /* A profile file from the build whose theme combo wrote the theme ID into
     * the MODE setting is repaired once, here, so a choice made with that
     * picker shows up instead of sitting inert in the wrong key. */
    rb_profile_repair_theme_setting(app->profiles);

    rb_profiles_save(app);
    rb_paths_free(dir);
    return 0;
}

/* Where downloads land: the user's Downloads folder, under the subfolder the
 * profile names in RB_PREF_DOWNLOAD_SUBFOLDER ("RoomBrowser" by default) —
 * the same place, and the same setting, as the Android edition.  Falls back
 * to <data dir>/downloads when the desktop has no Downloads special dir. */
static void rb_downloads_dir_init(App *app)
{
    const char *base = g_get_user_special_dir(G_USER_DIRECTORY_DOWNLOAD);
    const char *sub = rb_pref(app, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser");

    if (sub == NULL) sub = "RoomBrowser";
    if (base != NULL) {
        app->download_dir = (sub[0] != '\0')
            ? rb_paths_join(base, sub)
            : rb_strdup(base);
    } else {
        char *data = rb_paths_data_dir();
        app->download_dir = data ? rb_path_join(data, "downloads") : NULL;
        if (data) rb_paths_free(data);
    }
    if (app->download_dir != NULL) {
        rb_paths_mkdirs(app->download_dir);
    }
}

void rb_data_shutdown(App *app)
{
    if (app->history && app->path_history) {
        rb_history_save(app->history, app->path_history);
    }
    if (app->bookmarks && app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    if (app->settings && app->path_settings) {
        rb_settings_save(app->settings, app->path_settings);
    }
    if (app->downloads && app->path_downloads) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
    rb_profiles_save(app);
}

void rb_data_free(App *app)
{
    free(app->path_history);   app->path_history = NULL;
    free(app->path_bookmarks); app->path_bookmarks = NULL;
    free(app->path_settings);  app->path_settings = NULL;
    free(app->path_profiles);  app->path_profiles = NULL;
    free(app->path_downloads); app->path_downloads = NULL;
    free(app->download_dir);   app->download_dir = NULL;
    free(app->home_url);       app->home_url = NULL;
    free(app->active_profile_id); app->active_profile_id = NULL;
    if (app->store)     { rb_tabs_free(app->store);         app->store = NULL; }
    if (app->history)   { rb_history_free(app->history);    app->history = NULL; }
    if (app->bookmarks) { rb_bookmarks_free(app->bookmarks); app->bookmarks = NULL; }
    if (app->downloads) { rb_downloads_free(app->downloads); app->downloads = NULL; }
    if (app->settings)  { rb_settings_free(app->settings);  app->settings = NULL; }
    if (app->profiles)  { rb_profile_registry_free(app->profiles); app->profiles = NULL; }
    if (app->switcher)  { rb_switch_free(app->switcher);    app->switcher = NULL; }
    if (app->https)     { rb_https_pending_free(app->https); app->https = NULL; }
    if (app->filters)   { rb_filters_free(app->filters); app->filters = NULL; }
    rb_gw_content_blocking_clear(app);
    if (app->cb_store)  { g_object_unref(app->cb_store); app->cb_store = NULL; }
    free(app->tabs);
    app->tabs = NULL;
    app->tabs_n = 0;
    app->tabs_cap = 0;
}

/* ------------------------------------------------------------------ */
/* Profile-scoped settings.
 *
 * Android reads every feature switch off the active ProfileSettings.  These
 * are the desktop's spelling of that read, so a feature never has to know
 * whether a profile exists, and a missing key falls back to the same default
 * the Android edition uses. */

const rb_profile *rb_active_profile(App *app)
{
    if (!app || !app->profiles || !app->active_profile_id) { return NULL; }
    return rb_profile_by_id(app->profiles, app->active_profile_id);
}

const char *rb_pref(App *app, const char *key, const char *fallback)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p || !p->settings) { return fallback; }
    return rb_settings_get(p->settings, key, fallback);
}

int rb_pref_int(App *app, const char *key, int fallback)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p || !p->settings) { return fallback; }
    return rb_settings_get_int(p->settings, key, fallback);
}

void rb_pref_set(App *app, const char *key, const char *value)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p) { return; }
    /* rb_profile_by_id hands out a const row, but the settings store it points
     * at is not itself const: the pointer member is what carries the
     * qualification, so reading it out yields a mutable rb_settings *.  The
     * registry owns the store, so it is saved with the profile. */
    rb_settings_set(p->settings, key, value);
    rb_profiles_save(app);
}

void rb_pref_set_int(App *app, const char *key, int value)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p) { return; }
    rb_settings_set_int(p->settings, key, value);
    rb_profiles_save(app);
}

void rb_profiles_save(App *app)
{
    if (app && app->profiles && app->path_profiles) {
        rb_profile_registry_save(app->profiles, app->path_profiles);
    }
}

/* The active profile's effective User-Agent, malloc'd — or NULL when the
 * engine default should be sent untouched.  Mirrors UserAgents.
 * effectiveUserAgent(): the profile's ua_mode picks between the engine
 * default, one of the presets, and a free-form string. */
char *rb_ua_current(App *app)
{
    const char *mode = rb_pref(app, RB_PREF_UA_MODE, "default");
    rb_ua_mode m = RB_UA_MODE_DEFAULT;

    if (mode != NULL) {
        if (strcmp(mode, "preset") == 0) {
            m = RB_UA_MODE_PRESET;
        } else if (strcmp(mode, "custom") == 0) {
            m = RB_UA_MODE_CUSTOM;
        }
    }
    return rb_ua_effective(m, rb_pref(app, RB_PREF_UA_PRESET_ID, NULL),
                           rb_pref(app, RB_PREF_CUSTOM_USER_AGENT, NULL));
}

rb_filter_options rb_filter_opts(App *app)
{
    /* The compatibility-first defaults the engine ships, then the profile's
     * own overrides.  Reading through rb_pref_int means a profile that has
     * never touched these keys gets exactly the engine default. */
    rb_filter_options o = rb_filter_options_default();

    o.block_ads        = rb_pref_int(app, RB_PREF_BLOCK_ADS, o.block_ads);
    o.block_trackers   = rb_pref_int(app, RB_PREF_BLOCK_TRACKERS, o.block_trackers);
    o.block_cross_site = rb_pref_int(app, RB_PREF_BLOCK_CROSS_SITE, o.block_cross_site);
    o.block_malicious  = rb_pref_int(app, RB_PREF_BLOCK_MALICIOUS, o.block_malicious);
    o.block_popups     = rb_pref_int(app, RB_PREF_BLOCK_POPUPS, o.block_popups);
    return o;
}

/* ------------------------------------------------------------------ */
/* Tab resolution */

GtkTab *rb_tab_by_widget(App *app, GtkWidget *w)
{
    int i;
    if (!app || !w) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if ((GtkWidget *)app->tabs[i].wv == w ||
            app->tabs[i].label == w ||
            app->tabs[i].close_btn == w ||
            app->tabs[i].icon == w) {
            return &app->tabs[i];
        }
    }
    return NULL;
}

GtkTab *rb_active_tab(App *app)
{
    int i;
    if (!app) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == app->active_id) return &app->tabs[i];
    }
    return NULL;
}

/* Edge of the square a tab favicon is scaled into.  16px matches what Brave
 * and Chrome use, and leaves the tab's title the rest of the width. */
#define RB_TAB_ICON 16

void rb_tab_favicon_set(GtkTab *tab, WebKitWebView *wv)
{
    cairo_surface_t *surface;
    GdkPixbuf *pix = NULL;
    int w = 0, h = 0;

    if (!tab || !tab->icon || !wv) return;

    surface = webkit_web_view_get_favicon(wv);
    if (surface) {
        w = cairo_image_surface_get_width(surface);
        h = cairo_image_surface_get_height(surface);
        if (w > 0 && h > 0) {
            pix = gdk_pixbuf_get_from_surface(surface, 0, 0, w, h);
        }
    }

    if (pix) {
        /* Fit the icon into RB_TAB_ICON square, preserving the aspect ratio:
         * a non-square favicon scaled to 16x16 outright would be stretched. */
        int side = (w > h) ? w : h;
        int iw = w * RB_TAB_ICON / side;
        int ih = h * RB_TAB_ICON / side;
        GdkPixbuf *scaled;
        if (iw < 1) iw = 1;
        if (ih < 1) ih = 1;
        scaled = gdk_pixbuf_scale_simple(pix, iw, ih, GDK_INTERP_BILINEAR);
        g_object_unref(pix);
        if (scaled) {
            /* set_from_pixbuf takes its own reference, so ours goes here. */
            gtk_image_set_from_pixbuf(GTK_IMAGE(tab->icon), scaled);
            g_object_unref(scaled);
            gtk_widget_set_visible(tab->icon, TRUE);
            return;
        }
    }

    /* No favicon (or one we could not decode): hide the image rather than
     * leave a blank square, so the title keeps the full width. */
    gtk_image_clear(GTK_IMAGE(tab->icon));
    gtk_widget_set_visible(tab->icon, FALSE);
}

void rb_status_show(App *app, const char *text)
{
    if (!app || !app->status) return;
    if (text && text[0]) {
        gtk_label_set_text(GTK_LABEL(app->status), text);
        gtk_widget_show(app->status);
    } else {
        gtk_label_set_text(GTK_LABEL(app->status), "");
        gtk_widget_hide(app->status);
    }
}

static rb_tab *rb_store_tab(App *app)
{
    return app->active_id ? rb_tabs_get(app->store, app->active_id) : NULL;
}

/* ------------------------------------------------------------------ */
/* UI refresh helpers */

/* The connection indicator Brave puts at the leading edge of the omnibox.
 * HTTPS gets the padlock and plain HTTP the warning; anything else — an
 * internal page, or the blank new tab — gets NO icon rather than a
 * misleading one.  The names come from the icon theme, so a theme without
 * them shows nothing instead of failing. */
static void rb_update_omni_security(App *app, const char *url)
{
    const char *icon = NULL;
    const char *tip = NULL;

    if (url && g_str_has_prefix(url, "https://")) {
        icon = "channel-secure-symbolic";
        tip = "Connection is encrypted";
    } else if (url && g_str_has_prefix(url, "http://")) {
        icon = "channel-insecure-symbolic";
        tip = "Not secure \xE2\x80\x94 this connection is not encrypted";
    }

    gtk_entry_set_icon_from_icon_name(GTK_ENTRY(app->omnibox),
                                      GTK_ENTRY_ICON_PRIMARY, icon);
    gtk_entry_set_icon_tooltip_text(GTK_ENTRY(app->omnibox),
                                    GTK_ENTRY_ICON_PRIMARY,
                                    icon ? tip : NULL);
}

void rb_update_omni(App *app, const char *url)
{
    if (!app || !app->omnibox) return;
    gtk_entry_set_text(GTK_ENTRY(app->omnibox), url ? url : "");
    rb_update_omni_security(app, url);
}

void rb_update_titlebar(App *app)
{
    rb_tab *t = rb_store_tab(app);
    if (t && t->title && t->title[0]) {
        char buf[600];
        snprintf(buf, sizeof buf, "%s \xE2\x80\x94 Room Browser", t->title);
        gtk_window_set_title(GTK_WINDOW(app->win), buf);
    } else {
        gtk_window_set_title(GTK_WINDOW(app->win), "Room Browser");
    }
}

void rb_update_nav(App *app)
{
    gboolean back = FALSE, fwd = FALSE;
    rb_gw_can_nav(app, &back, &fwd);
    gtk_widget_set_sensitive(app->back, back);
    gtk_widget_set_sensitive(app->fwd, fwd);
}

void rb_update_star(App *app)
{
    rb_tab *t = rb_store_tab(app);
    int on = (t && t->url && rb_bookmarks_contains(app->bookmarks, t->url));
    gtk_button_set_label(GTK_BUTTON(app->star), on ? "\xE2\x98\x85" : "\xE2\x98\x86");
}

void rb_update_reloadbtn(App *app)
{
    gtk_button_set_label(GTK_BUTTON(app->reload), app->loading ? "\xC3\x97" : "\xE2\x9F\xB3");
}

/* ------------------------------------------------------------------ */
/* The page-load indicator.
 *
 * A 2px accent segment sliding along the very bottom of the toolbar while a
 * page is loading, and nothing at all when none is.  It mirrors the Win32
 * edition's strip: deliberately minimal — no track, no text, no percentage —
 * and drawn in its own 2px-tall strip between the toolbar and the page, so it
 * never covers anything being read and a running animation redraws two rows
 * rather than the window.  The reload button flipping to a stop glyph stays
 * the authoritative signal; this only makes a slow load visible at a glance.
 *
 * The colour is resolved per frame from the active profile's theme rather
 * than cached, so a theme change during a load is picked up for free. */

#define RB_PROGRESS_H      2
#define RB_PROGRESS_MS     16      /* ~60 Hz */
#define RB_PROGRESS_STEPS  140     /* ticks for one left-to-right sweep */
#define RB_PROGRESS_DIV    4       /* segment width = strip width / this */

static int   g_prog_on;            /* 1 while the animation timeout is armed */
static int   g_prog_phase;         /* 0..RB_PROGRESS_STEPS */
static guint g_prog_timer;         /* the timeout source, 0 when not armed */

static gboolean rb_progress_draw(GtkWidget *w, cairo_t *cr, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_theme_colors c;
    double wd, seg_w, x;

    if (app == NULL || !app->loading) return FALSE;
    wd = (double)gtk_widget_get_allocated_width(w);
    if (wd <= 0.0) return FALSE;

    seg_w = wd / RB_PROGRESS_DIV;
    if (seg_w < 32.0) seg_w = 32.0;
    /* Enters from the left edge, leaves past the right edge, repeats — the
     * standard indeterminate sweep, with no notion of "how far along". */
    x = ((double)g_prog_phase * (wd + seg_w)) / (double)RB_PROGRESS_STEPS - seg_w;

    c = rb_theme_palette(rb_theme_current(app), rb_theme_mode_current(app),
                         rb_system_is_dark());
    cairo_set_source_rgba(cr,
        (double)((c.primary >> 16) & 0xffu) / 255.0,
        (double)((c.primary >>  8) & 0xffu) / 255.0,
        (double)( c.primary        & 0xffu) / 255.0,
        1.0);
    cairo_rectangle(cr, x, 0.0, seg_w, (double)RB_PROGRESS_H);
    cairo_fill(cr);
    return FALSE;
}

static gboolean rb_progress_tick(gpointer user_data)
{
    App *app = (App *)user_data;

    g_prog_phase++;
    if (g_prog_phase > RB_PROGRESS_STEPS) g_prog_phase = 0;
    if (app != NULL && app->prog_area != NULL) {
        gtk_widget_queue_draw(app->prog_area);
    }
    return G_SOURCE_CONTINUE;
}

/* The strip is destroyed with the window, but a load can still be in flight
 * at that point (teardown calls rb_set_loading(app, 0) itself).  Dropping the
 * pointer and the timeout here is what keeps a later queue_draw — or the next
 * tick — from touching freed memory. */
static void rb_progress_area_gone(GtkWidget *w, gpointer user_data)
{
    App *app = (App *)user_data;

    if (app != NULL && app->prog_area == w) app->prog_area = NULL;
    if (g_prog_timer != 0) {
        g_source_remove(g_prog_timer);
        g_prog_timer = 0;
    }
    g_prog_on = 0;
}

/* Starts the sweep while a load is in flight, and on the way out clears the
 * strip by queuing one last draw with g_prog_on already 0. */
static void rb_progress_sync(App *app)
{
    int want = app->loading ? 1 : 0;

    if (want == g_prog_on) return;
    g_prog_on = want;
    g_prog_phase = 0;
    if (want) {
        g_prog_timer = g_timeout_add(RB_PROGRESS_MS, rb_progress_tick, app);
    } else if (g_prog_timer != 0) {
        g_source_remove(g_prog_timer);
        g_prog_timer = 0;
    }
    if (app->prog_area != NULL) gtk_widget_queue_draw(app->prog_area);
}

/* The one place app->loading changes.  Everything that shows loading state —
 * the reload/stop glyph and the progress strip — is driven from here, so the
 * call sites (navigation starting, navigation done, tabs torn down) cannot
 * disagree about whether a page is loading. */
void rb_set_loading(App *app, int on)
{
    app->loading = on ? 1 : 0;
    rb_update_reloadbtn(app);
    rb_progress_sync(app);
}

void rb_update_all(App *app)
{
    rb_tab *t = rb_store_tab(app);
    rb_update_omni(app, t && t->url ? t->url : "");
    rb_update_titlebar(app);
    rb_update_nav(app);
    rb_update_star(app);
    rb_update_reloadbtn(app);
}

/* ------------------------------------------------------------------ */
/* Actions */

static void rb_do_reload_or_stop(App *app)
{
    if (app->loading) rb_gw_stop(app);
    else              rb_gw_reload(app);
}

void rb_do_new_tab(App *app)
{
    rb_do_add_tab(app, NULL);
}

/* Opens a tab at `url`, or at the profile's homepage when `url` is NULL or
 * blank.  Everything that adds a tab goes through here so the notebook, the
 * app->tabs array and the rb_tabs store can never disagree on the index. */
void rb_do_add_tab(App *app, const char *url)
{
    WebKitWebView *wv;
    GtkWidget *hbox, *lbl, *close, *icon;
    const char *home = app->home_url ? app->home_url : "https://duckduckgo.com";
    const char *target = (url && url[0]) ? url : home;
    long id;
    int idx;

    wv = rb_gw_new_view(app);
    if (!wv) return;

    hbox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    lbl = gtk_label_new("New Tab");
    gtk_label_set_ellipsize(GTK_LABEL(lbl), PANGO_ELLIPSIZE_END);
    gtk_label_set_width_chars(GTK_LABEL(lbl), 10);
    /* The favicon slot.  It starts empty and hidden and is filled in by the
     * view's "notify::favicon" handler, so a page that never publishes one
     * costs the tab no width at all. */
    icon = gtk_image_new();
    gtk_widget_set_size_request(icon, RB_TAB_ICON, RB_TAB_ICON);
    gtk_widget_set_valign(icon, GTK_ALIGN_CENTER);
    close = gtk_button_new_with_label("\xC3\x97");
    rb_add_class(close, "rb-tab-close");
    g_signal_connect(close, "clicked", G_CALLBACK(on_tab_close_clicked), app);
    gtk_box_pack_start(GTK_BOX(hbox), icon, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(hbox), lbl, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(hbox), close, FALSE, FALSE, 0);

    if (app->tabs_n == app->tabs_cap) {
        int cap = app->tabs_cap ? app->tabs_cap * 2 : 8;
        GtkTab *tabs = (GtkTab *)realloc(app->tabs, (size_t)cap * sizeof(GtkTab));
        if (!tabs) {
            gtk_widget_destroy(hbox);
            gtk_widget_destroy(GTK_WIDGET(wv));
            return;
        }
        app->tabs = tabs;
        app->tabs_cap = cap;
    }

    id = rb_tabs_add(app->store, "New Tab", target, rb_profile_now_ms());
    app->tabs[app->tabs_n].id = id;
    app->tabs[app->tabs_n].wv = wv;
    app->tabs[app->tabs_n].label = lbl;
    app->tabs[app->tabs_n].close_btn = close;
    app->tabs[app->tabs_n].icon = icon;
    app->tabs_n++;

    app->silent = 1;
    idx = gtk_notebook_append_page(GTK_NOTEBOOK(app->notebook), GTK_WIDGET(wv), hbox);
    app->silent = 0;
    gtk_widget_show_all(hbox);
    gtk_widget_show(GTK_WIDGET(wv));

    app->active_id = id;
    if (idx >= 0) {
        gtk_notebook_set_current_page(GTK_NOTEBOOK(app->notebook), idx);
    }
    rb_update_all(app);
    webkit_web_view_load_uri(wv, target);
}

void rb_do_close_tab_id(App *app, long id)
{
    int i;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == id) break;
    }
    if (i >= app->tabs_n) return;

    app->silent = 1;
    gtk_notebook_remove_page(GTK_NOTEBOOK(app->notebook), i);
    app->silent = 0;
    memmove(&app->tabs[i], &app->tabs[i + 1],
            (size_t)(app->tabs_n - i - 1) * sizeof(app->tabs[0]));
    app->tabs_n--;
    rb_tabs_close(app->store, id, rb_profile_now_ms());
    if (app->active_id == id) app->active_id = 0;

    if (app->tabs_n == 0) {
        rb_do_new_tab(app);
        return;
    }
    if (app->active_id == 0) {
        gint cur = gtk_notebook_get_current_page(GTK_NOTEBOOK(app->notebook));
        if (cur >= 0 && cur < app->tabs_n) {
            app->active_id = app->tabs[cur].id;
        }
    }
    rb_update_all(app);
}

void rb_do_navigate(App *app, const char *url)
{
    rb_tab *t;
    char *upgraded = NULL;
    int did_upgrade = 0;
    const char *target = url;

    if (!app || !url || !url[0]) return;
    t = rb_store_tab(app);
    if (!t) return;

    /* HTTPS-First.  The omnibox already sends a bare domain straight to
     * https, so what this catches is everything else that is still plain
     * http at load time: a typed http:// URL, a bookmark or homepage entry,
     * a session-restored tab.  The upgrade is recorded so that the failure
     * handler can retry the original once — see rb_gw_new_view's load-failed.
     * With the setting off, http URLs load as typed, exactly as on Android. */
    if (app->https && rb_pref_int(app, RB_PREF_HTTPS_UPGRADE, 1)) {
        upgraded = rb_url_upgrade_to_https(url, &did_upgrade);
        if (did_upgrade && upgraded) {
            rb_https_register(app->https, upgraded, url);
            target = upgraded;
        }
    }

    rb_set_str(&t->url, rb_strdup(target));
    rb_set_loading(app, 1);
    rb_update_omni(app, target);
    rb_gw_navigate(app, target);
    free(upgraded);
}

/* ------------------------------------------------------------------ */
/* The bookmarks bar
 *
 * Brave's bookmarks bar, built from the two shapes the core's ordering
 * already gives us: a bookmark that NAMES a folder becomes a folder menu
 * button, and one without a folder becomes a plain button.  rb_bookmarks'
 * display order groups by folder first (BookmarkDao.observeAll), so folder
 * rows arrive contiguously and one forward walk builds every group — no
 * grouping pass here that could disagree with the order the manager screens
 * show.
 *
 * The bar hides itself when it has nothing to draw and when the profile turns
 * it off, so an empty or disabled bar costs no vertical space.  Buttons mirror
 * Brave's behaviour: left-click navigates the current tab, middle-click opens
 * a background tab, right-click offers the two things a bookmark bar is for.
 */

/* Where a button or menu item keeps its URL.  Object data rather than a
 * captured closure: GTK3 has no closure for a plain callback, and the URL is
 * owned by the store, which any bookmark edit can rewrite under us — so each
 * widget holds its own copy. */
#define RB_BM_URL_KEY "rb-bm-url"

static void rb_bm_children_free(GtkWidget *box)
{
    GList *kids = gtk_container_get_children(GTK_CONTAINER(box));
    GList *it;
    for (it = kids; it != NULL; it = it->next) {
        gtk_widget_destroy(GTK_WIDGET(it->data));
    }
    g_list_free(kids);
}

static const char *rb_bm_url_of(GtkWidget *w)
{
    return (const char *)g_object_get_data(G_OBJECT(w), RB_BM_URL_KEY);
}

static void rb_bm_take_url(GtkWidget *w, const char *url)
{
    g_object_set_data_full(G_OBJECT(w), RB_BM_URL_KEY, g_strdup(url), g_free);
}

static void rb_bm_open(GtkWidget *widget, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(widget);
    if (url && url[0]) rb_do_navigate(app, url);
}

/* Middle-click, the way a Brave bookmark button opens a background tab. */
static gboolean rb_bm_pressed(GtkWidget *widget, GdkEventButton *event,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(widget);
    if (event->button == 2 && url && url[0]) {
        rb_do_add_tab(app, url);
        return TRUE;
    }
    return FALSE;
}

static void rb_bm_open_new(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(GTK_WIDGET(item));
    if (url && url[0]) rb_do_add_tab(app, url);
}

static void rb_bm_remove(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(GTK_WIDGET(item));
    if (!url || !url[0]) return;
    rb_bookmarks_delete_url(app->bookmarks, url);
    if (app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    rb_update_star(app);
    rb_bookmarks_bar_refresh(app);
}

static gboolean rb_bm_context(GtkWidget *widget, GdkEvent *event,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    GdkEventButton *be = (GdkEventButton *)event;
    const char *url = rb_bm_url_of(widget);
    GtkWidget *menu, *item;

    if (event->type != GDK_BUTTON_PRESS || be->button != 3) return FALSE;
    if (!url || !url[0]) return FALSE;

    menu = gtk_menu_new();

    item = gtk_menu_item_new_with_label("Open in new tab");
    rb_bm_take_url(item, url);
    g_signal_connect(item, "activate", G_CALLBACK(rb_bm_open_new), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Remove bookmark");
    rb_bm_take_url(item, url);
    g_signal_connect(item, "activate", G_CALLBACK(rb_bm_remove), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_widget_show_all(menu);
    /* A popup menu is not owned by anything, so it has to destroy itself once
     * a choice has been made or dismissed. */
    g_signal_connect_swapped(menu, "selection-done",
                             G_CALLBACK(gtk_widget_destroy), menu);
    gtk_menu_popup_at_pointer(GTK_MENU(menu), event);
    return TRUE;
}

static GtkWidget *rb_bm_button(App *app, const rb_bookmark *bm)
{
    const char *text = (bm->title && bm->title[0]) ? bm->title : bm->url;
    GtkWidget *btn = gtk_button_new_with_label(text);
    GtkWidget *lbl = gtk_bin_get_child(GTK_BIN(btn));

    gtk_button_set_relief(GTK_BUTTON(btn), GTK_RELIEF_NONE);
    rb_add_class(btn, "rb-bmbtn");
    if (lbl) {
        gtk_label_set_ellipsize(GTK_LABEL(lbl), PANGO_ELLIPSIZE_END);
        gtk_label_set_max_width_chars(GTK_LABEL(lbl), 24);
    }
    gtk_widget_set_tooltip_text(btn, bm->url);
    rb_bm_take_url(btn, bm->url);
    g_signal_connect(btn, "clicked", G_CALLBACK(rb_bm_open), app);
    g_signal_connect(btn, "button-press-event", G_CALLBACK(rb_bm_pressed), app);
    g_signal_connect(btn, "button-press-event", G_CALLBACK(rb_bm_context), app);
    return btn;
}

static void rb_bookmarks_bar_refresh(App *app)
{
    int i, n, shown = 0;

    if (!app || !app->bmbar) return;

    rb_bm_children_free(app->bmbar);

    if (!rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1)) {
        gtk_widget_hide(app->bmbar);
        return;
    }

    n = rb_bookmarks_count(app->bookmarks);
    for (i = 0; i < n; ) {
        const rb_bookmark *bm = rb_bookmarks_at(app->bookmarks, i);
        if (!bm || !bm->url || !bm->url[0]) { i++; continue; }

        if (bm->folder && bm->folder[0]) {
            /* Folder rows are contiguous, so this group runs until the folder
             * name changes — or until a folder-less row, which sorts last. */
            const char *folder = bm->folder;
            GtkWidget *menu = gtk_menu_new();
            GtkWidget *btn = gtk_menu_button_new();

            while (i < n) {
                const rb_bookmark *f = rb_bookmarks_at(app->bookmarks, i);
                GtkWidget *item;
                if (!f || !f->url || !f->url[0]) { i++; continue; }
                if (!f->folder || strcmp(f->folder, folder) != 0) break;
                item = gtk_menu_item_new_with_label(
                    (f->title && f->title[0]) ? f->title : f->url);
                rb_bm_take_url(item, f->url);
                g_signal_connect(item, "activate",
                                 G_CALLBACK(rb_bm_open), app);
                gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
                i++;
            }

            gtk_widget_show_all(menu);
            gtk_button_set_label(GTK_BUTTON(btn), folder);
            gtk_button_set_relief(GTK_BUTTON(btn), GTK_RELIEF_NONE);
            rb_add_class(btn, "rb-bmbtn");
            gtk_widget_set_tooltip_text(btn, folder);
            /* Same deprecation as rb_build_menu: the classic GtkMenu is kept
             * deliberately, and is fully functional on GTK 3.x. */
            G_GNUC_BEGIN_IGNORE_DEPRECATIONS
            gtk_menu_button_set_popup(GTK_MENU_BUTTON(btn), menu);
            G_GNUC_END_IGNORE_DEPRECATIONS
            /* The button does not own what set_popup() attached, and this bar
             * throws its buttons away on every refresh — so the menu is tied
             * to the button's lifetime explicitly rather than left to leak a
             * widget tree per starred page. */
            g_signal_connect_swapped(btn, "destroy",
                                     G_CALLBACK(gtk_widget_destroy), menu);
            gtk_box_pack_start(GTK_BOX(app->bmbar), btn, FALSE, FALSE, 0);
        } else {
            gtk_box_pack_start(GTK_BOX(app->bmbar), rb_bm_button(app, bm),
                               FALSE, FALSE, 0);
            i++;
        }
        shown++;
    }

    /* A child packed into an already-shown container starts hidden, so the
     * refresh shows its own work; and a bar with nothing in it goes away
     * rather than leaving a bare strip of background. */
    if (shown) gtk_widget_show_all(app->bmbar);
    else gtk_widget_hide(app->bmbar);
}

void rb_do_toggle_bookmark(App *app)
{
    rb_tab *t = rb_store_tab(app);
    if (!t || !t->url || !t->url[0]) return;
    /* BrowserViewModel.toggleBookmark, policy included: the same call stars
     * and unstars, and a blank title falls back to the URL. */
    (void)rb_bookmarks_toggle(app->bookmarks, t->url,
                              t->title && t->title[0] ? t->title : t->url,
                              rb_profile_now_ms());
    if (app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    rb_update_star(app);
    /* Starring is how a page reaches the bar, so the bar follows it live. */
    rb_bookmarks_bar_refresh(app);
}

/* ------------------------------------------------------------------ */
/* Profile switching
 *
 * The seven-step protocol lives in rb_switch (a port of
 * ProfileSwitchStateMachine); this is the platform half.  Android restarts
 * the browser process at the end because WebView's data-directory suffix is
 * process-wide — on GTK each profile owns a WebKitWebContext built on its own
 * WebsiteDataManager, so the same protocol runs in-process and the destroy
 * before load rule still holds: a context is never reused for another
 * profile. */

/* Closed tabs and the tab list of a profile are desktop-only keys: Android
 * keeps tabs in a per-profile Room database, the desktop keeps them in the
 * profile's own settings. */
#define RB_PREF_OPEN_TABS_LOCAL  "open_tabs"    /* '\n'-separated URLs */
#define RB_PREF_ACTIVE_TAB_LOCAL "active_tab"   /* index into open_tabs  */

static void rb_tabs_save_state(App *app)
{
    size_t cap = 1;
    char *joined;
    char *w;
    int i;
    int active = 0;
    int written = 0;

    /* Sizing pass, then one write: the URLs can be long and there are as
     * many of them as the user has tabs. */
    for (i = 0; i < app->tabs_n; i++) {
        rb_tab *t = rb_tabs_get(app->store, app->tabs[i].id);
        if (t && t->url && t->url[0]) cap += strlen(t->url) + 1;
    }
    joined = (char *)malloc(cap);
    if (joined == NULL) return;
    w = joined;
    for (i = 0; i < app->tabs_n; i++) {
        rb_tab *t = rb_tabs_get(app->store, app->tabs[i].id);
        size_t n;
        if (!t || !t->url || !t->url[0]) continue;
        /* The index has to be into what was WRITTEN, not into app->tabs:
         * the restore reads it back positionally. */
        if (app->tabs[i].id == app->active_id) active = written;
        n = strlen(t->url);
        memcpy(w, t->url, n);
        w += n;
        *w++ = '\n';
        written++;
    }
    *w = '\0';

    rb_pref_set(app, RB_PREF_OPEN_TABS_LOCAL, joined);
    rb_pref_set_int(app, RB_PREF_ACTIVE_TAB_LOCAL, active);
    free(joined);
}

static void rb_tabs_restore_state(App *app)
{
    const char *joined = rb_pref(app, RB_PREF_OPEN_TABS_LOCAL, NULL);
    int want = rb_pref_int(app, RB_PREF_ACTIVE_TAB_LOCAL, 0);
    int opened = 0;

    if (joined != NULL && joined[0] != '\0') {
        const char *a = joined;
        while (*a != '\0') {
            const char *b = strchr(a, '\n');
            size_t n = (b != NULL) ? (size_t)(b - a) : strlen(a);
            if (n > 0) {
                char *url = (char *)malloc(n + 1);
                if (url == NULL) break;
                memcpy(url, a, n);
                url[n] = '\0';
                rb_do_add_tab(app, url);
                free(url);
                opened++;
            }
            if (b == NULL) break;
            a = b + 1;
        }
    }
    if (opened == 0) {
        rb_do_add_tab(app, NULL);   /* a fresh profile opens on the homepage */
        return;
    }
    if (want >= 0 && want < app->tabs_n) {
        app->active_id = app->tabs[want].id;
        app->silent = 1;
        gtk_notebook_set_current_page(GTK_NOTEBOOK(app->notebook), want);
        app->silent = 0;
        rb_update_all(app);
    }
}

/* Removes every tab and its view.  The views must be gone before the context
 * they were built on is released. */
static void rb_tabs_destroy_all(App *app)
{
    app->silent = 1;
    while (app->tabs_n > 0) {
        gtk_notebook_remove_page(GTK_NOTEBOOK(app->notebook), app->tabs_n - 1);
        app->tabs_n--;
    }
    app->silent = 0;
    app->active_id = 0;
    rb_set_loading(app, 0);
    while (rb_tabs_count(app->store) > 0) {
        const rb_tab *t = rb_tabs_at(app->store, 0);
        long id;
        if (t == NULL) break;
        id = t->id;   /* close() frees the row this pointer names */
        rb_tabs_close(app->store, id, rb_profile_now_ms());
    }
    rb_tabs_forget_closed(app->store);
    rb_https_clear(app->https);
    rb_update_all(app);
}

static void rb_msg(App *app, GtkMessageType type, const char *title,
                   const char *body)
{
    GtkWidget *dlg;

    /* A policy decision can fire before the window exists; a message that
     * cannot be shown must still not be lost. */
    if (app == NULL || app->win == NULL) {
        fprintf(stderr, "roombrowser: %s%s%s\n", title ? title : "",
                (body && body[0]) ? ": " : "", (body && body[0]) ? body : "");
        return;
    }
    dlg = gtk_message_dialog_new(GTK_WINDOW(app->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        type, GTK_BUTTONS_CLOSE, "%s", title ? title : "");
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dlg), "%s",
                                             body ? body : "");
    g_signal_connect_swapped(dlg, "response", G_CALLBACK(gtk_widget_destroy), dlg);
    gtk_widget_show(dlg);
}

void rb_warn(App *app, const char *title, const char *body)
{
    rb_msg(app, GTK_MESSAGE_WARNING, title, body);
}

static void rb_switch_error_dialog(App *app, const char *title,
                                   const char *body)
{
    rb_msg(app, GTK_MESSAGE_ERROR, title, body);
}

void rb_do_switch_profile(App *app, const char *to_id)
{
    char from_id[RB_PROFILE_ID_LEN + 1];
    int rc;
    int step;

    if (app == NULL || app->switcher == NULL || to_id == NULL) return;
    if (app->active_profile_id == NULL) return;
    if (strcmp(app->active_profile_id, to_id) == 0) return;

    /* Copy the id out: the switch replaces app->active_profile_id, and the
     * registry row it points into can be relocated by a mutation. */
    snprintf(from_id, sizeof from_id, "%s", app->active_profile_id);

    /* Begin requires IDLE, and a finished switch stays COMPLETE until it is
     * reset — so the log is cleared here rather than before each step. */
    rb_switch_reset(app->switcher);
    rc = rb_switch_begin(app->switcher, from_id, to_id);
    if (rc != 0) {
        const char *why = (rc == RB_SWITCH_ERR_BUSY) ? "a switch is already running"
                        : (rc == RB_SWITCH_ERR_SAME) ? "that profile is already active"
                                                     : "no profile was given";
        rb_switch_error_dialog(app, "Cannot switch profile", why);
        return;
    }

    while ((step = rb_switch_step_of(app->switcher)) >= 0) {
        switch (step) {
        case RB_SWITCH_STOP_NAVIGATION:
            {
                int i;
                for (i = 0; i < app->tabs_n; i++) {
                    if (app->tabs[i].wv) {
                        webkit_web_view_stop_loading(app->tabs[i].wv);
                    }
                }
            }
            break;
        case RB_SWITCH_SAVE_TAB_STATE:
            /* The OLD profile is still active, so the tabs land in its own
             * settings — which is the whole point of the step. */
            rb_tabs_save_state(app);
            break;
        case RB_SWITCH_DESTROY_BROWSER_CONTEXT:
            rb_tabs_destroy_all(app);
            rb_gw_context_free(app);
            break;
        case RB_SWITCH_FLUSH_PROFILE_STATE:
            rb_data_shutdown(app);
            break;
        case RB_SWITCH_RELEASE_PROFILE_RESOURCES:
            /* The in-memory stores that were the old profile's view of the
             * world.  History and bookmarks are installation-wide on the
             * desktop, so they stay; the session state does not. */
            rb_https_clear(app->https);
            break;
        case RB_SWITCH_LOAD_NEW_PROFILE_CONTEXT:
            rb_set_str(&app->active_profile_id, rb_strdup(to_id));
            rb_profile_touch(app->profiles, to_id);
            rb_gw_context_new(app);
            /* Re-read everything the new profile owns. */
            app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
            rb_set_str(&app->home_url,
                       rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                         "https://duckduckgo.com")));
            rb_set_str(&app->download_dir, NULL);
            rb_downloads_dir_init(app);
            rb_css_load(app);          /* the new profile's theme */
            rb_apply_font_scale(app);  /* ...and its font scale */
            if (g_js_item != NULL) {
                gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(g_js_item),
                                               app->js_enabled ? TRUE : FALSE);
            }
            break;
        case RB_SWITCH_RESTORE_NEW_PROFILE_TABS:
            rb_tabs_restore_state(app);
            break;
        default:
            break;
        }
        if (rb_switch_step_done(app->switcher) != 0) break;
    }

    if (rb_switch_state_of(app->switcher) == RB_SWITCH_FAILED) {
        const char *err = rb_switch_error(app->switcher);
        rb_switch_error_dialog(app, "Profile switch failed",
                               err ? err : "unknown error");
    }
    /* Leave the machine ready for the next switch.  reset() clears the
     * snapshot, so it is read above, before this. */
    rb_switch_reset(app->switcher);
    rb_update_all(app);
}

/* ------------------------------------------------------------------ */
/* Signal callbacks */

static void on_switch_page(GtkNotebook *notebook, GtkWidget *page,
                            guint page_num, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)notebook;
    (void)page;
    if (app->silent) return;
    if ((int)page_num < app->tabs_n) {
        app->active_id = app->tabs[page_num].id;
        rb_update_all(app);
    }
}

static void on_tab_close_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkTab *gt = rb_tab_by_widget(app, GTK_WIDGET(button));
    if (gt) rb_do_close_tab_id(app, gt->id);
}

static void on_newtab_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)button;
    rb_do_new_tab(app);
}

static void on_nav_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkWidget *w = GTK_WIDGET(button);
    if (w == app->back) {
        rb_gw_back(app);
    } else if (w == app->fwd) {
        rb_gw_forward(app);
    } else if (w == app->reload) {
        rb_do_reload_or_stop(app);
    } else if (w == app->home) {
        rb_do_navigate(app, app->home_url ? app->home_url
                                          : "https://duckduckgo.com");
    } else if (w == app->star) {
        rb_do_toggle_bookmark(app);
    }
}

static void on_omni_activate(GtkEntry *entry, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *text = gtk_entry_get_text(entry);
    const char *engine;
    char *url;
    int kind = RB_URL_INPUT_WEB;

    if (!text || !text[0]) return;

    /* The profile's engine.  A NULL or unknown id resolves to the default
     * inside rb_search_resolve, so no fallback is spelled here. */
    engine = rb_pref(app, RB_PREF_SEARCH_ENGINE, NULL);
    url = rb_url_classify(text, engine, &kind, NULL);
    if (url && url[0]) {
        rb_do_navigate(app, url);
    }
    free(url);
}

static gboolean on_omni_key(GtkWidget *widget, GdkEventKey *event,
                            gpointer user_data)
{
    App *app = (App *)user_data;
    (void)widget;
    if (event->keyval == GDK_KEY_Escape) {
        rb_tab *t = rb_store_tab(app);
        rb_update_omni(app, t && t->url ? t->url : "");
        return TRUE;
    }
    return FALSE;
}

/* ------------------------------------------------------------------ */
/* Hamburger menu */

static void on_menu_new_tab(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_new_tab(app);
}

static void on_menu_bookmark(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_toggle_bookmark(app);
}

static void on_js_toggled(GtkCheckMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    app->js_enabled = gtk_check_menu_item_get_active(item) ? 1 : 0;
    /* The switch belongs to the profile, not to the installation: Android
     * keeps it in ProfileSettings, so two profiles can disagree. */
    rb_pref_set_int(app, RB_PREF_JAVASCRIPT, app->js_enabled);
    rb_gw_apply_js(app);   /* live toggle: applies to the open webviews */
}

static void on_bm_toggled(GtkCheckMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    /* A per-profile preference like the JavaScript switch above, so the bar
     * is shown or hidden for the profile it was chosen in. */
    rb_pref_set_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL,
                    gtk_check_menu_item_get_active(item) ? 1 : 0);
    rb_bookmarks_bar_refresh(app);
}

static void on_dialog_response(GtkWidget *widget, gint response_id,
                               gpointer user_data)
{
    (void)response_id;
    (void)user_data;
    gtk_widget_destroy(widget);
}

/* ------------------------------------------------------------------ */
/* Preferences
 *
 * The desktop counterpart of Android's ProfileSettingsScreen.  Every control
 * writes through rb_pref_set() or rb_pref_set_int(), so a change lands in the
 * PROFILE it was made in — which is the whole point of the per-profile
 * settings the rest of this file reads.
 *
 * After a write the affected subsystem is refreshed immediately rather than
 * at the next navigation, so the switch does what it says while the user is
 * looking at it. */

/* Applies whatever a preference key controls to the running browser.  A key
 * with no live effect (a stored-only setting) falls through to the sweep at
 * the end, which is idempotent and cheap.
 *
 * The comparisons are exact strings, not a switch on the first letter: the
 * keys share initials ("theme" and "translate_target_language", "download_
 * subfolder" and "dns_mode"), and a prefix test would apply the wrong one. */
static void rb_prefs_apply_key(App *app, const char *key)
{
    if (app == NULL || key == NULL) return;

    if (strcmp(key, RB_PREF_JAVASCRIPT) == 0) {
        app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
        rb_gw_apply_js(app);
    } else if (strcmp(key, RB_PREF_HOME_LOCAL) == 0) {
        rb_set_str(&app->home_url,
                   rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                     "https://duckduckgo.com")));
    } else if (strcmp(key, RB_PREF_BOOKMARKS_BAR_LOCAL) == 0) {
        /* The bar is per profile, so a profile switch lands here too. */
        if (g_bm_item != NULL) {
            gtk_check_menu_item_set_active(
                GTK_CHECK_MENU_ITEM(g_bm_item),
                rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1) ? TRUE : FALSE);
        }
        rb_bookmarks_bar_refresh(app);
    } else if (strcmp(key, RB_PREF_UA_MODE) == 0 ||
               strcmp(key, RB_PREF_UA_PRESET_ID) == 0 ||
               strcmp(key, RB_PREF_CUSTOM_USER_AGENT) == 0) {
        rb_gw_apply_ua(app);
    } else if (strcmp(key, RB_PREF_THEME) == 0 ||
               strcmp(key, RB_PREF_ACCENT_ARGB) == 0) {
        rb_css_load(app);
    } else if (strcmp(key, RB_PREF_FONT_SCALE) == 0) {
        /* Scales the UI font.  No stylesheet reload: the scale rides on
         * gtk-font-name, which restyles every widget by itself. */
        rb_apply_font_scale(app);
    } else if (strcmp(key, RB_PREF_DOWNLOAD_SUBFOLDER) == 0) {
        /* rb_downloads_dir_init assigns rather than appends, so the old path
         * has to go first. */
        rb_set_str(&app->download_dir, NULL);
        rb_downloads_dir_init(app);
    }

    /* Everything the content blocker's rules or the WebKit view settings
     * depend on is re-applied wholesale: it is idempotent, so there is no
     * need to work out here which switch moved. */
    rb_gw_apply_web_settings(app);
    rb_gw_content_blocking_apply(app);
    rb_profiles_save(app);
}

/* Rows carry their preference key on the widget, so one callback serves every
 * switch in the dialog. */
static void on_pref_switch(GObject *obj, GParamSpec *pspec, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *key = (const char *)g_object_get_data(obj, "rb-pref-key");
    (void)pspec;
    if (key == NULL) return;
    rb_pref_set_int(app, key, gtk_switch_get_active(GTK_SWITCH(obj)) ? 1 : 0);
    rb_prefs_apply_key(app, key);
}

/* A labelled switch row.  `fallback` is what the core's default is, so a
 * profile that never changed the key still shows the right state. */
static void rb_pref_row(GtkWidget *grid, int row, App *app, const char *key,
                        int fallback, const char *title, const char *subtitle)
{
    GtkWidget *label;
    GtkWidget *sw;
    char *markup;

    markup = g_markup_printf_escaped("<b>%s</b>%s%s",
        title,
        (subtitle != NULL) ? "\n" : "",
        (subtitle != NULL) ? subtitle : "");
    label = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(label), markup);
    g_free(markup);
    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_label_set_line_wrap(GTK_LABEL(label), TRUE);
    gtk_widget_set_hexpand(label, TRUE);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 1, 1);

    sw = gtk_switch_new();
    gtk_widget_set_valign(sw, GTK_ALIGN_CENTER);
    gtk_switch_set_active(GTK_SWITCH(sw), rb_pref_int(app, key, fallback) ? TRUE : FALSE);
    g_object_set_data_full(G_OBJECT(sw), "rb-pref-key", rb_strdup(key), free);
    g_signal_connect(sw, "notify::active", G_CALLBACK(on_pref_switch), app);
    gtk_grid_attach(GTK_GRID(grid), sw, 1, row, 1, 1);
}

/* A combo row whose options are (id, label) pairs terminated by a NULL id.
 * One callback serves all of them; the key and the value list ride on the
 * widget.
 *
 * `is_theme` marks the one row that is NOT a setting.  The theme ID lives in
 * the profile's theme_json snapshot (see rb_profile_set_theme), so it cannot
 * go through rb_pref_set() with the rest — a distinction that has to be made
 * explicitly, because the two were once the same combo and the theme ID
 * overwrote the MODE setting every time it was changed. */
typedef struct {
    const char *key;
    const char *const *ids;    /* ids[i] pairs with labels[i] */
    const char *const *labels;
    int is_theme;
} rb_pref_choices;

static void on_pref_combo(GtkComboBox *combo, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_pref_choices *ch = (rb_pref_choices *)g_object_get_data(G_OBJECT(combo),
                                                              "rb-pref-choices");
    char *active;
    if (ch == NULL) return;
    active = gtk_combo_box_text_get_active_text(GTK_COMBO_BOX_TEXT(combo));
    if (active == NULL) return;
    /* The visible text is the label; the id is the label's twin by index. */
    {
        int i;
        for (i = 0; ch->labels[i] != NULL; i++) {
            if (strcmp(ch->labels[i], active) == 0) {
                if (ch->is_theme) {
                    /* Into the profile snapshot, then a repaint: the palette
                     * itself changed, which no setting can express. */
                    rb_profile_set_theme(app->profiles, app->active_profile_id,
                                         ch->ids[i]);
                    rb_profiles_save(app);
                    rb_css_load(app);
                } else {
                    rb_pref_set(app, ch->key, ch->ids[i]);
                    rb_prefs_apply_key(app, ch->key);
                }
                break;
            }
        }
    }
    g_free(active);
}

static void rb_pref_combo_row(GtkWidget *grid, int row, App *app,
                              const char *key, const char *const *ids,
                              const char *const *labels, const char *current,
                              const char *title, int is_theme)
{
    GtkWidget *label = gtk_label_new(title);
    GtkWidget *combo = gtk_combo_box_text_new();
    rb_pref_choices *ch = g_new0(rb_pref_choices, 1);
    int i;
    int sel = 0;

    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_widget_set_hexpand(label, TRUE);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 1, 1);

    ch->key = key;
    ch->ids = ids;
    ch->labels = labels;
    ch->is_theme = is_theme;
    for (i = 0; labels[i] != NULL; i++) {
        gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(combo), labels[i]);
        if (current != NULL && ids[i] != NULL && strcmp(ids[i], current) == 0) {
            sel = i;
        }
    }
    gtk_combo_box_set_active(GTK_COMBO_BOX(combo), sel);
    g_object_set_data_full(G_OBJECT(combo), "rb-pref-choices", ch, g_free);
    g_signal_connect(combo, "changed", G_CALLBACK(on_pref_combo), app);
    gtk_grid_attach(GTK_GRID(grid), combo, 1, row, 1, 1);
}

/* An entry row with an Apply button: the value is written when Apply is
 * pressed, not on every keystroke, so a half-typed URL is never saved. */
static void on_pref_entry_apply(GtkButton *btn, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *key = (const char *)g_object_get_data(G_OBJECT(btn), "rb-pref-key");
    GtkWidget *entry = (GtkWidget *)g_object_get_data(G_OBJECT(btn), "rb-pref-entry");
    const char *text;
    if (key == NULL || entry == NULL) return;
    text = gtk_entry_get_text(GTK_ENTRY(entry));
    if (text == NULL) text = "";

    /* The two DNS fields are checked before they are stored, with the same
     * validator the Android edition uses.  A value that could never be used
     * is refused here rather than saved, so the settings screen never shows a
     * "protected" DNS mode that silently is not one.  Empty is allowed: it
     * means "not configured". */
    if (strcmp(key, RB_PREF_DOH_URL) == 0 && text[0] != '\0' &&
        !rb_dns_valid_doh_url(text)) {
        rb_warn(app, "Not a valid DNS-over-HTTPS URL",
                "It has to be an https:// URL with a host, for example "
                "https://dns.example/dns-query.  Left unchanged.");
        return;
    }
    if (strcmp(key, RB_PREF_DOT_HOSTNAME) == 0 && text[0] != '\0' &&
        !rb_dns_valid_dot_hostname(text)) {
        rb_warn(app, "Not a valid DNS-over-TLS hostname",
                "Expected a hostname, optionally with a port, for example "
                "dns.example or dns.example:853.  Left unchanged.");
        return;
    }

    rb_pref_set(app, key, text);
    rb_prefs_apply_key(app, key);
}

static void rb_pref_entry_row(GtkWidget *grid, int row, App *app,
                              const char *key, const char *current,
                              const char *title)
{
    GtkWidget *label = gtk_label_new(title);
    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    GtkWidget *entry = gtk_entry_new();
    GtkWidget *apply = gtk_button_new_with_label("Apply");

    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 2, 1);

    gtk_entry_set_text(GTK_ENTRY(entry), (current != NULL) ? current : "");
    gtk_widget_set_hexpand(entry, TRUE);
    gtk_box_pack_start(GTK_BOX(box), entry, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(box), apply, FALSE, FALSE, 0);
    g_object_set_data_full(G_OBJECT(apply), "rb-pref-key", rb_strdup(key), free);
    g_object_set_data(G_OBJECT(apply), "rb-pref-entry", entry);
    g_signal_connect(apply, "clicked", G_CALLBACK(on_pref_entry_apply), app);
    gtk_grid_attach(GTK_GRID(grid), box, 0, row + 1, 2, 1);
}

static GtkWidget *rb_pref_page(void)
{
    GtkWidget *grid = gtk_grid_new();
    gtk_grid_set_row_spacing(GTK_GRID(grid), 8);
    gtk_grid_set_column_spacing(GTK_GRID(grid), 12);
    gtk_container_set_border_width(GTK_CONTAINER(grid), 12);
    gtk_widget_set_margin_end(grid, 12);
    return grid;
}

static GtkWidget *rb_pref_scrolled(GtkWidget *child)
{
    GtkWidget *sw = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(sw),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    gtk_container_add(GTK_CONTAINER(sw), child);
    return sw;
}

/* --- Danger zone: clear browsing data ---
 *
 * Android's ClearDataSection.  The WebKit half is real per-profile work
 * (cookies, cache and site storage live in the profile's data directories),
 * and the store half is the core's own files. */

typedef struct {
    App *app;
    GtkWidget *history, *cookies, *cache, *site_data, *downloads;
} rb_clear_widgets;

static void on_clear_data(GtkButton *btn, gpointer user_data)
{
    rb_clear_widgets *w = (rb_clear_widgets *)user_data;
    App *app = w->app;
    const rb_profile *p = rb_active_profile(app);
    GString *done = g_string_new("");
    WebKitWebsiteDataTypes types = 0;

    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->history)) &&
        app->history != NULL) {
        int n = rb_history_clear(app->history);
        if (app->path_history) rb_history_save(app->history, app->path_history);
        g_string_append_printf(done, "history (%d)\n", n);
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->cookies))) {
        types |= WEBKIT_WEBSITE_DATA_COOKIES;
        /* The core keeps no cookie jar — WebKit owns it — so there is nothing
         * to clear here beyond the data manager below. */
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->cache))) {
        types |= WEBKIT_WEBSITE_DATA_MEMORY_CACHE | WEBKIT_WEBSITE_DATA_DISK_CACHE;
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->site_data))) {
        types |= WEBKIT_WEBSITE_DATA_LOCAL_STORAGE |
                 WEBKIT_WEBSITE_DATA_INDEXEDDB_DATABASES |
                 WEBKIT_WEBSITE_DATA_WEBSQL_DATABASES |
                 WEBKIT_WEBSITE_DATA_SESSION_STORAGE |
                 WEBKIT_WEBSITE_DATA_SERVICE_WORKER_REGISTRATIONS |
                 WEBKIT_WEBSITE_DATA_DOM_CACHE;
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->downloads)) &&
        app->downloads != NULL && p != NULL) {
        int n = rb_downloads_clear_profile(app->downloads, p->id);
        if (app->path_downloads) {
            rb_downloads_save(app->downloads, app->path_downloads);
        }
        /* Records only: the FILES the user downloaded are never deleted by a
         * "clear browsing data" — that is the user's data, not the browser's. */
        g_string_append_printf(done, "download records (%d)\n", n);
    }

    if (types != 0 && app->ctx != NULL) {
        WebKitWebsiteDataManager *dm =
            webkit_web_context_get_website_data_manager(app->ctx);
        if (dm != NULL) {
            /* Every profile's data lives under its own directories, so this
             * reaches this profile's cookies and cache and no other's. */
            webkit_website_data_manager_clear(dm, types, 0, NULL, NULL, NULL);
            g_string_append(done, "cookies / cache / site data\n");
        }
    }

    if (done->len > 0) {
        g_string_prepend(done, "Cleared:\n");
        rb_warn(app, "Browsing data", done->str);
    } else {
        rb_warn(app, "Browsing data", "Nothing was selected.");
    }
    g_string_free(done, TRUE);
    (void)btn;
}

/* --- the dialog --- */

static void rb_show_prefs_dialog_impl(App *app)
{
    GtkWidget *dlg;
    GtkWidget *notebook;
    GtkWidget *grid;
    GtkWidget *area;
    int r;

    const rb_theme *theme;

    if (app == NULL || app->win == NULL) return;

    dlg = gtk_dialog_new_with_buttons("Profile Settings", GTK_WINDOW(app->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT | GTK_DIALOG_USE_HEADER_BAR,
        "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 720, 560);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    notebook = gtk_notebook_new();
    area = gtk_dialog_get_content_area(GTK_DIALOG(dlg));
    gtk_box_pack_start(GTK_BOX(area), notebook, TRUE, TRUE, 0);

    /* ---- Appearance ---- */
    grid = rb_pref_page();
    r = 0;
    {
        /* The theme list is the core's registry, so the desktop offers the
         * same presets the Android theme studio is built from.  The ID goes
         * into the profile's theme_json snapshot (is_theme = 1); the MODE is
         * the separate row below, and the two used to share one control —
         * which is why choosing a theme also silently reset the mode, and why
         * no theme was ever applied at all. */
        static const char *ids[64];
        static const char *labels[64];
        int n = rb_theme_count();
        int i;
        if (n > 63) n = 63;
        for (i = 0; i < n; i++) {
            const rb_theme *t = rb_theme_at(i);
            ids[i] = (t != NULL) ? t->id : "";
            labels[i] = (t != NULL && t->name != NULL) ? t->name : "";
        }
        ids[n] = NULL;
        labels[n] = NULL;
        theme = rb_theme_current(app);
        rb_pref_combo_row(grid, r++, app, NULL, ids, labels,
                          (theme != NULL) ? theme->id : NULL, "Theme", 1);
    }
    {
        /* rb_prefs.h's spellings for the mode, with "system" standing for
         * rb_theme.h's AUTO. */
        static const char *const mode_ids[] = {
            "system", "light", "dark", "amoled", NULL
        };
        static const char *const mode_labels[] = {
            "Match system", "Light", "Dark", "AMOLED", NULL
        };
        rb_pref_combo_row(grid, r++, app, RB_PREF_THEME, mode_ids, mode_labels,
                          rb_pref(app, RB_PREF_THEME, "system"),
                          "Appearance", 0);
    }
    {
        /* The scale every UI font is multiplied by.  Stored as a percentage so
         * the same value means the same thing here, on Windows, and to the
         * Android app's text-size setting. */
        static const char *const scale_ids[] = {
            "80", "90", "100", "110", "125", "150", NULL
        };
        static const char *const scale_labels[] = {
            "80%", "90%", "100% (default)", "110%", "125%", "150%", NULL
        };
        rb_pref_combo_row(grid, r++, app, RB_PREF_FONT_SCALE, scale_ids,
                          scale_labels,
                          rb_pref(app, RB_PREF_FONT_SCALE, "100"),
                          "Text size", 0);
    }
    rb_pref_row(grid, r++, app, RB_PREF_REDUCED_MOTION, 0, "Reduce motion",
                "Turns off the transitions the chrome animates");
    rb_pref_row(grid, r++, app, RB_PREF_HIGH_CONTRAST, 0, "High contrast",
                "Strengthens the contrast between text and its background");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Appearance"));

    /* ---- Search ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *ids[64];
        static const char *labels[64];
        int n = rb_search_count();
        int i;
        if (n > 63) n = 63;
        for (i = 0; i < n; i++) {
            const rb_search_engine *e = rb_search_at(i);
            ids[i] = (e != NULL) ? e->id : "";
            labels[i] = (e != NULL && e->label != NULL) ? e->label : "";
        }
        ids[n] = NULL;
        labels[n] = NULL;
        rb_pref_combo_row(grid, r++, app, RB_PREF_SEARCH_ENGINE, ids, labels,
                          rb_pref(app, RB_PREF_SEARCH_ENGINE, "duckduckgo"),
                          "Search engine", 0);
    }
    rb_pref_row(grid, r++, app, RB_PREF_SEARCH_SUGGESTIONS, 0,
                "Search suggestions",
                "Sends what you type to the search engine as you type it");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Search"));

    /* ---- Privacy & Blocking ---- */
    grid = rb_pref_page();
    r = 0;
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_ADS, 0, "Block ads",
                "Blocks known ad hosts from the bundled offline list");
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_TRACKERS, 0, "Block trackers",
                NULL);
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_CROSS_SITE, 0,
                "Block cross-site trackers", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_POPUPS, 0, "Block popups", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_MALICIOUS, 1,
                "Block malicious websites",
                "A top-level navigation into a listed malicious host is refused");
    rb_pref_row(grid, r++, app, RB_PREF_HTTPS_UPGRADE, 1, "HTTPS upgrades",
                "Upgrades http to https and falls back to http when the secure "
                "version is unreachable");
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_THIRD_PARTY_COOKIES, 0,
                "Block third-party cookies",
                "Off by default for compatibility: many logins and embeds need them");
    /* Shown even though it does nothing here: hiding it would let a user
     * believe their profile has no such setting, and the note says plainly
     * that this edition cannot honour it. */
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_MIXED_CONTENT, 0,
                "Block mixed content",
                "Stored and honoured on Android; WebKitGTK exposes no switch for "
                "it, so it has no effect on this edition");
    rb_pref_row(grid, r++, app, RB_PREF_JAVASCRIPT, 1, "JavaScript enabled",
                "Never disabled by default");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Privacy"));

    /* ---- User-Agent ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *ids[4] = { "default", "preset", "custom", NULL };
        static const char *labels[4] = { "Default (WebKit)",
                                         "Preset", "Custom", NULL };
        rb_pref_combo_row(grid, r++, app, RB_PREF_UA_MODE, ids, labels,
                          rb_pref(app, RB_PREF_UA_MODE, "default"),
                          "User-Agent mode", 0);
    }
    {
        static const char *ids[64];
        static const char *labels[64];
        static char buf[64][128];
        int n = rb_ua_count();
        int i;
        if (n > 63) n = 63;
        for (i = 0; i < n; i++) {
            const rb_ua_preset *p = rb_ua_at(i);
            ids[i] = (p != NULL) ? p->id : "";
            snprintf(buf[i], sizeof buf[i], "%s%s",
                     (p != NULL && p->label != NULL) ? p->label : "",
                     (p != NULL && p->is_desktop) ? "  (desktop)" : "");
            labels[i] = buf[i];
        }
        ids[n] = NULL;
        labels[n] = NULL;
        rb_pref_combo_row(grid, r++, app, RB_PREF_UA_PRESET_ID, ids, labels,
                          rb_pref(app, RB_PREF_UA_PRESET_ID, ""), "Preset", 0);
    }
    rb_pref_entry_row(grid, r, app, RB_PREF_CUSTOM_USER_AGENT,
                      rb_pref(app, RB_PREF_CUSTOM_USER_AGENT, ""),
                      "Custom User-Agent");
    r += 2;
    {
        char *ua = rb_ua_current(app);
        GtkWidget *note = gtk_label_new(NULL);
        char *markup = g_markup_printf_escaped(
            "<small>Current: %s\nChanging the User-Agent string does not change "
            "any other platform or device characteristic.</small>",
            (ua != NULL) ? ua : "the engine default");
        gtk_label_set_markup(GTK_LABEL(note), markup);
        gtk_label_set_xalign(GTK_LABEL(note), 0.0f);
        gtk_label_set_line_wrap(GTK_LABEL(note), TRUE);
        g_free(markup);
        free(ua);
        gtk_grid_attach(GTK_GRID(grid), note, 0, r++, 2, 1);
    }
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("User-Agent"));

    /* ---- DNS & Network ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *ids[5] = { "system", "auto", "doh", "dot", NULL };
        static const char *labels[5] = { "System",
                                         "Use the global browser setting",
                                         "DNS-over-HTTPS",
                                         "DNS-over-TLS", NULL };
        rb_pref_combo_row(grid, r++, app, RB_PREF_DNS_MODE, ids, labels,
                          rb_pref(app, RB_PREF_DNS_MODE, "system"), "DNS mode", 0);
    }
    rb_pref_entry_row(grid, r, app, RB_PREF_DOH_URL,
                      rb_pref(app, RB_PREF_DOH_URL, ""), "DNS-over-HTTPS URL");
    r += 2;
    rb_pref_entry_row(grid, r, app, RB_PREF_DOT_HOSTNAME,
                      rb_pref(app, RB_PREF_DOT_HOSTNAME, ""),
                      "DNS-over-TLS hostname");
    r += 2;
    {
        /* The effective mode, computed exactly as the Android settings screen
         * computes it (profile mode, then the global one, then validation).
         * Shown so the DNS fields above are not dead text: the user can see
         * what the profile would actually resolve with, including the
         * MISCONFIGURED case a half-typed URL produces. */
        const char *gm = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DNS_MODE, "system") : "system";
        const char *gd = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DOH_URL, "") : "";
        const char *gh = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DOT_HOSTNAME, "") : "";
        rb_dns_effective eff = rb_dns_resolve(
            rb_pref(app, RB_PREF_DNS_MODE, "system"),
            rb_pref(app, RB_PREF_DOH_URL, ""),
            rb_pref(app, RB_PREF_DOT_HOSTNAME, ""),
            gm, gd, gh);
        GtkWidget *note = gtk_label_new(NULL);
        char *markup = g_markup_printf_escaped(
            "<small>Effective mode: %s\n%s</small>",
            rb_dns_status_name(eff.status),
            (eff.status == RB_DNS_STATUS_MISCONFIGURED)
                ? "The configured value is not usable, so name resolution falls "
                  "back to the system resolver."
                : "This build does not resolve names itself — page loads use the "
                  "system resolver either way. See README.md.");
        gtk_label_set_markup(GTK_LABEL(note), markup);
        gtk_label_set_xalign(GTK_LABEL(note), 0.0f);
        gtk_label_set_line_wrap(GTK_LABEL(note), TRUE);
        g_free(markup);
        rb_dns_effective_free(&eff);
        gtk_grid_attach(GTK_GRID(grid), note, 0, r++, 2, 1);
    }
    rb_pref_row(grid, r++, app, RB_PREF_NET_PROTECT_GLOBAL, 1,
                "Network protection: use the global setting", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_NET_PROTECT_ENABLED, 1,
                "IP conflict warning enabled",
                "Warns when the address this profile resolved to is also in use "
                "on the local network");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Network"));

    /* ---- Homepage, tabs & language ---- */
    grid = rb_pref_page();
    r = 0;
    rb_pref_entry_row(grid, r, app, RB_PREF_HOME_LOCAL,
                      rb_pref(app, RB_PREF_HOME_LOCAL, "https://duckduckgo.com"),
                      "Homepage");
    r += 2;
    rb_pref_row(grid, r++, app, RB_PREF_HOMEPAGE_ENABLED, 1, "Show homepage",
                NULL);
    rb_pref_row(grid, r++, app, RB_PREF_SHOW_PRIVACY_STATS, 1,
                "Show privacy statistics", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_SHOW_RECENT_SITES, 1,
                "Show recent sites", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_SHOW_CLOCK, 1, "Show clock", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_DESKTOP_MODE_DEFAULT, 0,
                "Request desktop sites by default", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_AUTOFILL_ENABLED, 0,
                "Autofill integration",
                "Android delegates this to the system autofill framework, which "
                "a desktop browser has no equivalent of — this edition stores no "
                "credentials and does not fill forms");
    rb_pref_entry_row(grid, r, app, RB_PREF_DOWNLOAD_SUBFOLDER,
                      rb_pref(app, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser"),
                      "Downloads subfolder");
    r += 2;
    rb_pref_entry_row(grid, r, app, RB_PREF_TRANSLATE_TARGET,
                      rb_pref(app, RB_PREF_TRANSLATE_TARGET, "id"),
                      "Translate target language (e.g. id)");
    r += 2;
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Homepage & Languages"));

    /* ---- Danger zone ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *names[] = { "History", "Cookies", "Cache",
                                       "Site data (local storage, IndexedDB)",
                                       "Download records" };
        GtkWidget *boxes[5];
        rb_clear_widgets *w = g_new0(rb_clear_widgets, 1);
        GtkWidget *btn;
        GtkWidget *note;
        int i;
        static const int defaults[5] = { 1, 1, 1, 0, 0 };

        w->app = app;
        for (i = 0; i < 5; i++) {
            boxes[i] = gtk_check_button_new_with_label(names[i]);
            gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(boxes[i]),
                                         defaults[i] ? TRUE : FALSE);
            gtk_grid_attach(GTK_GRID(grid), boxes[i], 0, r++, 2, 1);
        }
        w->history = boxes[0];
        w->cookies = boxes[1];
        w->cache = boxes[2];
        w->site_data = boxes[3];
        w->downloads = boxes[4];

        note = gtk_label_new(NULL);
        gtk_label_set_markup(GTK_LABEL(note),
            "<small>This clears the data of the ACTIVE PROFILE only; every other "
            "profile keeps its own cookies and cache. Downloaded files are never "
            "deleted — only the records of them.</small>");
        gtk_label_set_xalign(GTK_LABEL(note), 0.0f);
        gtk_label_set_line_wrap(GTK_LABEL(note), TRUE);
        gtk_grid_attach(GTK_GRID(grid), note, 0, r++, 2, 1);

        btn = gtk_button_new_with_label("Clear selected data");
        g_signal_connect(btn, "clicked", G_CALLBACK(on_clear_data), w);
        g_signal_connect_swapped(dlg, "destroy", G_CALLBACK(g_free), w);
        gtk_grid_attach(GTK_GRID(grid), btn, 0, r++, 2, 1);
    }
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Clear data"));

    gtk_widget_show_all(dlg);
}

void rb_show_prefs_dialog(App *app)
{
    rb_show_prefs_dialog_impl(app);
}

static void rb_show_about(App *app)
{
    GtkWidget *dlg = gtk_about_dialog_new();
    const gchar *authors[2];
    authors[0] = "Maragung";
    authors[1] = NULL;
    gtk_window_set_transient_for(GTK_WINDOW(dlg), GTK_WINDOW(app->win));
    gtk_window_set_modal(GTK_WINDOW(dlg), TRUE);
    gtk_about_dialog_set_program_name(GTK_ABOUT_DIALOG(dlg), "Room Browser");
    gtk_about_dialog_set_version(GTK_ABOUT_DIALOG(dlg), RB_VERSION);
    gtk_about_dialog_set_authors(GTK_ABOUT_DIALOG(dlg), authors);
    gtk_about_dialog_set_comments(GTK_ABOUT_DIALOG(dlg),
        "Privacy desktop browser in pure C (Brave-inspired dark chrome).");
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);
    gtk_widget_show_all(dlg);
}

static void on_hist_row_activated(GtkListBox *box, GtkListBoxRow *row,
                                  gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = (const char *)g_object_get_data(G_OBJECT(row), "url");
    (void)box;
    if (url && url[0]) {
        rb_do_navigate(app, url);
    }
    gtk_widget_destroy(gtk_widget_get_toplevel(GTK_WIDGET(row)));
}

static void rb_show_history_dialog(App *app)
{
    GtkWidget *dlg, *scroll, *list;
    const rb_hist_entry *rec;
    int n = 0, i;

    dlg = gtk_dialog_new_with_buttons("Recent history", GTK_WINDOW(app->win),
            GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
            "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 560, 400);

    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    list = gtk_list_box_new();
    gtk_list_box_set_selection_mode(GTK_LIST_BOX(list), GTK_SELECTION_NONE);

    rec = rb_history_recent(app->history, 10, &n);
    for (i = 0; i < n; i++) {
        const char *title = (rec[i].title && rec[i].title[0]) ? rec[i].title
                                                              : rec[i].url;
        GtkWidget *row = gtk_list_box_row_new();
        GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
        GtkWidget *l1 = gtk_label_new(title ? title : "");
        GtkWidget *l2 = gtk_label_new(rec[i].url ? rec[i].url : "");
        gtk_label_set_ellipsize(GTK_LABEL(l1), PANGO_ELLIPSIZE_END);
        gtk_label_set_xalign(GTK_LABEL(l1), 0.0f);
        gtk_label_set_ellipsize(GTK_LABEL(l2), PANGO_ELLIPSIZE_MIDDLE);
        gtk_label_set_xalign(GTK_LABEL(l2), 0.0f);
        rb_add_class(l2, "rb-dim");
        g_object_set_data_full(G_OBJECT(row), "url",
                               rb_strdup(rec[i].url ? rec[i].url : ""), g_free);
        gtk_box_pack_start(GTK_BOX(vbox), l1, TRUE, TRUE, 0);
        gtk_box_pack_start(GTK_BOX(vbox), l2, TRUE, TRUE, 0);
        gtk_container_add(GTK_CONTAINER(row), vbox);
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }
    if (n == 0) {
        GtkWidget *row = gtk_list_box_row_new();
        gtk_container_add(GTK_CONTAINER(row),
                          gtk_label_new("(no history yet)"));
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }

    g_signal_connect(list, "row-activated",
                     G_CALLBACK(on_hist_row_activated), app);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    gtk_container_add(GTK_CONTAINER(scroll), list);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(dlg))),
                      scroll);
    gtk_widget_show_all(dlg);
}

/* One row: name, then a status line that carries the progress/error. */
static void rb_downloads_add_row(GtkWidget *list, const rb_download *dl)
{
    GtkWidget *row = gtk_list_box_row_new();
    GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l1 = gtk_label_new(dl->file_name ? dl->file_name : "");
    char meta[320];
    const char *status = rb_download_status_name(dl->status);

    if (dl->status == RB_DL_RUNNING && dl->total_bytes > 0) {
        snprintf(meta, sizeof meta, "%s — %d%%  (%lld / %lld bytes)",
                 status, rb_download_progress_percent(dl->downloaded_bytes,
                                                      dl->total_bytes),
                 dl->downloaded_bytes, dl->total_bytes);
    } else if (dl->status == RB_DL_FAILED && dl->error && dl->error[0]) {
        snprintf(meta, sizeof meta, "%s — %s", status, dl->error);
    } else {
        snprintf(meta, sizeof meta, "%s", status);
    }

    gtk_label_set_ellipsize(GTK_LABEL(l1), PANGO_ELLIPSIZE_END);
    gtk_label_set_xalign(GTK_LABEL(l1), 0.0f);
    g_object_set_data_full(G_OBJECT(row), "url",
                           rb_strdup(dl->url ? dl->url : ""), g_free);
    gtk_box_pack_start(GTK_BOX(vbox), l1, TRUE, TRUE, 0);
    {
        GtkWidget *l2 = gtk_label_new(meta);
        gtk_label_set_ellipsize(GTK_LABEL(l2), PANGO_ELLIPSIZE_MIDDLE);
        gtk_label_set_xalign(GTK_LABEL(l2), 0.0f);
        rb_add_class(l2, "rb-dim");
        gtk_box_pack_start(GTK_BOX(vbox), l2, TRUE, TRUE, 0);
    }
    gtk_container_add(GTK_CONTAINER(row), vbox);
    gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
}

static void on_dl_clear_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    const rb_profile *p = rb_active_profile(app);
    (void)button;
    /* Clears the RECORDS of this profile only; the files on disk belong to
     * the user and are never touched (DownloadDao.deleteAllFor). */
    rb_downloads_clear_profile(app->downloads, (p != NULL) ? p->id : "");
    if (app->path_downloads) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
    gtk_widget_destroy(gtk_widget_get_toplevel(GTK_WIDGET(button)));
}

static void rb_show_downloads_dialog(App *app)
{
    GtkWidget *dlg, *scroll, *list, *clear;
    int n, i;

    dlg = gtk_dialog_new_with_buttons("Downloads", GTK_WINDOW(app->win),
            GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
            "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 560, 400);

    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    list = gtk_list_box_new();
    gtk_list_box_set_selection_mode(GTK_LIST_BOX(list), GTK_SELECTION_NONE);

    n = rb_downloads_count(app->downloads);
    for (i = 0; i < n; i++) {
        const rb_download *dl = rb_downloads_at(app->downloads, i);
        if (dl != NULL) rb_downloads_add_row(list, dl);
    }
    if (n == 0) {
        GtkWidget *row = gtk_list_box_row_new();
        gtk_container_add(GTK_CONTAINER(row),
                          gtk_label_new("(no downloads yet)"));
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }

    clear = gtk_button_new_with_label("Clear list");
    g_signal_connect(clear, "clicked", G_CALLBACK(on_dl_clear_clicked), app);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    gtk_container_add(GTK_CONTAINER(scroll), list);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(dlg))),
                      scroll);
    /* get_action_area is deprecated since GTK 3.12 (GtkHeaderBar is the
     * replacement) but the classic action area is still what a GtkDialog
     * builds, and the deprecation is silenced the same way the menu popup
     * one is below. */
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_action_area(GTK_DIALOG(dlg))),
                      clear);
    G_GNUC_END_IGNORE_DEPRECATIONS
    gtk_widget_show_all(dlg);
}

static void on_menu_downloads(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_downloads_dialog(app);
}

static void on_menu_prefs(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_prefs_dialog(app);
}

/* ------------------------------------------------------------------ */
/* Profiles menu

 * Android's profile switcher (a sheet listing every profile, the active one
 * ticked, plus "add profile").  The desktop builds the same list as a
 * submenu; each item carries its profile id as object data, because the
 * registry row a pointer would name can move when the registry grows. */

static void rb_build_menu(App *app);   /* rebuilt when the profile list grows */

static gboolean rb_rebuild_menu_idle(gpointer data)
{
    rb_build_menu((App *)data);
    return G_SOURCE_REMOVE;
}

static void on_menu_profile(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *id = (const char *)g_object_get_data(G_OBJECT(item),
                                                     "rb-profile-id");
    if (id != NULL) rb_do_switch_profile(app, id);
}

static void on_menu_new_profile(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_profile_registry *reg;
    char name[64];
    char id[RB_PROFILE_ID_LEN + 1];
    int n;
    int made;
    (void)item;

    if (app == NULL || app->profiles == NULL) return;
    reg = app->profiles;
    n = rb_profile_count(reg);
    /* The new profile is created but NOT switched to: the row appears in the
     * menu, and activating it runs the full switch protocol.  Switching
     * straight from a menu click would leave the user with no way to see
     * which profile they landed in. */
    snprintf(name, sizeof name, "Profile %d", n + 1);
    id[0] = '\0';
    /* create() refuses a name that is already taken, so a user who renamed a
     * profile to "Profile 2" makes this call fail — bump the number until it
     * does not, which is the same thing the Android sheet does rather than
     * refusing to add a profile at all. */
    for (;;) {
        made = rb_profile_create(reg, name, NULL, 0, 1);
        if (made == 0 || n >= 99) break;
        n++;
        snprintf(name, sizeof name, "Profile %d", n + 1);
    }
    if (made != 0) {
        rb_switch_error_dialog(app, "Cannot add a profile",
            "The browser could not create another profile.");
        return;
    }
    {
        const rb_profile *p = rb_profile_at(reg, rb_profile_count(reg) - 1);
        if (p != NULL) snprintf(id, sizeof id, "%s", p->id);
    }
    if (id[0] == '\0') return;
    rb_profiles_save(app);
    /* The list is built from the registry, so it has to be rebuilt — but
     * this runs inside the activation of an item in the popup being
     * replaced, so the rebuild is deferred to the next main-loop turn
     * rather than destroying the menu under the signal that is emitting. */
    g_idle_add(rb_rebuild_menu_idle, app);
}

static void rb_profile_append_items(GtkWidget *menu, App *app)
{
    int i;
    int n = rb_profile_count(app->profiles);
    GSList *group = NULL;

    for (i = 0; i < n; i++) {
        const rb_profile *p = rb_profile_at(app->profiles, i);
        GtkWidget *item;
        char label[128];
        if (p == NULL) continue;
        snprintf(label, sizeof label, "%s%s",
                 (p->name && p->name[0]) ? p->name : "Profile",
                 (p->is_locked) ? "  (locked)" : "");
        item = gtk_radio_menu_item_new_with_label(group, label);
        /* The radio group is what puts the tick on the active profile, so it
         * has to be threaded through every item by hand — GTK takes the list
         * head, not a widget. */
        group = gtk_radio_menu_item_get_group(GTK_RADIO_MENU_ITEM(item));
        if (app->active_profile_id != NULL &&
            strcmp(app->active_profile_id, p->id) == 0) {
            gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(item), TRUE);
        }
        g_object_set_data_full(G_OBJECT(item), "rb-profile-id",
                               rb_strdup(p->id), free);
        g_signal_connect(item, "activate", G_CALLBACK(on_menu_profile), app);
        gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
    }
}

static void on_menu_history(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_history_dialog(app);
}

static void on_menu_about(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_about(app);
}

static void rb_build_menu(App *app)
{
    GtkWidget *menu = gtk_menu_new();
    GtkWidget *item;

    item = gtk_menu_item_new_with_label("New Tab");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_new_tab), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Bookmark this page");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_bookmark), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    g_bm_item = gtk_check_menu_item_new_with_label("Show bookmarks bar");
    gtk_check_menu_item_set_active(
        GTK_CHECK_MENU_ITEM(g_bm_item),
        rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1) ? TRUE : FALSE);
    g_signal_connect(g_bm_item, "toggled", G_CALLBACK(on_bm_toggled), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), g_bm_item);

    item = gtk_menu_item_new_with_label("Recent history");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_history), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Downloads");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_downloads), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Preferences");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_prefs), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    {
        GtkWidget *sub = gtk_menu_new();
        GtkWidget *top = gtk_menu_item_new_with_label("Profiles");
        rb_profile_append_items(sub, app);
        gtk_menu_shell_append(GTK_MENU_SHELL(sub),
                              gtk_separator_menu_item_new());
        item = gtk_menu_item_new_with_label("Add profile");
        g_signal_connect(item, "activate",
                         G_CALLBACK(on_menu_new_profile), app);
        gtk_menu_shell_append(GTK_MENU_SHELL(sub), item);
        gtk_widget_show_all(sub);
        gtk_menu_item_set_submenu(GTK_MENU_ITEM(top), sub);
        gtk_menu_shell_append(GTK_MENU_SHELL(menu), top);
    }

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    g_js_item = gtk_check_menu_item_new_with_label("Enable JavaScript");
    gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(g_js_item),
                                    app->js_enabled ? TRUE : FALSE);
    g_signal_connect(g_js_item, "toggled", G_CALLBACK(on_js_toggled), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), g_js_item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    item = gtk_menu_item_new_with_label("About");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_about), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_widget_show_all(menu);

    /* gtk_menu_button_set_popup() is deprecated in favour of popovers but
     * still fully functional on GTK 3.x; keep the classic GtkMenu (the
     * check item for JavaScript needs it) and silence the deprecation. */
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    gtk_menu_button_set_popup(GTK_MENU_BUTTON(app->menu_btn), menu);
    G_GNUC_END_IGNORE_DEPRECATIONS
}

/* ------------------------------------------------------------------ */
/* Keyboard accelerators (GSimpleAction on the GtkApplicationWindow) */

static void on_action_new_tab(GSimpleAction *action, GVariant *parameter,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_do_new_tab(app);
}

static void on_action_close_tab(GSimpleAction *action, GVariant *parameter,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (app->active_id) rb_do_close_tab_id(app, app->active_id);
}

static void on_action_focus_omnibox(GSimpleAction *action, GVariant *parameter,
                                    gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (app->omnibox) {
        gtk_widget_grab_focus(app->omnibox);
        gtk_editable_select_region(GTK_EDITABLE(app->omnibox), 0, -1);
    }
}

static void on_action_reload(GSimpleAction *action, GVariant *parameter,
                             gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_do_reload_or_stop(app);
}

static void on_action_back(GSimpleAction *action, GVariant *parameter,
                           gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_gw_back(app);
}

static void on_action_forward(GSimpleAction *action, GVariant *parameter,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_gw_forward(app);
}

static void rb_add_action(App *app, const char *name, GCallback callback,
                          const gchar *const *accels)
{
    GSimpleAction *action = g_simple_action_new(name, NULL);
    char detailed[64];

    g_signal_connect(action, "activate", callback, app);
    g_action_map_add_action(G_ACTION_MAP(app->win), G_ACTION(action));
    g_object_unref(action);

    if (accels) {
        snprintf(detailed, sizeof detailed, "win.%s", name);
        gtk_application_set_accels_for_action(app->app, detailed, accels);
    }
}

static void rb_add_actions(App *app)
{
    static const gchar *const accels_newtab[]  = { "<Primary>T", NULL };
    static const gchar *const accels_closetab[] = { "<Primary>W", NULL };
    static const gchar *const accels_omni[]   = { "<Primary>L", NULL };
    static const gchar *const accels_reload[] = { "F5", "<Primary>R", NULL };
    static const gchar *const accels_back[]   = { "<Alt>Left", NULL };
    static const gchar *const accels_fwd[]    = { "<Alt>Right", NULL };

    rb_add_action(app, "new-tab", G_CALLBACK(on_action_new_tab), accels_newtab);
    rb_add_action(app, "close-tab", G_CALLBACK(on_action_close_tab), accels_closetab);
    rb_add_action(app, "focus-omnibox", G_CALLBACK(on_action_focus_omnibox), accels_omni);
    rb_add_action(app, "reload", G_CALLBACK(on_action_reload), accels_reload);
    rb_add_action(app, "back", G_CALLBACK(on_action_back), accels_back);
    rb_add_action(app, "forward", G_CALLBACK(on_action_forward), accels_fwd);
}

/* ------------------------------------------------------------------ */
/* Chrome construction (GtkApplication "activate") */

void rb_on_activate(GtkApplication *gtk_app, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkWidget *root, *toolbar, *plus, *overlay;

    if (app->win) {
        gtk_window_present(GTK_WINDOW(app->win));
        return;
    }

    rb_css_load(app);
    /* Captures the system font and applies the profile's scale to it.  Before
     * the first window exists is the right moment: nothing has been laid out
     * at the unscaled size, so there is nothing to reflow. */
    rb_apply_font_scale(app);

    app->app = gtk_app;
    app->win = GTK_APPLICATION_WINDOW(gtk_application_window_new(gtk_app));
    gtk_window_set_default_size(GTK_WINDOW(app->win), RB_WINDOW_W, RB_WINDOW_H);
    gtk_window_set_title(GTK_WINDOW(app->win), "Room Browser");

    /* The chrome is a column inside an overlay, so the link-target bubble can
     * float over the page instead of stealing a row from it. */
    overlay = gtk_overlay_new();
    app->overlay = overlay;
    root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_container_add(GTK_CONTAINER(overlay), root);
    gtk_container_add(GTK_CONTAINER(app->win), overlay);

    /* Tab strip: the notebook pages ARE the per-tab WebKitWebViews. */
    app->notebook = gtk_notebook_new();
    gtk_notebook_set_show_border(GTK_NOTEBOOK(app->notebook), FALSE);
    g_signal_connect(app->notebook, "switch-page",
                     G_CALLBACK(on_switch_page), app);
    plus = gtk_button_new_with_label("+");
    rb_add_class(plus, "rb-btn");
    g_signal_connect(plus, "clicked", G_CALLBACK(on_newtab_clicked), app);
    gtk_notebook_set_action_widget(GTK_NOTEBOOK(app->notebook), plus,
                                    GTK_PACK_END);
    gtk_box_pack_start(GTK_BOX(root), app->notebook, TRUE, TRUE, 0);

    /* Toolbar */
    toolbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    rb_add_class(toolbar, "rb-toolbar");

    app->back = gtk_button_new_with_label("\xE2\x86\x90");
    app->fwd = gtk_button_new_with_label("\xE2\x86\x92");
    app->reload = gtk_button_new_with_label("\xE2\x9F\xB3");
    app->home = gtk_button_new_with_label("\xE2\x8C\x82");
    app->star = gtk_button_new_with_label("\xE2\x98\x86");
    app->menu_btn = gtk_menu_button_new();
    gtk_button_set_label(GTK_BUTTON(app->menu_btn), "\xE2\x98\xB0");
    rb_add_class(app->back, "rb-btn");
    rb_add_class(app->fwd, "rb-btn");
    rb_add_class(app->reload, "rb-btn");
    rb_add_class(app->home, "rb-btn");
    rb_add_class(app->star, "rb-btn");
    rb_add_class(app->menu_btn, "rb-btn");
    g_signal_connect(app->back, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->fwd, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->reload, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->home, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->star, "clicked", G_CALLBACK(on_nav_clicked), app);

    app->omnibox = gtk_entry_new();
    rb_add_class(app->omnibox, "rb-omni");
    gtk_entry_set_placeholder_text(GTK_ENTRY(app->omnibox),
                                   "Search or enter address");
    gtk_widget_set_hexpand(app->omnibox, TRUE);
    g_signal_connect(app->omnibox, "activate",
                     G_CALLBACK(on_omni_activate), app);
    g_signal_connect(app->omnibox, "key-press-event",
                     G_CALLBACK(on_omni_key), app);

    rb_build_menu(app);
    rb_gw_context_new(app);

    gtk_box_pack_start(GTK_BOX(toolbar), app->back, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->fwd, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->reload, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->home, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->omnibox, TRUE, TRUE, 6);
    gtk_box_pack_start(GTK_BOX(toolbar), app->star, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->menu_btn, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(root), toolbar, FALSE, FALSE, 0);

    /* The bookmarks bar: one more chrome row, under the toolbar and above the
     * progress strip.  Built empty here and filled by the refresh below. */
    app->bmbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 2);
    rb_add_class(app->bmbar, "rb-bmbar");
    gtk_box_pack_start(GTK_BOX(root), app->bmbar, FALSE, FALSE, 0);

    /* The page-load indicator: its own 2px strip between the toolbar and the
     * page.  A drawing area rather than CSS because the segment moves. */
    app->prog_area = gtk_drawing_area_new();
    gtk_widget_set_size_request(app->prog_area, -1, RB_PROGRESS_H);
    g_signal_connect(app->prog_area, "draw", G_CALLBACK(rb_progress_draw), app);
    g_signal_connect(app->prog_area, "destroy",
                     G_CALLBACK(rb_progress_area_gone), app);
    gtk_box_pack_start(GTK_BOX(root), app->prog_area, FALSE, FALSE, 0);

    rb_add_actions(app);

    gtk_widget_show_all(GTK_WIDGET(app->win));

    /* Built AFTER show_all so it starts hidden: a child added to an
     * already-shown container stays hidden until it is shown explicitly,
     * which is what hovering a link does.  (gtk_widget_set_no_show_all would
     * do the same job but is deprecated.) */
    app->status = gtk_label_new(NULL);
    rb_add_class(app->status, "rb-status");
    gtk_label_set_ellipsize(GTK_LABEL(app->status), PANGO_ELLIPSIZE_MIDDLE);
    gtk_label_set_max_width_chars(GTK_LABEL(app->status), 100);
    gtk_widget_set_halign(app->status, GTK_ALIGN_START);
    gtk_widget_set_valign(app->status, GTK_ALIGN_END);
    gtk_widget_set_margin_start(app->status, 8);
    gtk_widget_set_margin_bottom(app->status, 8);
    gtk_overlay_add_overlay(GTK_OVERLAY(overlay), app->status);

    /* Filled after show_all for the same reason as the bubble above: children
     * packed into an already-shown box stay hidden until shown, and the
     * refresh is what shows them — and what hides the whole bar when the
     * profile has no bookmarks or has the bar switched off. */
    rb_bookmarks_bar_refresh(app);

    /* First tab: navigates to the "home" setting. */
    rb_do_new_tab(app);
}

void rb_on_shutdown(GtkApplication *gtk_app, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)gtk_app;
    rb_data_shutdown(app);
}
