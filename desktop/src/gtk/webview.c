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

/* Defined below, next to the doc comment that explains what they may and may
 * not do; the context and view constructors need them first. */
static void rb_gw_web_settings_to(App *app, WebKitSettings *s);
static void rb_gw_cookie_policy_apply(App *app);

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

/* Brave shows the target of a hovered link in a bubble at the bottom-left.
 * Same information, and the only way to see where a link goes before
 * committing to it. */
static void on_mouse_target_changed(WebKitWebView *wv, WebKitHitTestResult *hit,
                                    guint modifiers, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)wv;
    (void)modifiers;
    if (hit && webkit_hit_test_result_context_is_link(hit)) {
        rb_status_show(app, webkit_hit_test_result_get_link_uri(hit));
    } else {
        rb_status_show(app, NULL);
    }
}

static void on_notify_favicon(GObject *obj, GParamSpec *pspec, gpointer user_data)
{
    App *app = (App *)user_data;
    WebKitWebView *wv = WEBKIT_WEB_VIEW(obj);
    GtkTab *gt = rb_tab_by_view(app, wv);
    (void)pspec;
    if (gt) rb_tab_favicon_set(gt, wv);
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
            rb_set_loading(app, 1);
        }
        break;
    case WEBKIT_LOAD_FINISHED:
        if (gt->id == app->active_id) {
            rb_set_loading(app, 0);
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
    /* Every profile has its OWN context, so this is wired once per context,
     * from rb_gw_context_new — never on the shared default context, which
     * this build does not use. */
    if (app != NULL && app->ctx != NULL) {
        g_signal_connect(app->ctx, "download-started",
                         G_CALLBACK(on_download_started), app);
    }
}

/* ------------------------------------------------------------------ */
/* Navigation policy
 *
 * The half of Android's WebViewClient that WebKitGTK splits differently.
 * Android's shouldInterceptRequest sees every subresource (that half is the
 * content blocker above); shouldOverrideUrlLoading sees main-frame
 * navigations, and this is its GTK counterpart.
 *
 * Mirrored from WebClients.kt exactly:
 *   - a main-frame navigation into a MALICIOUS host is blocked outright when
 *     block-malicious is on, and a suspicious-looking one is allowed but
 *     reported (Android's onSuspiciousSite);
 *   - a popup is blocked when block-popups is on, counted, and reported
 *     (Android's onPopupBlocked), and otherwise becomes a tab.
 *
 * The subresource switches do NOT apply here: Android returns early for the
 * main frame ("if (request.isForMainFrame) return null"), so a page is never
 * blocked by the ad or tracker list merely for being navigated to.  The
 * content blocker's rules exclude documents for exactly that reason. */

