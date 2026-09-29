/*
 * Room Browser (desktop) - GTK chrome implementation.
 *
 * GTK3 chrome in pure C11: GtkNotebook tab strip (one WebKitWebView per
 * tab), toolbar with omnibox, bookmark star and hamburger menu, keyboard
 * accelerators via GSimpleAction, plus the shared persistence glue.
 * Brave-inspired dark palette with purple accent (#A78BFA) loaded through
 * a GtkCssProvider (RB_CSS below).
 *
 * Design notes:
 *  - Array index of app->tabs == notebook page index == rb_tabs index;
 *    the switch-page handler keeps app->active_id in sync (suppressed via
 *    app->silent while the chrome itself mutates the notebook).
 *  - The per-tab close button resolves its tab by widget pointer
 *    (rb_tab_by_widget), so it keeps working after any reordering.
 *  - The hamburger menu is a classic GtkMenu attached to a GtkMenuButton
 *    (gtk_menu_button_set_popup is deprecated in favour of popovers but
 *    still fully functional on GTK 3.x; the two calls are wrapped in
 *    G_GNUC_BEGIN/END_IGNORE_DEPRECATIONS to stay warning-clean).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <gdk/gdkkeysyms.h>

#include "chrome.h"
#include "webview.h"
#include "rb_version.h"

/* Single global application state (declared extern in chrome.h). */
App g_app;

/* The JavaScript check menu item (single instance, module-static). */
static GtkWidget *g_js_item = NULL;

/* Forward declarations (defined below, referenced by earlier functions). */
static void on_tab_close_clicked(GtkButton *button, gpointer user_data);
static void on_newtab_clicked(GtkButton *button, gpointer user_data);

/* ------------------------------------------------------------------ */
/* Dark chrome CSS (Brave-inspired palette, purple accent #A78BFA). */

static const char RB_CSS[] =
"window { background-color: #202124; }\n"
"notebook header { background-color: #202124; border: none; }\n"
"notebook header tabs tab { background-color: #18191C; color: #9AA0A6;"
" padding: 3px 10px 3px 12px; }\n"
"notebook header tabs tab:checked { background-color: #292A2D;"
" color: #E8EAED; }\n"
"notebook header tabs tab:hover { color: #E8EAED; }\n"
".rb-toolbar { background-color: #292A2D; padding: 5px 7px; }\n"
".rb-btn { background-color: transparent; color: #E8EAED; border: none;"
" padding: 2px 9px; }\n"
".rb-btn:hover { background-color: rgba(167,139,250,0.22); }\n"
".rb-btn:disabled { color: #5F6368; }\n"
".rb-omni { background-color: #3C3D41; color: #E8EAED; border: none;"
" border-radius: 8px; padding: 3px 10px 5px 10px;"
" caret-color: #A78BFA; }\n"
".rb-tab-close { background-color: transparent; color: #9AA0A6;"
" border: none; padding: 0 3px; }\n"
".rb-tab-close:hover { color: #E8EAED;"
" background-color: rgba(167,139,250,0.25); }\n"
".rb-dim { color: #9AA0A6; }\n";

