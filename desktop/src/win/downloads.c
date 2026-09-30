/*
 * Room Browser (desktop) - Windows downloads window.
 *
 * See downloads.h for what this is and why.  The one design note worth
 * repeating here: the rows are SNAPSHOTTED into the window, and re-snapshotted
 * on a timer, rather than read from the store while it is painted.
 * rb_downloads_at_for() hands out pointers into the store and the store can
 * move them, so a paint that walked it would be reading through a pointer a
 * background download could have invalidated between two rows.  The list is
 * bounded by what the profile has actually downloaded, so the copy costs
 * nothing that matters.
 */
#include "downloads.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/* ------------------------------------------------------------------ */
/* Window metrics (mirror rb_show_downloads_dialog in the GTK chrome) */

#define RB_DL_WIN_W   560
#define RB_DL_WIN_H   400
#define RB_DL_MARGIN  12
#define RB_DL_BTNH    26
#define RB_DL_BTNW    96
#define RB_DL_PADX    8
#define RB_DL_PADY    5

#define RB_DL_LIST    4001
#define RB_DL_CLEAR   4002
#define RB_DL_TIMER   4003
#define RB_DL_CLOSE   IDCANCEL

/* One painted row: the file name, then the status line under it. */
typedef struct {
    wchar_t *name;
    wchar_t *meta;   /* NULL when there is nothing to say under the name */
} RbDlRow;

typedef struct {
    App *app;
    HWND dlg;
    HWND list;
    HFONT fnt;
    int row_h;

    RbDlRow *rows;
    int n;
} RbDownloads;

static RbDownloads *g_dl = NULL;

/* ------------------------------------------------------------------ */

static wchar_t *rb_dl_wide(const char *s)
{
    return rb_utf8_to_wide(s != NULL ? s : "");
}

/* The status line, carrying the same facts as the GTK window: the status name,
 * the progress while one is active, the size, and the error when one failed.
 * Both editions read the same core strings and the same core formatter, so the
 * two windows cannot describe one download differently.  The separator is " - "
 * rather than GTK's em dash because this file is compiled by MSVC, which reads
 * a narrow literal in the system codepage. */
static wchar_t *rb_dl_meta(const rb_download *dl)
{
    char buf[512];
    char have[32];
    char total[32];
    const char *status = rb_download_status_name(dl->status);

    rb_download_format_bytes(dl->downloaded_bytes, have, sizeof have);
    rb_download_format_bytes(dl->total_bytes, total, sizeof total);

    if (rb_download_is_active(dl)) {
        if (dl->total_bytes > 0) {
            snprintf(buf, sizeof buf, "%s - %d%%  (%s / %s)", status,
                     rb_download_progress_percent(dl->downloaded_bytes,
                                                  dl->total_bytes),
                     have, total);
        } else {
            /* No length header: report what has arrived rather than inventing
             * a percentage from a total the server never sent. */
            snprintf(buf, sizeof buf, "%s - %s so far", status, have);
        }
    } else if (dl->status == RB_DL_COMPLETED) {
        snprintf(buf, sizeof buf, "%s - %s", status, have);
    } else if (dl->error != NULL && dl->error[0]) {
        snprintf(buf, sizeof buf, "%s - %s", status, dl->error);
    } else {
        snprintf(buf, sizeof buf, "%s", status);
    }
    return rb_dl_wide(buf);
}

static void rb_dl_rows_free(RbDownloads *d)
{
    int i;

    for (i = 0; i < d->n; i++) {
        free(d->rows[i].name);
        free(d->rows[i].meta);
    }
    free(d->rows);
    d->rows = NULL;
    d->n = 0;
}

/* Reads the ACTIVE profile's rows out of the store, into the window's own
 * copy.  The list used to walk the whole store, which showed another profile's
 * downloads and offered rows the "Clear list" button could not touch — it
 * clears one profile (DownloadDao.deleteAllFor). */
