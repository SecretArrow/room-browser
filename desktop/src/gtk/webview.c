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
/* HTTPS-First fallback
 *
 * rb_https.h reasons in the engine error codes the two platform layers
 * report.  WebKitGTK reports through a GError domain instead, and it is
 * coarser than Chromium: a refused connection, a timeout and a TLS
 * handshake failure all arrive as one of the two codes below, and there is
 * no separate certificate-error domain in the public API.  The mapping is
 * therefore deliberately conservative — the failures that must never be
 * retried (the user pressing Stop, a file:// that is not there, a
 * navigation the policy blocked) are answered with a code that
 * rb_https_is_recoverable() calls unrecoverable, because retrying those
 * would fight the user or the policy rather than rescue an https-only
 * site. */

/* Not one of the rb_https_error values, so is_recoverable() reports 0. */
#define RB_GTK_HTTPS_NO_RETRY 0

static int rb_gtk_https_code(const GError *error)
{
    if (error == NULL) {
        return RB_GTK_HTTPS_NO_RETRY;
    }
    if (error->domain == WEBKIT_NETWORK_ERROR) {
        switch (error->code) {
        case WEBKIT_NETWORK_ERROR_TRANSPORT:
            return RB_HTTPS_ERR_SSL_HANDSHAKE;
        case WEBKIT_NETWORK_ERROR_FAILED:
            return RB_HTTPS_ERR_CONNECT;
        default:
            /* CANCELLED (the user stopped the load, or a redirect replaced
             * it), UNKNOWN_PROTOCOL, FILE_DOES_NOT_EXIST: nothing an http
             * retry would fix. */
            return RB_GTK_HTTPS_NO_RETRY;
        }
    }
    return RB_GTK_HTTPS_NO_RETRY;
}

static gboolean on_load_failed(WebKitWebView *wv, WebKitLoadEvent event,
                               const gchar *uri, GError *error,
                               gpointer user_data)
{
    App *app = (App *)user_data;
    char *retry;

    (void)event;
    if (!app || !app->https || !uri || !uri[0]) {
        return FALSE;
    }

    /* Only a URL THIS window upgraded to https has an entry, and consuming it
     * drops the entry, so the retry can fire at most once per navigation and
     * a failure of a URL the user typed as http is never silently turned
     * into a different scheme. */
    retry = rb_https_retry_url(app->https, uri, rb_gtk_https_code(error));
    if (retry == NULL) {
        return FALSE;   /* not our upgrade: let WebKit show the error page */
    }

    if (retry[0]) {
        GtkTab *gt = rb_tab_by_view(app, wv);
        rb_tab *t = gt ? rb_tabs_get(app->store, gt->id) : NULL;
        if (t) {
            rb_set_str(&t->url, rb_strdup(retry));
        }
        if (gt && gt->id == app->active_id) {
            rb_update_omni(app, retry);
        }
        webkit_web_view_load_uri(wv, retry);
    }
    free(retry);
    return TRUE;   /* stop the failed load: an http retry is starting */
}

/* ------------------------------------------------------------------ */
/* Downloads
 *
 * The transfer belongs to WebKit (it already does chunked encoding,
 * redirects, proxies, cookies and TLS); the RECORD belongs to rb_downloads,
 * which is what the downloads window, the persistence and the state machine
 * read.  Each WebKitDownload carries its record id as object data, so the
 * progress and completion callbacks can find their row without a side table.
 *
 * NOT PORTED: RB_DOWNLOAD_MAX_PARALLEL.  WebKitDownload has no suspend, so
 * a transfer cannot be held in QUEUED once WebKit has announced it — the
 * core still models the queue (and the Android edition still enforces it),
 * but on GTK every announced download starts immediately.  rb_downloads_pump
 * is therefore unused here. */

static void rb_dl_save(App *app)
{
    if (app && app->downloads && app->path_downloads) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
}

