/*
 * Room Browser (desktop) - Windows chrome header.
 *
 * Pure C11 Win32 chrome for the Room Browser desktop edition.
 * Brave-inspired dark chrome with a purple accent (#A78BFA) matching the
 * Android app. JavaScript is never disabled by default (project policy);
 * the persisted "javascript" setting is honored but defaults to 1.
 *
 * The core stores (tabs / history / bookmarks / settings / profiles / url /
 * paths / filters / themes) are implemented in desktop/src/core and linked as
 * rb_core.  Their declarations are pulled in from <core/rb_core.h> rather than
 * repeated here, so this layer cannot drift out of step with the core ABI.
 */
#ifndef RB_WIN_CHROME_H
#define RB_WIN_CHROME_H

#include <windows.h>
#include <commctrl.h>

#include "core/rb_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* Layout constants (mirror the GTK chrome) */

#define RB_WINDOW_W     1280
#define RB_WINDOW_H     800
#define RB_TABSTRIP_H   34
#define RB_TOOLBAR_H    40

/* ------------------------------------------------------------------ */
/* Palette.
 *
 * The colours are no longer constants: they come from the active profile's
 * theme (rb_theme_current), resolved into App::pal and turned into brushes by
 * rb_theme_apply().  The values below are what the DEFAULT theme ("obsidian")
 * resolves to — the Brave-inspired dark chrome with the #A78BFA accent — and
 * are kept as documentation of that look, not as code to draw with. */

#define RB_DEFAULT_BG      RGB(32, 33, 36)     /* #202124 */
#define RB_DEFAULT_SURFACE RGB(41, 42, 45)     /* #292A2D */
#define RB_DEFAULT_TABBAR  RGB(24, 25, 28)     /* #18191C */
#define RB_DEFAULT_OMNI    RGB(60, 61, 65)     /* #3C3D41 */
#define RB_DEFAULT_TEXT    RGB(232, 234, 237)  /* #E8EAED */
#define RB_DEFAULT_ACCENT  RGB(167, 139, 250)  /* #A78BFA */

/* ------------------------------------------------------------------ */
/* Control and command identifiers */

#define IDC_OMNI      2001
#define IDC_BACK      2002
#define IDC_FWD       2003
#define IDC_RELOAD    2004
#define IDC_HOME      2005
#define IDC_STAR      2006
#define IDC_MENU      2007
#define IDC_NEWBTN    2008

/* Per-tab buttons: even = tab button, odd = its close button. */
#define RB_ID_TAB_FIRST 10000

#define IDM_NEW_TAB   3001
#define IDM_BOOKMARK  3002
#define IDM_ABOUT     3003
#define IDM_HIST_FIRST 3100
#define RB_HIST_MENU_MAX 16

/* The Profiles submenu: one item per profile, then "Add profile".  The ids
 * are a contiguous range so the handler can turn one back into an index, the
 * same way the history items work. */
#define IDM_PROF_FIRST 3200
#define IDM_PROF_ADD   3199
#define RB_PROF_MENU_MAX 24

#define RB_OMNI_SUBID 1

/* ------------------------------------------------------------------ */
/* Application state. Webview state is opaque here (see webview.c). */

struct RbViews;

typedef struct App {
    HINSTANCE hinst;
    HWND hwnd;

    HWND omni;
    HWND back, fwd, reload, home, star, menu_btn, newtab;
    HWND *tab_btns;    /* parallel to rb_tabs indices */
    HWND *tab_closes;  /* parallel to rb_tabs indices */
    int  tab_slots;

    HBRUSH br_chrome, br_toolbar, br_tab_idle, br_omni, br_accent;
    HFONT  fnt_ui, fnt_omni;

    rb_tabs      *tabs;
    rb_history   *history;
    rb_bookmarks *bookmarks;
    rb_settings  *settings;   /* the GLOBAL settings (BrowserGlobalSettings) */

    /* Per-profile state.  The Win32 layer is a second skin over the same
     * core the GTK layer uses, so it carries the same stores: without the
     * registry there is nowhere for a profile's settings to live, and the
     * filter engine cannot answer at all. */
    rb_profile_registry *profiles;
    rb_switch_machine   *switcher;  /* the profile-switch protocol */
    rb_https_pending    *https;     /* the https upgrades this window may retry */
    rb_filters          *filters;   /* the bundled host list, loaded once */
    rb_downloads        *downloads;

    char *home_url;
    char *path_history;
    char *path_bookmarks;
    char *path_settings;
    char *path_profiles;
    char *path_downloads;
    char *download_dir;         /* where finished files are written */

    /* The profile every tab in this window is running as.  Never NULL once
     * rb_data_init() has succeeded.  Switching it goes through the switch
     * protocol in chrome.c, never by assigning here. */
    char *active_profile_id;

    /* The ACTIVE profile's resolved palette.  The brushes below are built
     * from it, so a theme change recreates them (rb_theme_apply). */
    rb_theme_colors pal;

    long active_id;   /* 0 = none */
    int  loading;
    int  js_enabled;

    struct RbViews *views;   /* owned by webview.c */

    char *hist_menu[RB_HIST_MENU_MAX];  /* URL snapshot for the menu */
    int   hist_menu_n;

    char *prof_menu[RB_PROF_MENU_MAX];  /* profile-id snapshot for the menu */
    int   prof_menu_n;

    int wv_failed;    /* WebView2 unavailable - message shown once */
} App;