static void rb_dl_rows_build(RbDownloads *d)
{
    const rb_profile *p = rb_active_profile(d->app);
    const char *pid = (p != NULL) ? p->id : "";
    int n, i;

    rb_dl_rows_free(d);
    n = rb_downloads_count_for(d->app->downloads, pid);
    if (n > 0) {
        d->rows = (RbDlRow *)calloc((size_t)n, sizeof *d->rows);
        if (d->rows == NULL) return;
    }
    for (i = 0; i < n; i++) {
        const rb_download *dl = rb_downloads_at_for(d->app->downloads, pid, i);
        if (dl == NULL) continue;
        d->rows[d->n].name = rb_dl_wide(dl->file_name);
        d->rows[d->n].meta = rb_dl_meta(dl);
        d->n++;
    }
    /* GTK's "(no downloads yet)" row, so an empty window says so rather than
     * showing a blank rectangle. */
    if (d->n == 0) {
        d->rows = (RbDlRow *)calloc(1, sizeof *d->rows);
        if (d->rows == NULL) return;
        d->rows[0].name = rb_dl_wide("(no downloads yet)");
        d->rows[0].meta = NULL;
        d->n = 1;
    }
}

/* Re-reads the store and repaints.
 *
 * The transfer a row describes keeps arriving while the window is open, so the
 * window follows it on a timer instead of freezing at the figures it was opened
 * with.  LB_RESETCONTENT is what keeps the item count and the snapshot in step:
 * the listbox owns one item per row and rb_dl_draw_row indexes the snapshot by
 * itemID, so the two must never disagree. */
static void rb_dl_rows_apply(RbDownloads *d)
{
    int i;

    if (d->list == NULL) return;
    rb_dl_rows_build(d);
    SendMessageW(d->list, LB_RESETCONTENT, 0, 0);
    for (i = 0; i < d->n; i++) {
        SendMessageW(d->list, LB_ADDSTRING, 0, (LPARAM)L"");
    }
    SendMessageW(d->list, LB_SETCURSEL, (WPARAM)-1, 0);
    InvalidateRect(d->list, NULL, TRUE);
}

/* ------------------------------------------------------------------ */

static HWND rb_dl_ctl(RbDownloads *d, const wchar_t *cls, const wchar_t *text,
                      DWORD style, int x, int y, int w, int h, int id,
                      HFONT font)
{
    HWND c = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style,
                             x, y, w, h, d->dlg, (HMENU)(INT_PTR)id,
                             d->app->hinst, NULL);
    if (c != NULL && font != NULL) {
        SendMessageW(c, WM_SETFONT, (WPARAM)font, TRUE);
    }
    return c;
}

static void rb_dl_close(RbDownloads *d)
{
    App *app = d->app;

    g_dl = NULL;
    if (d->dlg != NULL) DestroyWindow(d->dlg);
    /* Modality was the owner being disabled; giving it back is what closes
     * the dialog as far as the user is concerned. */
    EnableWindow(app->hwnd, TRUE);
    SetForegroundWindow(app->hwnd);
    if (d->fnt != NULL) DeleteObject(d->fnt);
    rb_dl_rows_free(d);
    free(d);
}

/* ------------------------------------------------------------------ */
/* Painting */

static void rb_dl_draw_row(RbDownloads *d, const DRAWITEMSTRUCT *dis)
{
    const RbDlRow *row;
    RECT r;
    COLORREF ink;

    if (dis->itemID == (UINT)-1) return;
    if ((int)dis->itemID >= d->n) return;
    row = &d->rows[dis->itemID];

    r = dis->rcItem;
    /* A visible cursor rather than GTK's invisible one: both windows treat
     * the list as informational (nothing opens on click in either edition),
     * but GTK sets GTK_SELECTION_NONE and Win32 cannot, so the honest thing
     * is to show where the keyboard is rather than to hide it. */
    FillRect(dis->hDC, &r,
             (dis->itemState & ODS_SELECTED) ? d->app->br_omni : d->app->br_chrome);

    SetBkMode(dis->hDC, TRANSPARENT);
    r.left += RB_DL_PADX;
    r.right -= RB_DL_PADX;

    ink = rb_col(d->app->pal.text_primary);
    SetTextColor(dis->hDC, ink);
    r.top += RB_DL_PADY;
    r.bottom = r.top + d->row_h / 2;
    DrawTextW(dis->hDC, row->name, -1, &r,
              DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX);

    if (row->meta != NULL) {
        SetTextColor(dis->hDC, rb_col(d->app->pal.text_secondary));
        r.top = r.bottom;
        r.bottom = r.top + d->row_h / 2;
        DrawTextW(dis->hDC, row->meta, -1, &r,
                  DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX);
    }
}