static void rb_dl_set_id(WebKitDownload *dl, long long id)
{
    long long *slot = g_new(long long, 1);
    *slot = id;
    g_object_set_data_full(G_OBJECT(dl), "rb-download-id", slot, g_free);
}

static long long rb_dl_get_id(WebKitDownload *dl)
{
    long long *slot = (long long *)g_object_get_data(G_OBJECT(dl), "rb-download-id");
    return slot ? *slot : 0;
}

/* decide-destination fires once WebKit knows the response, so the suggested
 * filename already accounts for Content-Disposition.  We name the file with
 * the core's sanitizer (which is also what de-duplicates it against the
 * profile's other downloads at enqueue time) and hand WebKit the full path.
 *
 * A file already sitting at that path is never replaced: the name gets the
 * usual " (1)", " (2)" suffix, because silently overwriting a file the user
 * downloaded earlier is not a thing a browser may do. */
static gboolean on_dl_decide_destination(WebKitDownload *dl,
                                         const gchar *suggested_filename,
                                         gpointer user_data)
{
    App *app = (App *)user_data;
    long long id = rb_dl_get_id(dl);
    const rb_download *rec;
    const char *dir = app ? app->download_dir : NULL;
    char *path;
    int n;
    (void)suggested_filename;

    if (!app || id == 0 || dir == NULL) return FALSE;
    rec = rb_downloads_by_id(app->downloads, id);
    if (!rec || !rec->file_name || !rec->file_name[0]) return FALSE;

    path = rb_paths_join(dir, rec->file_name);
    if (path == NULL) return FALSE;

    for (n = 1; n < 10000 && rb_paths_is_file(path); n++) {
        const char *dot = strrchr(rec->file_name, '.');
        char *cand;
        if (dot != NULL && dot != rec->file_name) {
            size_t k = (size_t)(dot - rec->file_name);
            size_t need = strlen(dir) + 1 + k + 24 + strlen(dot) + 1;
            cand = (char *)malloc(need);
            if (cand == NULL) break;
            snprintf(cand, need, "%s/%.*s (%d)%s", dir, (int)k,
                     rec->file_name, n, dot);
        } else {
            size_t need = strlen(dir) + strlen(rec->file_name) + 24;
            cand = (char *)malloc(need);
            if (cand == NULL) break;
            snprintf(cand, need, "%s/%s (%d)", dir, rec->file_name, n);
        }
        free(path);
        path = cand;
    }

    if (path != NULL) {
        webkit_download_set_destination(dl, path);
    }
    free(path);
    return path != NULL;
}

static void on_dl_received_data(WebKitDownload *dl, guint64 length,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    long long id = rb_dl_get_id(dl);
    WebKitURIResponse *res;
    long long total = -1;
    (void)length;

    if (!app || id == 0) return;
    res = webkit_download_get_response(dl);
    if (res != NULL) {
        guint64 len = webkit_uri_response_get_content_length(res);
        /* 0 means "the server did not say", which is what -1 is for. */
        total = (len > 0) ? (long long)len : -1;
    }
    rb_downloads_set_progress(app->downloads, id,
        (long long)webkit_download_get_received_data_length(dl), total);
}

static void on_dl_finished(WebKitDownload *dl, gpointer user_data)
{
    App *app = (App *)user_data;
    long long id = rb_dl_get_id(dl);
    const gchar *dest;

    if (!app || id == 0) return;
    dest = webkit_download_get_destination(dl);
    rb_downloads_complete(app->downloads, id, dest ? dest : "",
        (long long)webkit_download_get_received_data_length(dl),
        rb_profile_now_ms());
    rb_dl_save(app);
}

static void on_dl_failed(WebKitDownload *dl, GError *error, gpointer user_data)
{
    App *app = (App *)user_data;
    long long id = rb_dl_get_id(dl);
    int cancelled;

    if (!app || id == 0) return;
    /* A cancel arrives through the same signal, and the store keeps the two
     * apart: the user stopping a download is not a failure to report. */
    cancelled = (error != NULL &&
                 error->domain == WEBKIT_DOWNLOAD_ERROR &&
                 error->code == WEBKIT_DOWNLOAD_ERROR_CANCELLED_BY_USER);
    rb_downloads_set_status(app->downloads, id,
        cancelled ? RB_DL_CANCELLED : RB_DL_FAILED,
        (error != NULL) ? error->message : NULL);
    rb_dl_save(app);
}

