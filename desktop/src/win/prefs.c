/*
 * Room Browser (desktop) - Windows preferences editor.
 *
 * The desktop counterpart of Android's ProfileSettingsScreen.  It edits the
 * ACTIVE PROFILE's settings, laid out in the same pages, under the same
 * labels, with the same core defaults the GTK edition uses, so the two
 * desktop editions present the same thing and reading one teaches the other.
 *
 * Every control writes through rb_pref_set()/rb_pref_set_int() — the accessor
 * every other feature reads through — and a change that a live webview can
 * follow is applied at once rather than at the next start.
 *
 * Deliberately not a .rc dialog resource: the whole chrome is built in code,
 * and a resource would put half the layout in a file the Linux CI cannot
 * check.  A tab strip is drawn by hand for the same reason the main chrome
 * owner-draws its buttons — a themed tab control paints a light strip that
 * would sit in the middle of a dark window.
 */
#include <windows.h>
#include <commctrl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "prefs.h"
#include "webview.h"

/* ------------------------------------------------------------------ */
/* Layout */

#define RB_PREFS_PAGES   7
#define RB_PREFS_WIN_W       760
#define RB_PREFS_WIN_H       640
#define RB_PREFS_MARGIN  12
#define RB_PREFS_TABH    30     /* the page selector strip */
#define RB_PREFS_FOOTH   34     /* the Close button row */

#define PF_ROW_H   22           /* a switch or a combo */
#define PF_SUB_H   15           /* the dim line under a switch */
#define PF_GAP     9
#define PF_TITLE_H 17           /* an entry row's caption */
#define PF_EDIT_H  23
#define PF_NOTE_H  46

/* Control identifiers.  One contiguous block per role, so the handlers can
 * recognise a control by range instead of keeping a lookup per kind. */
#define RB_PREFS_TAB_BASE   4300    /* the page selector buttons */
#define RB_PREFS_CTL_BASE   4400    /* switch / combo / entry-apply button */
#define RB_PREFS_EDIT_BASE  4500    /* an entry row's edit box */
#define RB_PREFS_CLEAR_BASE 4600    /* the five "clear data" checkboxes */
#define RB_PREFS_CLEAR_BTN  4610
#define RB_PREFS_CLOSE      4611
#define RB_PREFS_MAX_CTL    48

static const wchar_t *const g_page_names[RB_PREFS_PAGES] = {
    L"Appearance", L"Search", L"Privacy", L"User-Agent",
    L"Network", L"Homepage & Languages", L"Clear data"
};

/* ------------------------------------------------------------------ */
/* State */

typedef enum { RB_PK_SWITCH, RB_PK_COMBO, RB_PK_ENTRY } RbPrefKind;

typedef struct {
    int          id;         /* the control's id (the Apply button for entries) */
    const char  *key;        /* the profile setting it edits */
    int          fallback;   /* the core default, for a key never written */
    RbPrefKind   kind;
    HWND         ctl;        /* the switch / combo / edit box */
    HWND         apply;      /* entry rows only: the Apply button */
    const char **ids;        /* combos: the id behind each item, by index */
} RbPrefCtl;

typedef struct {
    App  *app;
    HWND  dlg;
    HWND  pages[RB_PREFS_PAGES];
    HWND  tabs[RB_PREFS_PAGES];
    int   page_sel;
    HWND  ua_note;
    HWND  dns_note;
    HWND  clear[5];
    HFONT fnt;
    HFONT fnt_bold;
    int   page_w;
    int   page_h;
    RbPrefCtl ctl[RB_PREFS_MAX_CTL];
    int   n_ctl;
} RbPrefs;

/* One editor at a time: the window is modal, so a second request only has to
 * bring the first to the front. */
static RbPrefs *g_prefs = NULL;

/* ------------------------------------------------------------------ */
/* Small helpers */

static void pf_catf(char *buf, size_t cap, const char *fmt, ...)
{
    size_t used = strlen(buf);
    va_list ap;
    if (used >= cap) return;
    va_start(ap, fmt);
    vsnprintf(buf + used, cap - used, fmt, ap);
    va_end(ap);
}

/* The palette colour for the widget: 0 = primary text, 1 = dimmed.  Kept on
 * the control itself so one WM_CTLCOLORSTATIC handler serves every label. */
enum { RB_PF_INK_NORMAL = 0, RB_PF_INK_DIM = 1 };

static void pf_mk_ctl(HWND h, HFONT fnt, int ink)
{
    if (h == NULL) return;
    SendMessageW(h, WM_SETFONT, (WPARAM)fnt, TRUE);
    SetWindowLongPtrW(h, GWLP_USERDATA, (LONG_PTR)ink);
}

/* The check state of a checkbox.  SendMessage rather than the
 * Button_GetCheck() convenience macro: that one is only declared by
 * commctrl.h from a certain _WIN32_WINNT on, and the message itself is
 * always there. */
static int pf_checked(HWND box)
{
    return box != NULL && SendMessageW(box, BM_GETCHECK, 0, 0) == BST_CHECKED;
}

static RbPrefCtl *pf_find(RbPrefs *pf, int id)
{
    int i;
    for (i = 0; i < pf->n_ctl; i++) {
        if (pf->ctl[i].id == id) return &pf->ctl[i];
    }
    return NULL;
}

static int pf_add(RbPrefs *pf, const char *key, int fallback, RbPrefKind kind,
                  const char **ids)
{
    RbPrefCtl *c;
    if (pf->n_ctl >= RB_PREFS_MAX_CTL) return -1;
    c = &pf->ctl[pf->n_ctl];
    memset(c, 0, sizeof *c);
    c->id = RB_PREFS_CTL_BASE + pf->n_ctl;
    c->key = key;
    c->fallback = fallback;
    c->kind = kind;
    c->ids = ids;
    return pf->n_ctl++;
}

/* The free function only exists for the two notes, which are repainted from
 * the settings rather than from a control's own state. */
static void pf_set_note(RbPrefs *pf, HWND note, const wchar_t *text)
{
    if (note == NULL) return;
    SetWindowTextW(note, text);
    InvalidateRect(note, NULL, TRUE);
}

/* The window a setting is edited through, or NULL when this page does not show
 * it.  Used to grey out the rows a device makes irrelevant. */