static gboolean on_decide_policy(WebKitWebView *wv, WebKitPolicyDecision *decision,
                                 WebKitPolicyDecisionType type, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_filter_options opts;
    char *uri = NULL;
    char *host = NULL;
    gboolean handled = FALSE;

    (void)wv;
    if (app == NULL || decision == NULL) return FALSE;
    opts = rb_filter_opts(app);

    if (type == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) {
        WebKitNavigationAction *action =
            webkit_navigation_policy_decision_get_navigation_action(
                WEBKIT_NAVIGATION_POLICY_DECISION(decision));
        WebKitURIRequest *req =
            (action != NULL) ? webkit_navigation_action_get_request(action) : NULL;
        if (req != NULL) uri = rb_strdup(webkit_uri_request_get_uri(req));
    } else if (type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
        WebKitNavigationAction *action =
            webkit_navigation_policy_decision_get_navigation_action(
                WEBKIT_NAVIGATION_POLICY_DECISION(decision));
        WebKitURIRequest *req =
            (action != NULL) ? webkit_navigation_action_get_request(action) : NULL;
        if (req != NULL) uri = rb_strdup(webkit_uri_request_get_uri(req));

        if (opts.block_popups) {
            webkit_policy_decision_ignore(decision);
            if (app->filters != NULL) {
                rb_filters_count_block(app->filters, RB_FILTER_POPUP);
            }
            handled = TRUE;
        }
    } else {
        return FALSE;   /* responses are WebKit's business */
    }

    if (!handled && uri != NULL && uri[0] != '\0') {
        host = rb_url_host_of(uri);
        if (host != NULL && host[0] != '\0' && opts.block_malicious &&
            app->filters != NULL) {
            /* blockedCategory only consults the malicious list here, because
             * that is the list a main frame is allowed to be judged by. */
            rb_filter_category cat = rb_filters_blocked_category(app->filters, host);
            if (cat == RB_FILTER_MALICIOUS) {
                rb_filters_count_block(app->filters, RB_FILTER_MALICIOUS);
                webkit_policy_decision_ignore(decision);
                rb_warn(app, "Blocked a malicious site",
                        "This address is on the malicious-site blocklist, so "
                        "the page was not loaded.");
                handled = TRUE;
            }
        }
        if (!handled && host != NULL && host[0] != '\0') {
            /* Not on any list: the heuristics still get to speak, and the
             * page is allowed through — a warning, not a block. */
            unsigned int signals = rb_filters_suspicious_signals(uri);
            if (signals != RB_SUSPICIOUS_NONE) {
                char *text = rb_filters_suspicious_text(signals);
                if (text != NULL && text[0] != '\0') {
                    char *body = (char *)malloc(strlen(text) + 64);
                    if (body != NULL) {
                        sprintf(body, "Caution: %s", text);
                        rb_warn(app, "Suspicious address", body);
                        free(body);
                    }
                }
                free(text);
            }
        }
    }

    free(host);
    free(uri);
    return handled;
}

/* A page asking for a window becomes a tab, which is what Android does with
 * a popup that the profile has not blocked.  Returning NULL would drop it
 * silently. */
static WebKitWebView *on_create(WebKitWebView *wv, WebKitNavigationAction *action,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    WebKitURIRequest *req;
    const char *uri;
    GtkTab *gt;

    (void)wv;
    if (app == NULL || action == NULL) return NULL;
    req = webkit_navigation_action_get_request(action);
    uri = (req != NULL) ? webkit_uri_request_get_uri(req) : NULL;
    if (uri == NULL || uri[0] == '\0') return NULL;

    rb_do_add_tab(app, uri);
    gt = rb_active_tab(app);
    return (gt != NULL) ? gt->wv : NULL;
}

static gboolean on_close_view(WebKitWebView *wv, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkTab *gt = rb_tab_by_view(app, wv);
    if (gt != NULL) rb_do_close_tab_id(app, gt->id);
    return TRUE;   /* this layer took care of it */
}

/* ------------------------------------------------------------------ */
/* Content blocking
 *
 * Android blocks in shouldInterceptRequest, where the engine hands it every
 * subresource URL.  WebKitGTK has no such hook — decide-policy covers
 * navigations and responses, not subresources — so the same host list is
 * handed to WebKit as a content blocker instead.  WebKit compiles it and
 * applies it to subresources in its own process, which is strictly broader
 * than decide-policy could reach, and it is the mechanism Safari uses for the
 * same job.
 *
 * The rules come from the SAME bundled list the Android edition ships
 * (RB_FILTERLIST_LINES), selected by the profile's own switches, so the two
 * editions block the same hosts — and neither ever fetches a list at runtime.
 *
 * The compile is asynchronous.  A view whose load starts before it finishes
 * is unfiltered for that load only; rb_gw_new_view installs the compiled
 * filter on every later view, and the callback below installs it on every
 * view that already exists. */

/* Hosts in the bundled list are plain DNS names, so the only regex
 * metacharacter that can appear is '.' — but a malformed line must never
 * produce a rule that matches more than it should, so anything unexpected is
 * refused outright rather than escaped and hoped for. */
