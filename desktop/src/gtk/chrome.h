/*
 * Room Browser (desktop) - GTK chrome header.
 *
 * Pure C11 GTK3 chrome for the Room Browser desktop edition.
 * Brave-inspired dark UI with a purple accent (#A78BFA). JavaScript is
 * never disabled by default (project policy); the menu toggle (and the
 * persisted "javascript" setting) is the only way to turn it off.
 *
 * The core stores (tabs / history / bookmarks / settings / url / paths) are
 * implemented in desktop/src/core and linked as rb_core. The declarations
 * below mirror the agreed core API contract verbatim so this platform layer
 * compiles against the exact ABI.
 */
#ifndef RB_GTK_CHROME_H
#define RB_GTK_CHROME_H

#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

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
/* Layout constants (mirror the Windows chrome) */

#define RB_WINDOW_W 1280
#define RB_WINDOW_H 800

/* ------------------------------------------------------------------ */
/* Per-tab UI state. Array index == notebook page index == rb_tabs index. */

typedef struct GtkTab {
    long id;
    WebKitWebView *wv;      /* owned by the notebook page */
    GtkWidget *label;       /* GtkLabel inside the tab header */
    GtkWidget *close_btn;   /* the small per-tab close button */
} GtkTab;

typedef struct App {
    GtkApplication *app;
    GtkApplicationWindow *win;

    GtkWidget *notebook;
    GtkWidget *omnibox;
    GtkWidget *back, *fwd, *reload, *home, *star, *menu_btn;

    GtkTab *tabs;           /* parallel to the notebook pages */
    int tabs_n, tabs_cap;

    rb_tabs      *store;
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
    int  silent;       /* suppress switch-page handling while mutating tabs */
} App;

extern App g_app;

/* ------------------------------------------------------------------ */
/* Shared helpers (chrome.c) */

char *rb_strdup(const char *s);                 /* heap result; NULL-safe */
void  rb_set_str(char **dst, char *owned);      /* frees old, takes ownership */

/* Persistence: load at startup, save on change. */
int  rb_data_init(App *app);
void rb_data_shutdown(App *app);   /* final save (on "shutdown" of the app) */
void rb_data_free(App *app);       /* release everything */

/* Chrome construction + refresh. */
void rb_on_activate(GtkApplication *gtk_app, gpointer user_data);
void rb_on_shutdown(GtkApplication *gtk_app, gpointer user_data);
void rb_css_load(void);
void rb_update_omni(App *app, const char *url);
void rb_update_titlebar(App *app);
void rb_update_nav(App *app);
void rb_update_star(App *app);
void rb_update_reloadbtn(App *app);
void rb_update_all(App *app);

/* Actions. */
void rb_do_new_tab(App *app);
void rb_do_close_tab_id(App *app, long id);
void rb_do_navigate(App *app, const char *url);
void rb_do_toggle_bookmark(App *app);
GtkTab *rb_tab_by_widget(App *app, GtkWidget *w);  /* resolve a tab by any of its widgets */
GtkTab *rb_active_tab(App *app);

#ifdef __cplusplus
}
#endif

#endif /* RB_GTK_CHROME_H */