static HWND pf_ctl_by_key(RbPrefs *pf, const char *key)
{
    int i;
    if (pf == NULL || key == NULL) return NULL;
    for (i = 0; i < pf->n_ctl; i++) {
        if (pf->ctl[i].key != NULL && strcmp(pf->ctl[i].key, key) == 0) {
            return pf->ctl[i].ctl;
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Applying a change
 *
 * The Win32 twin of the GTK editor's rb_prefs_apply_key().  It exists for the
 * same reason: a setting that is already loaded somewhere has to be reloaded
 * when it changes, and the page that changed it should not have to know
 * where. */

static void pf_apply(App *app, RbPrefs *pf, const char *key)
{
    if (app == NULL || key == NULL) return;

    if (strcmp(key, RB_PREF_JAVASCRIPT) == 0) {
        app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
    } else if (strcmp(key, RB_PREF_HOME_LOCAL) == 0) {
        rb_set_str(&app->home_url,
                   rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                     "https://duckduckgo.com")));
    } else if (strcmp(key, RB_PREF_THEME) == 0 ||
               strcmp(key, RB_PREF_ACCENT_ARGB) == 0) {
        /* Rebuilds the palette brushes from the new theme.  The editor is
         * drawn from that same palette, so it has to be repainted with it —
         * otherwise the window would keep the old colours until reopened. */
        rb_theme_apply(app);
        if (pf != NULL && pf->dlg != NULL) {
            RedrawWindow(pf->dlg, NULL, NULL,
                         RDW_INVALIDATE | RDW_ERASE | RDW_ALLCHILDREN);
        }
    } else if (strcmp(key, RB_PREF_FONT_SCALE) == 0) {
        /* Rebuilds the chrome's fonts and re-lays the controls out around
         * them.  Nothing to repaint here: this window is drawn in fixed
         * metrics and keeps them, on purpose (see rb_show_prefs). */
        rb_apply_font_scale(app);
    } else if (strcmp(key, RB_PREF_REDUCED_MOTION) == 0) {
        /* The only thing this edition animates is the page-load strip, and
         * whether it should be sweeping depends on whether a load is in
         * flight — which only the progress code knows. */
        rb_progress_refresh(app);
    } else if (strcmp(key, RB_PREF_DOWNLOAD_SUBFOLDER) == 0) {
        /* The builder assigns rather than appends, so the old path has to go
         * first. */
        rb_set_str(&app->download_dir, NULL);
        rb_downloads_dir_refresh(app);
    }

    /* JavaScript and the User-Agent are per-webview settings the runtime
     * holds a copy of, so every live view is told again.  It is idempotent,
     * which is why the whole set is re-applied rather than only the one that
     * moved.
     *
     * The content-blocking switches need nothing here: on this edition they
     * are read per request, out of rb_filter_opts(), so the next request
     * already sees the new value.  GTK re-applies a compiled rule set
     * instead, which is why its version of this function has an extra call. */
    rb_wv_apply_settings_all(app);

    rb_profiles_save(app);
}

/* The two notes that describe computed state rather than a control's own
 * value, rebuilt from the profile after anything that could change them. */
static void pf_refresh_notes(RbPrefs *pf)
{
    App *app = pf->app;

    if (pf->ua_note != NULL) {
        char *ua = rb_ua_current(app);
        const char *device_id = rb_pref(app, RB_PREF_DEVICE_ID, "");
        int has_device = (device_id != NULL && device_id[0] != '\0');
        wchar_t *wua = rb_utf8_to_wide((ua != NULL) ? ua : "the engine default");
        wchar_t buf[512];
        if (wua != NULL) {
            swprintf(buf, 512,
                     L"Current: %ls\n%ls",
                     wua,
                     has_device
                         ? L"Set by the device above, along with the platform "
                           L"characteristics that go with it. Choose \"No "
                           L"device\" to set the string on its own."
                         : L"On its own, a User-Agent string changes no other "
                           L"platform or device characteristic.");
            free(wua);
        } else {
            swprintf(buf, 512, L"Current: the engine default");
        }
        pf_set_note(pf, pf->ua_note, buf);
        free(ua);
    }

    /* The three rows that only mean something with NO device chosen.  A
     * control that silently does nothing is worse than one that is visibly
     * off, so with a machine selected they grey out and the note above says
     * why - the settings screen never offers an answer the browser is going
     * to ignore. */
    {
        const char *device_id = rb_pref(app, RB_PREF_DEVICE_ID, "");
        BOOL on = (device_id == NULL || device_id[0] == '\0');
        static const char *const ua_keys[3] = { RB_PREF_UA_MODE,
                                                RB_PREF_UA_PRESET_ID,
                                                RB_PREF_CUSTOM_USER_AGENT };
        int k;
        for (k = 0; k < 3; k++) {
            HWND h = pf_ctl_by_key(pf, ua_keys[k]);
            if (h != NULL) EnableWindow(h, on);
        }
    }

    if (pf->dns_note != NULL) {
        /* The effective mode, computed exactly as the Android settings screen
         * computes it: the profile's mode first, the global one when the
         * profile defers, then validation.  Shown so the two DNS fields above
         * are not dead text — a half-typed URL shows up here as
         * MISCONFIGURED rather than silently doing nothing. */
        const char *gm = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DNS_MODE, "system")
            : "system";
        const char *gd = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DOH_URL, "") : "";
        const char *gh = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DOT_HOSTNAME, "") : "";
        rb_dns_effective eff = rb_dns_resolve(
            rb_pref(app, RB_PREF_DNS_MODE, "system"),
            rb_pref(app, RB_PREF_DOH_URL, ""),
            rb_pref(app, RB_PREF_DOT_HOSTNAME, ""),
            gm, gd, gh);
        wchar_t *status = rb_utf8_to_wide(rb_dns_status_name(eff.status));
        wchar_t buf[512];
        swprintf(buf, 512,
                 L"Effective mode: %ls\n%ls",
                 (status != NULL) ? status : L"",
                 (eff.status == RB_DNS_STATUS_MISCONFIGURED)
                     ? L"The configured value is not usable, so name resolution "
                       L"falls back to the system resolver."
                     : L"This build does not resolve names itself \x2014 page "
                       L"loads use the system resolver either way. See "
                       L"README.md.");
        free(status);
        rb_dns_effective_free(&eff);
        pf_set_note(pf, pf->dns_note, buf);
    }
}

/* ------------------------------------------------------------------ */
/* Page construction
 *
 * Each page is a window of its own, a child of the dialog and the parent of
 * its controls, so switching pages is ShowWindow() on seven handles rather
 * than a walk over every control in the dialog.  The colour messages a
 * control sends go to its immediate parent, so the page window forwards them
 * to the dialog, which is where the palette lives. */

