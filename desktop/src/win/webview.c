/*
 * Room Browser (desktop) - Windows WebView2 backend.
 *
 * Plain-C COM hosting of the Evergreen WebView2 runtime:
 *  - WebView2Loader.dll is resolved at runtime via LoadLibraryW/GetProcAddress
 *    (no import library needed; the DLL ships next to the executable).
 *  - Every completion handler / event handler follows the same static-COM
 *    pattern: the interface struct is the first member, refcounts floor at 1
 *    and the instances are NEVER freed (application lifetime).
 *  - PER-TAB controllers: each tab owns an ICoreWebView2Controller; switching
 *    tabs toggles put_IsVisible and resizes the visible controller, so every
 *    tab keeps its own session and back/forward history.
 *  - Event tokens are kept but never removed: the handlers live for the whole
 *    process, teardown happens by closing/releasing the controllers at quit.
 *
 * If the WebView2 Runtime is missing the chrome stays usable and an honest
 * message box is shown exactly once.
 */
#include <windows.h>
#include <objbase.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "chrome.h"
#include "webview.h"

/* WebView2.h uses MSVC-only '#pragma warning(...)' directives; silence the
 * unknown-pragma warning under clang-based cross toolchains.  MSVC never
 * sees these guards, so the file stays MSVC-safe. */
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunknown-pragmas"
#endif
#include "WebView2.h"
#ifdef __clang__
#pragma clang diagnostic pop
#endif

/* ------------------------------------------------------------------ */
/* Loader function pointers */

typedef HRESULT (STDMETHODCALLTYPE *RB_pfn_create_env_opts)(
    PCWSTR browserExecutableFolder,
    PCWSTR userDataFolder,
    ICoreWebView2EnvironmentOptions *environmentOptions,
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *environmentCreatedHandler);

typedef HRESULT (STDMETHODCALLTYPE *RB_pfn_create_env)(
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *environmentCreatedHandler);

static HMODULE g_loader = NULL;
static RB_pfn_create_env_opts g_create_env_opts = NULL;
static RB_pfn_create_env g_create_env = NULL;

/* ------------------------------------------------------------------ */
/* IIDs (verbatim from the official WebView2.h; kept local so we never
   depend on IID linkage). */

static const IID rb_iid_iunknown = {
    0x00000000, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46} };
static const IID rb_iid_env_done = {
    0x4e8a3389, 0xc9d8, 0x4bd2, {0xb6, 0xb5, 0x12, 0x4f, 0xee, 0x6c, 0xc1, 0x4d} };
static const IID rb_iid_ctrl_done = {
    0x6c4819f3, 0xc9b7, 0x4260, {0x81, 0x27, 0xc9, 0xf5, 0xbd, 0xe7, 0xf6, 0x8c} };
static const IID rb_iid_title_evt = {
    0xf5f2b923, 0x953e, 0x4042, {0x9f, 0x95, 0xf3, 0xa1, 0x18, 0xe1, 0xaf, 0xd4} };
static const IID rb_iid_source_evt = {
    0x3c067f9f, 0x5388, 0x4772, {0x8b, 0x48, 0x79, 0xf7, 0xef, 0x1a, 0xb3, 0x7c} };
static const IID rb_iid_history_evt = {
    0xc79a420c, 0xefd9, 0x4058, {0x92, 0x95, 0x3e, 0x8b, 0x4b, 0xca, 0xb6, 0x45} };
static const IID rb_iid_navcomp_evt = {
    0xd33a35bf, 0x1c49, 0x4f98, {0x93, 0xab, 0x00, 0x6e, 0x05, 0x33, 0xfe, 0x1c} };

/* Added with the profile/privacy work.  Every value below was read out of the
 * SDK's own WebView2.h (1.0.2903.40) rather than typed from memory: a wrong
 * IID does not fail to compile, it makes QueryInterface return E_NOINTERFACE
 * at run time and the feature silently does nothing. */
static const IID rb_iid_settings2 = {
    0xee9a0f68, 0xf46c, 0x4e32, {0xac, 0x23, 0xef, 0x8c, 0xac, 0x22, 0x4d, 0x2a} };
static const IID rb_iid_navstart_evt = {
    0x9adbe429, 0xf36d, 0x432b, {0x9d, 0xdc, 0xf8, 0x88, 0x1f, 0xbd, 0x76, 0xe3} };
static const IID rb_iid_newwin_evt = {
    0xd4c185fe, 0xc81c, 0x4989, {0x97, 0xaf, 0x2d, 0x3f, 0xa7, 0xab, 0x56, 0x51} };
static const IID rb_iid_webres_evt = {
    0xab00b74c, 0x15f1, 0x4646, {0x80, 0xe8, 0xe7, 0x63, 0x41, 0xd2, 0x5d, 0x71} };
static const IID rb_iid_dlstart_evt = {
    0xefedc989, 0xc396, 0x41ca, {0x83, 0xf7, 0x07, 0xf8, 0x45, 0xa5, 0x57, 0x24} };
/* add_WebResourceRequested + AddWebResourceRequestedFilter are used through
 * ICoreWebView2 itself.  The SDK marks that pair deprecated in favour of
 * ICoreWebView2_22's request-source-kind variants, but it is still
 * implemented by the Evergreen runtime and is one interface fewer to
 * QueryInterface for; the modern pair only adds filtering by request source,
 * which this layer does not need.
 *
 * add_DownloadStarting is NOT on the base interface: it arrived with
 * ICoreWebView2_4 (runtime 1.0.1108), so it has to be reached through the
 * QueryInterface below.  Asking for _4 rather than a later version is
 * deliberate — it is the lowest one that carries the method, so the download
 * feature works on the widest range of installed runtimes. */
static const IID rb_iid_wv4 = {
    /* ICoreWebView2_4 */
    0x20d02d59, 0x6df2, 0x42dc, {0xbd, 0x06, 0xf9, 0x8a, 0x69, 0x4b, 0x13, 0x02} };
static const IID rb_iid_dlstate_evt = {
    /* ICoreWebView2StateChangedEventHandler */
    0x81336594, 0x7ede, 0x4ba9, {0xbf, 0x71, 0xac, 0xf0, 0xa9, 0x5b, 0x58, 0xdd} };
static const IID rb_iid_dlbytes_evt = {
    /* ICoreWebView2BytesReceivedChangedEventHandler */
    0x828e8ab6, 0xd94c, 0x4264, {0x9c, 0xef, 0x52, 0x17, 0x17, 0x0d, 0x62, 0x51} };
/* Clearing browsing data is a property of the WebView2 *profile*, not of a
 * webview, so it is reached in two hops: ICoreWebView2_13 (runtime 1.0.1774)
 * carries get_Profile, and the profile is asked for ICoreWebView2Profile2
 * (runtime 1.0.1108), the lowest interface with ClearBrowsingData.  Both are
 * newer than the base interface, so both need a QueryInterface. */
static const IID rb_iid_wv13 = {
    /* ICoreWebView2_13 */
    0xf75f09a8, 0x667e, 0x4983, {0x88, 0xd6, 0xc8, 0x77, 0x3f, 0x31, 0x5e, 0x84} };
static const IID rb_iid_profile2 = {
    /* ICoreWebView2Profile2 */
    0xfa740d4b, 0x5eae, 0x4344, {0xa8, 0xad, 0x74, 0xbe, 0x31, 0x92, 0x53, 0x97} };
static const IID rb_iid_clear_done = {
    /* ICoreWebView2ClearBrowsingDataCompletedHandler */
    0xe9710a06, 0x1d1d, 0x49b2, {0x82, 0x34, 0x22, 0x6f, 0x35, 0x84, 0x6a, 0xe5} };

static int rb_iid_eq(REFIID a, const IID *b)
{
    return a && b && memcmp(a, b, sizeof(IID)) == 0;
}

/* ------------------------------------------------------------------ */
/* Per-tab view state */

typedef struct TabView {
    long tab_id;
    ICoreWebView2Controller *ctrl;   /* NULL until the controller is ready */
    ICoreWebView2 *wv;               /* NULL until the controller is ready */
    int creating;
} TabView;

struct RbViews {
    App *app;
    TabView *items;
    int n, cap;
    ICoreWebView2Environment *env;
};

static long g_ctrl_in_flight = 0;   /* tab id whose controller is being created */

/* ------------------------------------------------------------------ */
/* Handler wrapper structs (interface must be the FIRST member) */

typedef struct {
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler base;
    App *app;
    ULONG refs;
} EnvHandler;

typedef struct {
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler base;
    App *app;
    long tab_id;
    ULONG refs;
} CtrlHandler;

typedef struct {
    ICoreWebView2DocumentTitleChangedEventHandler base;
    App *app;
    ULONG refs;
} TitleEvt;

typedef struct {
    ICoreWebView2SourceChangedEventHandler base;
    App *app;
    ULONG refs;
} SourceEvt;

typedef struct {
    ICoreWebView2HistoryChangedEventHandler base;
    App *app;
    ULONG refs;
} HistoryEvt;

typedef struct {
    ICoreWebView2NavigationCompletedEventHandler base;
    App *app;
    ULONG refs;
} NavEvt;

typedef struct {
    ICoreWebView2NavigationStartingEventHandler base;
    App *app;
    ULONG refs;
} NavStartEvt;