void rb_css_load(void)
{
    GtkCssProvider *provider = gtk_css_provider_new();
    GdkScreen *screen = gdk_screen_get_default();
    gtk_css_provider_load_from_data(provider, RB_CSS, -1);
    if (screen) {
        gtk_style_context_add_provider_for_screen(screen,
            GTK_STYLE_PROVIDER(provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
    g_object_unref(provider);
}

/* ------------------------------------------------------------------ */
/* Small helpers */

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

static char *rb_path_join(const char *dir, const char *name)
{
    size_t n = strlen(dir) + strlen(name) + 2;
    char *p = (char *)malloc(n);
    if (!p) return NULL;
    snprintf(p, n, "%s/%s", dir, name);
    return p;
}

static void rb_add_class(GtkWidget *w, const char *cls)
{
    gtk_style_context_add_class(gtk_widget_get_style_context(w), cls);
}

/* ------------------------------------------------------------------ */
/* Persistence: load at startup, save on change (and a final save at quit). */

int rb_data_init(App *app)
{
    char *dir = rb_paths_data_dir();

    app->store = rb_tabs_new();
    app->history = rb_history_new();
    app->bookmarks = rb_bookmarks_new();
    app->settings = rb_settings_new();
    if (!app->store || !app->history || !app->bookmarks || !app->settings) {
        return -1;
    }
    if (!dir) return -1;

    app->path_history = rb_path_join(dir, "history.jsonl");
    app->path_bookmarks = rb_path_join(dir, "bookmarks.jsonl");
    app->path_settings = rb_path_join(dir, "settings.txt");
    if (!app->path_history || !app->path_bookmarks || !app->path_settings) {
        rb_paths_free(dir);
        return -1;
    }

    rb_settings_load(app->settings, app->path_settings);
    rb_history_load(app->history, app->path_history);
    rb_bookmarks_load(app->bookmarks, app->path_bookmarks);

    app->home_url = rb_strdup(rb_settings_get(app->settings, "home",
                                              "https://duckduckgo.com"));
    /* Project policy: JavaScript is NEVER disabled by default. */
    app->js_enabled = rb_settings_get_int(app->settings, "javascript", 1);
    rb_paths_free(dir);
    return 0;
}

void rb_data_shutdown(App *app)
{
    if (app->history && app->path_history) {
        rb_history_save(app->history, app->path_history);
    }
    if (app->bookmarks && app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    if (app->settings && app->path_settings) {
        rb_settings_save(app->settings, app->path_settings);
    }
}

void rb_data_free(App *app)
{
    free(app->path_history);   app->path_history = NULL;
    free(app->path_bookmarks); app->path_bookmarks = NULL;
    free(app->path_settings);  app->path_settings = NULL;
    free(app->home_url);       app->home_url = NULL;
    if (app->store)     { rb_tabs_free(app->store);         app->store = NULL; }
    if (app->history)   { rb_history_free(app->history);    app->history = NULL; }
    if (app->bookmarks) { rb_bookmarks_free(app->bookmarks); app->bookmarks = NULL; }
    if (app->settings)  { rb_settings_free(app->settings);  app->settings = NULL; }
    free(app->tabs);
    app->tabs = NULL;
    app->tabs_n = 0;
    app->tabs_cap = 0;
}

/* ------------------------------------------------------------------ */
/* Tab resolution */

GtkTab *rb_tab_by_widget(App *app, GtkWidget *w)
{
    int i;
    if (!app || !w) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if ((GtkWidget *)app->tabs[i].wv == w ||
            app->tabs[i].label == w ||
            app->tabs[i].close_btn == w) {
            return &app->tabs[i];
        }
    }
    return NULL;
}

GtkTab *rb_active_tab(App *app)
{
    int i;
    if (!app) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == app->active_id) return &app->tabs[i];
    }
    return NULL;
}

static rb_tab *rb_store_tab(App *app)
{
    return app->active_id ? rb_tabs_get(app->store, app->active_id) : NULL;
}

/* ------------------------------------------------------------------ */
/* UI refresh helpers */

void rb_update_omni(App *app, const char *url)
{
    if (!app || !app->omnibox) return;
    gtk_entry_set_text(GTK_ENTRY(app->omnibox), url ? url : "");
}

void rb_update_titlebar(App *app)
{
    rb_tab *t = rb_store_tab(app);
    if (t && t->title && t->title[0]) {
        char buf[600];
        snprintf(buf, sizeof buf, "%s \xE2\x80\x94 Room Browser", t->title);
        gtk_window_set_title(GTK_WINDOW(app->win), buf);
    } else {
        gtk_window_set_title(GTK_WINDOW(app->win), "Room Browser");
    }
}

void rb_update_nav(App *app)
{
    gboolean back = FALSE, fwd = FALSE;
    rb_gw_can_nav(app, &back, &fwd);
    gtk_widget_set_sensitive(app->back, back);
    gtk_widget_set_sensitive(app->fwd, fwd);
}

void rb_update_star(App *app)
{
    rb_tab *t = rb_store_tab(app);
    int on = (t && t->url && rb_bookmarks_contains(app->bookmarks, t->url));
    gtk_button_set_label(GTK_BUTTON(app->star), on ? "\xE2\x98\x85" : "\xE2\x98\x86");
}

void rb_update_reloadbtn(App *app)
{
    gtk_button_set_label(GTK_BUTTON(app->reload), app->loading ? "\xC3\x97" : "\xE2\x9F\xB3");
}

void rb_update_all(App *app)
{
    rb_tab *t = rb_store_tab(app);
    rb_update_omni(app, t && t->url ? t->url : "");
    rb_update_titlebar(app);
    rb_update_nav(app);
    rb_update_star(app);
    rb_update_reloadbtn(app);
}

/* ------------------------------------------------------------------ */
/* Actions */

static void rb_do_reload_or_stop(App *app)
{
    if (app->loading) rb_gw_stop(app);
    else              rb_gw_reload(app);
}

void rb_do_new_tab(App *app)
{
    WebKitWebView *wv;
    GtkWidget *hbox, *lbl, *close;
    const char *home = app->home_url ? app->home_url : "https://duckduckgo.com";
    long id;
    int idx;

    wv = rb_gw_new_view(app);
    if (!wv) return;

    hbox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    lbl = gtk_label_new("New Tab");
    gtk_label_set_ellipsize(GTK_LABEL(lbl), PANGO_ELLIPSIZE_END);
    gtk_label_set_width_chars(GTK_LABEL(lbl), 10);
    close = gtk_button_new_with_label("\xC3\x97");
    rb_add_class(close, "rb-tab-close");
    g_signal_connect(close, "clicked", G_CALLBACK(on_tab_close_clicked), app);
    gtk_box_pack_start(GTK_BOX(hbox), lbl, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(hbox), close, FALSE, FALSE, 0);

    if (app->tabs_n == app->tabs_cap) {
        int cap = app->tabs_cap ? app->tabs_cap * 2 : 8;
        GtkTab *tabs = (GtkTab *)realloc(app->tabs, (size_t)cap * sizeof(GtkTab));
        if (!tabs) {
            gtk_widget_destroy(hbox);
            gtk_widget_destroy(GTK_WIDGET(wv));
            return;
        }
        app->tabs = tabs;
        app->tabs_cap = cap;
    }

    id = rb_tabs_add(app->store, "New Tab", home);
    app->tabs[app->tabs_n].id = id;
    app->tabs[app->tabs_n].wv = wv;
    app->tabs[app->tabs_n].label = lbl;
    app->tabs[app->tabs_n].close_btn = close;
    app->tabs_n++;

    app->silent = 1;
    idx = gtk_notebook_append_page(GTK_NOTEBOOK(app->notebook), GTK_WIDGET(wv), hbox);
    app->silent = 0;
    gtk_widget_show_all(hbox);
    gtk_widget_show(GTK_WIDGET(wv));

    app->active_id = id;
    if (idx >= 0) {
        gtk_notebook_set_current_page(GTK_NOTEBOOK(app->notebook), idx);
    }
    rb_update_all(app);
    webkit_web_view_load_uri(wv, home);
}

void rb_do_close_tab_id(App *app, long id)
{
    int i;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == id) break;
    }
    if (i >= app->tabs_n) return;

    app->silent = 1;
    gtk_notebook_remove_page(GTK_NOTEBOOK(app->notebook), i);
    app->silent = 0;
    memmove(&app->tabs[i], &app->tabs[i + 1],
            (size_t)(app->tabs_n - i - 1) * sizeof(app->tabs[0]));
    app->tabs_n--;
    rb_tabs_close(app->store, id);
    if (app->active_id == id) app->active_id = 0;

    if (app->tabs_n == 0) {
        rb_do_new_tab(app);
        return;
    }
    if (app->active_id == 0) {
        gint cur = gtk_notebook_get_current_page(GTK_NOTEBOOK(app->notebook));
        if (cur >= 0 && cur < app->tabs_n) {
            app->active_id = app->tabs[cur].id;
        }
    }
    rb_update_all(app);
}