static LRESULT CALLBACK pf_page_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbPrefs *pf = (RbPrefs *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_ERASEBKGND:
        if (pf != NULL && pf->app != NULL) {
            RECT r;
            GetClientRect(hwnd, &r);
            FillRect((HDC)wp, &r, pf->app->br_chrome);
            return 1;
        }
        break;
    case WM_COMMAND:
    case WM_DRAWITEM:
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORLISTBOX:
    case WM_CTLCOLORBTN:
        if (pf != NULL) return SendMessageW(pf->dlg, msg, wp, lp);
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static HWND pf_mk(RbPrefs *pf, HWND parent, const wchar_t *cls,
                  const wchar_t *text, DWORD style, int x, int y, int w, int h,
                  int id, int ink)
{
    HWND c = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style,
                             x, y, w, h, parent, (HMENU)(INT_PTR)id,
                             pf->app->hinst, NULL);
    pf_mk_ctl(c, pf->fnt, ink);
    return c;
}

/* A switch row: the switch is the control with the id, the title is its own
 * text so clicking the words toggles it, and the explanation sits under it,
 * dimmed.  Returns the height the row consumed. */
static int pf_switch(RbPrefs *pf, int page, int y, const char *key,
                     int fallback, const wchar_t *title, const wchar_t *sub)
{
    int i = pf_add(pf, key, fallback, RB_PK_SWITCH, NULL);
    if (i < 0) return 0;
    pf->ctl[i].ctl = pf_mk(pf, pf->pages[page], L"BUTTON", title,
                           WS_TABSTOP | BS_AUTOCHECKBOX,
                           0, y, pf->page_w, PF_ROW_H, pf->ctl[i].id,
                           RB_PF_INK_NORMAL);
    /* SendMessage rather than the Button_SetCheck() convenience macro: that
     * one is only declared by commctrl.h from a certain _WIN32_WINNT on, and
     * the messages themselves are always there. */
    SendMessageW(pf->ctl[i].ctl, BM_SETCHECK,
                 rb_pref_int(pf->app, key, fallback) ? BST_CHECKED
                                                     : BST_UNCHECKED, 0);
    if (sub != NULL) {
        HWND s = pf_mk(pf, pf->pages[page], L"STATIC", sub,
                       SS_LEFT, 20, y + PF_ROW_H, pf->page_w - 20, PF_SUB_H,
                       0, RB_PF_INK_DIM);
        pf_mk_ctl(s, pf->fnt, RB_PF_INK_DIM);
        return PF_ROW_H + PF_SUB_H + PF_GAP;
    }
    return PF_ROW_H + PF_GAP;
}

/* A labelled drop-down whose items are (id, label) pairs.  The combo's item
 * index is the index into `ids`, which is what makes one handler enough for
 * every combo in the window. */
static int pf_combo(RbPrefs *pf, int page, int y, const char *key,
                    const char **ids, const char **labels, const char *current,
                    const wchar_t *title)
{
    int i = pf_add(pf, key, 0, RB_PK_COMBO, ids);
    HWND combo;
    int sel = 0, k;
    if (i < 0) return 0;

    pf_mk(pf, pf->pages[page], L"STATIC", title, SS_LEFT,
          0, y, 190, PF_ROW_H, 0, RB_PF_INK_NORMAL);

    combo = pf_mk(pf, pf->pages[page], L"COMBOBOX", L"",
                  WS_TABSTOP | WS_VSCROLL | CBS_DROPDOWNLIST,
                  200, y, pf->page_w - 200, 200, pf->ctl[i].id,
                  RB_PF_INK_NORMAL);
    pf->ctl[i].ctl = combo;
    if (combo == NULL) return PF_ROW_H + PF_GAP;

    for (k = 0; labels[k] != NULL; k++) {
        wchar_t *w = rb_utf8_to_wide(labels[k]);
        if (w == NULL) continue;
        SendMessageW(combo, CB_ADDSTRING, 0, (LPARAM)w);
        free(w);
        if (current != NULL && ids[k] != NULL &&
            strcmp(ids[k], current) == 0) {
            sel = k;
        }
    }
    SendMessageW(combo, CB_SETCURSEL, (WPARAM)sel, 0);
    return PF_ROW_H + PF_GAP;
}

/* An entry row: a caption, then the box and its Apply button.  The value is
 * written when Apply is pressed rather than on every keystroke, so a
 * half-typed URL is never saved. */
static int pf_entry(RbPrefs *pf, int page, int y, const char *key,
                    const char *current, const wchar_t *title)
{
    int i = pf_add(pf, key, 0, RB_PK_ENTRY, NULL);
    HWND edit, apply;
    if (i < 0) return 0;

    pf_mk(pf, pf->pages[page], L"STATIC", title, SS_LEFT,
          0, y, pf->page_w, PF_TITLE_H, 0, RB_PF_INK_NORMAL);

    edit = pf_mk(pf, pf->pages[page], L"EDIT", L"",
                 WS_TABSTOP | ES_LEFT | ES_AUTOHSCROLL,
                 0, y + PF_TITLE_H, pf->page_w - 76, PF_EDIT_H,
                 RB_PREFS_EDIT_BASE + i, RB_PF_INK_NORMAL);
    if (edit != NULL) {
        wchar_t *w = rb_utf8_to_wide((current != NULL) ? current : "");
        if (w != NULL) {
            SetWindowTextW(edit, w);
            free(w);
        }
    }
    apply = pf_mk(pf, pf->pages[page], L"BUTTON", L"Apply",
                  WS_TABSTOP | BS_PUSHBUTTON,
                  pf->page_w - 68, y + PF_TITLE_H, 68, PF_EDIT_H,
                  pf->ctl[i].id, RB_PF_INK_NORMAL);
    pf->ctl[i].ctl = edit;
    pf->ctl[i].apply = apply;
    return PF_TITLE_H + PF_EDIT_H + PF_GAP;
}

/* A dim paragraph under the rows that need one.  Returned so the dialog can
 * find it again: its text is computed state, not a control's own value. */
static HWND pf_note(RbPrefs *pf, int page, int y, int height)
{
    return pf_mk(pf, pf->pages[page], L"STATIC", L"",
                 SS_LEFT, 0, y, pf->page_w, height, 0, RB_PF_INK_DIM);
}

/* ------------------------------------------------------------------ */
/* The pages */

