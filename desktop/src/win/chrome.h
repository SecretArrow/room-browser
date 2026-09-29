/*
 * Room Browser (desktop) - Windows chrome header.
 *
 * Pure C11 Win32 chrome for the Room Browser desktop edition.
 * Brave-inspired dark chrome with a purple accent (#A78BFA) matching the
 * Android app. JavaScript is never disabled by default (project policy);
 * the persisted "javascript" setting is honored but defaults to 1.
 *
 * The core stores (tabs / history / bookmarks / settings / url / paths) are
 * implemented in desktop/src/core and linked as rb_core. The declarations
 * below mirror the agreed core API contract verbatim so this platform layer
 * compiles against the exact ABI.
 */
#ifndef RB_WIN_CHROME_H
#define RB_WIN_CHROME_H

#include <windows.h>
#include <commctrl.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* Core API contract (implemented by desktop/src/core, target rb_core) */

typedef struct { long id; char *title; char *url; } rb_tab;
typedef struct { char *url; char *title; long long visited_at; } rb_hist_entry;

typedef struct rb_tabs rb_tabs;
rb_tabs *rb_tabs_new(void);
void rb_tabs_free(rb_tabs *t);
long rb_tabs_add(rb_tabs *t, const char *title, const char *url);
int rb_tabs_count(const rb_tabs *t);
int rb_tabs_close(rb_tabs *t, long id);
rb_tab *rb_tabs_get(rb_tabs *t, long id);
const rb_tab *rb_tabs_at(const rb_tabs *t, int index);

typedef struct rb_history rb_history;
rb_history *rb_history_new(void);
void rb_history_free(rb_history *h);
void rb_history_append(rb_history *h, const char *url, const char *title);
int rb_history_count(const rb_history *h);
const rb_hist_entry *rb_history_recent(const rb_history *h, int n, int *out_n);
int rb_history_load(rb_history *h, const char *path);
int rb_history_save(const rb_history *h, const char *path);

typedef struct rb_bookmarks rb_bookmarks;
rb_bookmarks *rb_bookmarks_new(void);
void rb_bookmarks_free(rb_bookmarks *b);
int rb_bookmarks_add(rb_bookmarks *b, const char *url, const char *title);
int rb_bookmarks_remove(rb_bookmarks *b, const char *url);
int rb_bookmarks_contains(const rb_bookmarks *b, const char *url);
int rb_bookmarks_count(const rb_bookmarks *b);
const char *rb_bookmarks_url_at(const rb_bookmarks *b, int index);
const char *rb_bookmarks_title_at(const rb_bookmarks *b, int index);
int rb_bookmarks_load(rb_bookmarks *b, const char *path);
int rb_bookmarks_save(const rb_bookmarks *b, const char *path);

typedef struct rb_settings rb_settings;
rb_settings *rb_settings_new(void);
void rb_settings_free(rb_settings *s);
const char *rb_settings_get(const rb_settings *s, const char *key, const char *fallback);
int rb_settings_get_int(const rb_settings *s, const char *key, int fallback);
void rb_settings_set(rb_settings *s, const char *key, const char *value);
void rb_settings_set_int(rb_settings *s, const char *key, int value);
int rb_settings_load(rb_settings *s, const char *path);
int rb_settings_save(const rb_settings *s, const char *path);

char *rb_paths_data_dir(void);
void rb_paths_free(char *p);

int  rb_url_is_probably_url(const char *input);
char *rb_url_normalize(const char *input);
char *rb_url_build_search(const char *query);
char *rb_url_decide(const char *input);

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