void rb_do_navigate(App *app, const char *url)
{
    rb_tab *t;
    if (!app || !url || !url[0]) return;
    t = rb_store_tab(app);
    if (!t) return;
    rb_set_str(&t->url, rb_strdup(url));
    app->loading = 1;
    rb_update_reloadbtn(app);
    rb_update_omni(app, url);
    rb_gw_navigate(app, url);
}

void rb_do_toggle_bookmark(App *app)
{
    rb_tab *t = rb_store_tab(app);
    if (!t || !t->url || !t->url[0]) return;
    if (rb_bookmarks_contains(app->bookmarks, t->url)) {
        rb_bookmarks_remove(app->bookmarks, t->url);
    } else {
        rb_bookmarks_add(app->bookmarks, t->url,
                         t->title && t->title[0] ? t->title : t->url);
    }
    if (app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    rb_update_star(app);
}

/* ------------------------------------------------------------------ */
/* Signal callbacks */

static void on_switch_page(GtkNotebook *notebook, GtkWidget *page,
                            guint page_num, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)notebook;
    (void)page;
    if (app->silent) return;
    if ((int)page_num < app->tabs_n) {
        app->active_id = app->tabs[page_num].id;
        rb_update_all(app);
    }
}

static void on_tab_close_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkTab *gt = rb_tab_by_widget(app, GTK_WIDGET(button));
    if (gt) rb_do_close_tab_id(app, gt->id);
}