static void pf_build_appearance(RbPrefs *pf)
{
    static const char *ids[66];
    static const char *labels[66];
    static const char *const mode_ids[] = {
        "system", "light", "dark", "amoled", NULL
    };
    static const char *const mode_labels[] = {
        "Match system", "Light", "Dark", "AMOLED", NULL
    };
    static const char *const scale_ids[] = {
        "80", "90", "100", "110", "125", "150", NULL
    };
    static const char *const scale_labels[] = {
        "80%", "90%", "100% (default)", "110%", "125%", "150%", NULL
    };
    const rb_theme *cur = rb_theme_current(pf->app);
    int n = rb_theme_count(), i, y = 0;

    if (n > 64) n = 64;
    for (i = 0; i < n; i++) {
        const rb_theme *t = rb_theme_at(i);
        ids[i] = (t != NULL && t->id != NULL) ? t->id : "";
        labels[i] = (t != NULL && t->name != NULL) ? t->name : "";
    }
    ids[n] = NULL;
    labels[n] = NULL;
    /* The list is the core's theme registry, so the desktop offers the same
     * presets the Android theme studio is built from.
     *
     * A NULL key marks this row as the theme ID, which is stored in the
     * profile's theme_json snapshot rather than in the settings — see the
     * RB_PK_COMBO branch of the command handler.  The MODE is the separate row
     * below; the two used to share this one control, which is why picking a
     * theme also reset the mode and why no theme was ever applied. */
    y += pf_combo(pf, 0, y, NULL, ids, labels,
                  (cur != NULL) ? cur->id : NULL, L"Theme");
    /* rb_prefs.h's spellings for the mode, with "system" standing for
     * rb_theme.h's AUTO. */
    y += pf_combo(pf, 0, y, RB_PREF_THEME, mode_ids, mode_labels,
                  rb_pref(pf->app, RB_PREF_THEME, "system"), L"Appearance");
    /* The scale every chrome font and every layout coordinate is multiplied
     * by.  Stored as a percentage so the same value means the same thing to
     * both desktop editions and to the Android app's text-size setting. */
    y += pf_combo(pf, 0, y, RB_PREF_FONT_SCALE, scale_ids, scale_labels,
                  rb_pref(pf->app, RB_PREF_FONT_SCALE, "100"), L"Text size");
    y += pf_switch(pf, 0, y, RB_PREF_REDUCED_MOTION, 0, L"Reduce motion",
                   L"Stops the page-load strip sweeping; it still shows a load");
    y += pf_switch(pf, 0, y, RB_PREF_HIGH_CONTRAST, 0, L"High contrast",
                   L"Strengthens the contrast between text and its background");
}

static void pf_build_search(RbPrefs *pf)
{
    static const char *ids[66];
    static const char *labels[66];
    int n = rb_search_count(), i, y = 0;

    if (n > 64) n = 64;
    for (i = 0; i < n; i++) {
        const rb_search_engine *e = rb_search_at(i);
        ids[i] = (e != NULL && e->id != NULL) ? e->id : "";
        labels[i] = (e != NULL && e->label != NULL) ? e->label : "";
    }
    ids[n] = NULL;
    labels[n] = NULL;
    y += pf_combo(pf, 1, y, RB_PREF_SEARCH_ENGINE, ids, labels,
                  rb_pref(pf->app, RB_PREF_SEARCH_ENGINE, "duckduckgo"),
                  L"Search engine");
    y += pf_switch(pf, 1, y, RB_PREF_SEARCH_SUGGESTIONS, 0,
                   L"Search suggestions",
                   L"Sends what you type to the search engine as you type it");
}

static void pf_build_privacy(RbPrefs *pf)
{
    int y = 0;

    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_ADS, 0, L"Block ads",
                   L"Blocks known ad hosts from the bundled offline list");
    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_TRACKERS, 0, L"Block trackers",
                   NULL);
    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_CROSS_SITE, 0,
                   L"Block cross-site trackers", NULL);
    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_POPUPS, 0, L"Block popups", NULL);
    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_MALICIOUS, 1,
                   L"Block malicious websites",
                   L"A top-level navigation into a listed malicious host is "
                   L"refused");
    y += pf_switch(pf, 2, y, RB_PREF_HTTPS_UPGRADE, 1, L"HTTPS upgrades",
                   L"Upgrades http to https and falls back to http when the "
                   L"secure version is unreachable");
    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_THIRD_PARTY_COOKIES, 0,
                   L"Block third-party cookies",
                   L"Off by default for compatibility: many logins and embeds "
                   L"need them");
    /* Shown even though it does nothing here: hiding it would let a user
     * believe the profile has no such setting, and the note says plainly
     * that this edition cannot honour it. */
    y += pf_switch(pf, 2, y, RB_PREF_BLOCK_MIXED_CONTENT, 0,
                   L"Block mixed content",
                   L"Stored and honoured on Android; WebView2 exposes no switch "
                   L"for it, so it has no effect on this edition");
    y += pf_switch(pf, 2, y, RB_PREF_JAVASCRIPT, 1, L"JavaScript enabled",
                   L"Never disabled by default");
    {
        /* The same three options, in the same words, that the Android settings
         * screen offers.  This edition can express two of them: WebView2 has
         * no WebRTC switch, so the policy becomes a Chromium IP-handling
         * switch — see rb_wv_init() — and "Restrict local IP exposure" and
         * "Disabled" share it.  Saying so here is the whole point of the note:
         * a control that silently did less than it promised is the failure
         * this row exists to avoid. */
        static const char *const ids[] = {
            "default", "restrict_local_ip", "disabled", NULL
        };
        static const char *const labels[] = {
            "WebRTC: Default",
            "WebRTC: Restrict local IP exposure",
            "WebRTC: Disabled (may break calls)",
            NULL
        };
        const rb_profile *p = rb_active_profile(pf->app);

        y += pf_combo(pf, 2, y, RB_PREF_WEBRTC_POLICY, ids, labels,
                      rb_webrtc_policy_name(
                          rb_webrtc_policy_of(p ? p->settings : NULL)),
                      L"WebRTC");
        /* Deliberately taller than the lines it holds: the text is
         * top-aligned, so spare height costs nothing, while a rect that is one
         * line short would clip the sentence that matters. */
        (void)pf_mk(pf, pf->pages[2], L"STATIC",
                    L"WebView2 has no WebRTC switch, so the last two options "
                    L"share one Chromium IP-handling policy: candidates stay "
                    L"off this machine's interfaces.  It is read when the "
                    L"browser starts, so a change takes effect at the next "
                    L"launch rather than on the pages already open.",
                    SS_LEFT, 0, y, pf->page_w, 2 * PF_NOTE_H,
                    0, RB_PF_INK_DIM);
        y += 2 * PF_NOTE_H + PF_GAP;
    }
}