static int rb_cb_escape_host(const char *host, char *out, size_t out_len)
{
    size_t o = 0;
    const char *p;

    if (host == NULL || host[0] == '\0') return 0;
    for (p = host; *p != '\0'; p++) {
        unsigned char c = (unsigned char)*p;
        int alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '-' || c == '_';
        if (c == '.') {
            if (o + 3 >= out_len) return 0;
            out[o++] = '\\';
            out[o++] = '.';
        } else if (alnum) {
            if (o + 2 >= out_len) return 0;
            out[o++] = (char)c;
        } else {
            return 0;   /* not a DNS name: refuse rather than guess */
        }
    }
    out[o] = '\0';
    return o > 0;
}

/* One rule per host, covering the host and its subdomains over http(s).
 * "([^/]+\.)?" is the subdomain part; it cannot match across a '/' so it
 * stays inside the authority, and "([/:]|$)" ends the match at the port or
 * the path so "example.com.evil.test" is not caught by "example.com". */

/* The rule one host contributes, written into `out` (which must hold at least
 * RB_CB_RULE_MAX bytes).  Returns the length, or 0 when the host is not a
 * plain DNS name and therefore contributes nothing. */
#define RB_CB_RULE_MAX 512

static size_t rb_cb_rule(char *out, size_t out_len, const char *host, int comma)
{
    char esc[256];
    int n;

    if (!rb_cb_escape_host(host, esc, sizeof esc)) return 0;
    /* "resource-type" excludes "document": Android never lets the host list
     * judge a main-frame navigation ("if (request.isForMainFrame) return
     * null"), and a content blocker would otherwise apply the same rules to
     * top-level loads and blank out pages the user typed. */
    n = snprintf(out, out_len,
                 "%s{\"trigger\":{\"url-filter\":\"^https?://([^/]+\\\\.)?%s([/:]|$)\","
                 "\"url-filter-is-case-sensitive\":false,"
                 "\"resource-type\":[\"image\",\"style-sheet\",\"script\",\"font\","
                 "\"media\",\"raw\",\"svg-document\",\"popup\"]},"
                 "\"action\":{\"type\":\"block\"}}",
                 comma ? "," : "", esc);
    if (n < 0 || (size_t)n >= out_len) return 0;
    return (size_t)n;
}

/* Selects the lines the profile's switches ask for.  A category the profile
 * has NOT enabled produces no rule at all — the list is not a floor.
 *
 * Built with malloc/free rather than GString: the result is handed to
 * rb_set_str() and freed with free(), and mixing GLib's allocator with it
 * would be a latent hazard for no benefit. */
static char *rb_cb_build_json(const rb_filter_options *o)
{
    char rule[RB_CB_RULE_MAX];
    size_t cap = 3;   /* "[" + "]" + NUL */
    char *json;
    char *w;
    int first = 1;
    int i;

    for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
        const char *line = RB_FILTERLIST_LINES[i];
        const char *bar;
        size_t cat_len;
        int want = 0;

        if (line[0] == '#' || line[0] == '\0') continue;
        bar = strchr(line, '|');
        if (bar == NULL || bar[1] == '\0') continue;
        cat_len = (size_t)(bar - line);
        if (cat_len == 2 && strncmp(line, "ad", 2) == 0) {
            want = o->block_ads;
        } else if (cat_len == 7 && strncmp(line, "tracker", 7) == 0) {
            want = o->block_trackers;
        } else if (cat_len == 9 && strncmp(line, "malicious", 9) == 0) {
            want = o->block_malicious;
        }
        if (want) cap += rb_cb_rule(rule, sizeof rule, bar + 1, 1);
    }

    json = (char *)malloc(cap);
    if (json == NULL) return NULL;
    w = json;
    *w++ = '[';
    for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
        const char *line = RB_FILTERLIST_LINES[i];
        const char *bar;
        size_t cat_len;
        int want = 0;
        size_t n;

        if (line[0] == '#' || line[0] == '\0') continue;
        bar = strchr(line, '|');
        if (bar == NULL || bar[1] == '\0') continue;
        cat_len = (size_t)(bar - line);
        if (cat_len == 2 && strncmp(line, "ad", 2) == 0) {
            want = o->block_ads;
        } else if (cat_len == 7 && strncmp(line, "tracker", 7) == 0) {
            want = o->block_trackers;
        } else if (cat_len == 9 && strncmp(line, "malicious", 9) == 0) {
            want = o->block_malicious;
        }
        if (!want) continue;
        n = rb_cb_rule(w, (size_t)(json + cap - w), bar + 1, !first);
        if (n == 0) continue;
        w += n;
        first = 0;
    }
    *w++ = ']';
    *w = '\0';
    return json;
}