static void on_download_started(WebKitWebContext *context,
                                WebKitDownload *dl, gpointer user_data)
{
    App *app = (App *)user_data;
    WebKitURIRequest *req;
    WebKitURIResponse *res;
    const gchar *uri = NULL;
    const gchar *mime = NULL;
    const gchar *suggested = NULL;
    const rb_profile *prof;
    long long id;
    (void)context;

    if (!app || !dl || !app->downloads) return;

    req = webkit_download_get_request(dl);
    if (req != NULL) uri = webkit_uri_request_get_uri(req);
    res = webkit_download_get_response(dl);
    if (res != NULL) {
        mime = webkit_uri_response_get_mime_type(res);
        suggested = webkit_uri_response_get_suggested_filename(res);
    }
    if (uri == NULL || uri[0] == '\0') return;

    prof = rb_active_profile(app);
    id = rb_downloads_enqueue(app->downloads,
                              (prof != NULL) ? prof->id : "",
                              uri, suggested, mime, rb_profile_now_ms());
    if (id == 0) return;

    /* WebKit falls back to its own temporary location unless we take over. */
    webkit_download_set_allow_overwrite(dl, FALSE);
    rb_dl_set_id(dl, id);
    g_signal_connect(dl, "decide-destination",
                     G_CALLBACK(on_dl_decide_destination), app);
    g_signal_connect(dl, "received-data",
                     G_CALLBACK(on_dl_received_data), app);
    g_signal_connect(dl, "finished", G_CALLBACK(on_dl_finished), app);
    g_signal_connect(dl, "failed", G_CALLBACK(on_dl_failed), app);
    rb_dl_save(app);
}

void rb_gw_downloads_init(App *app)
{
    /* The default context is shared by every view, so this is wired once. */
    WebKitWebContext *ctx = webkit_web_context_get_default();
    if (ctx != NULL) {
        g_signal_connect(ctx, "download-started",
                         G_CALLBACK(on_download_started), app);
    }
}

/* ------------------------------------------------------------------ */
/* User-Agent
 *
 * The profile owns the UA (ua_mode + preset/custom), exactly as on Android.
 * A mode that resolves to NULL means "send the engine's own User-Agent", so
 * the engine default is captured once and restored on that path — otherwise
 * switching a profile back from a preset would leave the preset stuck on. */

static char *g_default_ua = NULL;

static void rb_gw_ua_apply_to(App *app, WebKitSettings *settings)
{
    char *ua;
    if (settings == NULL) return;
    if (g_default_ua == NULL) {
        g_default_ua = g_strdup(webkit_settings_get_user_agent(settings));
    }
    ua = rb_ua_current(app);
    g_object_set(settings, "user-agent",
                 (ua != NULL) ? ua : (g_default_ua ? g_default_ua : ""), NULL);
    free(ua);
}

void rb_gw_apply_ua(App *app)
{
    int i;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].wv) {
            rb_gw_ua_apply_to(app, webkit_web_view_get_settings(app->tabs[i].wv));
        }
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
    rb_gw_ua_apply_to(app, settings);

    wv = WEBKIT_WEB_VIEW(webkit_web_view_new_with_settings(settings));
    g_object_unref(settings);

    g_signal_connect(wv, "notify::title", G_CALLBACK(on_notify_title), app);
    g_signal_connect(wv, "notify::uri", G_CALLBACK(on_notify_uri), app);
    g_signal_connect(wv, "load-changed", G_CALLBACK(on_load_changed), app);
    g_signal_connect(wv, "load-failed", G_CALLBACK(on_load_failed), app);
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