static void pf_build_ua(RbPrefs *pf)
{
    static const char *mode_ids[4] = { "default", "preset", "custom", NULL };
    static const char *mode_labels[4] = { "Default (WebView2)", "Preset",
                                          "Custom", NULL };
    static const char *ids[66];
    static const char *labels[66];
    static char buf[64][128];
    /* The device list is generated and runs to a few hundred machines, so it
     * gets its own storage: the caption is built from the catalogue rather
     * than written out here.  Static, like the preset arrays above, because
     * one settings window exists at a time. */
    static const char *dev_ids[RB_DEVICE_CHOICES_MAX + 2];
    static const char *dev_labels[RB_DEVICE_CHOICES_MAX + 2];
    static char dev_buf[RB_DEVICE_CHOICES_MAX + 2][128];
    int n = rb_ua_count(), i, y = 0;
    int dn = rb_device_count(), j;

    if (dn > RB_DEVICE_CHOICES_MAX) dn = RB_DEVICE_CHOICES_MAX;
    dev_ids[0] = "";
    dev_labels[0] = "No device - use the User-Agent setting";
    for (j = 0; j < dn; j++) {
        const rb_device *d = rb_device_at(j);
        if (d == NULL || d->id == NULL) continue;
        dev_ids[j + 1] = d->id;
        snprintf(dev_buf[j + 1], sizeof dev_buf[j + 1], "%s %s - %s (%d)",
                 (d->brand != NULL) ? d->brand : "",
                 (d->model != NULL) ? d->model : "",
                 rb_device_os_name(d->os), d->year);
        dev_labels[j + 1] = dev_buf[j + 1];
    }
    dn++;
    dev_ids[dn] = NULL;
    dev_labels[dn] = NULL;

    /* A device decides the User-Agent, so it comes first: with one chosen,
     * the three rows below are not consulted at all. */
    y += pf_combo(pf, 3, y, RB_PREF_DEVICE_ID, dev_ids, dev_labels,
                  rb_pref(pf->app, RB_PREF_DEVICE_ID, ""), L"Device");

    y += pf_combo(pf, 3, y, RB_PREF_UA_MODE, mode_ids, mode_labels,
                  rb_pref(pf->app, RB_PREF_UA_MODE, "default"),
                  L"User-Agent mode");

    if (n > 64) n = 64;
    for (i = 0; i < n; i++) {
        const rb_ua_preset *p = rb_ua_at(i);
        ids[i] = (p != NULL && p->id != NULL) ? p->id : "";
        snprintf(buf[i], sizeof buf[i], "%s%s",
                 (p != NULL && p->label != NULL) ? p->label : "",
                 (p != NULL && p->is_desktop) ? "  (desktop)" : "");
        labels[i] = buf[i];
    }
    ids[n] = NULL;
    labels[n] = NULL;
    y += pf_combo(pf, 3, y, RB_PREF_UA_PRESET_ID, ids, labels,
                  rb_pref(pf->app, RB_PREF_UA_PRESET_ID, ""), L"Preset");
    y += pf_entry(pf, 3, y, RB_PREF_CUSTOM_USER_AGENT,
                  rb_pref(pf->app, RB_PREF_CUSTOM_USER_AGENT, ""),
                  L"Custom User-Agent");
    pf->ua_note = pf_note(pf, 3, y, PF_NOTE_H);
}

static void pf_build_network(RbPrefs *pf)
{
    static const char *mode_ids[5] = { "system", "auto", "doh", "dot", NULL };
    static const char *mode_labels[5] = { "System",
                                          "Use the global browser setting",
                                          "DNS-over-HTTPS",
                                          "DNS-over-TLS", NULL };
    int y = 0;

    y += pf_combo(pf, 4, y, RB_PREF_DNS_MODE, mode_ids, mode_labels,
                  rb_pref(pf->app, RB_PREF_DNS_MODE, "system"), L"DNS mode");
    y += pf_entry(pf, 4, y, RB_PREF_DOH_URL,
                  rb_pref(pf->app, RB_PREF_DOH_URL, ""),
                  L"DNS-over-HTTPS URL");
    y += pf_entry(pf, 4, y, RB_PREF_DOT_HOSTNAME,
                  rb_pref(pf->app, RB_PREF_DOT_HOSTNAME, ""),
                  L"DNS-over-TLS hostname");
    pf->dns_note = pf_note(pf, 4, y, PF_NOTE_H);
    y += PF_NOTE_H + PF_GAP;

    y += pf_switch(pf, 4, y, RB_PREF_NET_PROTECT_GLOBAL, 1,
                   L"Network protection: use the global setting", NULL);
    y += pf_switch(pf, 4, y, RB_PREF_NET_PROTECT_ENABLED, 1,
                   L"IP conflict warning enabled",
                   L"Warns when the address this profile resolved to is also in "
                   L"use on the local network");
}

