/*
 * Room Browser (desktop) - Windows webview header.
 *
 * WebView2 (Evergreen) hosting in plain C11 COM. The loader DLL is
 * resolved at runtime (LoadLibraryW) so the executable links against
 * nothing but the standard system libraries. If the WebView2 Runtime
 * is missing the browser chrome stays usable and an honest message is
 * shown once.
 */
#ifndef RB_WIN_WEBVIEW_H
#define RB_WIN_WEBVIEW_H

#include "chrome.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Loads WebView2Loader.dll and starts environment creation. Returns 0 on
 * success (creation is asynchronous), -1 if the runtime is unavailable. */
int  rb_wv_init(App *app);

/* Releases every controller/webview and the environment. Call at quit. */
void rb_wv_shutdown(App *app);

/* Show the controller of the given tab (hide the others), resize it and
 * start its creation if needed. Safe to call for any existing tab id. */
void rb_wv_activate(App *app, long tab_id);

/* Releases the controller of a tab that is about to be closed. */
void rb_wv_drop_tab(App *app, long tab_id);

/* Resize the visible controller to the content area (WM_SIZE). */
void rb_wv_resize(App *app);

/* Navigation actions on the active tab. */
void rb_wv_navigate(App *app, const char *url);
void rb_wv_goback(App *app);
void rb_wv_gofwd(App *app);
void rb_wv_reload(App *app);
void rb_wv_stop(App *app);
void rb_wv_can_nav(App *app, int *can_back, int *can_fwd);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_WEBVIEW_H */
