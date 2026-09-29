/*
 * Room Browser (desktop) - GTK webview header.
 *
 * WebKitGTK per-tab webviews: creation with settings, signal wiring and
 * navigation helpers. Kept separate from chrome.c so the Win32/WebKit
 * specifics stay isolated.
 */
#ifndef RB_GTK_WEBVIEW_H
#define RB_GTK_WEBVIEW_H

#include "chrome.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Creates a WebKitWebView with the app settings applied (JavaScript
 * default ON, developer extras off) and all signals wired. */
WebKitWebView *rb_gw_new_view(App *app);

/* Re-applies the JavaScript setting to every open webview (live toggle). */
void rb_gw_apply_js(App *app);

/* Navigation on the ACTIVE tab's webview. */
void rb_gw_navigate(App *app, const char *url);
void rb_gw_back(App *app);
void rb_gw_forward(App *app);
void rb_gw_reload(App *app);
void rb_gw_stop(App *app);
void rb_gw_can_nav(App *app, gboolean *can_back, gboolean *can_fwd);

#ifdef __cplusplus
}
#endif

#endif /* RB_GTK_WEBVIEW_H */