typedef struct {
    ICoreWebView2NewWindowRequestedEventHandler base;
    App *app;
    ULONG refs;
} NewWinEvt;

typedef struct {
    ICoreWebView2WebResourceRequestedEventHandler base;
    App *app;
    ULONG refs;
} WebResEvt;

typedef struct {
    ICoreWebView2DownloadStartingEventHandler base;
    App *app;
    ULONG refs;
} DlStartEvt;

/* One of these is allocated per download.  The store's id is the only thing
 * the progress handlers need and it is not reachable from the operation, so
 * it is carried here.  The operation itself is deliberately NOT stored: the
 * handler is invoked with it as the sender, and a borrowed pointer would go
 * stale the moment the runtime released it.  Like every other handler in
 * this file they are never freed — see the lifetime note at the top of the
 * file.  The cost is one small struct per download started in a session. */
typedef struct {
    ICoreWebView2StateChangedEventHandler base;
    App *app;
    long long dl_id;
    ULONG refs;
} DlStateEvt;

typedef struct {
    ICoreWebView2BytesReceivedChangedEventHandler base;
    App *app;
    long long dl_id;
    ULONG refs;
} DlBytesEvt;

/* ------------------------------------------------------------------ */
/* Forward declarations */

static void rb_wv_ensure_active(App *app);
static void rb_wv_wire(App *app, TabView *tv);
static void rb_wv_apply_web_settings(App *app, ICoreWebView2 *wv);
static void rb_wv_unavailable(App *app);
static void rb_wv_area(App *app, RECT *r);
static TabView *rb_views_slot(struct RbViews *v, long tab_id);

/* ------------------------------------------------------------------ */
/* Environment completed handler */

