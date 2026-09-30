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

/* Re-applies the active profile's User-Agent to every open webview. */
void rb_gw_apply_ua(App *app);

/* Re-applies every preference that lives on WebKitSettings (JavaScript,
 * WebRTC, DNS prefetching) to the open views, and the profile's cookie policy
 * to its data manager.  Idempotent, so a preferences change calls this rather
 * than working out which switch moved. */
void rb_gw_apply_web_settings(App *app);
/* Wires the download-started signal on the app's context so every download
 * is recorded in app->downloads.  Called by rb_gw_context_new. */
void rb_gw_downloads_init(App *app);

/* 1 while WebKit is still transferring this record, 0 when the transfer is
 * over or was never WebKit's (a record from a previous run).  The downloads
 * window offers "Cancel" for exactly those rows and for no others. */
int rb_gw_download_active(long long id);

/* Asks WebKit to stop that transfer.  Returns 1 when there was a live one to
 * stop; WebKit then reports the stop through its "failed" signal, which is
 * what moves the record to CANCELLED — the store is not written here, so the
 * row can never say CANCELLED while WebKit is still writing the file. */
int rb_gw_download_cancel(long long id);

/* Builds the ACTIVE profile's WebKit context, rooted in that profile's
 * browser_data / cache directories.  No-op when one already exists, or when
 * there is no active profile. */
void rb_gw_context_new(App *app);

/* Releases it.  Every view built on it must already be destroyed. */
void rb_gw_context_free(App *app);

/* ---- content blocking ----
 *
 * Compiles the active profile's rules and installs them on every open view.
 * Asynchronous (WebKit compiles in its own process), so a view created before
 * the compile finishes picks the rules up on its next load.  Called by
 * rb_gw_context_new; call it again after a switch or preference changes.
 *
 * Also rebuilds the JSON only when the profile's switches actually changed,
 * so a call on an unchanged profile is nearly free. */
void rb_gw_content_blocking_apply(App *app);

/* Drops the compiled rules and the JSON.  Called on a profile switch: the
 * next profile's rules describe different switches. */
void rb_gw_content_blocking_clear(App *app);

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