typedef struct {
    App *app;
    char *json;
    char identifier[RB_PROFILE_ID_LEN + 32];
} rb_cb_job;

static void rb_cb_saved(GObject *src, GAsyncResult *res, gpointer user_data)
{
    rb_cb_job *job = (rb_cb_job *)user_data;
    App *app = job->app;
    GError *err = NULL;
    WebKitUserContentFilter *filter;

    filter = webkit_user_content_filter_store_save_finish(
        WEBKIT_USER_CONTENT_FILTER_STORE(src), res, &err);
    if (filter == NULL) {
        fprintf(stderr, "roombrowser: content blocker not applied: %s\n",
                (err != NULL) ? err->message : "unknown error");
        g_clear_error(&err);
        free(job->json);
        free(job);
        return;
    }

    /* The compile finished after the request that asked for it may have been
     * superseded — by a preference change or a profile switch.  Comparing the
     * JSON is what stops a stale compile from overwriting newer rules. */
    if (app != NULL && app->cb_json != NULL &&
        strcmp(app->cb_json, job->json) == 0) {
        int i;
        if (app->cb_filter != NULL) g_object_unref(app->cb_filter);
        app->cb_filter = filter;   /* takes the reference save_finish gave us */
        for (i = 0; i < app->tabs_n; i++) {
            if (app->tabs[i].wv != NULL) {
                WebKitUserContentManager *ucm =
                    webkit_web_view_get_user_content_manager(app->tabs[i].wv);
                webkit_user_content_manager_remove_all_filters(ucm);
                webkit_user_content_manager_add_filter(ucm, app->cb_filter);
            }
        }
    } else {
        g_object_unref(filter);
    }

    free(job->json);
    free(job);
}

void rb_gw_content_blocking_clear(App *app)
{
    if (app == NULL) return;
    if (app->cb_filter != NULL) {
        g_object_unref(app->cb_filter);
        app->cb_filter = NULL;
    }
    rb_set_str(&app->cb_json, NULL);
}

void rb_gw_content_blocking_apply(App *app)
{
    rb_filter_options opts;
    char *json;
    rb_cb_job *job;
    char *data;
    char *store_path;

    if (app == NULL || app->filters == NULL) return;

    opts = rb_filter_opts(app);
    json = rb_cb_build_json(&opts);
    if (json == NULL) return;

    /* Unchanged switches: keep the compiled rules and skip the round trip.
     * WebKit's compile is not free and it happens on every window activation
     * and every profile switch otherwise. */
    if (app->cb_json != NULL && strcmp(app->cb_json, json) == 0) {
        free(json);
        return;
    }
    rb_set_str(&app->cb_json, json);   /* takes ownership; json is now app's */

    if (app->cb_store == NULL) {
        /* One store per run, under the user's cache dir: the compiled blobs
         * are a cache, so they belong where a cache belongs. */
        data = g_build_filename(g_get_user_cache_dir(), "roombrowser",
                                "content-filters", NULL);
        store_path = data;
        g_mkdir_with_parents(store_path, 0700);
        app->cb_store = webkit_user_content_filter_store_new(store_path);
        g_free(store_path);
    }

    job = g_new0(rb_cb_job, 1);
    job->app = app;
    job->json = rb_strdup(app->cb_json);
    /* The identifier carries the profile so two profiles' rules never share
     * a compiled blob in the store. */
    snprintf(job->identifier, sizeof job->identifier, "rb-%s",
             (app->active_profile_id != NULL) ? app->active_profile_id : "none");

    {
        GBytes *bytes = g_bytes_new(job->json, strlen(job->json));
        webkit_user_content_filter_store_save(app->cb_store, job->identifier,
                                              bytes, NULL, rb_cb_saved, job);
        g_bytes_unref(bytes);
    }
}