static HRESULT STDMETHODCALLTYPE EnvHandler_QueryInterface(
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *self, REFIID riid, void **ppv)
{
    EnvHandler *h = (EnvHandler *)self;
    if (!ppv) return E_POINTER;
    if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &rb_iid_env_done)) {
        *ppv = self;
        h->refs++;
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE EnvHandler_AddRef(
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *self)
{
    return ++((EnvHandler *)self)->refs;
}

static ULONG STDMETHODCALLTYPE EnvHandler_Release(
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *self)
{
    EnvHandler *h = (EnvHandler *)self;
    /* static allocation: refcount floors at 1, never freed */
    if (h->refs > 1) h->refs--;
    return h->refs;
}

static HRESULT STDMETHODCALLTYPE EnvHandler_Invoke(
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *self,
    HRESULT errorCode, ICoreWebView2Environment *result)
{
    EnvHandler *h = (EnvHandler *)self;
    App *app = h->app;
    if (FAILED(errorCode) || !result) {
        rb_wv_unavailable(app);
        return S_OK;
    }
    if (app && app->views) {
        app->views->env = result;
        result->lpVtbl->AddRef(result);   /* we keep our own reference */
    }
    rb_wv_ensure_active(app);
    return S_OK;
}

static ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandlerVtbl g_env_vtbl = {
    EnvHandler_QueryInterface, EnvHandler_AddRef, EnvHandler_Release, EnvHandler_Invoke
};

static EnvHandler g_env_handler;

/* ------------------------------------------------------------------ */
/* Controller completed handler */

static HRESULT STDMETHODCALLTYPE CtrlHandler_QueryInterface(
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler *self, REFIID riid, void **ppv)
{
    CtrlHandler *h = (CtrlHandler *)self;
    if (!ppv) return E_POINTER;
    if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &rb_iid_ctrl_done)) {
        *ppv = self;
        h->refs++;
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE CtrlHandler_AddRef(
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler *self)
{
    return ++((CtrlHandler *)self)->refs;
}

static ULONG STDMETHODCALLTYPE CtrlHandler_Release(
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler *self)
{
    CtrlHandler *h = (CtrlHandler *)self;
    /* static allocation: refcount floors at 1, never freed */
    if (h->refs > 1) h->refs--;
    return h->refs;
}

static HRESULT STDMETHODCALLTYPE CtrlHandler_Invoke(
    ICoreWebView2CreateCoreWebView2ControllerCompletedHandler *self,
    HRESULT errorCode, ICoreWebView2Controller *result)
{
    CtrlHandler *h = (CtrlHandler *)self;
    App *app = h->app;
    long tab_id = h->tab_id;
    struct RbViews *v = app ? app->views : NULL;
    TabView *tv;

    g_ctrl_in_flight = 0;

    if (FAILED(errorCode) || !result) {
        rb_wv_unavailable(app);
        rb_wv_ensure_active(app);
        return S_OK;
    }
    if (!v) return S_OK;

    if (!rb_tabs_get(app->tabs, tab_id)) {
        /* the tab was closed while creation was in flight */
        result->lpVtbl->Close(result);
        result->lpVtbl->Release(result);
        rb_wv_ensure_active(app);
        return S_OK;
    }

    tv = rb_views_slot(v, tab_id);
    if (!tv) {
        /* out of memory: release the fresh controller and move on */
        result->lpVtbl->Close(result);
        result->lpVtbl->Release(result);
        return S_OK;
    }
    if (tv->ctrl) {
        /* defensive: never keep two controllers for one tab */
        tv->ctrl->lpVtbl->Close(tv->ctrl);
        tv->ctrl->lpVtbl->Release(tv->ctrl);
        tv->ctrl = NULL;
        tv->wv = NULL;
    }
    tv->creating = 0;
    tv->ctrl = result;
    result->lpVtbl->AddRef(result);   /* we keep our own reference */

    {
        ICoreWebView2 *wv = NULL;
        if (SUCCEEDED(result->lpVtbl->get_CoreWebView2(result, &wv)) && wv) {
            tv->wv = wv;   /* out-param reference is ours */
            rb_wv_wire(app, tv);
        }
    }
    rb_wv_ensure_active(app);
    return S_OK;
}

static ICoreWebView2CreateCoreWebView2ControllerCompletedHandlerVtbl g_ctrl_vtbl = {
    CtrlHandler_QueryInterface, CtrlHandler_AddRef, CtrlHandler_Release, CtrlHandler_Invoke
};

static CtrlHandler g_ctrl_handler;

/* ------------------------------------------------------------------ */
/* Event handlers. They are attached to EVERY webview; the tab is resolved
   by comparing the sender pointer with the per-tab webviews. */

static TabView *rb_views_by_wv(struct RbViews *v, ICoreWebView2 *wv)
{
    int i;
    if (!v || !wv) return NULL;
    for (i = 0; i < v->n; i++) {
        if (v->items[i].wv == wv) return &v->items[i];
    }
    return NULL;
}

static HRESULT STDMETHODCALLTYPE TitleEvt_QueryInterface(
    ICoreWebView2DocumentTitleChangedEventHandler *self, REFIID riid, void **ppv)
{
    TitleEvt *h = (TitleEvt *)self;
    if (!ppv) return E_POINTER;
    if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &rb_iid_title_evt)) {
        *ppv = self;
        h->refs++;
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE TitleEvt_AddRef(
    ICoreWebView2DocumentTitleChangedEventHandler *self)
{
    return ++((TitleEvt *)self)->refs;
}

static ULONG STDMETHODCALLTYPE TitleEvt_Release(
    ICoreWebView2DocumentTitleChangedEventHandler *self)
{
    TitleEvt *h = (TitleEvt *)self;
    if (h->refs > 1) h->refs--;
    return h->refs;
}

static HRESULT STDMETHODCALLTYPE TitleEvt_Invoke(
    ICoreWebView2DocumentTitleChangedEventHandler *self,
    ICoreWebView2 *sender, IUnknown *args)
{
    TitleEvt *h = (TitleEvt *)self;
    App *app = h->app;
    TabView *tv = app && app->views ? rb_views_by_wv(app->views, sender) : NULL;
    rb_tab *t;
    LPWSTR wtitle = NULL;
    (void)args;
    if (!tv) return S_OK;
    t = rb_tabs_get(app->tabs, tv->tab_id);
    if (!t) return S_OK;
    if (SUCCEEDED(sender->lpVtbl->get_DocumentTitle(sender, &wtitle)) && wtitle) {
        char *u8 = rb_wide_to_utf8(wtitle);
        CoTaskMemFree(wtitle);
        if (u8 && u8[0]) {
            rb_set_str(&t->title, u8);   /* takes ownership */
            if (tv->tab_id == app->active_id) {
                rb_tabs_rebuild(app);
                rb_update_titlebar(app);
            }
        } else {
            free(u8);
        }
    }
    return S_OK;
}

static ICoreWebView2DocumentTitleChangedEventHandlerVtbl g_title_vtbl = {
    TitleEvt_QueryInterface, TitleEvt_AddRef, TitleEvt_Release, TitleEvt_Invoke
};

static TitleEvt g_title_evt;

static HRESULT STDMETHODCALLTYPE SourceEvt_QueryInterface(
    ICoreWebView2SourceChangedEventHandler *self, REFIID riid, void **ppv)
{
    SourceEvt *h = (SourceEvt *)self;
    if (!ppv) return E_POINTER;
    if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &rb_iid_source_evt)) {
        *ppv = self;
        h->refs++;
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE SourceEvt_AddRef(
    ICoreWebView2SourceChangedEventHandler *self)
{
    return ++((SourceEvt *)self)->refs;
}

static ULONG STDMETHODCALLTYPE SourceEvt_Release(
    ICoreWebView2SourceChangedEventHandler *self)
{
    SourceEvt *h = (SourceEvt *)self;
    if (h->refs > 1) h->refs--;
    return h->refs;
}

static HRESULT STDMETHODCALLTYPE SourceEvt_Invoke(
    ICoreWebView2SourceChangedEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2SourceChangedEventArgs *args)
{
    SourceEvt *h = (SourceEvt *)self;
    App *app = h->app;
    TabView *tv = app && app->views ? rb_views_by_wv(app->views, sender) : NULL;
    rb_tab *t;
    LPWSTR wsrc = NULL;
    (void)args;
    if (!tv) return S_OK;
    t = rb_tabs_get(app->tabs, tv->tab_id);
    if (!t) return S_OK;
    if (SUCCEEDED(sender->lpVtbl->get_Source(sender, &wsrc)) && wsrc) {
        char *u8 = rb_wide_to_utf8(wsrc);
        CoTaskMemFree(wsrc);
        if (u8) {
            int changed = (!t->url || strcmp(u8, t->url) != 0);
            rb_set_str(&t->url, u8);
            if (tv->tab_id == app->active_id) {
                rb_update_omni(app, t->url);
                if (changed) {
                    rb_set_loading(app, 1);
                }
            }
        }
    }
    return S_OK;
}

static ICoreWebView2SourceChangedEventHandlerVtbl g_source_vtbl = {
    SourceEvt_QueryInterface, SourceEvt_AddRef, SourceEvt_Release, SourceEvt_Invoke
};

static SourceEvt g_source_evt;

static HRESULT STDMETHODCALLTYPE HistoryEvt_QueryInterface(
    ICoreWebView2HistoryChangedEventHandler *self, REFIID riid, void **ppv)
{
    HistoryEvt *h = (HistoryEvt *)self;
    if (!ppv) return E_POINTER;
    if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &rb_iid_history_evt)) {
        *ppv = self;
        h->refs++;
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE HistoryEvt_AddRef(
    ICoreWebView2HistoryChangedEventHandler *self)
{
    return ++((HistoryEvt *)self)->refs;
}

static ULONG STDMETHODCALLTYPE HistoryEvt_Release(
    ICoreWebView2HistoryChangedEventHandler *self)
{
    HistoryEvt *h = (HistoryEvt *)self;
    if (h->refs > 1) h->refs--;
    return h->refs;
}

static HRESULT STDMETHODCALLTYPE HistoryEvt_Invoke(
    ICoreWebView2HistoryChangedEventHandler *self,
    ICoreWebView2 *sender, IUnknown *args)
{
    HistoryEvt *h = (HistoryEvt *)self;
    App *app = h->app;
    TabView *tv = app && app->views ? rb_views_by_wv(app->views, sender) : NULL;
    (void)args;
    if (!tv) return S_OK;
    if (tv->tab_id == app->active_id) {
        rb_update_nav(app);
    }
    return S_OK;
}

static ICoreWebView2HistoryChangedEventHandlerVtbl g_history_vtbl = {
    HistoryEvt_QueryInterface, HistoryEvt_AddRef, HistoryEvt_Release, HistoryEvt_Invoke
};

static HistoryEvt g_history_evt;

static HRESULT STDMETHODCALLTYPE NavEvt_QueryInterface(
    ICoreWebView2NavigationCompletedEventHandler *self, REFIID riid, void **ppv)
{
    NavEvt *h = (NavEvt *)self;
    if (!ppv) return E_POINTER;
    if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &rb_iid_navcomp_evt)) {
        *ppv = self;
        h->refs++;
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE NavEvt_AddRef(
    ICoreWebView2NavigationCompletedEventHandler *self)
{
    return ++((NavEvt *)self)->refs;
}

static ULONG STDMETHODCALLTYPE NavEvt_Release(
    ICoreWebView2NavigationCompletedEventHandler *self)
{
    NavEvt *h = (NavEvt *)self;
    if (h->refs > 1) h->refs--;
    return h->refs;
}

/* Maps a WebView2 navigation failure onto the core's retry vocabulary.
 *
 * Only the failures an http retry could plausibly fix are retry-worthy.  The
 * interesting case is a certificate rejection: an https upgrade that lands on
 * a host with a broken or self-signed certificate fails at the TLS layer, and
 * that is exactly the situation the fallback exists for, so it maps to
 * SSL_HANDSHAKE alongside the plain connect failures.
 *
 * A user cancel is deliberately not retryable — the user stopped the load,
 * and silently restarting it over http would be the rudest possible reply. */
#define RB_WIN_HTTPS_NO_RETRY 0

static int rb_win_https_code(COREWEBVIEW2_WEB_ERROR_STATUS st)
{
    switch (st) {
    case COREWEBVIEW2_WEB_ERROR_STATUS_CANNOT_CONNECT:
    case COREWEBVIEW2_WEB_ERROR_STATUS_SERVER_UNREACHABLE:
    case COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED:
    case COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_RESET:
    case COREWEBVIEW2_WEB_ERROR_STATUS_DISCONNECTED:
    case COREWEBVIEW2_WEB_ERROR_STATUS_HOST_NAME_NOT_RESOLVED:
    case COREWEBVIEW2_WEB_ERROR_STATUS_ERROR_HTTP_INVALID_SERVER_RESPONSE:
    case COREWEBVIEW2_WEB_ERROR_STATUS_UNEXPECTED_ERROR:
        return RB_HTTPS_ERR_CONNECT;

    case COREWEBVIEW2_WEB_ERROR_STATUS_TIMEOUT:
        return RB_HTTPS_ERR_TIMEOUT;

    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_IS_INVALID:
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_EXPIRED:
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_REVOKED:
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_COMMON_NAME_IS_INCORRECT:
        return RB_HTTPS_ERR_SSL_HANDSHAKE;

    default:
        /* OPERATION_CANCELED, REDIRECT_FAILED, the certificate-the-user-must-
         * answer cases and UNKNOWN: nothing an http retry would fix. */
        return RB_WIN_HTTPS_NO_RETRY;
    }
}

static HRESULT STDMETHODCALLTYPE NavEvt_Invoke(
    ICoreWebView2NavigationCompletedEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2NavigationCompletedEventArgs *args)
{
    NavEvt *h = (NavEvt *)self;
    App *app = h->app;
    TabView *tv = app && app->views ? rb_views_by_wv(app->views, sender) : NULL;
    rb_tab *t;
    BOOL ok = TRUE;
    if (!tv) return S_OK;
    t = rb_tabs_get(app->tabs, tv->tab_id);
    if (!t) return S_OK;

    if (args != NULL && SUCCEEDED(args->lpVtbl->get_IsSuccess(args, &ok)) && !ok) {
        COREWEBVIEW2_WEB_ERROR_STATUS st = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
        char *retry = NULL;
        args->lpVtbl->get_WebErrorStatus(args, &st);
        /* A URL that fails because the engine could not reach https may be
         * one this browser upgraded from http a moment ago.  When it is, the
         * original http URL comes back and is loaded instead — the fallback
         * half of "upgrade, but do not lock the user out". */
        retry = rb_https_retry_url(app->https, t->url, rb_win_https_code(st));
        if (retry != NULL) {
            if (retry[0] != '\0') {
                rb_set_str(&t->url, rb_strdup(retry));
                if (tv->tab_id == app->active_id) rb_update_omni(app, retry);
                {
                    wchar_t *wu = rb_utf8_to_wide(retry);
                    if (wu != NULL) {
                        sender->lpVtbl->Navigate(sender, wu);
                        free(wu);
                    }
                }
            }
            free(retry);
            return S_OK;   /* an http retry is starting; not a finished load */
        }
    }

    if (tv->tab_id == app->active_id) {
        rb_set_loading(app, 0);
    }
    if (t->url && t->url[0]) {
        rb_history_append(app->history, t->url, t->title && t->title[0] ? t->title : t->url);
        if (app->path_history) rb_history_save(app->history, app->path_history);
    }
    if (tv->tab_id == app->active_id) {
        rb_update_star(app);
        rb_update_titlebar(app);
    }
    return S_OK;
}

static ICoreWebView2NavigationCompletedEventHandlerVtbl g_nav_vtbl = {
    NavEvt_QueryInterface, NavEvt_AddRef, NavEvt_Release, NavEvt_Invoke
};

static NavEvt g_nav_evt;

/* ------------------------------------------------------------------ */
/* Navigation policy, content blocking and downloads.
 *
 * Every handler below implements the same three IUnknown-shaped methods and
 * differs only in its interface type and IID, so the boilerplate is
 * generated from one macro instead of copied six more times.  The handlers
 * above are left hand-written: they predate this and rewriting them would
 * bury the actual change in noise. */

#define RB_HANDLER_QI(ctype, htype, iidvar)                                   \
    static HRESULT STDMETHODCALLTYPE htype##_QueryInterface(ctype *self,      \
                                                            REFIID riid,      \
                                                            void **ppv)       \
    {                                                                         \
        htype *h = (htype *)self;                                             \
        if (!ppv) return E_POINTER;                                           \
        if (rb_iid_eq(riid, &rb_iid_iunknown) || rb_iid_eq(riid, &iidvar)) {  \
            *ppv = self;                                                      \
            h->refs++;                                                        \
            return S_OK;                                                      \
        }                                                                     \
        *ppv = NULL;                                                          \
        return E_NOINTERFACE;                                                 \
    }