static void on_newtab_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)button;
    rb_do_new_tab(app);
}

static void on_nav_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkWidget *w = GTK_WIDGET(button);
    if (w == app->back) {
        rb_gw_back(app);
    } else if (w == app->fwd) {
        rb_gw_forward(app);
    } else if (w == app->reload) {
        rb_do_reload_or_stop(app);
    } else if (w == app->home) {
        rb_do_navigate(app, app->home_url ? app->home_url
                                          : "https://duckduckgo.com");
    } else if (w == app->star) {
        rb_do_toggle_bookmark(app);
    }
}

static void on_omni_activate(GtkEntry *entry, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *text = gtk_entry_get_text(entry);
    char *url;
    if (!text || !text[0]) return;
    url = rb_url_decide(text);
    if (url) {
        rb_do_navigate(app, url);
        free(url);
    }
}

static gboolean on_omni_key(GtkWidget *widget, GdkEventKey *event,
                            gpointer user_data)
{
    App *app = (App *)user_data;
    (void)widget;
    if (event->keyval == GDK_KEY_Escape) {
        rb_tab *t = rb_store_tab(app);
        rb_update_omni(app, t && t->url ? t->url : "");
        return TRUE;
    }
    return FALSE;
}

/* ------------------------------------------------------------------ */
/* Hamburger menu */

static void on_menu_new_tab(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_new_tab(app);
}

static void on_menu_bookmark(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_toggle_bookmark(app);
}

static void on_js_toggled(GtkCheckMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    app->js_enabled = gtk_check_menu_item_get_active(item) ? 1 : 0;
    rb_settings_set_int(app->settings, "javascript", app->js_enabled);
    if (app->path_settings) {
        rb_settings_save(app->settings, app->path_settings);
    }
    rb_gw_apply_js(app);   /* live toggle: applies to the open webviews */
}

static void on_dialog_response(GtkWidget *widget, gint response_id,
                               gpointer user_data)
{
    (void)response_id;
    (void)user_data;
    gtk_widget_destroy(widget);
}

static void rb_show_about(App *app)
{
    GtkWidget *dlg = gtk_about_dialog_new();
    const gchar *authors[2];
    authors[0] = "Maragung";
    authors[1] = NULL;
    gtk_window_set_transient_for(GTK_WINDOW(dlg), GTK_WINDOW(app->win));
    gtk_window_set_modal(GTK_WINDOW(dlg), TRUE);
    gtk_about_dialog_set_program_name(GTK_ABOUT_DIALOG(dlg), "Room Browser");
    gtk_about_dialog_set_version(GTK_ABOUT_DIALOG(dlg), RB_VERSION);
    gtk_about_dialog_set_authors(GTK_ABOUT_DIALOG(dlg), authors);
    gtk_about_dialog_set_comments(GTK_ABOUT_DIALOG(dlg),
        "Privacy desktop browser in pure C (Brave-inspired dark chrome).");
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);
    gtk_widget_show_all(dlg);
}

static void on_hist_row_activated(GtkListBox *box, GtkListBoxRow *row,
                                  gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = (const char *)g_object_get_data(G_OBJECT(row), "url");
    (void)box;
    if (url && url[0]) {
        rb_do_navigate(app, url);
    }
    gtk_widget_destroy(gtk_widget_get_toplevel(GTK_WIDGET(row)));
}

