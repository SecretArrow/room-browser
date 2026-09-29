/*
 * Room Browser (desktop) - Windows chrome implementation.
 *
 * Win32 chrome in pure C11: tab strip (owner-drawn buttons), toolbar with
 * omnibox, bookmark star and menu, plus the shared persistence glue.
 * Dark Brave-inspired palette with purple accent (#A78BFA).
 *
 * Design notes (v1, documented honestly in desktop/README.md):
 *  - All buttons are standard BUTTON controls drawn via BS_OWNERDRAW so the
 *    dark palette is actually rendered (plain WM_CTLCOLOR theming cannot
 *    restyle themed push buttons reliably).
 *  - The omnibox is a standard EDIT control, dark-themed through
 *    WM_CTLCOLORECT + a purple focus ring painted by the parent.
 *  - Keyboard shortcuts are handled in the main window proc; while the
 *    WebView2 child has focus those accelerators are inactive (v1).
 */
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#include "chrome.h"
#include "webview.h"
#include "resource.h"
#include "rb_version.h"

/* ------------------------------------------------------------------ */
/* Small helpers */

/* Single global application state (declared extern in chrome.h). */
App g_app;

wchar_t *rb_utf8_to_wide(const char *s)
{
    int n;
    wchar_t *w;
    if (!s) return NULL;
    n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 0) return NULL;
    w = (wchar_t *)malloc((size_t)n * sizeof(wchar_t));
    if (!w) return NULL;
    if (MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n) <= 0) {
        free(w);
        return NULL;
    }
    return w;
}

char *rb_wide_to_utf8(const wchar_t *s)
{
    int n;
    char *u;
    if (!s) return NULL;
    n = WideCharToMultiByte(CP_UTF8, 0, s, -1, NULL, 0, NULL, NULL);
    if (n <= 0) return NULL;
    u = (char *)malloc((size_t)n);
    if (!u) return NULL;
    if (WideCharToMultiByte(CP_UTF8, 0, s, -1, u, n, NULL, NULL) <= 0) {
        free(u);
        return NULL;
    }
    return u;
}

char *rb_strdup(const char *s)
{
    size_t n;
    char *p;
    if (!s) return NULL;
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (!p) return NULL;
    memcpy(p, s, n);
    return p;
}

void rb_set_str(char **dst, char *owned)
{
    if (!dst) { free(owned); return; }
    free(*dst);
    *dst = owned;
}

void rb_mkdirs_wide(const wchar_t *path)
{
    wchar_t buf[1024];
    size_t len, i;
    if (!path) return;
    len = wcslen(path);
    if (len == 0 || len >= 1024) return;
    memcpy(buf, path, (len + 1) * sizeof(wchar_t));
    for (i = 0; buf[i]; i++) {
        if (buf[i] == L'\\' && i > 0) {
            buf[i] = 0;
            CreateDirectoryW(buf, NULL);
            buf[i] = L'\\';
        }
    }
    CreateDirectoryW(buf, NULL);
}

void rb_mkdirs_utf8(const char *path)
{
    wchar_t *w = rb_utf8_to_wide(path);
    if (!w) return;
    rb_mkdirs_wide(w);
    free(w);
}

/* ------------------------------------------------------------------ */
/* Persistence: load at startup, save on change (and a final save at quit). */

int rb_data_init(App *app)
{
    char *dir = rb_paths_data_dir();
    size_t n;

    app->tabs = rb_tabs_new();
    app->history = rb_history_new();
    app->bookmarks = rb_bookmarks_new();
    app->settings = rb_settings_new();
    if (!app->tabs || !app->history || !app->bookmarks || !app->settings) return -1;

    if (!dir) return -1;
    rb_mkdirs_utf8(dir);
    n = strlen(dir) + 32;

    app->path_history = (char *)malloc(n);
    app->path_bookmarks = (char *)malloc(n);
    app->path_settings = (char *)malloc(n);
    if (!app->path_history || !app->path_bookmarks || !app->path_settings) {
        rb_paths_free(dir);
        return -1;
    }
    snprintf(app->path_history, n, "%s\\history.jsonl", dir);
    snprintf(app->path_bookmarks, n, "%s\\bookmarks.jsonl", dir);
    snprintf(app->path_settings, n, "%s\\settings.txt", dir);
    rb_paths_free(dir);

    rb_settings_load(app->settings, app->path_settings);
    rb_history_load(app->history, app->path_history);
    rb_bookmarks_load(app->bookmarks, app->path_bookmarks);

    app->home_url = rb_strdup(rb_settings_get(app->settings, "home", "https://duckduckgo.com"));
    /* Project policy: JavaScript is NEVER disabled by default. */
    app->js_enabled = rb_settings_get_int(app->settings, "javascript", 1);
    return 0;
}