#define RB_HANDLER_REFCOUNT(ctype, htype)                                     \
    static ULONG STDMETHODCALLTYPE htype##_AddRef(ctype *self)                \
    {                                                                         \
        return ++((htype *)self)->refs;                                       \
    }                                                                         \
    static ULONG STDMETHODCALLTYPE htype##_Release(ctype *self)               \
    {                                                                         \
        htype *h = (htype *)self;                                             \
        if (h->refs > 1) h->refs--;                                           \
        return h->refs;                                                       \
    }

/* The category to block for `url` requested by the page at `page_url`, or
 * RB_FILTER_NONE.  Both URLs are absolute; `page_url` may be NULL, which the
 * engine reads as "no page context" (a main-frame request). */
static rb_filter_category rb_wv_block_category(App *app, const char *url,
                                               const char *page_url)
{
    rb_filter_options opts;
    char *host;
    char *phost = NULL;
    char *path;
    rb_filter_category cat;

    if (app == NULL || app->filters == NULL || url == NULL) return RB_FILTER_NONE;
    host = rb_url_host_of(url);
    if (host == NULL) return RB_FILTER_NONE;
    path = rb_url_path_of(url);
    if (page_url != NULL) phost = rb_url_host_of(page_url);

    opts = rb_filter_opts(app);
    cat = rb_filters_decide(app->filters, host, phost, path, &opts, NULL);

    free(host);
    free(phost);
    free(path);
    return cat;
}

/* Answers a blocked subresource with an empty 403.  WebView2 has no "cancel"
 * for a WebResourceRequested — putting a response IS the block, and an empty
 * one keeps the page's own error handling intact instead of tearing the
 * request down. */
static void rb_wv_block_request(App *app,
                                ICoreWebView2WebResourceRequestedEventArgs *args,
                                rb_filter_category cat)
{
    struct RbViews *v = app ? app->views : NULL;
    ICoreWebView2WebResourceResponse *resp = NULL;

    if (v == NULL || v->env == NULL) return;
    if (FAILED(v->env->lpVtbl->CreateWebResourceResponse(v->env, NULL, 403,
                                                         L"Blocked", L"",
                                                         &resp)) ||
        resp == NULL) {
        return;
    }
    args->lpVtbl->put_Response(args, resp);
    resp->lpVtbl->Release(resp);
    rb_filters_count_block(app->filters, cat);
}

/* ---- NavigationStarting: the main-frame policy ----
 *
 * Android's shouldOverrideUrlLoading.  A navigation into a host the bundled
 * list calls malicious is stopped with a dialog; a host that is merely
 * suspicious (plain http, an IP literal, punycode) is allowed through with a
 * warning, because those heuristics have false positives and silently
 * refusing a page the user asked for is worse than telling them about it.
 *
 * The dialog is modal and runs a nested message loop inside a WebView2 event
 * handler.  That is deliberate and it is what the GTK layer does too: the
 * navigation has already been cancelled by the time it appears, so nothing
 * is waiting on it. */
RB_HANDLER_QI(ICoreWebView2NavigationStartingEventHandler, NavStartEvt,
              rb_iid_navstart_evt)
RB_HANDLER_REFCOUNT(ICoreWebView2NavigationStartingEventHandler, NavStartEvt)

static HRESULT STDMETHODCALLTYPE NavStartEvt_Invoke(
    ICoreWebView2NavigationStartingEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2NavigationStartingEventArgs *args)
{
    NavStartEvt *h = (NavStartEvt *)self;
    App *app = h->app;
    rb_filter_options opts;
    LPWSTR wurl = NULL;
    char *url = NULL;
    (void)sender;

    if (app == NULL || app->filters == NULL || args == NULL) return S_OK;
    if (FAILED(args->lpVtbl->get_Uri(args, &wurl)) || wurl == NULL) return S_OK;
    url = rb_wide_to_utf8(wurl);
    CoTaskMemFree(wurl);
    if (url == NULL) return S_OK;

    /* The switches are read per navigation, not cached, so turning
     * "Block malicious sites" off takes effect on the next page rather than
     * at the next restart.  Same as the GTK layer, and the same single
     * source of truth (rb_filter_opts) so the two cannot disagree. */
    opts = rb_filter_opts(app);
    if (opts.block_malicious) {
        char *host = rb_url_host_of(url);
        if (host != NULL && host[0] != '\0') {
            /* blocked_category only consults the malicious list here, because
             * that is the list a main frame is allowed to be judged by. */
            rb_filter_category cat =
                rb_filters_blocked_category(app->filters, host);
            if (cat == RB_FILTER_MALICIOUS) {
                rb_filters_count_block(app->filters, RB_FILTER_MALICIOUS);
                args->lpVtbl->put_Cancel(args, TRUE);
                rb_warn(app, "Blocked a malicious site",
                        "This address is on the malicious-site blocklist, so "
                        "the page was not loaded.");
                free(host);
                free(url);
                return S_OK;
            }
        }
        free(host);
    }

    /* Not on any list: the heuristics still get to speak, and the page is
     * allowed through — a warning, not a block.  They have false positives,
     * and silently refusing a page the user asked for is worse than telling
     * them what looks odd about it. */
    {
        unsigned int signals = rb_filters_suspicious_signals(url);
        if (signals != RB_SUSPICIOUS_NONE) {
            char *text = rb_filters_suspicious_text(signals);
            if (text != NULL && text[0] != '\0') {
                size_t need = strlen(text) + 16;
                char *body = (char *)malloc(need);
                if (body != NULL) {
                    snprintf(body, need, "Caution: %s", text);
                    rb_warn(app, "Suspicious address", body);
                    free(body);
                }
            }
            free(text);
        }
    }

    free(url);
    return S_OK;
}

static ICoreWebView2NavigationStartingEventHandlerVtbl g_navstart_vtbl = {
    NavStartEvt_QueryInterface, NavStartEvt_AddRef, NavStartEvt_Release,
    NavStartEvt_Invoke
};

static NavStartEvt g_navstart_evt;

/* ---- NewWindowRequested: window.open goes to a tab ----
 *
 * With block_popups off (the compatibility default) the request is handed to
 * the tab strip, so window.open behaves the way a browser user expects
 * rather than being silently dropped.  With it on, the request is marked
 * handled and nothing opens. */
RB_HANDLER_QI(ICoreWebView2NewWindowRequestedEventHandler, NewWinEvt,
              rb_iid_newwin_evt)
RB_HANDLER_REFCOUNT(ICoreWebView2NewWindowRequestedEventHandler, NewWinEvt)

static HRESULT STDMETHODCALLTYPE NewWinEvt_Invoke(
    ICoreWebView2NewWindowRequestedEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2NewWindowRequestedEventArgs *args)
{
    NewWinEvt *h = (NewWinEvt *)self;
    App *app = h->app;
    rb_filter_options opts;
    LPWSTR wurl = NULL;
    char *url = NULL;
    (void)sender;

    if (app == NULL || args == NULL) return S_OK;
    if (FAILED(args->lpVtbl->get_Uri(args, &wurl)) || wurl == NULL) return S_OK;
    url = rb_wide_to_utf8(wurl);
    CoTaskMemFree(wurl);

    opts = rb_filter_opts(app);
    if (opts.block_popups) {
        args->lpVtbl->put_Handled(args, TRUE);
        rb_filters_count_block(app->filters, RB_FILTER_POPUP);
        free(url);
        return S_OK;
    }

    /* Handled here whatever happens next, so the runtime does not open its
     * own popup window on top of the tab this creates. */
    args->lpVtbl->put_Handled(args, TRUE);
    if (url != NULL && url[0] != '\0') {
        long id = rb_tabs_add(app->tabs, "New Tab", url, rb_profile_now_ms());
        if (id != 0) {
            app->active_id = id;
            rb_tabs_rebuild(app);
            rb_update_all(app);
            rb_wv_activate(app, id);
        }
    }
    free(url);
    return S_OK;
}

static ICoreWebView2NewWindowRequestedEventHandlerVtbl g_newwin_vtbl = {
    NewWinEvt_QueryInterface, NewWinEvt_AddRef, NewWinEvt_Release,
    NewWinEvt_Invoke
};

static NewWinEvt g_newwin_evt;

/* ---- WebResourceRequested: content blocking ----
 *
 * WebKitGTK's decide-policy cannot see subresources, which is why the GTK
 * layer hands its rules to WebKit's content blocker.  WebView2 can answer
 * per request, so this is Android's shape instead: the same rb_filters_decide
 * call over the same switches.
 *
 * A main-frame request is never blocked here.  Blocking it would show a blank
 * page with no explanation; the navigation policy above handles that case
 * with a dialog the user can actually read.  Same rule as Android's
 * "if (request.isForMainFrame) return null". */
RB_HANDLER_QI(ICoreWebView2WebResourceRequestedEventHandler, WebResEvt,
              rb_iid_webres_evt)
RB_HANDLER_REFCOUNT(ICoreWebView2WebResourceRequestedEventHandler, WebResEvt)