static void rb_show_history_dialog(App *app)
{
    GtkWidget *dlg, *scroll, *list;
    const rb_hist_entry *rec;
    int n = 0, i;

    dlg = gtk_dialog_new_with_buttons("Recent history", GTK_WINDOW(app->win),
            GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
            "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 560, 400);

    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    list = gtk_list_box_new();
    gtk_list_box_set_selection_mode(GTK_LIST_BOX(list), GTK_SELECTION_NONE);

    rec = rb_history_recent(app->history, 10, &n);
    for (i = 0; i < n; i++) {
        const char *title = (rec[i].title && rec[i].title[0]) ? rec[i].title
                                                              : rec[i].url;
        GtkWidget *row = gtk_list_box_row_new();
        GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
        GtkWidget *l1 = gtk_label_new(title ? title : "");
        GtkWidget *l2 = gtk_label_new(rec[i].url ? rec[i].url : "");
        gtk_label_set_ellipsize(GTK_LABEL(l1), PANGO_ELLIPSIZE_END);
        gtk_label_set_xalign(GTK_LABEL(l1), 0.0f);
        gtk_label_set_ellipsize(GTK_LABEL(l2), PANGO_ELLIPSIZE_MIDDLE);
        gtk_label_set_xalign(GTK_LABEL(l2), 0.0f);
        rb_add_class(l2, "rb-dim");
        g_object_set_data_full(G_OBJECT(row), "url",
                               rb_strdup(rec[i].url ? rec[i].url : ""), g_free);
        gtk_box_pack_start(GTK_BOX(vbox), l1, TRUE, TRUE, 0);
        gtk_box_pack_start(GTK_BOX(vbox), l2, TRUE, TRUE, 0);
        gtk_container_add(GTK_CONTAINER(row), vbox);
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }
    if (n == 0) {
        GtkWidget *row = gtk_list_box_row_new();
        gtk_container_add(GTK_CONTAINER(row),
                          gtk_label_new("(no history yet)"));
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }

    g_signal_connect(list, "row-activated",
                     G_CALLBACK(on_hist_row_activated), app);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    gtk_container_add(GTK_CONTAINER(scroll), list);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(dlg))),
                      scroll);
    gtk_widget_show_all(dlg);
}

static void on_menu_history(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_history_dialog(app);
}

static void on_menu_about(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_about(app);
}

static void rb_build_menu(App *app)
{
    GtkWidget *menu = gtk_menu_new();
    GtkWidget *item;

    item = gtk_menu_item_new_with_label("New Tab");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_new_tab), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Bookmark this page");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_bookmark), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Recent history");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_history), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    g_js_item = gtk_check_menu_item_new_with_label("Enable JavaScript");
    gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(g_js_item),
                                    app->js_enabled ? TRUE : FALSE);
    g_signal_connect(g_js_item, "toggled", G_CALLBACK(on_js_toggled), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), g_js_item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    item = gtk_menu_item_new_with_label("About");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_about), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_widget_show_all(menu);

    /* gtk_menu_button_set_popup() is deprecated in favour of popovers but
     * still fully functional on GTK 3.x; keep the classic GtkMenu (the
     * check item for JavaScript needs it) and silence the deprecation. */
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    gtk_menu_button_set_popup(GTK_MENU_BUTTON(app->menu_btn), menu);
    G_GNUC_END_IGNORE_DEPRECATIONS
}

/* ------------------------------------------------------------------ */
/* Keyboard accelerators (GSimpleAction on the GtkApplicationWindow) */

static void on_action_new_tab(GSimpleAction *action, GVariant *parameter,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_do_new_tab(app);
}

static void on_action_close_tab(GSimpleAction *action, GVariant *parameter,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (app->active_id) rb_do_close_tab_id(app, app->active_id);
}

static void on_action_focus_omnibox(GSimpleAction *action, GVariant *parameter,
                                    gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (app->omnibox) {
        gtk_widget_grab_focus(app->omnibox);
        gtk_editable_select_region(GTK_EDITABLE(app->omnibox), 0, -1);
    }
}

static void on_action_reload(GSimpleAction *action, GVariant *parameter,
                             gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_do_reload_or_stop(app);
}

static void on_action_back(GSimpleAction *action, GVariant *parameter,
                           gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_gw_back(app);
}

static void on_action_forward(GSimpleAction *action, GVariant *parameter,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_gw_forward(app);
}

static void rb_add_action(App *app, const char *name, GCallback callback,
                          const gchar *const *accels)
{
    GSimpleAction *action = g_simple_action_new(name, NULL);
    char detailed[64];

    g_signal_connect(action, "activate", callback, app);
    g_action_map_add_action(G_ACTION_MAP(app->win), G_ACTION(action));
    g_object_unref(action);

    if (accels) {
        snprintf(detailed, sizeof detailed, "win.%s", name);
        gtk_application_set_accels_for_action(app->app, detailed, accels);
    }
}