extern App g_app;

/* ------------------------------------------------------------------ */
/* Shared helpers (chrome.c) */

wchar_t *rb_utf8_to_wide(const char *s);   /* heap result; NULL-safe */
char    *rb_wide_to_utf8(const wchar_t *s);/* heap result; NULL-safe */
char    *rb_strdup(const char *s);         /* heap result; NULL-safe */
void     rb_set_str(char **dst, char *owned);  /* frees old, takes ownership */
void     rb_mkdirs_utf8(const char *path);    /* recursive mkdir, best effort */
void     rb_mkdirs_wide(const wchar_t *path);  /* recursive mkdir, best effort */

/* Persistence: data dir + files, load at startup, save on change. */
int  rb_data_init(App *app);
void rb_data_shutdown(App *app);   /* final save (WM_DESTROY) */
void rb_data_free(App *app);       /* release everything (after the message loop) */

/* The active profile, or NULL when the registry is empty.  The pointer is
 * INTO the registry: it dies at the next registry mutation. */
const rb_profile *rb_active_profile(App *app);

/* A setting of the ACTIVE PROFILE, with `fallback` when it is absent (or
 * when there is no active profile).  This is the Win32 spelling of the
 * Android app reading its ProfileSettings, and it is what every feature
 * switch should go through rather than touching rb_settings directly. */
const char *rb_pref(App *app, const char *key, const char *fallback);
int         rb_pref_int(App *app, const char *key, int fallback);

/* Writes a per-profile setting back and persists the registry. */
void rb_pref_set(App *app, const char *key, const char *value);
void rb_pref_set_int(App *app, const char *key, int value);

/* Persists the profile registry (settings live inside it). */
void rb_profiles_save(App *app);

/* The ACTIVE profile's content-blocking switches, as the filter engine
 * wants them.  Never NULL-safe: returns the compatibility defaults when
 * there is no active profile. */
rb_filter_options rb_filter_opts(App *app);

/* The active profile's effective User-Agent, malloc'd — or NULL when the
 * engine default should be sent untouched (mode "default", a preset with an
 * empty value, or a blank custom string).  Caller frees. */
char *rb_ua_current(App *app);

/* The active profile's theme, and the palette resolved from it. */
const rb_theme *rb_theme_current(App *app);
void rb_theme_apply(App *app);     /* rebuild palette + brushes, repaint */

/* A modal message box parented to the window. */
void rb_warn(App *app, const char *title, const char *body);

/* Chrome construction + layout. */
int  rb_chrome_create(App *app);
void rb_layout(App *app);
void rb_tabs_rebuild(App *app);

/* UI refresh helpers. */
void rb_update_omni(App *app, const char *url);
void rb_update_titlebar(App *app);
void rb_update_nav(App *app);
void rb_update_star(App *app);
void rb_update_reloadbtn(App *app);
void rb_update_all(App *app);

/* Actions shared by buttons, menu items and keyboard shortcuts. */
void rb_do_new_tab(App *app);
void rb_do_add_tab(App *app, const char *url);   /* NULL = the homepage */
void rb_do_close_tab(App *app, long id);
void rb_do_activate(App *app, long id);
void rb_do_navigate(App *app, const char *url);
void rb_do_toggle_bookmark(App *app);
void rb_do_reload_or_stop(App *app);

/* Runs the core's switch protocol: every step in order, the same one the GTK
 * edition runs, so the two editions cannot diverge on what switching a
 * profile means.  A no-op when `to_id` is already active. */
void rb_do_switch_profile(App *app, const char *to_id);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_CHROME_H */
