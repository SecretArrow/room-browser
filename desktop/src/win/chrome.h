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
/* Layout + palette (Brave-inspired dark, purple accent) */

#define RB_WINDOW_W     1280
#define RB_WINDOW_H     800
#define RB_TABSTRIP_H   34
#define RB_TOOLBAR_H    40

#define RB_COL_CHROME     RGB(32, 33, 36)     /* #202124 */
#define RB_COL_TOOLBAR    RGB(41, 42, 45)     /* #292A2D */
#define RB_COL_TAB_ACTIVE RGB(41, 42, 45)     /* #292A2D */
#define RB_COL_TAB_IDLE   RGB(24, 25, 28)     /* #18191C */
#define RB_COL_OMNI_BG    RGB(60, 61, 65)     /* #3C3D41 */
#define RB_COL_TEXT       RGB(232, 234, 237)  /* #E8EAED */
#define RB_COL_ACCENT     RGB(167, 139, 250)  /* #A78BFA */

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
    rb_settings  *settings;

    char *home_url;
    char *path_history;
    char *path_bookmarks;
    char *path_settings;

    long active_id;   /* 0 = none */
    int  loading;
    int  js_enabled;

    struct RbViews *views;   /* owned by webview.c */

    char *hist_menu[RB_HIST_MENU_MAX];  /* URL snapshot for the menu */
    int   hist_menu_n;

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
void rb_do_close_tab(App *app, long id);
void rb_do_activate(App *app, long id);
void rb_do_navigate(App *app, const char *url);
void rb_do_toggle_bookmark(App *app);
void rb_do_reload_or_stop(App *app);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_CHROME_H */
