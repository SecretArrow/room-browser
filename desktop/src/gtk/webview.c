/*
 * Room Browser (desktop) - WebKitGTK backend.
 *
 * One WebKitWebView per tab (the notebook page IS the webview), signals
 * wired per view, tab resolved by comparing the sender pointer. JavaScript
 * defaults to ON (project policy) and can only be disabled through the
 * persisted "javascript" setting (menu toggle).
 */
#include <string.h>

#include "chrome.h"
#include "webview.h"

/* ------------------------------------------------------------------ */
/* Tab resolution (by webview pointer) */

static GtkTab *rb_tab_by_view(App *app, WebKitWebView *wv)
{
    int i;
    if (!app || !wv) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].wv == wv) return &app->tabs[i];
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Signals */

static void on_notify_title(GObject *obj, GParamSpec *pspec, gpointer user_data)
{
    App *app = (App *)user_data;
    WebKitWebView *wv = WEBKIT_WEB_VIEW(obj);
    GtkTab *gt = rb_tab_by_view(app, wv);
    rb_tab *t;
    const char *title;
    (void)pspec;
    if (!gt) return;
    t = rb_tabs_get(app->store, gt->id);
    if (!t) return;
    title = webkit_web_view_get_title(wv);
    rb_set_str(&t->title, rb_strdup((title && title[0]) ? title : "New Tab"));
    gtk_label_set_text(GTK_LABEL(gt->label), t->title ? t->title : "New Tab");
    if (gt->id == app->active_id) rb_update_titlebar(app);
}

static void on_notify_uri(GObject *obj, GParamSpec *pspec, gpointer user_data)
{
    App *app = (App *)user_data;
    WebKitWebView *wv = WEBKIT_WEB_VIEW(obj);
    GtkTab *gt = rb_tab_by_view(app, wv);
    rb_tab *t;
    const char *uri;
    (void)pspec;
    if (!gt) return;
    t = rb_tabs_get(app->store, gt->id);
    if (!t) return;
    uri = webkit_web_view_get_uri(wv);
    rb_set_str(&t->url, rb_strdup(uri ? uri : ""));
    if (gt->id == app->active_id) {
        rb_update_omni(app, t->url ? t->url : "");
        rb_update_nav(app);
    }
}

static void on_load_changed(WebKitWebView *wv, WebKitLoadEvent event, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkTab *gt = rb_tab_by_view(app, wv);
    rb_tab *t;
    if (!gt) return;
    t = rb_tabs_get(app->store, gt->id);
    if (!t) return;

    switch (event) {
    case WEBKIT_LOAD_STARTED:
        if (gt->id == app->active_id) {
            app->loading = 1;
            rb_update_reloadbtn(app);
        }
        break;
    case WEBKIT_LOAD_FINISHED:
        if (gt->id == app->active_id) {
            app->loading = 0;
            rb_update_reloadbtn(app);
            rb_update_nav(app);
            rb_update_star(app);
        }
        {
            const char *uri = webkit_web_view_get_uri(wv);
            const char *title = webkit_web_view_get_title(wv);
            if (uri && uri[0]) {
                rb_history_append(app->history, uri,
                                  (title && title[0]) ? title : uri);
                if (app->path_history) rb_history_save(app->history, app->path_history);
            }
        }
        break;
    default:
        break;
    }
}

/* ------------------------------------------------------------------ */
/* Public API */

WebKitWebView *rb_gw_new_view(App *app)
{
    WebKitSettings *settings = webkit_settings_new();
    WebKitWebView *wv;

    /* Project policy: JavaScript is NEVER disabled by default. The persisted
       setting is the only way to turn it off and it is applied both to new
       webviews (here) and to the already open ones (rb_gw_apply_js). */
    g_object_set(settings, "enable-javascript", app->js_enabled ? TRUE : FALSE, NULL);
    g_object_set(settings, "enable-developer-extras", FALSE, NULL);

    wv = WEBKIT_WEB_VIEW(webkit_web_view_new_with_settings(settings));
    g_object_unref(settings);

    g_signal_connect(wv, "notify::title", G_CALLBACK(on_notify_title), app);
    g_signal_connect(wv, "notify::uri", G_CALLBACK(on_notify_uri), app);
    g_signal_connect(wv, "load-changed", G_CALLBACK(on_load_changed), app);
    return wv;
}

void rb_gw_apply_js(App *app)
{
    int i;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].wv) {
            WebKitSettings *settings = webkit_web_view_get_settings(app->tabs[i].wv);
            if (settings) {
                g_object_set(settings, "enable-javascript",
                             app->js_enabled ? TRUE : FALSE, NULL);
            }
        }
    }
}

void rb_gw_navigate(App *app, const char *url)
{
    GtkTab *gt = rb_active_tab(app);
    if (!gt || !gt->wv || !url || !url[0]) return;
    webkit_web_view_load_uri(gt->wv, url);
}

void rb_gw_back(App *app)
{
    GtkTab *gt = rb_active_tab(app);
    if (gt && gt->wv) webkit_web_view_go_back(gt->wv);
}

void rb_gw_forward(App *app)
{
    GtkTab *gt = rb_active_tab(app);
    if (gt && gt->wv) webkit_web_view_go_forward(gt->wv);
}

void rb_gw_reload(App *app)
{
    GtkTab *gt = rb_active_tab(app);
    if (gt && gt->wv) webkit_web_view_reload(gt->wv);
}

void rb_gw_stop(App *app)
{
    GtkTab *gt = rb_active_tab(app);
    if (gt && gt->wv) webkit_web_view_stop_loading(gt->wv);
}

void rb_gw_can_nav(App *app, gboolean *can_back, gboolean *can_fwd)
{
    GtkTab *gt = rb_active_tab(app);
    if (can_back) *can_back = FALSE;
    if (can_fwd) *can_fwd = FALSE;
    if (!gt || !gt->wv) return;
    if (can_back) *can_back = webkit_web_view_can_go_back(gt->wv) ? TRUE : FALSE;
    if (can_fwd) *can_fwd = webkit_web_view_can_go_forward(gt->wv) ? TRUE : FALSE;
}