static HRESULT STDMETHODCALLTYPE WebResEvt_Invoke(
    ICoreWebView2WebResourceRequestedEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2WebResourceRequestedEventArgs *args)
{
    WebResEvt *h = (WebResEvt *)self;
    App *app = h->app;
    TabView *tv = app && app->views ? rb_views_by_wv(app->views, sender) : NULL;
    ICoreWebView2WebResourceRequest *req = NULL;
    COREWEBVIEW2_WEB_RESOURCE_CONTEXT ctx;
    LPWSTR wurl = NULL;
    char *url = NULL;
    const char *page_url = NULL;
    rb_tab *t;
    rb_filter_category cat;

    if (app == NULL || app->filters == NULL || args == NULL) return S_OK;
    if (FAILED(args->lpVtbl->get_ResourceContext(args, &ctx))) return S_OK;
    if (ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT) return S_OK;

    if (FAILED(args->lpVtbl->get_Request(args, &req)) || req == NULL) return S_OK;
    if (FAILED(req->lpVtbl->get_Uri(req, &wurl)) || wurl == NULL) {
        req->lpVtbl->Release(req);
        return S_OK;
    }
    url = rb_wide_to_utf8(wurl);
    CoTaskMemFree(wurl);
    req->lpVtbl->Release(req);
    if (url == NULL) return S_OK;

    if (tv != NULL) {
        t = rb_tabs_get(app->tabs, tv->tab_id);
        if (t != NULL) page_url = t->url;
    }

    cat = rb_wv_block_category(app, url, page_url);
    if (cat != RB_FILTER_NONE) {
        rb_wv_block_request(app, args, cat);
    }
    free(url);
    return S_OK;
}

static ICoreWebView2WebResourceRequestedEventHandlerVtbl g_webres_vtbl = {
    WebResEvt_QueryInterface, WebResEvt_AddRef, WebResEvt_Release,
    WebResEvt_Invoke
};

static WebResEvt g_webres_evt;

/* ---- Downloads ----
 *
 * Android's DownloadEngine: the transfer is recorded in the profile's
 * downloads store, and the file lands under the profile's download
 * directory.  WebView2 shows its own download UI unless the event is marked
 * handled, so the destination is set and the default UI suppressed — the
 * desktop's own downloads list is the one the user sees. */
static void rb_dl_save(App *app)
{
    if (app->downloads && app->path_downloads) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
}

/* Splits "<dir>\<name>" and returns the trailing name, or the whole string
 * when there is no separator. */
static const char *rb_dl_basename(const char *path)
{
    const char *slash;
    if (path == NULL) return "";
    slash = strrchr(path, '\\');
    if (slash == NULL) slash = strrchr(path, '/');
    return (slash != NULL) ? slash + 1 : path;
}

/* Where the file should land: the profile's download directory plus the name
 * the store chose, with the usual " (1)", " (2)" suffix when that path is
 * taken.  Overwriting a file the user downloaded earlier is not a thing a
 * browser may do, so the collision is resolved here rather than left to
 * WebView2.  Mirrors the GTK layer's on_dl_decide_destination.  Caller
 * frees; NULL when the directory or the record is missing. */
static char *rb_dl_pick_destination(App *app, long long id)
{
    const rb_download *rec;
    const char *dir;
    char *path;
    int n;

    if (app == NULL || app->download_dir == NULL) return NULL;
    dir = app->download_dir;
    rec = rb_downloads_by_id(app->downloads, id);
    if (rec == NULL || rec->file_name == NULL || rec->file_name[0] == '\0') {
        return NULL;
    }

    path = rb_paths_join(dir, rec->file_name);
    if (path == NULL) return NULL;

    for (n = 1; n < 10000 && rb_paths_is_file(path); n++) {
        const char *dot = strrchr(rec->file_name, '.');
        char *cand;
        if (dot != NULL && dot != rec->file_name) {
            size_t k = (size_t)(dot - rec->file_name);
            size_t need = strlen(dir) + 1 + k + 24 + strlen(dot) + 1;
            cand = (char *)malloc(need);
            if (cand == NULL) break;
            snprintf(cand, need, "%s\\%.*s (%d)%s", dir, (int)k,
                     rec->file_name, n, dot);
        } else {
            size_t need = strlen(dir) + strlen(rec->file_name) + 24;
            cand = (char *)malloc(need);
            if (cand == NULL) break;
            snprintf(cand, need, "%s\\%s (%d)", dir, rec->file_name, n);
        }
        free(path);
        path = cand;
    }
    return path;
}

/* Moves the store's view of a download to match the operation's state.
 * WebView2 reports completion through StateChanged, and a user cancel
 * arrives as INTERRUPTED with USER_CANCELED — which is not a failure to
 * report, the same distinction the GTK layer draws. */
static void rb_dl_state_changed(App *app, long long id,
                                ICoreWebView2DownloadOperation *op)
{
    COREWEBVIEW2_DOWNLOAD_STATE st;
    if (app == NULL || app->downloads == NULL || op == NULL || id == 0) return;
    if (FAILED(op->lpVtbl->get_State(op, &st))) return;

    if (st == COREWEBVIEW2_DOWNLOAD_STATE_COMPLETED) {
        LPWSTR wpath = NULL;
        char *path = NULL;
        INT64 received = 0;
        if (SUCCEEDED(op->lpVtbl->get_ResultFilePath(op, &wpath)) && wpath) {
            path = rb_wide_to_utf8(wpath);
            CoTaskMemFree(wpath);
        }
        op->lpVtbl->get_BytesReceived(op, &received);
        rb_downloads_complete(app->downloads, id, path ? path : "",
                              (long long)received, rb_profile_now_ms());
        free(path);
        rb_dl_save(app);
        return;
    }
    if (st == COREWEBVIEW2_DOWNLOAD_STATE_INTERRUPTED) {
        COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON reason =
            COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NONE;
        int cancelled;
        op->lpVtbl->get_InterruptReason(op, &reason);
        cancelled = (reason == COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_CANCELED);
        rb_downloads_set_status(app->downloads, id,
                                cancelled ? RB_DL_CANCELLED : RB_DL_FAILED,
                                cancelled ? NULL : "the transfer was interrupted");
        rb_dl_save(app);
        return;
    }
    rb_downloads_set_status(app->downloads, id, RB_DL_RUNNING, NULL);
}

RB_HANDLER_QI(ICoreWebView2StateChangedEventHandler, DlStateEvt, rb_iid_dlstate_evt)
RB_HANDLER_REFCOUNT(ICoreWebView2StateChangedEventHandler, DlStateEvt)

static HRESULT STDMETHODCALLTYPE DlStateEvt_Invoke(
    ICoreWebView2StateChangedEventHandler *self,
    ICoreWebView2DownloadOperation *sender, IUnknown *args)
{
    DlStateEvt *h = (DlStateEvt *)self;
    (void)args;
    rb_dl_state_changed(h->app, h->dl_id, sender);
    return S_OK;
}

static ICoreWebView2StateChangedEventHandlerVtbl g_dlstate_vtbl = {
    DlStateEvt_QueryInterface, DlStateEvt_AddRef, DlStateEvt_Release,
    DlStateEvt_Invoke
};

RB_HANDLER_QI(ICoreWebView2BytesReceivedChangedEventHandler, DlBytesEvt,
              rb_iid_dlbytes_evt)
RB_HANDLER_REFCOUNT(ICoreWebView2BytesReceivedChangedEventHandler, DlBytesEvt)

static HRESULT STDMETHODCALLTYPE DlBytesEvt_Invoke(
    ICoreWebView2BytesReceivedChangedEventHandler *self,
    ICoreWebView2DownloadOperation *sender, IUnknown *args)
{
    DlBytesEvt *h = (DlBytesEvt *)self;
    INT64 received = 0, total = 0;
    (void)args;
    if (h->app == NULL || h->app->downloads == NULL || h->dl_id == 0) return S_OK;
    if (FAILED(sender->lpVtbl->get_BytesReceived(sender, &received))) return S_OK;
    if (FAILED(sender->lpVtbl->get_TotalBytesToReceive(sender, &total))) {
        total = -1;   /* the store reads <= -1 as "length unknown" */
    }
    rb_downloads_set_progress(h->app->downloads, h->dl_id,
                              (long long)received, (long long)total);
    return S_OK;
}

static ICoreWebView2BytesReceivedChangedEventHandlerVtbl g_dlbytes_vtbl = {
    DlBytesEvt_QueryInterface, DlBytesEvt_AddRef, DlBytesEvt_Release,
    DlBytesEvt_Invoke
};

/* --- ClearBrowsingData completion ------------------------------------
 *
 * The only COM callback in this file that carries no state: clearing is
 * fire-and-forget, and there is nothing useful to do with the result beyond
 * not crashing on it.  It exists because the API takes a handler and the
 * runtime will not accept NULL. */
typedef struct {
    ICoreWebView2ClearBrowsingDataCompletedHandler base;
    ULONG refs;
} ClearDoneEvt;

RB_HANDLER_QI(ICoreWebView2ClearBrowsingDataCompletedHandler, ClearDoneEvt,
              rb_iid_clear_done)
RB_HANDLER_REFCOUNT(ICoreWebView2ClearBrowsingDataCompletedHandler, ClearDoneEvt)