/* ------------------------------------------------------------------ */
/* Per-profile WebKit contexts
 *
 * The desktop counterpart of Android's per-profile WebView data-directory
 * suffix: each profile gets a WebsiteDataManager rooted in its own
 * browser_data / cache directories, so cookies, HTTP cache, localStorage and
 * IndexedDB of one profile are unreachable from another.  That is also what
 * makes the switch protocol's DESTROY_BROWSER_CONTEXT / LOAD_NEW_PROFILE_
 * CONTEXT steps real work here rather than a process restart.
 *
 * Everything created here is released by rb_gw_context_free, which the
 * switch calls only after every view built on it is gone. */

void rb_gw_context_new(App *app)
{
    const rb_profile *p;
    char *data = NULL;
    char *data_dir = NULL;
    char *cache_dir = NULL;
    WebKitWebsiteDataManager *manager;

    if (app == NULL || app->ctx != NULL) return;
    p = rb_active_profile(app);
    if (p == NULL) return;

    data = rb_paths_data_dir();
    if (data != NULL && rb_profile_ensure_dirs(data, p->id) == 0) {
        data_dir = rb_profile_subdir(data, p->id, RB_PROFILE_DIR_BROWSER_DATA);
        cache_dir = rb_profile_subdir(data, p->id, RB_PROFILE_DIR_CACHE);
    }
    if (data != NULL) rb_paths_free(data);

    if (data_dir != NULL && cache_dir != NULL) {
        manager = webkit_website_data_manager_new(
            "base-data-directory", data_dir,
            "base-cache-directory", cache_dir,
            NULL);
    } else {
        /* The profile directories could not be created.  Fall back to a
         * manager with the default locations, which means this run has NO
         * profile isolation — say so rather than pretend. */
        fprintf(stderr, "roombrowser: cannot create profile directories for "
                        "%s; the profile will share the default site data\n",
                p->id);
        manager = webkit_website_data_manager_new(NULL);
    }
    free(data_dir);
    free(cache_dir);

    app->ctx = webkit_web_context_new_with_website_data_manager(manager);
    g_object_unref(manager);
    rb_gw_downloads_init(app);
    rb_gw_cookie_policy_apply(app);
    rb_gw_content_blocking_apply(app);
}

