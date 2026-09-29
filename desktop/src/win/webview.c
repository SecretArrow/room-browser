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

/* ------------------------------------------------------------------ */
/* Forward declarations */

static void rb_wv_ensure_active(App *app);
static void rb_wv_wire(App *app, TabView *tv);
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
                    app->loading = 1;
                    rb_update_reloadbtn(app);
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

static HRESULT STDMETHODCALLTYPE NavEvt_Invoke(
    ICoreWebView2NavigationCompletedEventHandler *self,
    ICoreWebView2 *sender, ICoreWebView2NavigationCompletedEventArgs *args)
{
    NavEvt *h = (NavEvt *)self;
    App *app = h->app;
    TabView *tv = app && app->views ? rb_views_by_wv(app->views, sender) : NULL;
    rb_tab *t;
    (void)args;
    if (!tv) return S_OK;
    t = rb_tabs_get(app->tabs, tv->tab_id);
    if (!t) return S_OK;

    if (tv->tab_id == app->active_id) {
        app->loading = 0;
        rb_update_reloadbtn(app);
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
    r->top += RB_TABSTRIP_H + RB_TOOLBAR_H;
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

    /* Project policy: JavaScript defaults to ON; the persisted setting is
       the only way to turn it off, applied when the webview is created. */
    if (SUCCEEDED(wv->lpVtbl->get_Settings(wv, &st)) && st) {
        st->lpVtbl->put_IsScriptEnabled(st, app->js_enabled ? TRUE : FALSE);
        st->lpVtbl->Release(st);
    }

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
/* One-time handler initialization. The four event handlers are static
 * (they live for the whole process), but their vtable/app/refs must be
 * wired BEFORE any add_*() hands them to WebView2, otherwise the very
 * first AddRef/QueryInterface call would dereference a NULL lpVtbl. */

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
}

/* ------------------------------------------------------------------ */
/* Public API */

static int rb_wv_user_data_dir(wchar_t *out, size_t cap)
{
    wchar_t la[1024];
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", la, 1024);
    int w;
    if (n == 0 || n >= 1024) return -1;
    w = swprintf(out, cap, L"%ls\\RoomBrowser\\WebView2", la);
    if (w < 0) return -1;
    rb_mkdirs_wide(out);
    return 0;
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
    if (rb_wv_user_data_dir(udd, 1024) != 0) {
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
