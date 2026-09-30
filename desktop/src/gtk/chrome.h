/*
 * Room Browser (desktop) - GTK chrome header.
 *
 * Pure C11 GTK3 chrome for the Room Browser desktop edition.
 * Brave-inspired dark UI with a purple accent (#A78BFA). JavaScript is
 * never disabled by default (project policy); the menu toggle (and the
 * persisted "javascript" setting) is the only way to turn it off.
 *
 * The core stores (tabs / history / bookmarks / settings / profiles / url /
 * paths / filters / themes) are implemented in desktop/src/core and linked as
 * rb_core.  Their declarations are pulled in from <core/rb_core.h> rather than
 * repeated here, so this layer cannot drift out of step with the core ABI.
 */
#ifndef RB_GTK_CHROME_H
#define RB_GTK_CHROME_H

#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

#include "core/rb_core.h"

#ifdef __cplusplus
extern "C" {
#endif

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
    GtkWidget *icon;        /* 16x16 GtkImage: the page favicon, hidden when
                             * the page has none (see rb_tab_favicon_set) */
} GtkTab;

typedef struct App {
    GtkApplication *app;
    GtkApplicationWindow *win;

    GtkWidget *notebook;
    GtkWidget *omnibox;
    GtkWidget *back, *fwd, *reload, *home, *star, *menu_btn;
    GtkWidget *bmbar;       /* the bookmarks bar under the toolbar; its
                             * children are rebuilt by rb_bookmarks_bar_refresh
                             * and it hides itself when there is nothing to
                             * show.  May stay NULL when the pref is off. */
    GtkWidget *prog_area;   /* the 2px page-load strip under the toolbar */
    GtkWidget *status;      /* floating link-target label (bottom-left) */
    GtkWidget *overlay;     /* the GtkOverlay that floats `status` */

    /* Find in page.  The bar is the window's and starts hidden; the search it
     * drives belongs to a tab, so find_id is the tab it was last run on and
     * is what tells a tab switch to finish it on the tab being left. */
    GtkWidget *findbar;
    GtkWidget *find_entry;
    GtkWidget *find_label;
    long find_id;

    GtkTab *tabs;           /* parallel to the notebook pages */
    int tabs_n, tabs_cap;

    rb_tabs      *store;
    rb_history   *history;
    rb_bookmarks *bookmarks;
    rb_downloads *downloads;
    rb_settings  *settings;     /* the GLOBAL settings (BrowserGlobalSettings) */
    rb_profile_registry *profiles;
    rb_switch_machine   *switcher;
    rb_https_pending    *https;  /* the https upgrades this window may retry */
    rb_filters          *filters; /* the bundled host list, loaded once */

    /* The ACTIVE profile's WebKit context and the data manager it is built
     * on.  Owned here; every view is created from it, and a profile switch
     * tears it down and builds the next profile's.  This is what keeps
     * cookies, cache and site storage from crossing profiles. */
    WebKitWebContext *ctx;

    /* The ACTIVE profile's compiled content blocker, and the JSON it was
     * compiled from.  Both are per profile because the rules are filtered by
     * that profile's switches.  Owned here; replaced on a settings change and
     * dropped on a switch. */
    WebKitUserContentFilter *cb_filter;
    char *cb_json;
    WebKitUserContentFilterStore *cb_store;

    char *home_url;
    char *path_history;
    char *path_bookmarks;
    char *path_settings;
    char *path_profiles;
    char *path_downloads;
    char *download_dir;         /* where finished files are written */

    /* The profile every tab in this window is running as.  Never NULL once
     * rb_data_init() has succeeded: a fresh data directory gets one created
     * for it.  Switching it goes through rb_switch_* and the platform steps
     * in chrome.c, never by assigning here. */
    char *active_profile_id;

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

/* The active profile, or NULL when the registry is empty.  The pointer is
 * INTO the registry: it dies at the next registry mutation. */
const rb_profile *rb_active_profile(App *app);

/* A setting of the ACTIVE PROFILE, with `fallback` when it is absent (or
 * when there is no active profile).  This is the desktop's spelling of the
 * Android app reading its ProfileSettings, and it is what every feature
 * switch below should go through rather than touching rb_settings directly. */
const char *rb_pref(App *app, const char *key, const char *fallback);
int         rb_pref_int(App *app, const char *key, int fallback);

/* Writes a per-profile setting back and persists the registry. */
void rb_pref_set(App *app, const char *key, const char *value);
void rb_pref_set_int(App *app, const char *key, int value);

/* Persists the profile registry (settings live inside it). */
void rb_profiles_save(App *app);

/* The ACTIVE profile's content-blocking switches, as the filter engine wants
 * them.  Never NULL-safe: returns the compatibility defaults when there is no
 * active profile. */
rb_filter_options rb_filter_opts(App *app);

/* The active profile's effective User-Agent, malloc'd — or NULL when the
 * engine default should be sent untouched (mode "default", a preset with an
 * empty value, or a blank custom string).  Caller frees. */
char *rb_ua_current(App *app);

/* Chrome construction + refresh. */
void rb_on_activate(GtkApplication *gtk_app, gpointer user_data);
void rb_on_shutdown(GtkApplication *gtk_app, gpointer user_data);
void rb_css_load(App *app);       /* (re)loads the stylesheet from the theme */
const rb_theme *rb_theme_current(App *app);  /* the active profile's theme */
void rb_apply_font_scale(App *app);  /* applies the profile's UI font scale */
void rb_apply_reduced_motion(App *app);  /* ditto for "Reduce motion" */
void rb_update_omni(App *app, const char *url);
void rb_update_titlebar(App *app);
void rb_update_nav(App *app);
void rb_update_star(App *app);
void rb_update_reloadbtn(App *app);
/* The one place App::loading changes: updates the reload/stop glyph and
 * starts or stops the toolbar progress strip.  Every path that begins or
 * ends a load goes through this rather than assigning the field. */
void rb_set_loading(App *app, int on);
void rb_update_all(App *app);

/* A modal message box parented to the window.  `body` is secondary text and
 * may be NULL. */
void rb_warn(App *app, const char *title, const char *body);

/* The per-profile preferences editor (Android's ProfileSettingsScreen). */
void rb_show_prefs_dialog(App *app);

/* Actions. */
void rb_do_new_tab(App *app);
void rb_do_add_tab(App *app, const char *url);   /* NULL/"" -> the homepage */
void rb_do_close_tab_id(App *app, long id);
void rb_do_navigate(App *app, const char *url);
void rb_do_toggle_bookmark(App *app);
void rb_do_switch_profile(App *app, const char *to_id);
GtkTab *rb_tab_by_widget(App *app, GtkWidget *w);  /* resolve a tab by any of its widgets */
GtkTab *rb_active_tab(App *app);

/* Repaints a tab's favicon from its view.  Called on WebKit's
 * "notify::favicon"; a page with no favicon clears the image and hides it,
 * so the label takes the whole tab rather than sitting beside a gap. */
void rb_tab_favicon_set(GtkTab *tab, WebKitWebView *wv);

/* Wires a new view's find controller to the window's find bar.  Called from
 * rb_gw_new_view for every view, because the controller belongs to the view
 * and not to the window. */
void rb_find_watch(App *app, WebKitWebView *wv);

/* The floating link-target label at the bottom-left (Brave's status
 * bubble).  NULL or an empty string hides it. */
void rb_status_show(App *app, const char *text);

#ifdef __cplusplus
}
#endif

#endif /* RB_GTK_CHROME_H */