void rb_data_shutdown(App *app)
{
    if (app->history && app->path_history) rb_history_save(app->history, app->path_history);
    if (app->bookmarks && app->path_bookmarks) rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    if (app->settings && app->path_settings) rb_settings_save(app->settings, app->path_settings);
}

void rb_data_free(App *app)
{
    int i;
    for (i = 0; i < RB_HIST_MENU_MAX; i++) {
        free(app->hist_menu[i]);
        app->hist_menu[i] = NULL;
    }
    app->hist_menu_n = 0;
    free(app->path_history);   app->path_history = NULL;
    free(app->path_bookmarks); app->path_bookmarks = NULL;
    free(app->path_settings);  app->path_settings = NULL;
    free(app->home_url);       app->home_url = NULL;
    if (app->tabs)      { rb_tabs_free(app->tabs);        app->tabs = NULL; }
    if (app->history)   { rb_history_free(app->history);  app->history = NULL; }
    if (app->bookmarks) { rb_bookmarks_free(app->bookmarks); app->bookmarks = NULL; }
    if (app->settings)  { rb_settings_free(app->settings); app->settings = NULL; }
    free(app->tab_btns);   app->tab_btns = NULL;
    free(app->tab_closes); app->tab_closes = NULL;
    app->tab_slots = 0;
    if (app->br_chrome)  { DeleteObject(app->br_chrome);  app->br_chrome = NULL; }
    if (app->br_toolbar) { DeleteObject(app->br_toolbar); app->br_toolbar = NULL; }
    if (app->br_tab_idle){ DeleteObject(app->br_tab_idle);app->br_tab_idle = NULL; }
    if (app->br_omni)    { DeleteObject(app->br_omni);    app->br_omni = NULL; }
    if (app->br_accent)  { DeleteObject(app->br_accent);  app->br_accent = NULL; }
    if (app->fnt_ui)     { DeleteObject(app->fnt_ui);     app->fnt_ui = NULL; }
    if (app->fnt_omni)   { DeleteObject(app->fnt_omni);   app->fnt_omni = NULL; }
}

/* ------------------------------------------------------------------ */
/* UI refresh helpers */

void rb_update_omni(App *app, const char *url)
{
    wchar_t *w = rb_utf8_to_wide(url ? url : "");
    if (!w) return;
    SetWindowTextW(app->omni, w);
    free(w);
}

void rb_update_titlebar(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    if (t && t->title && t->title[0]) {
        wchar_t *wt = rb_utf8_to_wide(t->title);
        if (wt) {
            wchar_t buf[600];
            if (swprintf(buf, 600, L"%ls \x2014 Room Browser", wt) > 0) {
                SetWindowTextW(app->hwnd, buf);
            }
            free(wt);
            return;
        }
    }
    SetWindowTextW(app->hwnd, L"Room Browser");
}

void rb_update_nav(App *app)
{
    int back = 0, fwd = 0;
    rb_wv_can_nav(app, &back, &fwd);
    EnableWindow(app->back, back ? TRUE : FALSE);
    EnableWindow(app->fwd, fwd ? TRUE : FALSE);
}

void rb_update_star(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    int on = (t && t->url && rb_bookmarks_contains(app->bookmarks, t->url));
    SetWindowTextW(app->star, on ? L"\x2605" : L"\x2606");
}

void rb_update_reloadbtn(App *app)
{
    SetWindowTextW(app->reload, app->loading ? L"\x00D7" : L"\x21BB");
}

void rb_update_all(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    rb_update_omni(app, t && t->url ? t->url : "");
    rb_update_titlebar(app);
    rb_update_nav(app);
    rb_update_star(app);
    rb_update_reloadbtn(app);
}

/* ------------------------------------------------------------------ */
/* Actions */

