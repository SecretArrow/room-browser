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
#define RB_BMBAR_H      30   /* the bookmarks bar, when it is shown */
#define RB_FINDBAR_H    34   /* the find bar, when it is shown */

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

/* The bookmarks bar: one button per bookmark or folder group, and one command
 * per entry of a folder's popup.  The bases sit BELOW RB_ID_TAB_FIRST because
 * rb_draw_button reads an id at or above it as a tab index. */
#define RB_ID_BM_FIRST      4000
#define RB_ID_BM_ITEM_FIRST 8000
#define RB_BM_CTX_OPEN      3901
#define RB_BM_CTX_REMOVE    3902
#define RB_BM_SUBID         3

/* The find bar's own controls, below RB_ID_TAB_FIRST for the same reason, and
 * clear of the bookmarks bar's range (4000 + up to RB_BM_MAX buttons). */
#define RB_ID_FIND_EDIT  5000
#define RB_ID_FIND_PREV  5001
#define RB_ID_FIND_NEXT  5002
#define RB_ID_FIND_CLOSE 5003
#define RB_FIND_SUBID    4

#define IDM_NEW_TAB   3001
#define IDM_BOOKMARK  3002
#define IDM_ABOUT     3003
#define IDM_PREFS     3004
#define IDM_DOWNLOADS 3005
#define IDM_BMBAR     3006
#define IDM_TRANSLATE 3007
#define IDM_FIND      3008
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

    HWND *bm_btns;     /* the bookmarks bar, left to right */
    int   bm_slots;
    int   bmbar_h;     /* the bar's client height: 0 when it is hidden, which
                        * is what tells the page where it starts */

    /* Find in page.  The controls live in a strip at the bottom of the client
     * area and start hidden; findbar_h is that strip's height, 0 while it is
     * hidden, which is what tells the page where it now ends. */
    HWND find_edit;
    HWND find_label;
    HWND find_prev, find_next, find_close;
    int  findbar_h;
    long find_id;     /* the tab the count in find_label belongs to: a result
                       * that lands after a tab switch is not this tab's */

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

/* The ACTIVE profile's store, or NULL when there is none.  For the core
 * helpers that take a whole store rather than one key — the ones that have to
 * read several keys together to answer (rb_screen_claim_of, the WebRTC
 * policy).  Reading a single setting should go through rb_pref() above;
 * this exists so a call site never reaches into rb_profile::settings
 * itself, which is how the two editions would drift apart. */
const rb_settings *rb_pref_store(App *app);

/* Writes a per-profile setting back and persists the registry. */
void rb_pref_set(App *app, const char *key, const char *value);
void rb_pref_set_int(App *app, const char *key, int value);

/* Persists the profile registry (settings live inside it). */
void rb_profiles_save(App *app);

/* Rebuilds App::download_dir from the active profile's download subfolder.
 * Called at startup and again whenever the preferences change that setting;
 * the directory is created if it does not exist. */
void rb_downloads_dir_refresh(App *app);

/* A theme token (0xAARRGGBB) as a GDI COLORREF.  GDI has no alpha, and the
 * palette's tokens are already resolved to opaque colours by the time they
 * reach App::pal, so the alpha byte is simply dropped.  Every layer that
 * paints with the palette goes through this one conversion. */
COLORREF rb_col(unsigned int argb);

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

/* Dark title bar via runtime-loaded dwmapi; a silent no-op where the OS
 * does not have the attribute.  Shared by every window the chrome opens. */
void rb_apply_dark_titlebar(HWND hwnd);

/* A modal message box parented to the window. */
void rb_warn(App *app, const char *title, const char *body);

/* Chrome construction + layout. */
int  rb_chrome_create(App *app);
void rb_layout(App *app);
void rb_tabs_rebuild(App *app);

/* A design pixel (the sizes rb_layout() is written in, at 100%) scaled to the
 * active profile's font scale.  Both the fonts AND these coordinates have to
 * move together: unlike GTK, which sizes a widget from its content, this
 * edition places every control at an absolute position, so a font scaled on
 * its own would overflow the box holding it. */
int  rb_scaled(App *app, int design_px);

/* Rebuilds the UI fonts at the profile's scale, re-hands them to the controls
 * that carry them, and re-lays out.  Called when the setting changes. */
void rb_apply_font_scale(App *app);

/* UI refresh helpers. */
void rb_update_omni(App *app, const char *url);
void rb_update_titlebar(App *app);
void rb_update_nav(App *app);
void rb_update_star(App *app);

/* Rebuilds the bookmarks bar for the active profile.  Called when a bookmark
 * changes, when "Show bookmarks bar" is toggled, on a profile switch and on a
 * text-size change — anything that changes either the rows or their height. */
void rb_bmbar_refresh(App *app);
/* Opens the find bar (focusing its field) or closes it.  Closing also ends
 * the search, so the page keeps no highlight behind it. */
void rb_findbar_show(App *app);
void rb_findbar_hide(App *app);
/* Re-runs the open bar's search against the tab that is now active, or does
 * nothing when the bar is closed.  The count belongs to a page, so it has to
 * follow the page when the active tab changes. */
void rb_findbar_retarget(App *app);
void rb_update_reloadbtn(App *app);
/* The one place App::loading changes: updates the reload/stop glyph and
 * starts or stops the toolbar progress strip.  Every path that begins or ends
 * a load goes through this rather than assigning the field. */
void rb_set_loading(App *app, int on);
/* Repaints the page-load strip for a caller that changed something it
 * depends on (the "Reduce motion" switch), without touching the load
 * state itself. */
void rb_progress_refresh(App *app);
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