static void rb_add_actions(App *app)
{
    static const gchar *const accels_newtab[]  = { "<Primary>T", NULL };
    static const gchar *const accels_closetab[] = { "<Primary>W", NULL };
    static const gchar *const accels_omni[]   = { "<Primary>L", NULL };
    static const gchar *const accels_reload[] = { "F5", "<Primary>R", NULL };
    static const gchar *const accels_back[]   = { "<Alt>Left", NULL };
    static const gchar *const accels_fwd[]    = { "<Alt>Right", NULL };

    rb_add_action(app, "new-tab", G_CALLBACK(on_action_new_tab), accels_newtab);
    rb_add_action(app, "close-tab", G_CALLBACK(on_action_close_tab), accels_closetab);
    rb_add_action(app, "focus-omnibox", G_CALLBACK(on_action_focus_omnibox), accels_omni);
    rb_add_action(app, "reload", G_CALLBACK(on_action_reload), accels_reload);
    rb_add_action(app, "back", G_CALLBACK(on_action_back), accels_back);
    rb_add_action(app, "forward", G_CALLBACK(on_action_forward), accels_fwd);
}

/* ------------------------------------------------------------------ */
/* Chrome construction (GtkApplication "activate") */

void rb_on_activate(GtkApplication *gtk_app, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkWidget *root, *toolbar, *plus;

    if (app->win) {
        gtk_window_present(GTK_WINDOW(app->win));
        return;
    }

    rb_css_load();

    app->app = gtk_app;
    app->win = GTK_APPLICATION_WINDOW(gtk_application_window_new(gtk_app));
    gtk_window_set_default_size(GTK_WINDOW(app->win), RB_WINDOW_W, RB_WINDOW_H);
    gtk_window_set_title(GTK_WINDOW(app->win), "Room Browser");

    root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_container_add(GTK_CONTAINER(app->win), root);

    /* Tab strip: the notebook pages ARE the per-tab WebKitWebViews. */
    app->notebook = gtk_notebook_new();
    gtk_notebook_set_show_border(GTK_NOTEBOOK(app->notebook), FALSE);
    g_signal_connect(app->notebook, "switch-page",
                     G_CALLBACK(on_switch_page), app);
    plus = gtk_button_new_with_label("+");
    rb_add_class(plus, "rb-btn");
    g_signal_connect(plus, "clicked", G_CALLBACK(on_newtab_clicked), app);
    gtk_notebook_set_action_widget(GTK_NOTEBOOK(app->notebook), plus,
                                    GTK_PACK_END);
    gtk_box_pack_start(GTK_BOX(root), app->notebook, TRUE, TRUE, 0);

    /* Toolbar */
    toolbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    rb_add_class(toolbar, "rb-toolbar");

    app->back = gtk_button_new_with_label("\xE2\x86\x90");
    app->fwd = gtk_button_new_with_label("\xE2\x86\x92");
    app->reload = gtk_button_new_with_label("\xE2\x9F\xB3");
    app->home = gtk_button_new_with_label("\xE2\x8C\x82");
    app->star = gtk_button_new_with_label("\xE2\x98\x86");
    app->menu_btn = gtk_menu_button_new();
    gtk_button_set_label(GTK_BUTTON(app->menu_btn), "\xE2\x98\xB0");
    rb_add_class(app->back, "rb-btn");
    rb_add_class(app->fwd, "rb-btn");
    rb_add_class(app->reload, "rb-btn");
    rb_add_class(app->home, "rb-btn");
    rb_add_class(app->star, "rb-btn");
    rb_add_class(app->menu_btn, "rb-btn");
    g_signal_connect(app->back, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->fwd, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->reload, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->home, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->star, "clicked", G_CALLBACK(on_nav_clicked), app);

    app->omnibox = gtk_entry_new();
    rb_add_class(app->omnibox, "rb-omni");
    gtk_entry_set_placeholder_text(GTK_ENTRY(app->omnibox),
                                   "Search or enter address");
    gtk_widget_set_hexpand(app->omnibox, TRUE);
    g_signal_connect(app->omnibox, "activate",
                     G_CALLBACK(on_omni_activate), app);
    g_signal_connect(app->omnibox, "key-press-event",
                     G_CALLBACK(on_omni_key), app);

    rb_build_menu(app);

    gtk_box_pack_start(GTK_BOX(toolbar), app->back, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->fwd, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->reload, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->home, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->omnibox, TRUE, TRUE, 6);
    gtk_box_pack_start(GTK_BOX(toolbar), app->star, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->menu_btn, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(root), toolbar, FALSE, FALSE, 0);

    rb_add_actions(app);

    gtk_widget_show_all(GTK_WIDGET(app->win));

    /* First tab: navigates to the "home" setting. */
    rb_do_new_tab(app);
}

void rb_on_shutdown(GtkApplication *gtk_app, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)gtk_app;
    rb_data_shutdown(app);
}