void rb_do_new_tab(App *app)
{
    long id = rb_tabs_add(app->tabs, "New Tab",
                          app->home_url ? app->home_url : "https://duckduckgo.com");
    app->active_id = id;
    rb_tabs_rebuild(app);
    rb_update_all(app);
    rb_wv_activate(app, id);
}

void rb_do_close_tab(App *app, long id)
{
    rb_wv_drop_tab(app, id);
    rb_tabs_close(app->tabs, id);
    if (app->active_id == id) app->active_id = 0;
    if (rb_tabs_count(app->tabs) == 0) {
        rb_do_new_tab(app);
        return;
    }
    if (app->active_id == 0) {
        const rb_tab *first = rb_tabs_at(app->tabs, 0);
        app->active_id = first ? first->id : 0;
    }
    rb_tabs_rebuild(app);
    rb_update_all(app);
    if (app->active_id) rb_wv_activate(app, app->active_id);
}

void rb_do_activate(App *app, long id)
{
    app->active_id = id;
    rb_tabs_rebuild(app);
    rb_update_all(app);
    rb_wv_activate(app, id);
}

void rb_do_navigate(App *app, const char *url)
{
    rb_tab *t;
    if (!url || !url[0]) return;
    t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    if (!t) return;
    rb_set_str(&t->url, rb_strdup(url));
    app->loading = 1;
    rb_update_reloadbtn(app);
    rb_update_omni(app, url);
    rb_wv_navigate(app, url);
}

void rb_do_toggle_bookmark(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    if (!t || !t->url || !t->url[0]) return;
    /* BrowserViewModel.toggleBookmark, policy included: the same call stars
     * and unstars, and a blank title falls back to the URL. */
    (void)rb_bookmarks_toggle(app->bookmarks, t->url,
                              t->title && t->title[0] ? t->title : t->url,
                              rb_profile_now_ms());
    if (app->path_bookmarks) rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    rb_update_star(app);
}

void rb_do_reload_or_stop(App *app)
{
    if (app->loading) {
        rb_wv_stop(app);
    } else {
        rb_wv_reload(app);
    }
}

/* ------------------------------------------------------------------ */
/* Layout */

void rb_layout(App *app)
{
    RECT rc;
    int w, n, i, x, tw, ow;
    if (!app->hwnd) return;
    GetClientRect(app->hwnd, &rc);
    w = rc.right > 0 ? rc.right : 0;

    n = rb_tabs_count(app->tabs);
    tw = (n > 0) ? (w - 40) / n : 180;
    if (tw > 180) tw = 180;
    if (tw < 40) tw = 40;
    x = 0;
    for (i = 0; i < n && i < app->tab_slots; i++) {
        if (app->tab_btns[i])   MoveWindow(app->tab_btns[i], x, 2, tw, 30, TRUE);
        if (app->tab_closes[i]) MoveWindow(app->tab_closes[i], x + tw - 24, 7, 18, 20, TRUE);
        x += tw;
    }
    if (app->newtab) MoveWindow(app->newtab, x + 2, 2, 30, 30, TRUE);

    if (app->back)   MoveWindow(app->back,   4,       36, 36, 36, TRUE);
    if (app->fwd)    MoveWindow(app->fwd,    44,      36, 36, 36, TRUE);
    if (app->reload) MoveWindow(app->reload, 84,      36, 36, 36, TRUE);
    if (app->home)   MoveWindow(app->home,   124,     36, 36, 36, TRUE);
    if (app->star)   MoveWindow(app->star,   w - 116, 36, 36, 36, TRUE);
    if (app->menu_btn) MoveWindow(app->menu_btn, w - 72, 36, 36, 36, TRUE);
    ow = w - 286;
    if (ow < 60) ow = 60;
    if (app->omni) MoveWindow(app->omni, 166, 38, ow, 32, TRUE);

    rb_wv_resize(app);
}