static HRESULT STDMETHODCALLTYPE ClearDoneEvt_Invoke(
    ICoreWebView2ClearBrowsingDataCompletedHandler *self, HRESULT errorCode)
{
    (void)self;
    (void)errorCode;
    return S_OK;
}

static ICoreWebView2ClearBrowsingDataCompletedHandlerVtbl g_cleardone_vtbl = {
    ClearDoneEvt_QueryInterface, ClearDoneEvt_AddRef, ClearDoneEvt_Release,
    ClearDoneEvt_Invoke
};

RB_HANDLER_QI(ICoreWebView2DownloadStartingEventHandler, DlStartEvt,
              rb_iid_dlstart_evt)
RB_HANDLER_REFCOUNT(ICoreWebView2DownloadStartingEventHandler, DlStartEvt)

static HRESULT STDMETHODCALLTYPE DlStartEvt_Invoke(
    ICoreWebView2DownloadStartingEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2DownloadStartingEventArgs *args)
{
    DlStartEvt *h = (DlStartEvt *)self;
    App *app = h->app;
    ICoreWebView2DownloadOperation *op = NULL;
    LPWSTR wurl = NULL, wmime = NULL, wpath = NULL;
    char *url = NULL, *mime = NULL, *suggested = NULL, *dest = NULL;
    const rb_profile *prof;
    long long id;
    (void)sender;

    if (app == NULL || app->downloads == NULL || args == NULL) return S_OK;
    if (FAILED(args->lpVtbl->get_DownloadOperation(args, &op)) || op == NULL) {
        return S_OK;
    }

    if (SUCCEEDED(op->lpVtbl->get_Uri(op, &wurl)) && wurl != NULL) {
        url = rb_wide_to_utf8(wurl);
        CoTaskMemFree(wurl);
    }
    if (SUCCEEDED(op->lpVtbl->get_MimeType(op, &wmime)) && wmime != NULL) {
        mime = rb_wide_to_utf8(wmime);
        CoTaskMemFree(wmime);
    }
    /* WebView2's own idea of the name, which is this API's equivalent of
     * WebKit's suggested_filename.  Only the basename is useful — the rest is
     * WebView2's default folder, which is not where the file is going. */
    if (SUCCEEDED(args->lpVtbl->get_ResultFilePath(args, &wpath)) && wpath != NULL) {
        char *full = rb_wide_to_utf8(wpath);
        if (full != NULL) {
            const char *base = rb_dl_basename(full);
            suggested = (base[0] != '\0') ? rb_strdup(base) : NULL;
            free(full);
        }
        CoTaskMemFree(wpath);
    }

    if (url == NULL || url[0] == '\0') {
        op->lpVtbl->Release(op);
        free(mime);
        free(suggested);
        return S_OK;
    }

    prof = rb_active_profile(app);
    id = rb_downloads_enqueue(app->downloads,
                              (prof != NULL) ? prof->id : "",
                              url, suggested, mime, rb_profile_now_ms());
    free(suggested);
    if (id == 0) {
        op->lpVtbl->Release(op);
        free(url);
        free(mime);
        return S_OK;
    }

    /* The store has now chosen and de-duplicated the name, so the path built
     * from it is the one the downloads list will show.  Without this WebView2
     * would write somewhere else entirely and the row would point at a file
     * that does not exist. */
    dest = rb_dl_pick_destination(app, id);
    if (dest != NULL) {
        wchar_t *wdest = rb_utf8_to_wide(dest);
        if (wdest != NULL) {
            args->lpVtbl->put_ResultFilePath(args, wdest);
            free(wdest);
        }
    }
    /* The desktop's downloads list is the UI; WebView2's own download bar
     * would be a second, unrelated one. */
    args->lpVtbl->put_Handled(args, TRUE);
    free(dest);

    {
        DlStateEvt *se = (DlStateEvt *)malloc(sizeof *se);
        DlBytesEvt *be = (DlBytesEvt *)malloc(sizeof *be);
        EventRegistrationToken token;
        if (se != NULL) {
            se->base.lpVtbl = &g_dlstate_vtbl;
            se->app = app;
            se->dl_id = id;
            se->refs = 1;
            op->lpVtbl->add_StateChanged(op, &se->base, &token);
        }
        if (be != NULL) {
            be->base.lpVtbl = &g_dlbytes_vtbl;
            be->app = app;
            be->dl_id = id;
            be->refs = 1;
            op->lpVtbl->add_BytesReceivedChanged(op, &be->base, &token);
        }
    }

    rb_dl_save(app);
    op->lpVtbl->Release(op);
    free(url);
    free(mime);
    return S_OK;
}

static ICoreWebView2DownloadStartingEventHandlerVtbl g_dlstart_vtbl = {
    DlStartEvt_QueryInterface, DlStartEvt_AddRef, DlStartEvt_Release,
    DlStartEvt_Invoke
};

static DlStartEvt g_dlstart_evt;

/* ------------------------------------------------------------------ */
/* Views array helpers */

static TabView *rb_views_slot(struct RbViews *v, long tab_id)
{
    int i;
    TabView *fresh;
    for (i = 0; i < v->n; i++) {
        if (v->items[i].tab_id == tab_id) return &v->items[i];
    }
    if (v->n == v->cap) {
        int cap = v->cap ? v->cap * 2 : 8;
        TabView *items = (TabView *)realloc(v->items, (size_t)cap * sizeof(TabView));
        if (!items) return NULL;
        v->items = items;
        v->cap = cap;
    }
    fresh = &v->items[v->n];
    memset(fresh, 0, sizeof *fresh);
    fresh->tab_id = tab_id;
    v->n++;
    return fresh;
}

static TabView *rb_views_find(struct RbViews *v, long tab_id)
{
    int i;
    if (!v) return NULL;
    for (i = 0; i < v->n; i++) {
        if (v->items[i].tab_id == tab_id) return &v->items[i];
    }
    return NULL;
}

static TabView *rb_active_view(App *app)
{
    return app && app->views ? rb_views_find(app->views, app->active_id) : NULL;
}

static void rb_wv_area(App *app, RECT *r)
{
    GetClientRect(app->hwnd, r);
    /* The chrome above the page scales with the profile's font scale, so the
     * page has to start below whatever height that is now — otherwise a
     * scaled-up chrome draws over the top of the document. */
    r->top += rb_scaled(app, RB_TABSTRIP_H) + rb_scaled(app, RB_TOOLBAR_H);
    if (r->bottom < r->top) r->bottom = r->top;
}

/* ------------------------------------------------------------------ */
/* Wiring a freshly created controller/webview to the app */