void rb_gw_context_free(App *app)
{
    if (app == NULL || app->ctx == NULL) return;
    /* The views built on it must already be destroyed; WebKit holds a
     * reference while any of them is alive, so this only drops ours. */
    g_object_unref(app->ctx);
    app->ctx = NULL;
    /* The rules describe the profile that just went away. */
    rb_gw_content_blocking_clear(app);
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

/* ------------------------------------------------------------------ */
/* Device shim
 *
 * A profile that presents a real machine sends that machine's User-Agent
 * (above) AND answers the questions a page asks about it — platform, client
 * hints, memory, cores, WebGL.  A UA string on its own is the cheapest thing
 * to fake and the easiest to contradict, so the two travel together: this
 * runs wherever the UA is applied.
 *
 * The script is per view, and it is stored ON the view rather than in a
 * table keyed by it: a table would outlive the view and a later view could
 * be allocated at the same address, finding another view's script.  Storing
 * it as object data ties its lifetime to the view's. */

static const char *RB_DEVICE_SCRIPT_KEY = "rb-device-script";

static void rb_gw_device_apply_to(App *app, WebKitWebView *wv)
{
    WebKitUserContentManager *ucm;
    WebKitUserScript *previous;
    WebKitUserScript *script;
    const rb_device *device;
    char *js;

    if (wv == NULL) return;
    ucm = webkit_web_view_get_user_content_manager(wv);
    if (ucm == NULL) return;

    /* configure() runs again on every settings change, so the old script is
     * removed first — otherwise a long session stacks one copy per edit.
     *
     * The key is stolen rather than cleared, and the reference dropped here:
     * whether g_object_set_data(x, key, NULL) runs the old destroy notify is
     * a GLib implementation detail, and this should not leak a script if that
     * ever changes. */
    previous = g_object_get_data(G_OBJECT(wv), RB_DEVICE_SCRIPT_KEY);
    if (previous != NULL) {
        webkit_user_content_manager_remove_script(ucm, previous);
        g_object_steal_data(G_OBJECT(wv), RB_DEVICE_SCRIPT_KEY);
        g_object_unref(previous);
    }

    device = rb_device_by_id(rb_pref(app, RB_PREF_DEVICE_ID, NULL));
    if (device == NULL) return;

    js = rb_device_shim_js(device);
    if (js == NULL) return;
    script = webkit_user_script_new(js,
                                    WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
                                    WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                                    NULL, NULL);
    free(js);
    if (script == NULL) return;

    webkit_user_content_manager_add_script(ucm, script);
    /* Takes the reference; the view's destruction drops the script too. */
    g_object_set_data_full(G_OBJECT(wv), RB_DEVICE_SCRIPT_KEY, script,
                           g_object_unref);
}

void rb_gw_apply_ua(App *app)
{
    int i;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].wv) {
            rb_gw_ua_apply_to(app, webkit_web_view_get_settings(app->tabs[i].wv));
            rb_gw_device_apply_to(app, app->tabs[i].wv);
        }
    }
}

/* ------------------------------------------------------------------ */
/* Public API */