static void pf_build_home(RbPrefs *pf)
{
    int y = 0;

    y += pf_entry(pf, 5, y, RB_PREF_HOME_LOCAL,
                  rb_pref(pf->app, RB_PREF_HOME_LOCAL,
                          "https://duckduckgo.com"), L"Homepage");
    y += pf_switch(pf, 5, y, RB_PREF_HOMEPAGE_ENABLED, 1, L"Show homepage",
                   NULL);
    y += pf_switch(pf, 5, y, RB_PREF_SHOW_PRIVACY_STATS, 1,
                   L"Show privacy statistics", NULL);
    y += pf_switch(pf, 5, y, RB_PREF_SHOW_RECENT_SITES, 1,
                   L"Show recent sites", NULL);
    y += pf_switch(pf, 5, y, RB_PREF_SHOW_CLOCK, 1, L"Show clock", NULL);
    y += pf_switch(pf, 5, y, RB_PREF_DESKTOP_MODE_DEFAULT, 0,
                   L"Request desktop sites by default", NULL);
    y += pf_switch(pf, 5, y, RB_PREF_AUTOFILL_ENABLED, 0,
                   L"Autofill integration",
                   L"Android delegates this to the system autofill framework, "
                   L"which a desktop browser has no equivalent of \x2014 this "
                   L"edition stores no credentials and does not fill forms");
    y += pf_entry(pf, 5, y, RB_PREF_DOWNLOAD_SUBFOLDER,
                  rb_pref(pf->app, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser"),
                  L"Downloads subfolder");
    y += pf_entry(pf, 5, y, RB_PREF_TRANSLATE_TARGET,
                  rb_pref(pf->app, RB_PREF_TRANSLATE_TARGET, "id"),
                  L"Translate target language (e.g. id)");
}

static void pf_build_clear(RbPrefs *pf)
{
    static const wchar_t *const names[5] = {
        L"History", L"Cookies", L"Cache",
        L"Site data (local storage, IndexedDB)", L"Download records"
    };
    static const int defaults[5] = { 1, 1, 1, 0, 0 };
    int i, y = 0;

    for (i = 0; i < 5; i++) {
        pf->clear[i] = pf_mk(pf, pf->pages[6], L"BUTTON", names[i],
                             WS_TABSTOP | BS_AUTOCHECKBOX,
                             0, y, pf->page_w, PF_ROW_H,
                             RB_PREFS_CLEAR_BASE + i, RB_PF_INK_NORMAL);
        SendMessageW(pf->clear[i], BM_SETCHECK,
                     defaults[i] ? BST_CHECKED : BST_UNCHECKED, 0);
        y += PF_ROW_H + 2;
    }
    y += PF_GAP;
    pf_mk(pf, pf->pages[6], L"STATIC",
          L"This clears the data of the ACTIVE PROFILE only; every other "
          L"profile keeps its own cookies and cache. Downloaded files are "
          L"never deleted \x2014 only the records of them.",
          SS_LEFT, 0, y, pf->page_w, PF_NOTE_H, 0, RB_PF_INK_DIM);
    y += PF_NOTE_H + PF_GAP;
    pf_mk(pf, pf->pages[6], L"BUTTON", L"Clear selected data",
          WS_TABSTOP | BS_PUSHBUTTON, 0, y, 170, 26,
          RB_PREFS_CLEAR_BTN, RB_PF_INK_NORMAL);
}

/* ------------------------------------------------------------------ */
/* The danger zone: clear browsing data
 *
 * Android's ClearDataSection.  The store half is the core's own files; the
 * engine half is the WebView2 profile's, reached through webview.c. */

static void pf_do_clear(RbPrefs *pf)
{
    App *app = pf->app;
    const rb_profile *p = rb_active_profile(app);
    unsigned wv_kinds = 0;
    char done[512];

    done[0] = '\0';

    if (pf_checked(pf->clear[0]) && app->history != NULL) {
        int n = rb_history_clear(app->history);
        if (app->path_history != NULL) {
            rb_history_save(app->history, app->path_history);
        }
        pf_catf(done, sizeof done, "history (%d)\n", n);
    }
    if (pf_checked(pf->clear[1])) {
        wv_kinds |= RB_WV_CLEAR_COOKIES;
    }
    if (pf_checked(pf->clear[2])) {
        wv_kinds |= RB_WV_CLEAR_CACHE;
    }
    if (pf_checked(pf->clear[3])) {
        wv_kinds |= RB_WV_CLEAR_SITE_DATA;
    }
    if (pf_checked(pf->clear[4]) &&
        app->downloads != NULL && p != NULL) {
        int n = rb_downloads_clear_profile(app->downloads, p->id);
        if (app->path_downloads != NULL) {
            rb_downloads_save(app->downloads, app->path_downloads);
        }
        /* Records only: the FILES the user downloaded are never deleted by a
         * "clear browsing data" — that is the user's data, not the browser's. */
        pf_catf(done, sizeof done, "download records (%d)\n", n);
    }

    if (wv_kinds != 0) {
        /* Every profile's data lives in its own user-data folder, so this
         * reaches this profile's cookies and cache and no other's. */
        rb_wv_clear_browsing_data(app, wv_kinds);
        pf_catf(done, sizeof done, "cookies / cache / site data\n");
    }

    if (done[0] != '\0') {
        char body[768];
        snprintf(body, sizeof body, "Cleared:\n%s", done);
        rb_warn(app, "Browsing data", body);
    } else {
        rb_warn(app, "Browsing data", "Nothing was selected.");
    }
}

/* ------------------------------------------------------------------ */
/* Commands */

/* The entry rows validate before they store.  The two DNS fields use the same
 * validator the Android edition uses, and a value that could never be used is
 * refused here rather than saved, so the settings screen never shows a
 * "protected" DNS mode that silently is not one.  Empty is allowed: it means
 * "not configured". */
static int pf_validate(RbPrefs *pf, const char *key, const char *text)
{
    if (strcmp(key, RB_PREF_DOH_URL) == 0 && text[0] != '\0' &&
        !rb_dns_valid_doh_url(text)) {
        rb_warn(pf->app, "Not a valid DNS-over-HTTPS URL",
                "It has to be an https:// URL with a host, for example "
                "https://dns.example/dns-query.  Left unchanged.");
        return 0;
    }
    if (strcmp(key, RB_PREF_DOT_HOSTNAME) == 0 && text[0] != '\0' &&
        !rb_dns_valid_dot_hostname(text)) {
        rb_warn(pf->app, "Not a valid DNS-over-TLS hostname",
                "Expected a hostname, optionally with a port, for example "
                "dns.example or dns.example:853.  Left unchanged.");
        return 0;
    }
    return 1;
}

static void pf_on_command(RbPrefs *pf, int id, int code)
{
    RbPrefCtl *c;

    if (id == RB_PREFS_CLOSE) {
        if (code == BN_CLICKED) PostMessageW(pf->dlg, WM_CLOSE, 0, 0);
        return;
    }
    /* IDCANCEL is what IsDialogMessage turns Escape into; without this the
     * key would be swallowed and do nothing. */
    if (id == IDCANCEL) {
        PostMessageW(pf->dlg, WM_CLOSE, 0, 0);
        return;
    }
    if (id == RB_PREFS_CLEAR_BTN) {
        if (code == BN_CLICKED) pf_do_clear(pf);
        return;
    }
    if (id >= RB_PREFS_TAB_BASE && id < RB_PREFS_TAB_BASE + RB_PREFS_PAGES) {
        if (code == BN_CLICKED) {
            int want = id - RB_PREFS_TAB_BASE;
            int i;
            if (want == pf->page_sel) return;
            ShowWindow(pf->pages[pf->page_sel], SW_HIDE);
            pf->page_sel = want;
            ShowWindow(pf->pages[want], SW_SHOW);
            for (i = 0; i < RB_PREFS_PAGES; i++) {
                InvalidateRect(pf->tabs[i], NULL, TRUE);
            }
        }
        return;
    }

    c = pf_find(pf, id);
    if (c == NULL) return;

    if (c->kind == RB_PK_SWITCH) {
        if (code != BN_CLICKED) return;
        rb_pref_set_int(pf->app, c->key,
                        pf_checked(c->ctl) ? 1 : 0);
        pf_apply(pf->app, pf, c->key);
        pf_refresh_notes(pf);
    } else if (c->kind == RB_PK_COMBO) {
        LRESULT sel;
        if (code != CBN_SELCHANGE) return;
        sel = SendMessageW(c->ctl, CB_GETCURSEL, 0, 0);
        if (sel == CB_ERR || c->ids == NULL || c->ids[sel] == NULL) return;
        if (c->key == NULL) {
            /* The theme row, and the only combo with no settings key — because
             * its ID is not a setting.  It belongs to the profile's theme_json
             * snapshot, which is where rb_theme_current() reads it; storing it
             * in RB_PREF_THEME, as this row used to, put a theme name in the
             * MODE key and left the profile rendering the default theme no
             * matter what was picked. */
            rb_profile_set_theme(pf->app->profiles,
                                 pf->app->active_profile_id, c->ids[sel]);
            /* RB_PREF_THEME is the key pf_apply() repaints on, which is what
             * has to happen here: the palette itself changed. */
            pf_apply(pf->app, pf, RB_PREF_THEME);
        } else {
            rb_pref_set(pf->app, c->key, c->ids[sel]);
            pf_apply(pf->app, pf, c->key);
        }
        pf_refresh_notes(pf);
    } else if (c->kind == RB_PK_ENTRY) {
        /* The Apply button carries the tuple's id; the edit box that goes
         * with it carries RB_PREFS_EDIT_BASE + its index. */
        char text[1024];
        wchar_t wtext[1024];
        char *u8;
        if (code != BN_CLICKED) return;
        wtext[0] = 0;
        if (c->ctl != NULL) GetWindowTextW(c->ctl, wtext, 1024);
        u8 = rb_wide_to_utf8(wtext);
        if (u8 == NULL) return;
        snprintf(text, sizeof text, "%s", u8);
        free(u8);
        if (!pf_validate(pf, c->key, text)) return;
        rb_pref_set(pf->app, c->key, text);
        pf_apply(pf->app, pf, c->key);
        pf_refresh_notes(pf);
    }
}

/* ------------------------------------------------------------------ */
/* Painting */

static void pf_draw_tab(RbPrefs *pf, const DRAWITEMSTRUCT *dis)
{
    App *app = pf->app;
    int idx = (int)dis->CtlID - RB_PREFS_TAB_BASE;
    int sel;
    RECT r;
    HGDIOBJ old;

    if (idx < 0 || idx >= RB_PREFS_PAGES) return;
    sel = (idx == pf->page_sel);
    r = dis->rcItem;
    FillRect(dis->hDC, &r, app->br_chrome);
    if (sel) {
        /* The selected page is marked the way the browser's own tab strip
         * marks the active tab: brighter text over a short accent rule. */
        RECT rule;
        rule = r;
        rule.top = rule.bottom - 2;
        FillRect(dis->hDC, &rule, app->br_accent);
    }
    SetBkMode(dis->hDC, TRANSPARENT);
    SetTextColor(dis->hDC, sel ? rb_col(app->pal.text_primary)
                               : rb_col(app->pal.text_secondary));
    old = SelectObject(dis->hDC, sel ? pf->fnt_bold : pf->fnt);
    r.left += 10;
    r.right -= 10;
    DrawTextW(dis->hDC, g_page_names[idx], -1, &r,
              DT_SINGLELINE | DT_VCENTER | DT_CENTER | DT_NOPREFIX);
    SelectObject(dis->hDC, old);
    if ((dis->itemState & ODS_FOCUS) != 0) {
        RECT f = dis->rcItem;
        InflateRect(&f, -1, -1);
        DrawFocusRect(dis->hDC, &f);
    }
}

/* ------------------------------------------------------------------ */
/* The window */

static void pf_close(RbPrefs *pf)
{
    if (pf == NULL || pf->dlg == NULL) return;
    DestroyWindow(pf->dlg);
}

static void pf_teardown(RbPrefs *pf)
{
    App *app = pf->app;

    /* The owner was disabled for modality; a failure to re-enable it would
     * leave the whole application dead, so it happens unconditionally here
     * rather than in whichever path closed the window. */
    if (app != NULL && app->hwnd != NULL) {
        EnableWindow(app->hwnd, TRUE);
        SetForegroundWindow(app->hwnd);
    }
    if (pf->fnt != NULL) DeleteObject(pf->fnt);
    if (pf->fnt_bold != NULL) DeleteObject(pf->fnt_bold);
    /* The pages and their controls are destroyed with the dialog; only this
     * struct goes, and the two window classes, which are registered once for
     * the life of the process. */
    g_prefs = NULL;
    free(pf);
}

static LRESULT CALLBACK pf_wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbPrefs *pf = (RbPrefs *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_CREATE:
        return 0;
    case WM_ERASEBKGND:
        if (pf != NULL) {
            RECT r;
            GetClientRect(hwnd, &r);
            FillRect((HDC)wp, &r, pf->app->br_chrome);
            return 1;
        }
        break;
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORLISTBOX: {
        if (pf == NULL) break;
        {
            int ink = (int)(LONG_PTR)GetWindowLongPtrW((HWND)lp, GWLP_USERDATA);
            SetBkMode((HDC)wp, OPAQUE);
            SetBkColor((HDC)wp, rb_col(pf->app->pal.background));
            SetTextColor((HDC)wp, (ink == RB_PF_INK_DIM)
                             ? rb_col(pf->app->pal.text_secondary)
                             : rb_col(pf->app->pal.text_primary));
            return (LRESULT)pf->app->br_chrome;
        }
    }
    case WM_DRAWITEM:
        if (pf != NULL) {
            const DRAWITEMSTRUCT *dis = (const DRAWITEMSTRUCT *)lp;
            if ((int)dis->CtlID >= RB_PREFS_TAB_BASE &&
                (int)dis->CtlID < RB_PREFS_TAB_BASE + RB_PREFS_PAGES) {
                pf_draw_tab(pf, dis);
                return TRUE;
            }
        }
        break;
    case WM_COMMAND:
        if (pf != NULL) pf_on_command(pf, (int)LOWORD(wp), (int)HIWORD(wp));
        return 0;
    case WM_KEYDOWN:
        if (wp == VK_ESCAPE && pf != NULL) {
            pf_close(pf);
            return 0;
        }
        break;
    case WM_CLOSE:
        if (pf != NULL) pf_close(pf);
        return 0;
    case WM_DESTROY:
        if (pf != NULL) pf_teardown(pf);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

/* One registration for the process; the class is for the dialog and for the
 * page windows, which differ only in their procedure. */
static int pf_register_classes(HINSTANCE hinst)
{
    WNDCLASSEXW wc;
    int ok = 1;

    memset(&wc, 0, sizeof wc);
    wc.cbSize = sizeof wc;
    wc.lpfnWndProc = pf_wndproc;
    wc.hInstance = hinst;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = NULL;
    wc.lpszClassName = L"RoomBrowserPrefs";
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        ok = 0;
    }

    memset(&wc, 0, sizeof wc);
    wc.cbSize = sizeof wc;
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = pf_page_proc;
    wc.hInstance = hinst;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = NULL;
    wc.lpszClassName = L"RoomBrowserPrefsPage";
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        ok = 0;
    }
    return ok ? 0 : -1;
}