static void rb_wv_wire(App *app, TabView *tv)
{
    ICoreWebView2 *wv = tv->wv;
    ICoreWebView2Settings *st = NULL;
    RECT area;
    EventRegistrationToken token;   /* kept implicitly; handlers live forever */

    wv->lpVtbl->add_DocumentTitleChanged(wv, &g_title_evt.base, &token);
    wv->lpVtbl->add_SourceChanged(wv, &g_source_evt.base, &token);
    wv->lpVtbl->add_HistoryChanged(wv, &g_history_evt.base, &token);
    wv->lpVtbl->add_NavigationCompleted(wv, &g_nav_evt.base, &token);

    /* The main-frame policy: the malicious block, the suspicious warning and
     * the HTTPS retry all happen on this one event. */
    wv->lpVtbl->add_NavigationStarting(wv, &g_navstart_evt.base, &token);

    /* window.open becomes a tab unless the profile blocks popups. */
    wv->lpVtbl->add_NewWindowRequested(wv, &g_newwin_evt.base, &token);

    /* Content blocking.  The filter is registered for every resource type
     * and every URL; the handler is what decides, per request, using the
     * same rb_filters_decide the Android edition calls.  WebView2 has no
     * content-blocker JSON equivalent, so this is the shape that fits —
     * and it is actually closer to Android than WebKitGTK's rule list is. */
    wv->lpVtbl->AddWebResourceRequestedFilter(
        wv, L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
    wv->lpVtbl->add_WebResourceRequested(wv, &g_webres_evt.base, &token);

    /* Downloads land in the profile's download directory and are recorded in
     * the downloads store; WebView2's own download bar is suppressed.
     * add_DownloadStarting is on ICoreWebView2_4, so this is the one event
     * that needs a QueryInterface.  When the installed runtime is too old for
     * it the subscription is simply skipped: downloads then go through
     * WebView2's own UI, which is worse than the desktop's list but far
     * better than a browser that cannot download at all. */
    {
        ICoreWebView2_4 *wv4 = NULL;
        if (SUCCEEDED(wv->lpVtbl->QueryInterface(
                wv, &rb_iid_wv4, (void **)&wv4)) && wv4 != NULL) {
            wv4->lpVtbl->add_DownloadStarting(wv4, &g_dlstart_evt.base, &token);
            wv4->lpVtbl->Release(wv4);
        }
    }

    /* Project policy: JavaScript defaults to ON; the persisted setting is
       the only way to turn it off, applied when the webview is created. */
    if (SUCCEEDED(wv->lpVtbl->get_Settings(wv, &st)) && st) {
        st->lpVtbl->put_IsScriptEnabled(st, app->js_enabled ? TRUE : FALSE);
        st->lpVtbl->Release(st);
    }
    rb_wv_apply_web_settings(app, wv);

    rb_wv_area(app, &area);
    tv->ctrl->lpVtbl->put_Bounds(tv->ctrl, area);
    tv->ctrl->lpVtbl->put_IsVisible(tv->ctrl, (tv->tab_id == app->active_id) ? TRUE : FALSE);

    {
        rb_tab *t = rb_tabs_get(app->tabs, tv->tab_id);
        if (t && t->url && t->url[0]) {
            wchar_t *wu = rb_utf8_to_wide(t->url);
            if (wu) {
                wv->lpVtbl->Navigate(wv, wu);
                free(wu);
            }
        }
    }
}

/* Applies the ACTIVE profile's per-webview settings to one webview.
 *
 * Called at wire time and again whenever the preferences editor changes
 * something, so a switch takes effect on the pages already open rather than
 * at the next restart.  Each setting is applied only when the interface that
 * carries it is actually present: a machine running an older WebView2
 * Runtime returns E_NOINTERFACE, and the honest response is to leave the
 * engine default alone rather than to fail. */
static void rb_wv_apply_web_settings(App *app, ICoreWebView2 *wv)
{
    ICoreWebView2Settings *st = NULL;
    char *ua;

    if (app == NULL || wv == NULL) return;

    if (SUCCEEDED(wv->lpVtbl->get_Settings(wv, &st)) && st != NULL) {
        st->lpVtbl->put_IsScriptEnabled(st, app->js_enabled ? TRUE : FALSE);
        /* The default context menu is deliberately NOT disabled.  It is the
         * only way to copy text out of a page, and the GTK edition leaves
         * WebKit's menu alone for the same reason; switching it off would
         * cost more than the tidier look is worth. */
        /* The link-target preview bubble is off.  Android's WebView has none
         * and neither does WebKitGTK, so this is the parity choice — and it
         * is the one thing given up here, since a link's address is then
         * only visible after it has been clicked. */
        st->lpVtbl->put_IsStatusBarEnabled(st, FALSE);
        st->lpVtbl->Release(st);
        st = NULL;
    }

    /* The User-Agent is the one setting that needs the newer interface:
     * put_UserAgent lives on ICoreWebView2Settings2, not on the base
     * settings object.  A NULL from rb_ua_current() means the profile wants
     * the engine default, which is exactly what happens when the put is
     * skipped. */
    ua = rb_ua_current(app);
    if (ua != NULL) {
        ICoreWebView2Settings2 *st2 = NULL;
        if (SUCCEEDED(wv->lpVtbl->QueryInterface(
                wv, &rb_iid_settings2, (void **)&st2)) && st2 != NULL) {
            wchar_t *wua = rb_utf8_to_wide(ua);
            if (wua != NULL) {
                st2->lpVtbl->put_UserAgent(st2, wua);
                free(wua);
            }
            st2->lpVtbl->Release(st2);
        }
        free(ua);
    }
}

/* Re-applies the settings to every live view.  The preferences editor calls
 * this after a change so the open pages follow it immediately. */
void rb_wv_apply_settings_all(App *app)
{
    struct RbViews *v = app ? app->views : NULL;
    int i;
    if (v == NULL) return;
    for (i = 0; i < v->n; i++) {
        TabView *tv = &v->items[i];
        if (tv->wv != NULL) rb_wv_apply_web_settings(app, tv->wv);
    }
}

/* Erases cookies, cache or site storage for the ACTIVE PROFILE.
 *
 * The data lives in the profile's own user-data folder, which the WebView2
 * runtime owns while it is running: deleting files behind it would be both
 * racy and ineffective, so the runtime's own eraser is used instead.  It is
 * reached through the profile object, which means the request has to go via
 * a live webview of that profile — and every profile has at least the visible
 * tab, so the loop over the views covers it.
 *
 * The calls are asynchronous and their results are not reported: the same
 * choice the GTK edition makes, where webkit_website_data_manager_clear is
 * also started and left to finish. */
void rb_wv_clear_browsing_data(App *app, unsigned kinds)
{
    struct RbViews *v = app ? app->views : NULL;
    COREWEBVIEW2_BROWSING_DATA_KINDS wv_kinds = (COREWEBVIEW2_BROWSING_DATA_KINDS)0;
    int i;

    if (v == NULL || kinds == 0) return;

    /* The three switches the preferences page offers, mapped onto the
     * runtime's own vocabulary.  Site data is a bundle rather than one kind:
     * Android's "site data" clears all of it, so this asks for every storage
     * kind WebView2 names. */
    if (kinds & RB_WV_CLEAR_COOKIES) {
        wv_kinds |= COREWEBVIEW2_BROWSING_DATA_KINDS_COOKIES;
    }
    if (kinds & RB_WV_CLEAR_CACHE) {
        wv_kinds |= COREWEBVIEW2_BROWSING_DATA_KINDS_DISK_CACHE;
    }
    if (kinds & RB_WV_CLEAR_SITE_DATA) {
        wv_kinds |= COREWEBVIEW2_BROWSING_DATA_KINDS_ALL_DOM_STORAGE |
                    COREWEBVIEW2_BROWSING_DATA_KINDS_INDEXED_DB |
                    COREWEBVIEW2_BROWSING_DATA_KINDS_LOCAL_STORAGE |
                    COREWEBVIEW2_BROWSING_DATA_KINDS_WEB_SQL |
                    COREWEBVIEW2_BROWSING_DATA_KINDS_CACHE_STORAGE |
                    COREWEBVIEW2_BROWSING_DATA_KINDS_SERVICE_WORKERS |
                    COREWEBVIEW2_BROWSING_DATA_KINDS_FILE_SYSTEMS;
    }

    for (i = 0; i < v->n; i++) {
        TabView *tv = &v->items[i];
        ICoreWebView2_13 *wv13 = NULL;
        ICoreWebView2Profile *prof = NULL;
        ICoreWebView2Profile2 *prof2 = NULL;
        ClearDoneEvt *done;

        if (tv->wv == NULL) continue;
        if (FAILED(tv->wv->lpVtbl->QueryInterface(tv->wv, &rb_iid_wv13,
                                                  (void **)&wv13)) ||
            wv13 == NULL) {
            continue;   /* runtime too old for the profile object */
        }
        if (SUCCEEDED(wv13->lpVtbl->get_Profile(wv13, &prof)) && prof != NULL) {
            if (SUCCEEDED(prof->lpVtbl->QueryInterface(prof, &rb_iid_profile2,
                                                       (void **)&prof2)) &&
                prof2 != NULL) {
                done = (ClearDoneEvt *)malloc(sizeof *done);
                if (done != NULL) {
                    memset(done, 0, sizeof *done);
                    done->base.lpVtbl = &g_cleardone_vtbl;
                    done->refs = 1;
                    prof2->lpVtbl->ClearBrowsingData(prof2, wv_kinds,
                                                     &done->base);
                    done->base.lpVtbl->Release(&done->base);
                }
                prof2->lpVtbl->Release(prof2);
            }
            prof->lpVtbl->Release(prof);
        }
        wv13->lpVtbl->Release(wv13);
    }
}

static void rb_wv_ensure_active(App *app)
{
    struct RbViews *v;
    TabView *tv;
    HRESULT hr;
    if (!app || !app->views) return;
    v = app->views;
    if (!v->env || app->active_id == 0) return;
    if (g_ctrl_in_flight != 0) return;   /* one creation at a time, chained */
    tv = rb_views_slot(v, app->active_id);
    if (!tv || tv->ctrl || tv->creating) return;

    tv->creating = 1;
    g_ctrl_in_flight = app->active_id;
    g_ctrl_handler.base.lpVtbl = &g_ctrl_vtbl;
    g_ctrl_handler.app = app;
    g_ctrl_handler.tab_id = app->active_id;
    g_ctrl_handler.refs = 1;

    hr = v->env->lpVtbl->CreateCoreWebView2Controller(v->env, app->hwnd, &g_ctrl_handler.base);
    if (FAILED(hr)) {
        tv->creating = 0;
        g_ctrl_in_flight = 0;
        rb_wv_unavailable(app);
    }
}

/* ------------------------------------------------------------------ */
/* Honest failure reporting (shown once) */

static void rb_wv_unavailable(App *app)
{
    if (!app || app->wv_failed) return;
    app->wv_failed = 1;
    MessageBoxW(app->hwnd,
        L"WebView2 Runtime is required (Evergreen).\n"
        L"Install from https://developer.microsoft.com/microsoft-edge/webview2/\n\n"
        L"The browser window stays open, but web pages will not load.",
        L"Room Browser - WebView2 missing",
        MB_OK | MB_ICONWARNING);
}

/* ------------------------------------------------------------------ */
/* One-time handler initialization. The event handlers are static (they
 * live for the whole process), but their vtable/app/refs must be wired
 * BEFORE any add_*() hands them to WebView2, otherwise the very first
 * AddRef/QueryInterface call would dereference a NULL lpVtbl. */

static void rb_wv_handlers_init(App *app)
{
    g_title_evt.base.lpVtbl = &g_title_vtbl;
    g_title_evt.app = app;
    g_title_evt.refs = 1;

    g_source_evt.base.lpVtbl = &g_source_vtbl;
    g_source_evt.app = app;
    g_source_evt.refs = 1;

    g_history_evt.base.lpVtbl = &g_history_vtbl;
    g_history_evt.app = app;
    g_history_evt.refs = 1;

    g_nav_evt.base.lpVtbl = &g_nav_vtbl;
    g_nav_evt.app = app;
    g_nav_evt.refs = 1;

    g_navstart_evt.base.lpVtbl = &g_navstart_vtbl;
    g_navstart_evt.app = app;
    g_navstart_evt.refs = 1;

    g_newwin_evt.base.lpVtbl = &g_newwin_vtbl;
    g_newwin_evt.app = app;
    g_newwin_evt.refs = 1;

    g_webres_evt.base.lpVtbl = &g_webres_vtbl;
    g_webres_evt.app = app;
    g_webres_evt.refs = 1;

    g_dlstart_evt.base.lpVtbl = &g_dlstart_vtbl;
    g_dlstart_evt.app = app;
    g_dlstart_evt.refs = 1;
}

/* ------------------------------------------------------------------ */
/* Public API */

/* The WebView2 user data folder for the ACTIVE profile, which is where
 * cookies, localStorage, the cache and the HTTP auth cache live.  Putting it
 * under the profile's browser_data directory is what makes profile isolation
 * real on Windows: two profiles get two folders and therefore two unrelated
 * cookie jars, exactly as they do on Android and on the GTK edition
 * (which does the same thing with WebKit's website data manager).
 *
 * The profile id is a UUID and a UUID is not a safe folder name on its own,
 * so rb_profile_safe_suffix() is what names it — the same helper the core
 * hands the Android and GTK editions.
 *
 * Returns 0 and fills `out`, or -1 to let WebView2 fall back to its own
 * default location (a shared folder, so isolation is lost — the caller
 * cannot do better, and silently failing to start would be worse). */
static int rb_wv_user_data_dir(App *app, wchar_t *out, size_t cap)
{
    const rb_profile *p;
    char *data = NULL;
    char *dir = NULL;
    wchar_t *wide = NULL;
    int rc = -1;

    if (app == NULL || out == NULL || cap == 0) return -1;
    p = rb_active_profile(app);
    if (p == NULL || p->id[0] == '\0') return -1;

    data = rb_paths_data_dir();
    if (data != NULL && rb_profile_ensure_dirs(data, p->id) == 0) {
        dir = rb_profile_subdir(data, p->id, RB_PROFILE_DIR_BROWSER_DATA);
    }
    if (data != NULL) rb_paths_free(data);

    if (dir != NULL) {
        wide = rb_utf8_to_wide(dir);
        if (wide != NULL) {
            rb_mkdirs_wide(wide);
            if (wcslen(wide) < cap) {
                wcscpy(out, wide);
                rc = 0;
            }
            free(wide);
        }
    }
    free(dir);
    return rc;
}

int rb_wv_init(App *app)
{
    struct RbViews *v;
    wchar_t udd[1024];
    HRESULT hr;

    v = (struct RbViews *)calloc(1, sizeof *v);
    if (!v) return -1;
    v->app = app;
    app->views = v;

    rb_wv_handlers_init(app);

    g_loader = LoadLibraryW(L"WebView2Loader.dll");
    if (!g_loader) {
        rb_wv_unavailable(app);
        return -1;
    }
    g_create_env_opts = (RB_pfn_create_env_opts)(void *)GetProcAddress(
        g_loader, "CreateCoreWebView2EnvironmentWithOptions");
    if (!g_create_env_opts) {
        g_create_env = (RB_pfn_create_env)(void *)GetProcAddress(
            g_loader, "CreateCoreWebView2Environment");
    }
    if (!g_create_env_opts && !g_create_env) {
        rb_wv_unavailable(app);
        return -1;
    }

    udd[0] = 0;
    if (rb_wv_user_data_dir(app, udd, 1024) != 0) {
        udd[0] = 0;   /* fall back to the default user data folder */
    }

    g_env_handler.base.lpVtbl = &g_env_vtbl;
    g_env_handler.app = app;
    g_env_handler.refs = 1;

    if (g_create_env_opts) {
        hr = g_create_env_opts(NULL, udd[0] ? udd : NULL, NULL, &g_env_handler.base);
    } else {
        hr = g_create_env(&g_env_handler.base);
    }
    if (FAILED(hr)) {
        rb_wv_unavailable(app);
        return -1;
    }
    return 0;
}

void rb_wv_activate(App *app, long tab_id)
{
    struct RbViews *v = app ? app->views : NULL;
    int i;
    if (!v) return;
    for (i = 0; i < v->n; i++) {
        TabView *tv = &v->items[i];
        if (!tv->ctrl) continue;
        tv->ctrl->lpVtbl->put_IsVisible(tv->ctrl, (tv->tab_id == tab_id) ? TRUE : FALSE);
    }
    {
        TabView *tv = rb_views_find(v, tab_id);
        if (tv && tv->ctrl) {
            RECT area;
            rb_wv_area(app, &area);
            tv->ctrl->lpVtbl->put_Bounds(tv->ctrl, area);
        }
    }
    rb_wv_ensure_active(app);
}

void rb_wv_drop_tab(App *app, long tab_id)
{
    struct RbViews *v = app ? app->views : NULL;
    int i;
    if (!v) return;
    for (i = 0; i < v->n; i++) {
        if (v->items[i].tab_id == tab_id) {
            TabView *tv = &v->items[i];
            if (tv->ctrl) {
                tv->ctrl->lpVtbl->Close(tv->ctrl);
                tv->ctrl->lpVtbl->Release(tv->ctrl);
                tv->ctrl = NULL;
            }
            if (tv->wv) {
                tv->wv->lpVtbl->Release(tv->wv);
                tv->wv = NULL;
            }
            memmove(&v->items[i], &v->items[i + 1],
                    (size_t)(v->n - i - 1) * sizeof(v->items[0]));
            v->n--;
            return;
        }
    }
}

void rb_wv_resize(App *app)
{
    struct RbViews *v = app ? app->views : NULL;
    RECT area;
    int i;
    if (!v) return;
    rb_wv_area(app, &area);
    for (i = 0; i < v->n; i++) {
        if (v->items[i].ctrl) {
            v->items[i].ctrl->lpVtbl->put_Bounds(v->items[i].ctrl, area);
        }
    }
}

void rb_wv_navigate(App *app, const char *url)
{
    TabView *tv = rb_active_view(app);
    wchar_t *wu;
    if (!tv || !tv->wv || !url) return;
    wu = rb_utf8_to_wide(url);
    if (!wu) return;
    tv->wv->lpVtbl->Navigate(tv->wv, wu);
    free(wu);
}

void rb_wv_goback(App *app)
{
    TabView *tv = rb_active_view(app);
    if (tv && tv->wv) tv->wv->lpVtbl->GoBack(tv->wv);
}

void rb_wv_gofwd(App *app)
{
    TabView *tv = rb_active_view(app);
    if (tv && tv->wv) tv->wv->lpVtbl->GoForward(tv->wv);
}

void rb_wv_reload(App *app)
{
    TabView *tv = rb_active_view(app);
    if (tv && tv->wv) tv->wv->lpVtbl->Reload(tv->wv);
}

void rb_wv_stop(App *app)
{
    TabView *tv = rb_active_view(app);
    if (tv && tv->wv) tv->wv->lpVtbl->Stop(tv->wv);
}

/* Stops every view, not just the visible one.  The profile switch needs this
 * before it tears the environment down: a hidden webview still loading would
 * otherwise be handed a released environment. */
void rb_wv_stop_all(App *app)
{
    struct RbViews *v = app ? app->views : NULL;
    int i;
    if (v == NULL) return;
    for (i = 0; i < v->n; i++) {
        TabView *tv = &v->items[i];
        if (tv->wv != NULL) tv->wv->lpVtbl->Stop(tv->wv);
    }
}

void rb_wv_can_nav(App *app, int *can_back, int *can_fwd)
{
    TabView *tv = rb_active_view(app);
    BOOL b = FALSE, f = FALSE;
    if (can_back) *can_back = 0;
    if (can_fwd) *can_fwd = 0;
    if (!tv || !tv->wv) return;
    if (SUCCEEDED(tv->wv->lpVtbl->get_CanGoBack(tv->wv, &b)) && b) {
        if (can_back) *can_back = 1;
    }
    if (SUCCEEDED(tv->wv->lpVtbl->get_CanGoForward(tv->wv, &f)) && f) {
        if (can_fwd) *can_fwd = 1;
    }
}

void rb_wv_shutdown(App *app)
{
    struct RbViews *v = app ? app->views : NULL;
    int i;
    if (!v) return;
    for (i = 0; i < v->n; i++) {
        TabView *tv = &v->items[i];
        if (tv->ctrl) {
            tv->ctrl->lpVtbl->Close(tv->ctrl);
            tv->ctrl->lpVtbl->Release(tv->ctrl);
            tv->ctrl = NULL;
        }
        if (tv->wv) {
            tv->wv->lpVtbl->Release(tv->wv);
            tv->wv = NULL;
        }
    }
    free(v->items);
    if (v->env) {
        v->env->lpVtbl->Release(v->env);
        v->env = NULL;
    }
    free(v);
    app->views = NULL;
    if (g_loader) {
        FreeLibrary(g_loader);
        g_loader = NULL;
    }
    g_create_env_opts = NULL;
    g_create_env = NULL;
    g_ctrl_in_flight = 0;
}