WebKitWebView *rb_gw_new_view(App *app)
{
    WebKitWebContext *ctx = (app->ctx != NULL) ? app->ctx
                                               : webkit_web_context_get_default();
    /* The settings object belongs to the view, so they are applied to it
     * rather than to a separate WebKitSettings the view is built from —
     * that is what lets the view come from the profile's context. */
    WebKitWebView *wv = WEBKIT_WEB_VIEW(webkit_web_view_new_with_context(ctx));
    WebKitSettings *settings = webkit_web_view_get_settings(wv);

    /* Project policy: JavaScript is NEVER disabled by default. The persisted
       setting is the only way to turn it off and it is applied both to new
       webviews (here) and to the already open ones (rb_gw_apply_js). */
    g_object_set(settings, "enable-javascript", app->js_enabled ? TRUE : FALSE, NULL);
    g_object_set(settings, "enable-developer-extras", FALSE, NULL);
    rb_gw_ua_apply_to(app, settings);
    rb_gw_device_apply_to(app, wv);

    /* A view created after the rules were compiled gets them immediately;
     * one created while the compile is still running picks them up from the
     * save callback instead. */
    if (app->cb_filter != NULL) {
        webkit_user_content_manager_add_filter(
            webkit_web_view_get_user_content_manager(wv), app->cb_filter);
    }

    g_signal_connect(wv, "notify::title", G_CALLBACK(on_notify_title), app);
    g_signal_connect(wv, "notify::uri", G_CALLBACK(on_notify_uri), app);
    g_signal_connect(wv, "notify::favicon", G_CALLBACK(on_notify_favicon), app);
    g_signal_connect(wv, "mouse-target-changed",
                     G_CALLBACK(on_mouse_target_changed), app);
    g_signal_connect(wv, "load-changed", G_CALLBACK(on_load_changed), app);
    g_signal_connect(wv, "load-failed", G_CALLBACK(on_load_failed), app);
    g_signal_connect(wv, "decide-policy", G_CALLBACK(on_decide_policy), app);
    g_signal_connect(wv, "create", G_CALLBACK(on_create), app);
    g_signal_connect(wv, "close", G_CALLBACK(on_close_view), app);
    /* The switches that live on WebKitSettings are applied to THIS view's
     * settings — the view is not in app->tabs yet, so the sweep over the open
     * views would miss it. */
    rb_gw_web_settings_to(app, settings);
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

/* Pushes every per-view WebKit setting that a profile preference controls
 * onto the open views, and the per-profile cookie policy onto the data
 * manager.  Idempotent: it reads the preferences and applies all of them, so
 * a caller never has to work out which one changed.
 *
 * NOT PORTED, and deliberately not faked:
 *   - block-mixed-content.  WebKitGTK blocks active mixed content always and
 *     allows passive mixed content; it exposes no switch, so the profile
 *     preference is stored (and honoured by the Android edition) but has no
 *     effect here.  Pretending otherwise would be a lie in the UI.
 *   - WebRTC local-IP restrictions.  WebKitSettings has one on/off switch, so
 *     "restrict_local_ip" and "default" both leave WebRTC on; only "disabled"
 *     is expressible.  The Win32 edition gets closer, because Chromium has an
 *     IP-handling policy the profile's value maps onto directly.  The
 *     Android edition documents the same class of limitation for WebView. */

static void rb_gw_web_settings_to(App *app, WebKitSettings *s)
{
    const rb_profile *p = rb_active_profile(app);
    rb_webrtc_policy webrtc = rb_webrtc_policy_of(p ? p->settings : NULL);

    if (s == NULL) return;

    g_object_set(s, "enable-javascript", app->js_enabled ? TRUE : FALSE, NULL);
    /* Two states out of three.  WebKitSettings has one on/off switch and no
     * notion of an IP-handling policy, so "restrict_local_ip" — which is what
     * a fresh profile gets — renders as WebRTC on, exactly as "default" does.
     * The preferences row says so where the choice is made rather than
     * pretending the setting did something. */
    g_object_set(s, "enable-webrtc",
                 (webrtc == RB_WEBRTC_DISABLED) ? FALSE : TRUE, NULL);
    /* Android's search-suggestions switch is about whether typed text leaves
     * the device; DNS prefetching is the same class of background request, so
     * it follows the same switch. */
    g_object_set(s, "enable-dns-prefetching",
                 rb_pref_int(app, RB_PREF_SEARCH_SUGGESTIONS, 0) ? TRUE : FALSE,
                 NULL);
    /* Never enabled: a page must not be able to open windows by itself.  A
     * popup the profile ALLOWS still becomes a tab — that goes through the
     * "create" handler, which the user's own click triggers. */
    g_object_set(s, "javascript-can-open-windows-automatically", FALSE, NULL);
}

/* The cookie policy lives on the data manager, so it is a property of the
 * profile's context rather than of any one view. */
static void rb_gw_cookie_policy_apply(App *app)
{
    WebKitWebsiteDataManager *dm;
    WebKitCookieManager *cm;

    if (app == NULL || app->ctx == NULL) return;
    dm = webkit_web_context_get_website_data_manager(app->ctx);
    if (dm == NULL) return;
    cm = webkit_website_data_manager_get_cookie_manager(dm);
    if (cm == NULL) return;
    webkit_cookie_manager_set_accept_policy(cm,
        rb_pref_int(app, RB_PREF_BLOCK_THIRD_PARTY_COOKIES, 0)
            ? WEBKIT_COOKIE_POLICY_ACCEPT_NO_THIRD_PARTY
            : WEBKIT_COOKIE_POLICY_ACCEPT_ALWAYS);
}
void rb_gw_apply_web_settings(App *app)
{
    int i;

    if (app == NULL) return;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].wv != NULL) {
            rb_gw_web_settings_to(app,
                webkit_web_view_get_settings(app->tabs[i].wv));
        }
    }
    rb_gw_cookie_policy_apply(app);
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
