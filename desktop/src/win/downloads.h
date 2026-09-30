/*
 * Room Browser (desktop) - Windows downloads window.
 *
 * The GTK edition has shown a Downloads window since the beginning; Windows
 * recorded every download and had nowhere to show it, which is a difference
 * a user notices.  This is that window, built to the same shape: the ACTIVE
 * profile's records, newest first, each row a file name over a status line,
 * a row of per-row actions (Open, Show in folder, Copy link, Details, Remove
 * from list) that follow the selection, and "Clear list" / "Close".
 *
 * It is a plain window rather than a DLGTEMPLATE one, exactly like the
 * preferences window, and it is modal the same way: by disabling its owner
 * instead of by a system modal loop.
 */
#ifndef RB_WIN_DOWNLOADS_H
#define RB_WIN_DOWNLOADS_H

#include "chrome.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Opens the window, or focuses it when it is already open. */
void rb_show_downloads(App *app);

/* The message loop's hook, alongside rb_prefs_is_msg().  A plain window gets
 * no dialog keyboard behaviour unless it is asked for: this gives the list
 * and the two buttons Tab/Enter/Escape and consumes the message when it
 * handled it.  Returns 0 for every other window. */
int rb_downloads_is_msg(const MSG *msg);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_DOWNLOADS_H */