/* Places the selector strip across the top: each button is as wide as its own
 * label needs, so nothing is truncated and nothing is padded either. */
static int pf_layout_tabs(RbPrefs *pf)
{
    HDC dc = GetDC(pf->dlg);
    int x = RB_PREFS_MARGIN, i;

    for (i = 0; i < RB_PREFS_PAGES; i++) {
        SIZE sz;
        int w = 90;
        sz.cx = 0;
        sz.cy = 0;
        if (dc != NULL) {
            HGDIOBJ old = SelectObject(dc, pf->fnt_bold);
            GetTextExtentPoint32W(dc, g_page_names[i],
                                  (int)wcslen(g_page_names[i]), &sz);
            SelectObject(dc, old);
        }
        if (sz.cx > 0) w = sz.cx + 24;
        MoveWindow(pf->tabs[i], x, RB_PREFS_MARGIN, w, RB_PREFS_TABH, TRUE);
        x += w + 2;
    }
    if (dc != NULL) ReleaseDC(pf->dlg, dc);
    return RB_PREFS_MARGIN + RB_PREFS_TABH + 6;
}

/* The message loop's hook.  The editor is a plain window rather than a
 * DLGTEMPLATE one, so nothing gives it dialog keyboard behaviour unless it is
 * asked for: IsDialogMessage does, and it needs WS_EX_CONTROLPARENT on the
 * window and on each page for Tab to descend into the page's controls. */
