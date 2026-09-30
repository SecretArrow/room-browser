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
void rb_wv_stop_all(App *app);   /* every view; the profile switch needs it */
void rb_wv_can_nav(App *app, int *can_back, int *can_fwd);

/* Re-applies the active profile's per-webview settings (JavaScript, the
 * User-Agent) to every live webview.  The preferences editor calls this
 * after a change so the pages already open follow it, instead of the change
 * waiting for a restart.  Only this half is public: applying to one webview
 * needs ICoreWebView2, which is webview.c's private business. */
void rb_wv_apply_settings_all(App *app);

/* What the "Clear data" page of the preferences asks for.  A bit set means
 * "erase this"; the store-side half of that page (history, download records)
 * is the core's and does not come through here. */
#define RB_WV_CLEAR_COOKIES    0x1u
#define RB_WV_CLEAR_CACHE      0x2u
#define RB_WV_CLEAR_SITE_DATA  0x4u

/* Erases the given kinds of browsing data for the ACTIVE PROFILE, through the
 * runtime's own eraser.  Asynchronous: it returns once the request has been
 * handed over, not once the data is gone. */
void rb_wv_clear_browsing_data(App *app, unsigned kinds);

/* Run `js` in the ACTIVE tab's page.  `done` receives the runtime's result as
 * the JSON text it produced ("12" for a number, "\"x\"" for a string), or NULL
 * when the call failed; it may be NULL when the caller does not want the
 * answer.  The callback runs on this thread, and once. */
typedef void (*RbJsDone)(App *app, const wchar_t *result_json);
void rb_wv_run_js(App *app, const wchar_t *js, RbJsDone done);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_WEBVIEW_H */
