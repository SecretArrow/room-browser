/*
 * Room Browser (desktop) - Windows preferences editor.
 *
 * The desktop counterpart of Android's ProfileSettingsScreen: one window over
 * the ACTIVE PROFILE's settings, laid out in the same pages the GTK edition
 * uses so the two desktop editions present the same thing under the same
 * names.  Every control writes through rb_pref_set()/rb_pref_set_int(), which
 * is the same accessor every other feature reads through, and a change that
 * a live webview can follow is applied at once rather than at the next start.
 *
 * The guard is RB_WIN_PREFS_H, not RB_PREFS_H: the CORE has a rb_prefs.h of
 * its own (the preference keys), and two headers sharing a guard means the
 * second one included is silently dropped. */
#ifndef RB_WIN_PREFS_H
#define RB_WIN_PREFS_H

#include "chrome.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Shows the editor, modal to the main window.  Only one exists at a time:
 * a second call while it is open just brings it to the front. */
void rb_show_prefs(App *app);

/* TRUE when `msg` belongs to the preferences window and has been handled as a
 * dialog message — the message loop calls this before Translate/Dispatch so
 * the editor gets Tab navigation and Escape-to-close.  FALSE for everything
 * else, including when no editor is open. */
int rb_prefs_is_msg(const MSG *msg);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_PREFS_H */