int rb_prefs_is_msg(const MSG *msg)
{
    if (g_prefs == NULL || msg == NULL) return 0;
    return IsDialogMessageW(g_prefs->dlg, (LPMSG)msg) ? 1 : 0;
}

void rb_show_prefs(App *app)
{
    RbPrefs *pf;
    RECT want, rc;
    int y, i;

    if (app == NULL || app->hwnd == NULL) return;
    if (g_prefs != NULL) {
        SetForegroundWindow(g_prefs->dlg);
        return;
    }
    if (pf_register_classes(app->hinst) != 0) return;

    pf = (RbPrefs *)calloc(1, sizeof *pf);
    if (pf == NULL) return;
    pf->app = app;
    /* Deliberately NOT at the profile's font scale.  Every number in this
     * window — its size, its margins, the pitch of a row — is a fixed metric,
     * and Win32 sizes a combo box from its font rather than from the height it
     * was given.  Scaling the font alone would therefore make the rows overlap
     * and push the last of them past the bottom of a page that cannot scroll.
     * The chrome is what "Text size" scales; this window scales when its whole
     * layout does. */
    pf->fnt = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                          OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                          CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                          L"Segoe UI");
    pf->fnt_bold = CreateFontW(-15, 0, 0, 0, FW_SEMIBOLD, 0, 0, 0,
                               DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                               CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                               DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");

    /* The layout maths is written against the CLIENT area, so the requested
     * window rectangle is grown by the frame rather than guessed at. */
    want.left = 0; want.top = 0;
    want.right = RB_PREFS_WIN_W; want.bottom = RB_PREFS_WIN_H;
    AdjustWindowRectEx(&want, WS_POPUP | WS_CAPTION | WS_SYSMENU, FALSE, 0);

    g_prefs = pf;
    pf->dlg = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT,
                              L"RoomBrowserPrefs",
                              L"Profile Settings",
                              WS_POPUP | WS_CAPTION | WS_SYSMENU,
                              CW_USEDEFAULT, CW_USEDEFAULT,
                              want.right - want.left, want.bottom - want.top,
                              app->hwnd, NULL, app->hinst, NULL);
    if (pf->dlg == NULL) {
        g_prefs = NULL;
        if (pf->fnt != NULL) DeleteObject(pf->fnt);
        if (pf->fnt_bold != NULL) DeleteObject(pf->fnt_bold);
        free(pf);
        return;
    }
    SetWindowLongPtrW(pf->dlg, GWLP_USERDATA, (LONG_PTR)pf);

    GetClientRect(pf->dlg, &rc);
    pf->page_w = rc.right - 2 * RB_PREFS_MARGIN;
    pf->page_h = rc.bottom - (RB_PREFS_MARGIN + RB_PREFS_TABH + 6)
                 - RB_PREFS_FOOTH - RB_PREFS_MARGIN;
    y = RB_PREFS_MARGIN + RB_PREFS_TABH + 6;

    /* The pages exist from the start and are shown one at a time: building
     * them lazily would mean re-reading every setting on each switch, and
     * this window is short-lived anyway.  Each page is the parent of its own
     * controls, so switching pages is one ShowWindow, not a walk. */
    for (i = 0; i < RB_PREFS_PAGES; i++) {
        pf->pages[i] = CreateWindowExW(WS_EX_CONTROLPARENT,
                                       L"RoomBrowserPrefsPage", L"",
                                       WS_CHILD | WS_CLIPCHILDREN,
                                       RB_PREFS_MARGIN, y,
                                       pf->page_w, pf->page_h,
                                       pf->dlg, NULL, app->hinst, NULL);
        if (pf->pages[i] == NULL) { pf_close(pf); return; }
        SetWindowLongPtrW(pf->pages[i], GWLP_USERDATA, (LONG_PTR)pf);
        /* The label is drawn by pf_draw_tab from g_page_names; the control's
         * own text stays empty, so the name has one source. */
        pf->tabs[i] = pf_mk(pf, pf->dlg, L"BUTTON", L"",
                            WS_TABSTOP | BS_OWNERDRAW,
                            0, 0, 90, RB_PREFS_TABH,
                            RB_PREFS_TAB_BASE + i, RB_PF_INK_NORMAL);
        if (pf->tabs[i] == NULL) { pf_close(pf); return; }
    }
    pf_layout_tabs(pf);

    pf_build_appearance(pf);
    pf_build_search(pf);
    pf_build_privacy(pf);
    pf_build_ua(pf);
    pf_build_network(pf);
    pf_build_home(pf);
    pf_build_clear(pf);

    ShowWindow(pf->pages[0], SW_SHOW);
    pf->page_sel = 0;

    pf_mk(pf, pf->dlg, L"BUTTON", L"Close",
          WS_TABSTOP | BS_PUSHBUTTON,
          rc.right - RB_PREFS_MARGIN - 90, rc.bottom - RB_PREFS_MARGIN - 26,
          90, 26, RB_PREFS_CLOSE, RB_PF_INK_NORMAL);

    pf_refresh_notes(pf);

    /* Modality by hand: a disabled owner cannot be interacted with, which is
     * what GTK_DIALOG_MODAL does for the GTK edition. */
    EnableWindow(app->hwnd, FALSE);
    ShowWindow(pf->dlg, SW_SHOW);
    SetForegroundWindow(pf->dlg);
    SetFocus(pf->tabs[0]);
}