/* The records of the ACTIVE profile only; the files on disk belong to the
 * user and are never touched (DownloadDao.deleteAllFor).  Same rule, and the
 * same close-afterwards, as the GTK window. */
static void rb_dl_clear(RbDownloads *d)
{
    App *app = d->app;
    const rb_profile *p = rb_active_profile(app);

    rb_downloads_clear_profile(app->downloads, (p != NULL) ? p->id : "");
    if (app->path_downloads != NULL) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
    rb_dl_close(d);
}

/* ------------------------------------------------------------------ */

static LRESULT CALLBACK rb_dl_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbDownloads *d = (RbDownloads *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_ERASEBKGND:
        if (d != NULL) {
            RECT r;
            GetClientRect(hwnd, &r);
            FillRect((HDC)wp, &r, d->app->br_chrome);
            return 1;
        }
        break;
    case WM_MEASUREITEM:
        if (d != NULL) {
            MEASUREITEMSTRUCT *mis = (MEASUREITEMSTRUCT *)lp;
            if (mis->CtlType == ODT_LISTBOX) {
                mis->itemHeight = (UINT)d->row_h;
                return TRUE;
            }
        }
        break;
    case WM_DRAWITEM:
        if (d != NULL) {
            const DRAWITEMSTRUCT *dis = (const DRAWITEMSTRUCT *)lp;
            if (dis->CtlType == ODT_LISTBOX && dis->CtlID == RB_DL_LIST) {
                rb_dl_draw_row(d, dis);
                return TRUE;
            }
        }
        break;
    case WM_CTLCOLORLISTBOX:
    case WM_CTLCOLORBTN:
        if (d != NULL) {
            SetBkMode((HDC)wp, TRANSPARENT);
            SetBkColor((HDC)wp, rb_col(d->app->pal.background));
            SetTextColor((HDC)wp, rb_col(d->app->pal.text_primary));
            return (LRESULT)d->app->br_chrome;
        }
        break;
    case WM_TIMER:
        if (d != NULL && wp == RB_DL_TIMER) {
            rb_dl_rows_apply(d);
            return 0;
        }
        break;
    case WM_COMMAND:
        if (d != NULL) {
            int id = LOWORD(wp);
            if (id == RB_DL_CLEAR) { rb_dl_clear(d); return 0; }
            if (id == RB_DL_CLOSE || id == IDOK) { rb_dl_close(d); return 0; }
        }
        break;
    case WM_CLOSE:
        if (d != NULL) { rb_dl_close(d); return 0; }
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int rb_downloads_is_msg(const MSG *msg)
{
    if (g_dl == NULL || msg == NULL) return 0;
    /* A timer is never a dialog-key message, and the window's live refresh
     * depends on it reaching the window proc: IsDialogMessageW swallows
     * anything it claims to have processed, so this is spelled out rather than
     * left to what it happens to return. */
    if (msg->message == WM_TIMER) return 0;
    return IsDialogMessageW(g_dl->dlg, (LPMSG)msg) ? 1 : 0;
}

void rb_show_downloads(App *app)
{
    RbDownloads *d;
    RECT want, rc;
    HDC dc;
    TEXTMETRICW tm;
    int y, list_h;

    if (app == NULL || app->hwnd == NULL) return;
    if (g_dl != NULL) {
        SetForegroundWindow(g_dl->dlg);
        return;
    }

    d = (RbDownloads *)calloc(1, sizeof *d);
    if (d == NULL) return;
    d->app = app;

    /* Not at the profile's font scale, for the reason rb_show_prefs gives:
     * the layout is written against fixed metrics, and scaling the font alone
     * would move the rows out of a box that did not move with them. */
    d->fnt = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                         OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                         CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                         L"Segoe UI");
    /* The row has to hold two lines of whatever font we actually got. */
    d->row_h = 36;
    dc = GetDC(NULL);
    if (dc != NULL) {
        HGDIOBJ old = SelectObject(dc, d->fnt);
        if (GetTextMetricsW(dc, &tm)) {
            d->row_h = (int)tm.tmHeight * 2 + 2 * RB_DL_PADY;
        }
        SelectObject(dc, old);
        ReleaseDC(NULL, dc);
    }

    {
        WNDCLASSEXW wc;
        memset(&wc, 0, sizeof wc);
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = rb_dl_proc;
        wc.hInstance = app->hinst;
        wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
        wc.hbrBackground = NULL;
        wc.lpszClassName = L"RoomBrowserDownloads";
        if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            if (d->fnt != NULL) DeleteObject(d->fnt);
                    free(d);
            return;
        }
    }

    want.left = 0; want.top = 0;
    want.right = RB_DL_WIN_W; want.bottom = RB_DL_WIN_H;
    AdjustWindowRectEx(&want, WS_POPUP | WS_CAPTION | WS_SYSMENU, FALSE, 0);

    g_dl = d;
    d->dlg = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT,
                             L"RoomBrowserDownloads", L"Downloads",
                             WS_POPUP | WS_CAPTION | WS_SYSMENU,
                             CW_USEDEFAULT, CW_USEDEFAULT,
                             want.right - want.left, want.bottom - want.top,
                             app->hwnd, NULL, app->hinst, NULL);
    if (d->dlg == NULL) { rb_dl_close(d); return; }
    SetWindowLongPtrW(d->dlg, GWLP_USERDATA, (LONG_PTR)d);
    rb_apply_dark_titlebar(d->dlg);

    GetClientRect(d->dlg, &rc);
    list_h = rc.bottom - RB_DL_MARGIN - RB_DL_BTNH - RB_DL_MARGIN - RB_DL_MARGIN;

    d->list = rb_dl_ctl(d, L"LISTBOX", L"",
                        WS_TABSTOP | WS_VSCROLL | WS_BORDER |
                        LBS_OWNERDRAWFIXED | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT,
                        RB_DL_MARGIN, RB_DL_MARGIN,
                        rc.right - 2 * RB_DL_MARGIN, list_h, RB_DL_LIST,
                        d->fnt);
    if (d->list == NULL) { rb_dl_close(d); return; }

    rb_dl_rows_apply(d);

    y = rc.bottom - RB_DL_MARGIN - RB_DL_BTNH;
    rb_dl_ctl(d, L"BUTTON", L"Clear list", WS_TABSTOP | BS_PUSHBUTTON,
              RB_DL_MARGIN, y, RB_DL_BTNW, RB_DL_BTNH, RB_DL_CLEAR, d->fnt);
    /* Close carries IDCANCEL and the default-push-button style on purpose:
     * IsDialogMessageW turns Escape into a WM_COMMAND for IDCANCEL and Enter
     * into one for IDOK, so the two ids together are what make Escape and
     * Enter both close the window. */
    rb_dl_ctl(d, L"BUTTON", L"Close", WS_TABSTOP | BS_DEFPUSHBUTTON,
              rc.right - RB_DL_MARGIN - RB_DL_BTNW, y,
              RB_DL_BTNW, RB_DL_BTNH, RB_DL_CLOSE, d->fnt);

    EnableWindow(app->hwnd, FALSE);
    ShowWindow(d->dlg, SW_SHOW);
    SetForegroundWindow(d->dlg);
    /* The timer belongs to the window, so DestroyWindow in rb_dl_close takes
     * it down with the dialog; there is nothing to kill by hand. */
    SetTimer(d->dlg, RB_DL_TIMER, 1000, NULL);
}