void rb_tabs_rebuild(App *app)
{
    int n = rb_tabs_count(app->tabs);
    int i;

    for (i = 0; i < app->tab_slots; i++) {
        if (app->tab_btns[i])   DestroyWindow(app->tab_btns[i]);
        if (app->tab_closes[i]) DestroyWindow(app->tab_closes[i]);
        app->tab_btns[i] = NULL;
        app->tab_closes[i] = NULL;
    }

    if (n > app->tab_slots) {
        int cap = app->tab_slots ? app->tab_slots * 2 : 8;
        HWND *nb, *nc;
        while (cap < n) cap *= 2;
        nb = (HWND *)calloc((size_t)cap, sizeof(HWND));
        nc = (HWND *)calloc((size_t)cap, sizeof(HWND));
        if (nb && nc) {
            if (app->tab_slots > 0) {
                memcpy(nb, app->tab_btns,   (size_t)app->tab_slots * sizeof(HWND));
                memcpy(nc, app->tab_closes, (size_t)app->tab_slots * sizeof(HWND));
            }
            free(app->tab_btns);
            free(app->tab_closes);
            app->tab_btns = nb;
            app->tab_closes = nc;
            app->tab_slots = cap;
        } else {
            free(nb);
            free(nc);
            n = app->tab_slots;   /* cannot grow: draw what fits */
        }
    }

    for (i = 0; i < n && i < app->tab_slots; i++) {
        const rb_tab *t = rb_tabs_at(app->tabs, i);
        const wchar_t *txt = L"New Tab";
        wchar_t *tmp = NULL;
        if (t && t->title && t->title[0]) {
            tmp = rb_utf8_to_wide(t->title);
            if (tmp) txt = tmp;
        }
        app->tab_btns[i] = CreateWindowExW(0, L"BUTTON", txt,
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON | BS_OWNERDRAW,
            0, 2, 180, 30, app->hwnd,
            (HMENU)(INT_PTR)(RB_ID_TAB_FIRST + 2 * i), app->hinst, NULL);
        if (app->tab_btns[i]) {
            SendMessageW(app->tab_btns[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
        }
        free(tmp);
        app->tab_closes[i] = CreateWindowExW(0, L"BUTTON", L"\x00D7",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON | BS_OWNERDRAW,
            0, 7, 18, 20, app->hwnd,
            (HMENU)(INT_PTR)(RB_ID_TAB_FIRST + 2 * i + 1), app->hinst, NULL);
        if (app->tab_closes[i]) {
            SendMessageW(app->tab_closes[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
        }
    }
    rb_layout(app);
}

/* ------------------------------------------------------------------ */
/* Owner-drawn buttons */

static LRESULT rb_draw_button(App *app, const DRAWITEMSTRUCT *dis)
{
    int id = (int)dis->CtlID;
    int idx = id - RB_ID_TAB_FIRST;
    BOOL is_tab = (idx >= 0 && (idx & 1) == 0);
    BOOL is_close = (idx >= 0 && (idx & 1) == 1);
    BOOL active = FALSE;
    HBRUSH bg = app->br_toolbar;
    wchar_t text[512];
    UINT fmt = DT_SINGLELINE | DT_VCENTER | DT_CENTER | DT_NOPREFIX;
    RECT tr = dis->rcItem;
    HFONT old;

    text[0] = 0;
    GetWindowTextW(dis->hwndItem, text, 512);

    if (is_tab || is_close) {
        int i = idx / 2;
        int n = rb_tabs_count(app->tabs);
        const rb_tab *t = (i >= 0 && i < n) ? rb_tabs_at(app->tabs, i) : NULL;
        active = (t && t->id == app->active_id);
        bg = active ? app->br_toolbar : app->br_tab_idle;
    }

    FillRect(dis->hDC, &dis->rcItem, bg);
    if (is_tab && active) {
        RECT ul = dis->rcItem;   /* 2px purple underline for the active tab */
        ul.top = ul.bottom - 2;
        FillRect(dis->hDC, &ul, app->br_accent);
    }
    if (dis->itemState & ODS_SELECTED) {
        FrameRect(dis->hDC, &dis->rcItem, app->br_accent);
    }

    SetBkMode(dis->hDC, TRANSPARENT);
    SetTextColor(dis->hDC, RB_COL_TEXT);
    old = (HFONT)SelectObject(dis->hDC, app->fnt_ui);
    if (is_tab) {
        tr.left += 8;
        tr.right -= 26;   /* keep clear of the close button */
        fmt = DT_SINGLELINE | DT_VCENTER | DT_LEFT | DT_END_ELLIPSIS | DT_NOPREFIX;
    }
    DrawTextW(dis->hDC, text, -1, &tr, fmt);
    SelectObject(dis->hDC, old);
    return TRUE;
}

/* ------------------------------------------------------------------ */
/* Menu (popup) */

static void rb_show_about(App *app)
{
    wchar_t *v = rb_utf8_to_wide(RB_VERSION);
    wchar_t msg[192];
    if (v) {
        if (swprintf(msg, 192, L"Room Browser %ls \x2014 desktop edition by Maragung", v) <= 0) {
            swprintf(msg, 192, L"Room Browser \x2014 desktop edition by Maragung");
        }
        free(v);
    } else {
        swprintf(msg, 192, L"Room Browser \x2014 desktop edition by Maragung");
    }
    MessageBoxW(app->hwnd, msg, L"About Room Browser", MB_OK | MB_ICONINFORMATION);
}

static void rb_menu_show(App *app)
{
    HMENU m = CreatePopupMenu();
    HMENU hist = CreatePopupMenu();
    int i, n = 0;
    const rb_hist_entry *rec;
    RECT r;

    AppendMenuW(m, MF_STRING, IDM_NEW_TAB, L"New Tab");
    AppendMenuW(m, MF_STRING, IDM_BOOKMARK, L"Bookmark this page");

    /* Snapshot the recent history URLs; WM_COMMAND for these ids arrives
       after TrackPopupMenu returns, so the snapshot lives until the next
       time the menu is opened. */
    for (i = 0; i < app->hist_menu_n; i++) {
        free(app->hist_menu[i]);
        app->hist_menu[i] = NULL;
    }
    app->hist_menu_n = 0;
    rec = rb_history_recent(app->history, 10, &n);
    for (i = 0; i < n && i < RB_HIST_MENU_MAX; i++) {
        const char *url = rec[i].url ? rec[i].url : "";
        const char *title = (rec[i].title && rec[i].title[0]) ? rec[i].title : url;
        wchar_t *wl = rb_utf8_to_wide(title);
        app->hist_menu[i] = rb_strdup(url);
        app->hist_menu_n++;
        if (wl) {
            AppendMenuW(hist, MF_STRING, IDM_HIST_FIRST + i, wl);
            free(wl);
        }
    }
    if (app->hist_menu_n == 0) {
        AppendMenuW(hist, MF_STRING | MF_GRAYED, 0, L"(empty)");
    }
    AppendMenuW(m, MF_POPUP, (UINT_PTR)hist, L"Recent history");
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING, IDM_ABOUT, L"About");

    GetWindowRect(app->menu_btn, &r);
    SetForegroundWindow(app->hwnd);
    TrackPopupMenu(m, TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, r.left, r.top, 0, app->hwnd, NULL);
    DestroyMenu(m);
}

static void rb_on_command(App *app, int id, int notify)
{
    if (notify != BN_CLICKED && notify != 0) return;

    if (id == IDC_BACK) {
        rb_wv_goback(app);
    } else if (id == IDC_FWD) {
        rb_wv_gofwd(app);
    } else if (id == IDC_RELOAD) {
        rb_do_reload_or_stop(app);
    } else if (id == IDC_HOME) {
        rb_do_navigate(app, app->home_url ? app->home_url : "https://duckduckgo.com");
    } else if (id == IDC_STAR) {
        rb_do_toggle_bookmark(app);
    } else if (id == IDC_MENU) {
        rb_menu_show(app);
    } else if (id == IDC_NEWBTN) {
        rb_do_new_tab(app);
    } else if (id == IDM_NEW_TAB) {
        rb_do_new_tab(app);
    } else if (id == IDM_BOOKMARK) {
        rb_do_toggle_bookmark(app);
    } else if (id == IDM_ABOUT) {
        rb_show_about(app);
    } else if (id >= IDM_HIST_FIRST && id < IDM_HIST_FIRST + app->hist_menu_n) {
        const char *url = app->hist_menu[id - IDM_HIST_FIRST];
        if (url) rb_do_navigate(app, url);
    } else if (id >= RB_ID_TAB_FIRST) {
        int k = id - RB_ID_TAB_FIRST;
        int i = k / 2;
        int n = rb_tabs_count(app->tabs);
        if (i >= 0 && i < n) {
            const rb_tab *t = rb_tabs_at(app->tabs, i);
            if (t) {
                if ((k & 1) == 0) rb_do_activate(app, t->id);
                else rb_do_close_tab(app, t->id);
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Omnibox subclass (Enter navigates, Escape restores the current URL) */

static LRESULT CALLBACK rb_omni_subclass(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp,
                                         UINT_PTR uSubclass, DWORD_PTR dwData)
{
    App *app = (App *)dwData;
    (void)uSubclass;
    if (app) {
        if (msg == WM_KEYDOWN && wp == VK_RETURN) {
            wchar_t buf[2048];
            int len = GetWindowTextW(hwnd, buf, 2048);
            if (len > 0) {
                char *u8 = rb_wide_to_utf8(buf);
                if (u8) {
                    char *url = rb_url_decide(u8);
                    free(u8);
                    if (url) {
                        rb_do_navigate(app, url);
                        free(url);
                    }
                }
            }
            return 0;
        }
        if (msg == WM_KEYDOWN && wp == VK_ESCAPE) {
            rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
            rb_update_omni(app, t && t->url ? t->url : "");
            return 0;
        }
        if (msg == WM_SETFOCUS || msg == WM_KILLFOCUS) {
            RECT r;
            GetWindowRect(hwnd, &r);
            InflateRect(&r, 2, 2);
            MapWindowPoints(NULL, app->hwnd, (POINT *)&r, 2);
            InvalidateRect(app->hwnd, &r, FALSE);
        }
    }
    return DefSubclassProc(hwnd, msg, wp, lp);
}

/* ------------------------------------------------------------------ */
/* Dark title bar via runtime-loaded dwmapi (silent fallback). */

static void rb_apply_dark_titlebar(HWND hwnd)
{
    typedef HRESULT (WINAPI *PFN_DwmSetWindowAttribute)(HWND, DWORD, LPCVOID, DWORD);
    HMODULE mod = LoadLibraryW(L"dwmapi.dll");
    PFN_DwmSetWindowAttribute fn;
    if (!mod) return;
    fn = (PFN_DwmSetWindowAttribute)(void *)GetProcAddress(mod, "DwmSetWindowAttribute");
    if (fn) {
        BOOL dark = TRUE;
        fn(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &dark, sizeof(dark));
    }
    FreeLibrary(mod);
}

/* ------------------------------------------------------------------ */
/* Window procedure */

static LRESULT CALLBACK rb_wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    App *app = (App *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_CREATE: {
        CREATESTRUCTW *cs = (CREATESTRUCTW *)lp;
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)cs->lpCreateParams);
        return 0;
    }
    case WM_SIZE:
        if (app) rb_layout(app);
        return 0;
    case WM_ERASEBKGND:
        return 1;   /* painted in WM_PAINT to avoid flicker */
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(hwnd, &ps);
        if (app && dc) {
            FillRect(dc, &ps.rcPaint, app->br_chrome);
            if (app->omni && GetFocus() == app->omni) {
                RECT r;
                POINT tl, br;
                GetWindowRect(app->omni, &r);
                tl.x = r.left;  tl.y = r.top;
                br.x = r.right; br.y = r.bottom;
                ScreenToClient(hwnd, &tl);
                ScreenToClient(hwnd, &br);
                SetRect(&r, tl.x, tl.y, br.x, br.y);
                InflateRect(&r, 1, 1);
                FrameRect(dc, &r, app->br_accent);
            }
        }
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_CTLCOLOREDIT:
        if (app && (HWND)lp == app->omni) {
            SetTextColor((HDC)wp, RB_COL_TEXT);
            SetBkColor((HDC)wp, RB_COL_OMNI_BG);
            return (LRESULT)app->br_omni;
        }
        break;
    case WM_CTLCOLORBTN:
        if (app) return (LRESULT)app->br_chrome;
        break;
    case WM_COMMAND:
        if (app) rb_on_command(app, (int)LOWORD(wp), (int)HIWORD(wp));
        return 0;
    case WM_DRAWITEM:
        if (app) return rb_draw_button(app, (const DRAWITEMSTRUCT *)lp);
        break;
    case WM_KEYDOWN:
        if (app) {
            BOOL ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
            BOOL alt = (GetKeyState(VK_MENU) & 0x8000) != 0;
            if (wp == VK_F5 || (wp == 'R' && ctrl)) {
                rb_do_reload_or_stop(app);
                return 0;
            }
            if (wp == 'T' && ctrl) {
                rb_do_new_tab(app);
                return 0;
            }
            if (wp == 'W' && ctrl && app->active_id) {
                rb_do_close_tab(app, app->active_id);
                return 0;
            }
            if (wp == 'L' && ctrl && app->omni) {
                SetFocus(app->omni);
                SendMessageW(app->omni, EM_SETSEL, 0, (LPARAM)-1);
                return 0;
            }
            if (wp == VK_LEFT && alt) {
                rb_wv_goback(app);
                return 0;
            }
            if (wp == VK_RIGHT && alt) {
                rb_wv_gofwd(app);
                return 0;
            }
        }
        break;
    case WM_DESTROY:
        if (app) {
            rb_data_shutdown(app);
            rb_wv_shutdown(app);
            if (app->omni) RemoveWindowSubclass(app->omni, rb_omni_subclass, RB_OMNI_SUBID);
        }
        PostQuitMessage(0);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

/* ------------------------------------------------------------------ */
/* Chrome construction */

static HWND rb_mk_button(App *app, const wchar_t *text, int id)
{
    HWND h = CreateWindowExW(0, L"BUTTON", text,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON | BS_OWNERDRAW,
        0, 0, 36, 36, app->hwnd, (HMENU)(INT_PTR)id, app->hinst, NULL);
    if (h) SendMessageW(h, WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
    return h;
}

int rb_chrome_create(App *app)
{
    WNDCLASSEXW wc;
    ATOM cls;

    memset(&wc, 0, sizeof wc);
    wc.cbSize = sizeof wc;
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = rb_wndproc;
    wc.hInstance = app->hinst;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hIcon = (HICON)LoadImageW(app->hinst, MAKEINTRESOURCEW(IDI_ICON1),
                                 IMAGE_ICON, 0, 0, LR_DEFAULTSIZE);
    wc.hIconSm = wc.hIcon;
    wc.hbrBackground = NULL;
    wc.lpszClassName = L"RoomBrowserWnd";
    cls = RegisterClassExW(&wc);
    if (!cls) return -1;

    app->br_chrome  = CreateSolidBrush(RB_COL_CHROME);
    app->br_toolbar = CreateSolidBrush(RB_COL_TOOLBAR);
    app->br_tab_idle = CreateSolidBrush(RB_COL_TAB_IDLE);
    app->br_omni    = CreateSolidBrush(RB_COL_OMNI_BG);
    app->br_accent  = CreateSolidBrush(RB_COL_ACCENT);
    app->fnt_ui = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                              OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                              CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                              L"Segoe UI");
    app->fnt_omni = CreateFontW(-16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                                OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                                L"Segoe UI");

    app->hwnd = CreateWindowExW(0, L"RoomBrowserWnd", L"Room Browser",
                                WS_OVERLAPPEDWINDOW,
                                CW_USEDEFAULT, CW_USEDEFAULT,
                                RB_WINDOW_W, RB_WINDOW_H,
                                NULL, NULL, app->hinst, app);
    if (!app->hwnd) return -1;

    rb_apply_dark_titlebar(app->hwnd);

    app->back   = rb_mk_button(app, L"\x2190", IDC_BACK);
    app->fwd    = rb_mk_button(app, L"\x2192", IDC_FWD);
    app->reload = rb_mk_button(app, L"\x21BB", IDC_RELOAD);
    app->home   = rb_mk_button(app, L"\x2302", IDC_HOME);
    app->star   = rb_mk_button(app, L"\x2606", IDC_STAR);
    app->menu_btn = rb_mk_button(app, L"\x2630", IDC_MENU);
    app->newtab = rb_mk_button(app, L"\x002B", IDC_NEWBTN);

    app->omni = CreateWindowExW(0, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_LEFT | ES_AUTOHSCROLL,
        166, 38, 600, 32, app->hwnd, (HMENU)(INT_PTR)IDC_OMNI, app->hinst, NULL);
    if (app->omni) {
        SendMessageW(app->omni, WM_SETFONT, (WPARAM)app->fnt_omni, TRUE);
        SetWindowSubclass(app->omni, rb_omni_subclass, RB_OMNI_SUBID, (DWORD_PTR)app);
    }

    rb_tabs_rebuild(app);
    rb_update_all(app);
    return 0;
}
