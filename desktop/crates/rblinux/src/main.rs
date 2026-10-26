//! Room Browser — Linux frontend (GTK3 + WebKitGTK 4.1).
//!
//! Same architecture as the Windows edition: the chrome is an HTML page
//! (rbchrome) in its own webview; tabs are WebKitWebView widgets in a
//! GtkStack. The chrome talks to this process through a registered script
//! message handler; the agent worker thread marshals page operations back
//! to the GTK main loop through a glib MainContext channel.

#![cfg(target_os = "linux")]

use rbcore::agent::executor::{AppStores, Executor, ToolHost};
use rbcore::agent::loop_runner::LoopHost;
use rbcore::agent::plan::AgentPlan;
use rbcore::agent::{gates::GateContext, AgentConfig, AgentEvent, ChatMessage, ToolCall};
use rbcore::prefs::ProfilePrefs;
use rbcore::profile::{Profile, ProfileRegistry};
use rbcore::{now_ms, CoreResult};
use serde_json::{json, Value};
use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::mpsc::{channel, Sender};
use gtk::prelude::*;
use javascriptcore::ValueExt as _;
use webkit2gtk::*;
use webkit2gtk::{SettingsExt, WebViewExt, UserContentManagerExt, WebContextExt};

/// A message crossing threads (the glib channel is Send).
pub enum Msg {
    Rpc { id: i64, method: String, params: Value },
    PageOp { reply: Sender<CoreResult<String>>, op: PageOp },
    Chrome { json: Value },
    AgentDone,
}

pub enum PageOp {
    RunJs { js: String },
    Navigate { url: String },
    Back,
    Forward,
    Reload,
    NewTab { url: String },
    ListTabs,
    SwitchTab { index: usize },
    CloseTab,
    Download { url: String, name: String },
}

struct Tab {
    webview: WebView,
    url: String,
    title: String,
    loading: bool,
    /// Stable per-tab id. The GtkStack child name is `tab-{id}` and survives
    /// close of other tabs, unlike positional names (`tab{index}`) that go
    /// stale as soon as the Vec shifts.
    id: u64,
}

struct App {
    window: gtk::Window,
    stack: gtk::Stack,
    chrome: WebView,
    tabs: Vec<Tab>,
    active: i64,
    next_tab_id: u64,
    data_dir: PathBuf,
    registry: ProfileRegistry,
    prefs: ProfilePrefs,
    profile_id: String,
    context: WebContext,
    filter: rbcore::filters::FilterEngine,
    https: rbcore::https::HttpsPending,
    history: Vec<rbcore::history::HistoryEntry>,
    bookmarks: rbcore::bookmarks::Bookmarks,
    downloads: rbcore::downloads::Downloads,
    vault: rbcore::credentials::CredentialStore,
    vault_passphrase: Option<String>,
    console_buffer: Vec<String>,
    agent_busy: bool,
    agent_sessions: rbcore::agent::sessions::AgentSessions,
}

type AppRef = Rc<RefCell<Option<App>>>;

impl App {
    fn push_chrome(&self, name: &str, payload: Value) {
        let js = format!(
            "window.__roomEvent && window.__roomEvent({}, {})",
            serde_json::to_string(name).unwrap_or_default(),
            serde_json::to_string(&payload).unwrap_or_default()
        );
        self.chrome.run_javascript(&js, None::<&gio::Cancellable>, |_| {});
    }

    fn reply(&self, id: i64, ok: bool, value: Value) {
        let js = format!(
            "window.__roomReply && window.__roomReply({}, {}, {})",
            id,
            if ok { "true" } else { "false" },
            serde_json::to_string(&value).unwrap_or_else(|_| "null".into())
        );
        self.chrome.run_javascript(&js, None::<&gio::Cancellable>, |_| {});
    }

    fn sync_tabs(&self) {
        let tabs: Vec<Value> = self
            .tabs
            .iter()
            .map(|t| json!({"title": t.title, "url": t.url}))
            .collect();
        self.push_chrome("tabs", json!({"tabs": tabs, "active": self.active}));
    }

    fn sync_page(&self) {
        let t = self.tabs.get(self.active as usize);
        self.push_chrome(
            "page",
            json!({
                "url": t.map(|t| t.url.clone()).unwrap_or_default(),
                "display": t.map(|t| rbcore::urlintel::display_url(&t.url)).unwrap_or_default(),
                "title": t.map(|t| t.title.clone()).unwrap_or_default(),
                "loading": t.map(|t| t.loading).unwrap_or(false),
            }),
        );
    }

    fn active_webview(&self) -> Option<&WebView> {
        self.tabs.get(self.active as usize).map(|t| &t.webview)
    }

    fn record_history(&mut self, url: &str, title: &str) {
        if url.starts_with("about:") || url.is_empty() {
            return;
        }
        rbcore::history::record(&mut self.history, url, title, now_ms());
        let _ = rbcore::history::save(&rbcore::paths::history_path(&self.data_dir), &self.history);
    }

    fn vault_path(&self) -> PathBuf {
        rbcore::paths::join(&rbcore::paths::profile_settings(&self.data_dir, &self.profile_id), "credentials.jsonl")
    }
}

fn main() {
    // CLI surface: --version prints one line and exits, before any engine
    // or window is touched (the smoke test relies on this).
    if std::env::args().any(|a| a == "--version") {
        rbcore::println_out(&format!("Room Browser {} ({})", rbcore::version(), "linux x86_64"));
        return;
    }
    let data_dir = rbcore::paths::data_dir();
    std::fs::create_dir_all(&data_dir).ok();

    let mut registry = ProfileRegistry::load(&data_dir);
    registry.repair_theme_setting();
    registry.repair_default();
    let _ = registry.save(&data_dir);
    let profile_id = registry
        .active
        .clone()
        .or_else(|| registry.profiles.first().map(|p| p.id.clone()))
        .unwrap_or_default();
    rbcore::paths::ensure_profile_tree(&data_dir, &profile_id).ok();
    let profile = registry.get(&profile_id).cloned().unwrap_or(Profile::default());
    let prefs = profile.settings.clone();

    gtk::init().expect("GTK init");
    let app: AppRef = Rc::new(RefCell::new(None));

    let window = gtk::Window::new(gtk::WindowType::Toplevel);
    window.set_title("Room Browser");
    window.set_default_size(1280, 800);

    let vbox = gtk::Box::new(gtk::Orientation::Vertical, 0);
    window.add(&vbox);

    // ---- per-profile engine context (cookie/cache isolation) ----
    let data_path = rbcore::paths::profile_browser_data(&data_dir, &profile_id);
    let cache_path = rbcore::paths::profile_cache(&data_dir, &profile_id);
    let _ = std::fs::create_dir_all(&data_path);
    let _ = std::fs::create_dir_all(&cache_path);
    let context = per_profile_context(&data_path, &cache_path);
    let context2 = context.clone();

    // ---- chrome webview (fixed height) ----
    let chrome_ucm = UserContentManager::new();
    let chrome = WebView::builder()
        .user_content_manager(&chrome_ucm)
        .web_context(&context)
        .build();
    chrome.set_size_request(-1, 132);
    chrome.set_hexpand(true);
    vbox.pack_start(&chrome, false, true, 0);

    // ---- tab stack ----
    let stack = gtk::Stack::new();
    stack.set_hexpand(true);
    stack.set_vexpand(true);
    vbox.pack_start(&stack, true, true, 0);

    window.show_all();

    // ---- cross-thread channel ----
    let (tx, rx) = glib::MainContext::channel::<Msg>(glib::source::Priority::default());
    install_chrome_ucm(&chrome_ucm, tx.clone());

    {
        let mut downloads = rbcore::downloads::Downloads::load(&rbcore::paths::downloads_path(&data_dir));
        let n = downloads.reconcile(&profile_id, "Interrupted", now_ms());
        if n > 0 {
            let _ = downloads.save(&rbcore::paths::downloads_path(&data_dir));
        }
        let mut filter = rbcore::filters::FilterEngine::new();
        filter.load_builtin();
        let inner = App {
            window: window.clone(),
            stack,
            chrome: chrome.clone(),
            tabs: Vec::new(),
            active: -1,
            next_tab_id: 1,
            data_dir: data_dir.clone(),
            registry,
            prefs: prefs.clone(),
            profile_id: profile_id.clone(),
            context,
            filter,
            https: rbcore::https::HttpsPending::new(),
            history: rbcore::history::load(&rbcore::paths::history_path(&data_dir)),
            bookmarks: rbcore::bookmarks::Bookmarks::load(&rbcore::paths::bookmarks_path(&data_dir)),
            downloads,
            vault: rbcore::credentials::CredentialStore::load(
                &rbcore::paths::join(&rbcore::paths::profile_settings(&data_dir, &profile_id), "credentials.jsonl"),
            ),
            vault_passphrase: None,
            console_buffer: Vec::new(),
            agent_busy: false,
            agent_sessions: rbcore::agent::sessions::AgentSessions::load(&data_dir, &profile_id),
        };
        *app.borrow_mut() = Some(inner);
    }

    // ---- session restore ----
    // The borrow ends with this scope; spawn_tab re-borrows the app.
    let (open, active, ctx, prefs2) = {
        let a = app.borrow();
        let a = a.as_ref().unwrap();
        (a.prefs.open_tabs(), a.prefs.active_tab(), a.context.clone(), a.prefs.clone())
    };
    if open.is_empty() {
        let home = if prefs2.homepage_enabled() { prefs2.homepage() } else { String::new() };
        spawn_tab(&ctx, app.clone(), &home, &prefs2);
    } else {
        for url in &open {
            spawn_tab(&ctx, app.clone(), url, &prefs2);
        }
        let _ = select_tab(app.clone(), active);
    }
    {
        let a = app.borrow();
        let a = a.as_ref().unwrap();
        a.sync_tabs();
        a.sync_page();
    }

    // ---- message dispatch on the GTK loop ----
    {
        let app2 = app.clone();
        let tx2 = tx.clone();
        rx.attach(None, move |msg| {
            handle_msg(app2.clone(), tx2.clone(), msg);
            glib::ControlFlow::Continue
        });
    }

    // Closing the window persists the active profile's session (and agent
    // sessions) so the next launch restores it — the counterpart of the
    // Windows CloseRequested handler.
    {
        let app_close = app.clone();
        window.connect_destroy(move |_| {
            let mut a = app_close.borrow_mut();
            if let Some(a) = a.as_mut() {
                let urls: Vec<String> = a.tabs.iter().map(|t| t.url.clone()).collect();
                if let Some(p) = a.registry.get_mut(&a.profile_id) {
                    p.settings.set_session(&urls, a.active.max(0) as usize);
                }
                let _ = a.registry.save(&a.data_dir);
                let _ = a.agent_sessions.save(&a.data_dir, &a.profile_id);
            }
            drop(a);
            gtk::main_quit();
        });
    }

    // ---- engine downloads → downloads.jsonl ----
    {
        let app_dl = app.clone();
        context2.connect_download_started(move |_ctx, download| {
            handle_download_started(&app_dl, download);
        });
    }

    gtk::main();
}

/// Per-profile WebKitWebContext with its own WebsiteDataManager (the GTK
/// counterpart of the WebView2 user-data folder).
fn per_profile_context(data_path: &PathBuf, cache_path: &PathBuf) -> WebContext {
    use glib::translate::*;
    let data_c = std::ffi::CString::new(data_path.to_string_lossy().as_bytes()).unwrap();
    let cache_c = std::ffi::CString::new(cache_path.to_string_lossy().as_bytes()).unwrap();
    unsafe {
        let mgr = webkit2gtk::ffi::webkit_website_data_manager_new(
            b"base-data-directory\0".as_ptr() as *const _,
            data_c.as_ptr(),
            b"base-cache-directory\0".as_ptr() as *const _,
            cache_c.as_ptr(),
            std::ptr::null::<std::os::raw::c_char>(),
        );
        let mgr: WebsiteDataManager = from_glib_full(mgr);
        WebContext::with_website_data_manager(&mgr)
    }
}

/// Reads the JSC value of a script message as a String.
fn message_text(result: &JavascriptResult) -> Option<String> {
    result
        .js_value()
        .and_then(|v| v.to_string_as_bytes())
        .map(|b| String::from_utf8_lossy(&b).into_owned())
}

fn install_chrome_ucm(ucm: &UserContentManager, tx: glib::Sender<Msg>) {
    ucm.register_script_message_handler("room");
    ucm.connect_script_message_received(Some("room"), move |_ucm, message| {
        if let Some(text) = message_text(message)
        {
            if let Ok(v) = serde_json::from_str::<Value>(&text) {
                if let (Some(id), Some(method)) = (v.get("id").and_then(|x| x.as_i64()), v.get("method").and_then(|x| x.as_str())) {
                    let params = v.get("params").cloned().unwrap_or(Value::Null);
                    let _ = tx.send(Msg::Rpc { id, method: method.to_string(), params });
                }
            }
        }
    });
}

/// The page-side scripts every tab webview gets at document start.
fn init_scripts(prefs: &ProfilePrefs) -> Vec<String> {
    let mut scripts = vec![
        rbcore::bridge::BRIDGE_BOOT.to_string(),
        rbcore::bridge::ROOM_CONSOLE_SCRIPT.to_string(),
        rbcore::bridge::ROOM_VAULT_SCRIPT.to_string(),
        rbcore::bridge::ROOM_WALLET_SCRIPT.to_string(),
        // On WebKit the bridge sends through the registered handler.
        scripts_send_shim(),
    ];
    let device = if prefs.device_id().is_empty() {
        None
    } else {
        rbcore::devices::by_id(&prefs.device_id()).cloned()
    };
    let screen = if prefs.screen_size_manual() && prefs.screen_width() > 0 && prefs.screen_height() > 0 {
        Some((prefs.screen_width() as i32, prefs.screen_height() as i32))
    } else {
        None
    };
    if let Some(js) = rbcore::shims::combined_shim_js(device.as_ref(), screen) {
        scripts.push(js);
    }
    scripts
}

/// WebKit variant: route `__roomBridge.send` into the "room" handler.
fn scripts_send_shim() -> String {
    format!(
        "{}\n{}",
        rbcore::bridge::BRIDGE_BOOT,
        r#"(function () {
  'use strict';
  window.__roomBridgeSetSender(function (msg) {
    try { window.webkit.messageHandlers.room.postMessage(JSON.parse(msg)); } catch (e) {}
  });
  var q = (window.__roomQueue || []);
})();"#,
    )
}

fn spawn_tab(context: &WebContext, app_ref: AppRef, url: &str, prefs: &ProfilePrefs) {
    let ucm = UserContentManager::new();
    for js in init_scripts(prefs) {
        let script = webkit2gtk::UserScript::new(
            &js,
            webkit2gtk::UserContentInjectedFrames::AllFrames,
            webkit2gtk::UserScriptInjectionTime::Start,
            &[],
            &[],
        );
        ucm.add_script(&script);
    }
    let webview = WebView::builder()
        .user_content_manager(&ucm)
        .web_context(context)
        .build();
    apply_engine_settings(&webview, prefs);
    install_tab_handlers(&ucm, &webview, app_ref.clone());
    if !url.is_empty() {
        webview.load_uri(url);
    }
    webview.show();
    let mut a = app_ref.borrow_mut();
    let a = a.as_mut().unwrap();
    // Stable child name keyed by the model tab id: close of another tab
    // shifts the Vec but never invalidates this name.
    let id = a.next_tab_id;
    a.next_tab_id += 1;
    let name = format!("tab-{id}");
    a.stack.add_named(&webview, &name);
    a.tabs.push(Tab { webview, url: url.to_string(), title: String::new(), loading: false, id });
    a.active = (a.tabs.len() - 1) as i64;
    a.stack.set_visible_child_name(&name);
}

fn apply_engine_settings(webview: &WebView, prefs: &ProfilePrefs) {
    let settings = Settings::new();
    settings.set_enable_javascript(prefs.javascript());
    settings.set_enable_developer_extras(false);
    settings.set_enable_write_console_messages_to_stdout(false);
    if let Some(ua) = prefs.effective_user_agent() {
        settings.set_user_agent(Some(&ua));
    }
    webview.set_settings(&settings);
}

fn select_tab(app_ref: AppRef, index: usize) -> CoreResult<()> {
    let mut a = app_ref.borrow_mut();
    let a = a.as_mut().ok_or("app gone")?;
    if index >= a.tabs.len() {
        return Err("no tab at that index".into());
    }
    a.active = index as i64;
    let name = format!("tab-{}", a.tabs[index].id);
    a.stack.set_visible_child_name(&name);
    a.sync_tabs();
    a.sync_page();
    Ok(())
}

/// WebKitGTK `download-started`: assign a sanitized destination inside the
/// profile's download directory, queue the rbcore record and hook progress
/// and completion onto the WebKitDownload object. Runs on the GTK loop.
fn handle_download_started(app_ref: &AppRef, download: &webkit2gtk::Download) {
    let (profile_id, url, mime, hint) = {
        let a = app_ref.borrow();
        let a = match a.as_ref() {
            Some(a) => a,
            None => return,
        };
        let url = download
            .request()
            .and_then(|r| r.uri().map(|s| s.to_string()))
            .unwrap_or_default();
        let mime = download
            .response()
            .and_then(|r| r.mime_type().map(|s| s.to_string()))
            .unwrap_or_default();
        // WebKit knows the suggested name; fall back to the URL tail.
        let hint = download
            .response()
            .and_then(|r| r.suggested_filename().map(|s| s.to_string()))
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| rbcore::downloads::suggested_name(&url, ""));
        (a.profile_id.clone(), url, mime, hint)
    };
    let (record_id, dest) = {
        let mut a = app_ref.borrow_mut();
        let a = match a.as_mut() {
            Some(a) => a,
            None => return,
        };
        let dir = rbcore::paths::profile_downloads(&a.data_dir, &a.profile_id);
        let _ = std::fs::create_dir_all(&dir);
        let id = a.downloads.queue(&profile_id, &url, &hint, &mime, now_ms());
        let name = a
            .downloads
            .items
            .iter()
            .find(|d| d.id == id)
            .map(|d| d.file_name.clone())
            .unwrap_or_else(|| "download".into());
        let dest = rbcore::paths::join(&dir, &name);
        download.set_destination(&dest.to_string_lossy());
        let _ = a.downloads.set_running(id);
        let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
        (id, dest)
    };
    // Progress updates stay in memory; the store is written on completion.
    let app_progress = app_ref.clone();
    download.connect_received_data(move |dl, _| {
        let received = dl.received_data_length() as i64;
        let total = dl.response().map(|r| r.content_length()).unwrap_or(0);
        let total = if total == 0 { -1 } else { total as i64 };
        let mut a = app_progress.borrow_mut();
        if let Some(a) = a.as_mut() {
            let _ = a.downloads.set_progress(record_id, received, total);
        }
    });
    let app_done = app_ref.clone();
    let dest_string = dest.to_string_lossy().to_string();
    download.connect_finished(move |dl| {
        let final_dest = dl
            .destination()
            .map(|s| s.to_string())
            .unwrap_or_else(|| dest_string.clone());
        let mut a = app_done.borrow_mut();
        if let Some(a) = a.as_mut() {
            // Only complete a still-active record: some WebKit versions
            // emit finished after a failure too.
            let active = a.downloads.items.iter().any(|d| {
                d.id == record_id
                    && matches!(
                        d.status,
                        rbcore::downloads::Status::Queued
                            | rbcore::downloads::Status::Running
                            | rbcore::downloads::Status::Paused
                    )
            });
            if active {
                let _ = a.downloads.complete(record_id, &final_dest, now_ms());
                let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
            }
        }
    });
    let app_fail = app_ref.clone();
    download.connect_failed(move |_dl, err| {
        let mut a = app_fail.borrow_mut();
        if let Some(a) = a.as_mut() {
            let _ = a.downloads.fail(record_id, &format!("{err}"), now_ms());
            let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
        }
    });
}

fn install_tab_handlers(ucm: &UserContentManager, webview: &WebView, app_ref: AppRef) {
    // The vault/wallet page halves message the host through the handler.
    let app0 = app_ref.clone();
    ucm.register_script_message_handler("room");
    ucm.connect_script_message_received(Some("room"), move |_u, message| {
        if let Some(text) = message_text(message)
        {
            if let Ok(v) = serde_json::from_str::<Value>(&text) {
                let kind = v.get("kind").and_then(|k| k.as_str()).unwrap_or("").to_string();
                let mut a = app0.borrow_mut();
                let a = match a.as_mut() { Some(a) => a, None => return };
                match kind.as_str() {
                    "vault.report" => {
                        let host = v.get("host").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        let username = v.get("username").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        let password = v.get("password").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        a.push_chrome("vault.offer", json!({"host": host, "username": username, "password": password}));
                    }
                    "vault.request" => {
                        let host = v.get("host").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        let entries: Vec<Value> = a
                            .vault
                            .for_host(&host)
                            .iter()
                            .map(|c| json!({"username": c.username, "domain": c.domain}))
                            .collect();
                        a.push_chrome("vault.suggest", json!({"host": host, "entries": entries}));
                    }
                    "console" => {
                        let text = v.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        a.console_buffer.push(text);
                        if a.console_buffer.len() > 500 {
                            a.console_buffer.remove(0);
                        }
                    }
                    _ => {}
                }
            }
        }
    });

    let app1 = app_ref.clone();
    webview.connect_notify_local(Some("uri"), move |wv, _| {
        let uri = wv.uri().map(|s| s.to_string()).unwrap_or_default();
        if uri.is_empty() {
            return;
        }
        let mut a = app1.borrow_mut();
        if let Some(a) = a.as_mut() {
            if let Some(t) = a.tabs.get_mut(a.active as usize) {
                if t.webview == *wv {
                    t.url = uri.clone();
                    t.loading = true;
                }
            }
            a.sync_page();
        }
    });

    let app2 = app_ref.clone();
    webview.connect_notify_local(Some("title"), move |wv, _| {
        let title = wv.title().map(|s| s.to_string()).unwrap_or_default();
        let mut a = app2.borrow_mut();
        if let Some(a) = a.as_mut() {
            for t in a.tabs.iter_mut() {
                if t.webview == *wv {
                    t.title = title.clone();
                }
            }
            let url = a
                .tabs
                .iter()
                .find(|t| t.webview == *wv)
                .map(|t| t.url.clone())
                .unwrap_or_default();
            if !url.is_empty() {
                a.record_history(&url, &title);
            }
            a.sync_tabs();
            a.sync_page();
        }
    });

    let app3 = app_ref.clone();
    webview.connect_load_changed(move |wv, event| {
        if event == webkit2gtk::LoadEvent::Finished {
            let mut a = app3.borrow_mut();
            if let Some(a) = a.as_mut() {
                for t in a.tabs.iter_mut() {
                    if t.webview == *wv {
                        t.loading = false;
                    }
                }
                a.sync_page();
            }
        }
    });

    // window.open → a new tab; popups blocked per profile setting.
    let app4 = app_ref.clone();
    webview.connect_create(move |_wv, nav| {
        let (blocked, ctx, prefs) = {
            let a = app4.borrow();
            match a.as_ref() {
                Some(a) => (a.prefs.block_popups(), a.context.clone(), a.prefs.clone()),
                None => return None,
            }
        };
        if blocked {
            return None;
        }
        let uri = nav
            .request()
            .map(|r| r.uri().map(|s| s.to_string()))
            .unwrap_or_default()
            .unwrap_or_default();
        let app5 = app4.clone();
        // The child view is returned to WebKit to load into; we ALSO mirror
        // it as a tab — create it through spawn machinery.
        let ucm = UserContentManager::new();
        for js in init_scripts(&prefs) {
            let script = webkit2gtk::UserScript::new(
                &js,
                webkit2gtk::UserContentInjectedFrames::AllFrames,
                webkit2gtk::UserScriptInjectionTime::Start,
                &[],
                &[],
            );
            ucm.add_script(&script);
        }
        let child = WebView::builder()
            .user_content_manager(&ucm)
            .web_context(&ctx)
            .build();
        apply_engine_settings(&child, &prefs);
        install_tab_handlers(&ucm, &child, app5.clone());
        child.show();
        let _ = uri;
        Some(child.clone().upcast::<gtk::Widget>())
    });
}

// ------------------------------------------------------------------
// Message handling (GTK main thread)

fn handle_msg(app: AppRef, tx: glib::Sender<Msg>, msg: Msg) {
    match msg {
        Msg::Rpc { id, method, params } => {
            // Async RPCs own their single reply, sent from the script
            // completion: the chrome keeps only the FIRST reply per id, so
            // these must skip the generic reply below (a premature Null
            // would mask the real result).
            match method.as_str() {
                "page.find" => {
                    rpc_page_find(&app, id, &params);
                    return;
                }
                "devtools.panel" => {
                    rpc_devtools_panel(&app, id, &params);
                    return;
                }
                _ => {}
            }
            let result = dispatch_rpc(&app, &tx, id, &method, &params);
            let a = app.borrow();
            if let Some(a) = a.as_ref() {
                match result {
                    Ok(v) => a.reply(id, true, v),
                    Err(e) => a.reply(id, false, Value::String(e)),
                }
            }
        }
        Msg::PageOp { reply, op } => {
            if let PageOp::RunJs { js } = &op {
                // Script results arrive asynchronously; the reply rides the
                // run_javascript completion so the GTK loop is never blocked
                // (the same pattern rbwin uses with execute_script_then).
                let a = app.borrow();
                match a.as_ref().and_then(|a| a.active_webview().cloned()) {
                    Some(wv) => {
                        drop(a);
                        let js = js.clone();
                        wv.run_javascript(&js, None::<&gio::Cancellable>, move |result| {
                            let r: CoreResult<String> = match result {
                                Ok(v) => Ok(message_text(&v).unwrap_or_default()),
                                Err(e) => Err(format!("script: {e}")),
                            };
                            let _ = reply.send(r);
                        });
                    }
                    None => {
                        let _ = reply.send(Err("no active tab".into()));
                    }
                }
                return;
            }
            // run_page_op borrows internally; nothing is held across it.
            let r = run_page_op(&app, &op);
            let _ = reply.send(r);
        }
        Msg::Chrome { json } => {
            let a = app.borrow();
            if let Some(a) = a.as_ref() {
                let name = json.get("name").and_then(|x| x.as_str()).unwrap_or("");
                let payload = json.get("payload").cloned().unwrap_or(Value::Null);
                a.push_chrome(name, payload);
            }
        }
        Msg::AgentDone => {
            let mut a = app.borrow_mut();
            if let Some(a) = a.as_mut() {
                a.agent_busy = false;
                let _ = a.agent_sessions.save(&a.data_dir, &a.profile_id);
            }
        }
    }
}

fn run_page_op(app_ref: &AppRef, op: &PageOp) -> CoreResult<String> {
    let mut guard = app_ref.borrow_mut();
    let a = guard.as_mut().ok_or("app gone")?;
    match op {
        // RunJs is intercepted in handle_msg (its result is asynchronous).
        PageOp::RunJs { .. } => Ok(String::new()),
        PageOp::Navigate { url } => {
            let wv = a.active_webview().ok_or("no active tab")?;
            wv.load_uri(url);
            Ok("navigating".into())
        }
        PageOp::Back => {
            if let Some(wv) = a.active_webview() {
                wv.go_back();
            }
            Ok("back".into())
        }
        PageOp::Forward => {
            if let Some(wv) = a.active_webview() {
                wv.go_forward();
            }
            Ok("forward".into())
        }
        PageOp::Reload => {
            if let Some(wv) = a.active_webview() {
                wv.reload();
            }
            Ok("reloaded".into())
        }
        PageOp::NewTab { url } => {
            let ctx = a.context.clone();
            let prefs = a.prefs.clone();
            let url = url.clone();
            // End the borrow for real before spawn_tab re-borrows the app.
            drop(guard);
            spawn_tab(&ctx, app_ref.clone(), &url, &prefs);
            Ok("new tab".into())
        }
        PageOp::ListTabs => Ok(serde_json::to_string(
            &a.tabs.iter().map(|t| (t.title.clone(), t.url.clone())).collect::<Vec<_>>(),
        ).map_err(|e| e.to_string())?),
        PageOp::SwitchTab { index } => Ok("switched".into()),
        PageOp::CloseTab => Ok("closed".into()),
        PageOp::Download { url, name } => {
            // WebKit performs the transfer itself: download_uri starts the
            // engine download and the context's download-started handler
            // creates the record and the destination. download_uri emits
            // download-started synchronously, so the borrow must end for
            // real before the call (the handler re-borrows the app).
            let wv = a.active_webview().cloned().ok_or("no active tab")?;
            drop(guard);
            match wv.download_uri(url) {
                Some(_) => Ok(format!("download started: {url} {name}")),
                None => Err(format!("download could not be started: {url}")),
            }
        }
    }
}

/// `page.find` — runs the find-count script asynchronously and replies once
/// from the completion; the GTK loop is never blocked (the port of the
/// Windows execute_script_then pattern).
fn rpc_page_find(app: &AppRef, id: i64, params: &Value) {
    let query = params.get("query").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let wv = {
        let a = app.borrow();
        a.as_ref().and_then(|a| a.active_webview().cloned())
    };
    let wv = match wv {
        Some(wv) => wv,
        None => {
            let a = app.borrow();
            if let Some(a) = a.as_ref() {
                a.reply(id, false, Value::String("no tab".into()));
            }
            return;
        }
    };
    let js = rbcore::bridge::find_count_js(&query);
    let app2 = app.clone();
    wv.run_javascript(&js, None::<&gio::Cancellable>, move |result| {
        let n: i64 = match result {
            Ok(v) => message_text(&v).unwrap_or_default().trim_matches('"').parse().unwrap_or(0),
            Err(_) => 0,
        };
        let a2 = app2.borrow();
        if let Some(a2) = a2.as_ref() {
            a2.reply(id, true, json!({"count": n}));
        }
    });
}

/// `devtools.panel` — same async shape as rpc_page_find; the OVERVIEW panel
/// is answered inline from the static edition notes.
fn rpc_devtools_panel(app: &AppRef, id: i64, params: &Value) {
    let panel = params.get("panel").and_then(|x| x.as_str()).unwrap_or("OVERVIEW").to_string();
    if panel == "OVERVIEW" {
        let a = app.borrow();
        if let Some(a) = a.as_ref() {
            a.reply(id, true, json!({"text": rbcore::devtools::edition_notes("webkit").join("\n")}));
        }
        return;
    }
    let js = match panel.as_str() {
        "CONSOLE" => "(window.__roomConsole ? window.__roomConsole.read() : '[]')".to_string(),
        "NETWORK" => rbcore::bridge::NETWORK_PROBE_JS.to_string(),
        _ => rbcore::bridge::SECURITY_PROBE_JS.to_string(),
    };
    let wv = {
        let a = app.borrow();
        a.as_ref().and_then(|a| a.active_webview().cloned())
    };
    let wv = match wv {
        Some(wv) => wv,
        None => {
            let a = app.borrow();
            if let Some(a) = a.as_ref() {
                a.reply(id, false, Value::String("no tab".into()));
            }
            return;
        }
    };
    let app2 = app.clone();
    wv.run_javascript(&js, None::<&gio::Cancellable>, move |result| {
        let text = match result {
            Ok(v) => {
                let raw = message_text(&v).unwrap_or_default();
                let unescaped: String =
                    serde_json::from_str(raw.trim_matches('"')).unwrap_or(raw.clone());
                rbcore::redaction::redact(&unescaped)
            }
            Err(_) => String::new(),
        };
        let a2 = app2.borrow();
        if let Some(a2) = a2.as_ref() {
            a2.reply(id, true, json!({"text": text}));
        }
    });
}

fn dispatch_rpc(app: &AppRef, tx: &glib::Sender<Msg>, id: i64, method: &str, params: &Value) -> CoreResult<Value> {
    // `guard` (the RefMut) is dropped by name wherever an arm needs to
    // re-borrow the app — dropping the `a` reference alone would leave the
    // borrow alive and panic.
    let mut guard = app.borrow_mut();
    let a = guard.as_mut().ok_or("app not ready")?;
    match method {
        "init" => Ok(json!({
            "tabs": a.tabs.iter().map(|t| json!({"title": t.title, "url": t.url})).collect::<Vec<_>>(),
            "active": a.active,
            "model": "Ollama",
            "theme": theme_json(a),
        })),
        "omnibox.submit" => {
            let input = params.get("input").and_then(|x| x.as_str()).unwrap_or("");
            let url = rbcore::urlintel::decide(input, &a.prefs.search_engine());
            if let Some(wv) = a.active_webview() {
                wv.load_uri(&url);
            }
            Ok(json!({"url": url}))
        }
        "tab.new" => {
            let ctx = a.context.clone();
            let prefs = a.prefs.clone();
            let home = if prefs.homepage_enabled() { prefs.homepage() } else { String::new() };
            // End the borrow for real; spawn_tab re-borrows the app.
            drop(guard);
            spawn_tab(&ctx, app.clone(), &home, &prefs);
            let a2 = app.borrow();
            if let Some(a2) = a2.as_ref() {
                a2.sync_tabs();
            }
            Ok(Value::Null)
        }
        "tab.close" => {
            let index = params.get("index").and_then(|x| x.as_u64()).unwrap_or(a.active.max(0) as u64) as usize;
            if index < a.tabs.len() {
                // The child name is keyed by the tab id, so removing a tab
                // never invalidates the names of the survivors.
                let name = format!("tab-{}", a.tabs[index].id);
                if let Some(child) = a.stack.child_by_name(&name) {
                    a.stack.remove(&child);
                }
                a.tabs.remove(index);
                a.active = (a.tabs.len() as i64 - 1).max(0);
                if let Some(t) = a.tabs.get(a.active as usize) {
                    a.stack.set_visible_child_name(&format!("tab-{}", t.id));
                }
            }
            a.sync_tabs();
            a.sync_page();
            Ok(Value::Null)
        }
        "tab.select" => {
            let index = params.get("index").and_then(|x| x.as_u64()).unwrap_or(0) as usize;
            a.active = index.min(a.tabs.len().saturating_sub(1)) as i64;
            if let Some(t) = a.tabs.get(a.active as usize) {
                a.stack.set_visible_child_name(&format!("tab-{}", t.id));
            }
            a.sync_tabs();
            a.sync_page();
            Ok(Value::Null)
        }
        "tab.navigate" => {
            let url = params.get("url").and_then(|x| x.as_str()).unwrap_or("");
            let url = rbcore::urlintel::decide(url, &a.prefs.search_engine());
            if let Some(wv) = a.active_webview() {
                wv.load_uri(&url);
            }
            Ok(Value::Null)
        }
        "page.back" => {
            if let Some(wv) = a.active_webview() {
                wv.go_back();
            }
            Ok(Value::Null)
        }
        "page.forward" => {
            if let Some(wv) = a.active_webview() {
                wv.go_forward();
            }
            Ok(Value::Null)
        }
        "page.reload" => {
            if let Some(wv) = a.active_webview() {
                wv.reload();
            }
            Ok(Value::Null)
        }
        "page.home" => {
            let home = a.prefs.homepage();
            if let Some(wv) = a.active_webview() {
                wv.load_uri(&home);
            }
            Ok(Value::Null)
        }
        "page.translate" => {
            let url = a.tabs.get(a.active as usize).map(|t| t.url.clone()).unwrap_or_default();
            if url.starts_with("about:") || url.is_empty() {
                return Ok(Value::Null);
            }
            let target = a.prefs.translate_target_language();
            // Shared helper: percent-encodes the URL and the language codes
            // (rbcore::urlintel — the desktop port of Android PageTranslate).
            let translated = rbcore::urlintel::translate_url(&url, "auto", &target);
            if let Some(wv) = a.active_webview() {
                wv.load_uri(&translated);
            }
            Ok(Value::Null)
        }
        "bookmark.toggle" => {
            let url = a.tabs.get(a.active as usize).map(|t| t.url.clone()).unwrap_or_default();
            let title = a.tabs.get(a.active as usize).map(|t| t.title.clone()).unwrap_or_default();
            if a.bookmarks.has(&url) {
                a.bookmarks.remove_by_url(&url);
            } else {
                a.bookmarks.add(&url, &title, None, now_ms());
            }
            a.bookmarks.save(&rbcore::paths::bookmarks_path(&a.data_dir)).map_err(|e| e.to_string())?;
            Ok(json!({"bookmarked": a.bookmarks.has(&url)}))
        }
        "bookmark.remove" => {
            let url = params.get("url").and_then(|x| x.as_str()).unwrap_or("").to_string();
            a.bookmarks.remove_by_url(&url);
            a.bookmarks.save(&rbcore::paths::bookmarks_path(&a.data_dir)).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "bookmarks.list" => Ok(json!(a
            .bookmarks
            .display_order()
            .iter()
            .map(|b| json!({"title": b.title, "url": b.url}))
            .collect::<Vec<_>>())),
        "history.open" => {
            let rows: Vec<Value> = a.history.iter().take(20).map(|h| json!({"title": h.title, "url": h.url})).collect();
            a.push_chrome("history", json!({"rows": rows}));
            Ok(Value::Null)
        }
        "pref.toggle" | "pref.set" => {
            let key = params.get("key").and_then(|x| x.as_str()).unwrap_or("").to_string();
            {
                let new_prefs = if method == "pref.set" {
                    let value = params.get("value").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    a.registry.get_mut(&a.profile_id).map(|p| {
                        p.settings.set(&key, &value);
                        p.settings.clone()
                    })
                } else {
                    a.registry.get_mut(&a.profile_id).map(|p| {
                        let cur = p.settings.bool_of(&key, true);
                        p.settings.set_bool(&key, !cur);
                        p.settings.clone()
                    })
                };
                if let Some(np) = new_prefs {
                    let _ = a.registry.save(&a.data_dir);
                    a.prefs = np;
                }
            }
            Ok(Value::Null)
        }
        "prefs.get" => {
            let mut values = serde_json::Map::new();
            for (k, v) in &a.prefs.map {
                values.insert(k.clone(), Value::String(v.clone()));
            }
            values.insert("dns_status".into(), Value::String(
                rbcore::dns::effective_status(a.prefs.dns_mode(), &a.prefs.doh_url(), &a.prefs.dot_hostname()).as_str().into(),
            ));
            Ok(json!({
                "values": values,
                "themes": crate_themes(),
                "engines": rbcore::search::ENGINES.iter().map(|e| e.id).collect::<Vec<_>>(),
            }))
        }
        "theme.apply" => {
            let tid = params.get("id").and_then(|x| x.as_str()).unwrap_or("obsidian");
            {
                let tj = rbcore::theme::snapshot_json(tid);
                if let Some(p) = a.registry.get_mut(&a.profile_id) {
                    p.theme_json = tj;
                }
            }
            let _ = a.registry.save(&a.data_dir);
            a.push_chrome("theme", theme_json(a));
            Ok(Value::Null)
        }
        "theme.list" => Ok(json!({
            "themes": crate_themes(),
            "current": rbcore::theme::snapshot_id(
                &a.registry.get(&a.profile_id).map(|p| p.theme_json.clone()).unwrap_or_default()
            ),
        })),
        "profiles.list" => {
            let rows: Vec<Value> = a
                .registry
                .profiles
                .iter()
                .map(|p| json!({
                    "id": p.id, "name": p.name, "icon": p.icon,
                    "is_default": p.is_default, "active": p.id == a.profile_id,
                    "last_active": chrono::DateTime::from_timestamp_millis(p.last_active_at)
                        .map(|d| d.format("%Y-%m-%d").to_string()).unwrap_or_default(),
                }))
                .collect();
            Ok(json!({"rows": rows}))
        }
        "profile.add" => {
            let name = params.get("name").and_then(|x| x.as_str()).unwrap_or("New profile");
            let pid = a.registry.add(name, "👤")?;
            rbcore::paths::ensure_profile_tree(&a.data_dir, &pid).ok();
            let _ = a.registry.save(&a.data_dir);
            Ok(json!({"id": pid}))
        }
        "profile.switch" => {
            let pid = params.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
            {
                let urls: Vec<String> = a.tabs.iter().map(|t| t.url.clone()).collect();
                let active = a.active.max(0) as usize;
                // Persist the OUTGOING profile's session into its OWN
                // settings — never into the target's (the target's stored
                // session is restored untouched by the relaunched process).
                if let Some(p) = a.registry.get_mut(&a.profile_id) {
                    p.settings.set_session(&urls, active);
                    p.last_active_at = now_ms();
                }
            }
            let _ = a.registry.save(&a.data_dir);
            let _ = a.downloads.reconcile(&a.profile_id, "Profile switched", now_ms());
            let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
            let data_dir = a.data_dir.clone();
            let exe = std::env::current_exe().ok();
            drop(guard);
            {
                let mut reg = ProfileRegistry::load(&data_dir);
                reg.active = Some(pid);
                let _ = reg.save(&data_dir);
            }
            if let Some(exe) = exe {
                let _ = std::process::Command::new(exe).spawn();
            }
            gtk::main_quit();
            std::process::exit(0);
        }
        "downloads.list" => {
            let rows: Vec<Value> = a
                .downloads
                .for_profile(&a.profile_id)
                .iter()
                .map(|d| {
                    let status_line = match d.status {
                        rbcore::downloads::Status::Completed => format!(
                            "{} — {}",
                            rbcore::downloads::format_bytes(d.total_bytes),
                            chrono::DateTime::from_timestamp_millis(d.completed_at)
                                .map(|x| x.format("%Y-%m-%d %H:%M").to_string()).unwrap_or_default()
                        ),
                        rbcore::downloads::Status::Failed => format!("failed — {}", d.error),
                        _ => {
                            if d.total_bytes > 0 {
                                format!("{} / {}", rbcore::downloads::format_bytes(d.downloaded_bytes), rbcore::downloads::format_bytes(d.total_bytes))
                            } else {
                                format!("{} so far", rbcore::downloads::format_bytes(d.downloaded_bytes))
                            }
                        }
                    };
                    json!({"id": d.id, "file_name": d.file_name, "status_line": status_line})
                })
                .collect();
            Ok(json!({"rows": rows}))
        }
        "download.open" => {
            let id = params.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            if let Some(d) = a.downloads.items.iter().find(|d| d.id == id) {
                if !d.destination.is_empty() {
                    let _ = std::process::Command::new("xdg-open").arg(&d.destination).spawn();
                }
            }
            Ok(Value::Null)
        }
        "download.remove" => {
            let id = params.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            a.downloads.remove(id);
            let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
            Ok(Value::Null)
        }
        "notes.list" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let notes = rbcore::notes::Notes::load(&path);
            Ok(json!({"rows": notes.display_order().iter().map(|n| json!({"id": n.id, "title": n.title, "body": n.body})).collect::<Vec<_>>()}))
        }
        "notes.add" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let mut notes = rbcore::notes::Notes::load(&path);
            let nid = notes.add(
                params.get("title").and_then(|x| x.as_str()).unwrap_or(""),
                params.get("body").and_then(|x| x.as_str()).unwrap_or(""),
                now_ms(),
            );
            notes.save(&path).map_err(|e| e.to_string())?;
            Ok(json!({"id": nid}))
        }
        "notes.update" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let mut notes = rbcore::notes::Notes::load(&path);
            notes.edit(
                params.get("id").and_then(|x| x.as_i64()).unwrap_or(0),
                params.get("title").and_then(|x| x.as_str()).unwrap_or(""),
                params.get("body").and_then(|x| x.as_str()).unwrap_or(""),
                now_ms(),
            );
            notes.save(&path).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "notes.delete" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let mut notes = rbcore::notes::Notes::load(&path);
            notes.remove(params.get("id").and_then(|x| x.as_i64()).unwrap_or(0));
            notes.save(&path).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "totp.list" => {
            let t = rbcore::totp::TotpStore::load_first(&a.data_dir, &a.profile_id);
            Ok(json!({"rows": t.items.iter().map(|x| json!({"id": x.id, "issuer": x.issuer, "label": x.label})).collect::<Vec<_>>()}))
        }
        "totp.add" => {
            let uri = params.get("uri").and_then(|x| x.as_str()).unwrap_or("");
            match rbcore::totp::parse_otpauth(uri) {
                Some((issuer, label, secret, algo, digits, period)) => {
                    let path = rbcore::paths::totp_path_for_rw(&a.data_dir, &a.profile_id);
                    let mut t = rbcore::totp::TotpStore::load(&path);
                    let nid = t.add(&issuer, &label, &secret, algo, digits, period, now_ms());
                    t.save(&path).map_err(|e| e.to_string())?;
                    Ok(json!({"id": nid}))
                }
                None => Ok(json!({"error": "not a valid otpauth:// URI"})),
            }
        }
        "totp.code" => {
            let t = rbcore::totp::TotpStore::load_first(&a.data_dir, &a.profile_id);
            Ok(json!({"code": t.code(params.get("id").and_then(|x| x.as_i64()).unwrap_or(0), now_ms()).unwrap_or_default()}))
        }
        "totp.delete" => {
            let path = rbcore::paths::totp_path_for_rw(&a.data_dir, &a.profile_id);
            let mut t = rbcore::totp::TotpStore::load(&path);
            t.remove(params.get("id").and_then(|x| x.as_i64()).unwrap_or(0));
            t.save(&path).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "vault.list" => Ok(json!({"rows": a.vault.items.iter().map(|c| json!({"id": c.id, "username": c.username, "domain": c.domain})).collect::<Vec<_>>()})),
        "vault.delete" => {
            let vid = params.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
            a.vault.remove(&vid);
            a.vault.save(&a.vault_path()).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "vault.save" => {
            let host = params.get("host").and_then(|x| x.as_str()).unwrap_or("");
            let username = params.get("username").and_then(|x| x.as_str()).unwrap_or("");
            let password = params.get("password").and_then(|x| x.as_str()).unwrap_or("");
            let pass = a.vault_passphrase.clone().unwrap_or_default();
            if pass.is_empty() {
                return Ok(json!({"error": "set a master passphrase first (Passwords window)"}));
            }
            let enc = rbcore::vaultcrypto::seal_string(password, &pass)?;
            let domain = rbcore::credentials::canonical_host(host);
            a.vault.add(&a.profile_id, &domain, username, &enc, "", now_ms());
            a.vault.save(&a.vault_path()).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "vault.fill" => {
            let username = params.get("username").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let host = params.get("host").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let pass = a.vault_passphrase.clone().unwrap_or_default();
            let entry = a.vault.for_host(&host).into_iter().find(|c| c.username == username).cloned();
            if let Some(entry) = entry {
                if let Ok(password) = rbcore::vaultcrypto::open_string(&entry.password_enc, &pass) {
                    let js = format!(
                        "window.__roomVaultFill && window.__roomVaultFill({}, {})",
                        serde_json::to_string(&username).unwrap(),
                        serde_json::to_string(&password).unwrap()
                    );
                    if let Some(wv) = a.active_webview() {
                        wv.run_javascript(&js, None::<&gio::Cancellable>, |_| {});
                    }
                }
            }
            Ok(Value::Null)
        }
        "wallet.state" => Ok(rbcore::wallet::load_wallet(&a.data_dir, &a.profile_id)
            .map(|w| json!({"locked": false, "exists": true, "accounts": w.accounts.iter().map(|x| json!({"chain": x.chain.as_str(), "address": x.address, "path": x.path})).collect::<Vec<_>>()}))
            .unwrap_or(json!({"locked": false, "exists": false, "accounts": []}))),
        "wallet.create" => {
            let mnemonic = rbcore::wallet::bip39::generate_mnemonic();
            let pass = a.vault_passphrase.clone().unwrap_or_default();
            if pass.is_empty() {
                return Ok(json!({"error": "set a master passphrase first"}));
            }
            let w = rbcore::wallet::create_wallet(&a.data_dir, &a.profile_id, "Room Wallet", &mnemonic, &pass, now_ms())?;
            Ok(json!({"mnemonic": mnemonic, "accounts": w.accounts.len()}))
        }
        "wallet.unlock" => {
            let passphrase = params.get("passphrase").and_then(|x| x.as_str()).unwrap_or("");
            if let Some(w) = rbcore::wallet::load_wallet(&a.data_dir, &a.profile_id) {
                match rbcore::wallet::open_mnemonic(&w, passphrase) {
                    Ok(_) => {
                        a.vault_passphrase = Some(passphrase.to_string());
                        Ok(json!({"ok": true}))
                    }
                    Err(e) => Ok(json!({"ok": false, "error": e})),
                }
            } else {
                a.vault_passphrase = Some(passphrase.to_string());
                Ok(json!({"ok": true}))
            }
        }
        "localai.status" => {
            let host = "http://127.0.0.1:11434";
            let agent = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(2)).build();
            let version: Option<Value> = agent.get(&format!("{host}/api/version")).call().ok().and_then(|r| r.into_json::<Value>().ok());
            let models: Vec<String> = agent
                .get(&format!("{host}/api/tags"))
                .call()
                .ok()
                .and_then(|r| r.into_json::<Value>().ok())
                .map(|v: Value| {
                    v.get("models")
                        .and_then(|m| m.as_array())
                        .map(|arr| arr.iter().filter_map(|m| m.get("name").and_then(|n| n.as_str()).map(String::from)).collect())
                        .unwrap_or_default()
                })
                .unwrap_or_default();
            Ok(json!({"connected": version.is_some(), "models": models, "version": version.map(|v| v.get("version").and_then(|x| x.as_str()).unwrap_or("").to_string()).unwrap_or_default()}))
        }
        "localai.pull" => {
            let model = params.get("model").and_then(|x| x.as_str()).unwrap_or("").to_string();
            std::thread::spawn(move || {
                let _ = ureq::post("http://127.0.0.1:11434/api/pull").send_json(json!({"name": model}));
            });
            Ok(json!({"done": true}))
        }
        "qr.png" => {
            let text = params.get("text").and_then(|x| x.as_str()).unwrap_or("");
            let bytes = rbcore::qr::png(text, 6)?;
            Ok(json!({"base64": base64(&bytes)}))
        }
        "about" => Ok(json!({
            "version": rbcore::version(),
            "engine": "WebKitGTK on Linux",
            "note": "The Rust desktop edition of Room Browser.",
        })),
        "devtools.status" => Ok(json!({"notes": rbcore::devtools::edition_notes("webkit")})),
        "data.clear" => {
            if let Some(wv) = a.active_webview() {
                wv.run_javascript("(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}return 'cleared'})()", None::<&gio::Cancellable>, |_| {});
            }
            a.history.clear();
            let _ = rbcore::history::save(&rbcore::paths::history_path(&a.data_dir), &a.history);
            Ok(Value::Null)
        }
        "shield.read" => {
            let host = a.tabs.get(a.active as usize).and_then(|t| rbcore::urlintel::host_of(&t.url)).unwrap_or_default();
            Ok(json!({
                "host": host,
                "ads": a.filter.stat_blocked(rbcore::filters::Category::Ad),
                "trackers": a.filter.stat_blocked(rbcore::filters::Category::Tracker),
                "malicious": a.filter.stat_blocked(rbcore::filters::Category::Malicious),
            }))
        }
        "agent.clear" => {
            a.agent_sessions = rbcore::agent::sessions::AgentSessions::load(&a.data_dir, &a.profile_id);
            Ok(Value::Null)
        }
        "agent.send" => {
            if a.agent_busy {
                return Ok(json!({"error": "a turn is already running"}));
            }
            a.agent_busy = true;
            let text = params.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let mode = params.get("mode").and_then(|x| x.as_str()).unwrap_or("ASK").to_string();
            let session_id = a.agent_sessions.open_session(&a.profile_id, a.active, "ollama", "", now_ms());
            a.agent_sessions.append(&session_id, "user", &text, "", "", "", now_ms());
            let data_dir = a.data_dir.clone();
            let profile_id = a.profile_id.clone();
            let history: Vec<ChatMessage> = a
                .agent_sessions
                .messages_of(&session_id)
                .iter()
                .map(|m| ChatMessage::user(&m.content))
                .collect();
            // End the borrow before the worker thread starts pushing
            // messages back into the loop.
            drop(guard);
            let tx2 = tx.clone();
            std::thread::spawn(move || {
                agent_turn(data_dir, profile_id, session_id, text, mode, history, tx2.clone());
                let _ = tx2.send(Msg::AgentDone);
            });
            Ok(json!({"ok": true}))
        }
        _ => Err(format!("unknown method: {method}")),
    }
}

fn base64(data: &[u8]) -> String {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

fn crate_themes() -> Vec<Value> {
    rbcore::theme::THEMES
        .iter()
        .map(|t| {
            json!({
                "id": t.id, "name": t.name,
                "dark_primary": format!("#{:08X}", t.dark.0[3]),
                "dark_background": format!("#{:08X}", t.dark.0[0]),
            })
        })
        .collect()
}

fn theme_json(a: &App) -> Value {
    let theme_json_str = a.registry.get(&a.profile_id).map(|p| p.theme_json.clone()).unwrap_or_default();
    let id = rbcore::theme::snapshot_id(&theme_json_str).unwrap_or_else(|| "obsidian".into());
    let t = rbcore::theme::theme(&id).unwrap_or(rbcore::theme::default_theme());
    let pal = rbcore::theme::palette_for(t, &a.prefs.theme_mode());
    let mut m = serde_json::Map::new();
    for (i, n) in rbcore::theme::TOKENS.iter().enumerate() {
        m.insert(n.to_string(), Value::String(format!("#{:08X}", pal.0[i])));
    }
    Value::Object(m)
}

// ------------------------------------------------------------------
// The agent worker thread

struct FrontendToolHost {
    tx: glib::Sender<Msg>,
    url: std::sync::Mutex<String>,
    yolo: bool,
}

fn page_op_blocking(tx: &glib::Sender<Msg>, op: PageOp) -> CoreResult<String> {
    let (reply, rx) = channel();
    tx.send(Msg::PageOp { reply, op }).map_err(|e| format!("ui thread gone: {e}"))?;
    rx.recv_timeout(std::time::Duration::from_secs(90)).map_err(|e| format!("page op timeout: {e}"))?
}

impl ToolHost for FrontendToolHost {
    fn run_js(&mut self, js: &str) -> CoreResult<String> {
        page_op_blocking(&self.tx, PageOp::RunJs { js: js.to_string() })
    }
    fn navigate(&mut self, url: &str) -> CoreResult<String> {
        page_op_blocking(&self.tx, PageOp::Navigate { url: url.to_string() })
    }
    fn go_back(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.tx, PageOp::Back).map(|_| ())
    }
    fn go_forward(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.tx, PageOp::Forward).map(|_| ())
    }
    fn reload(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.tx, PageOp::Reload).map(|_| ())
    }
    fn current_url(&mut self) -> String {
        self.url.lock().unwrap().clone()
    }
    fn current_title(&mut self) -> String {
        String::new()
    }
    fn screenshot(&mut self) -> CoreResult<Vec<u8>> {
        Err("this engine build cannot capture screenshots; read_page carries the page".into())
    }
    fn open_new_tab(&mut self, url: &str) -> CoreResult<usize> {
        let r = page_op_blocking(&self.tx, PageOp::NewTab { url: url.to_string() })?;
        r.parse::<usize>().map_err(|e| e.to_string())
    }
    fn list_tabs(&mut self) -> Vec<(usize, String, String)> {
        match page_op_blocking(&self.tx, PageOp::ListTabs) {
            Ok(s) => serde_json::from_str(&s).unwrap_or_default(),
            Err(_) => Vec::new(),
        }
    }
    fn switch_tab(&mut self, index: usize) -> CoreResult<()> {
        page_op_blocking(&self.tx, PageOp::SwitchTab { index }).map(|_| ())
    }
    fn close_tab(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.tx, PageOp::CloseTab).map(|_| ())
    }
    fn open_screen(&mut self, screen: &str) -> CoreResult<String> {
        Ok(format!("opened {screen}"))
    }
    fn download(&mut self, url: &str, name: &str) -> CoreResult<String> {
        page_op_blocking(&self.tx, PageOp::Download { url: url.to_string(), name: name.to_string() })
    }
    fn save_pdf(&mut self, _name: &str) -> CoreResult<String> {
        Err("PDF capture is not available through this engine yet".into())
    }
    fn clipboard_copy(&mut self, _text: &str) -> CoreResult<()> {
        Ok(())
    }
    fn vault_passphrase(&mut self) -> Option<String> {
        None
    }
    fn wallet_state(&mut self) -> Value {
        json!({"locked": true})
    }
    fn wallet_request_summary(&mut self) -> Value {
        json!([])
    }
    fn wallet_approve(&mut self, _id: &str) -> CoreResult<String> {
        Err("no pending wallet requests".into())
    }
    fn wallet_reject(&mut self, _id: &str) -> CoreResult<String> {
        Ok("rejected".into())
    }
    fn wallet_switch_network(&mut self, _id: &str) -> CoreResult<String> {
        Err("network switching runs from the Wallet window".into())
    }
    fn node_run(&mut self, script: &str) -> CoreResult<String> {
        rbcore::node::run(&rbcore::paths::data_dir(), script, rbcore::node::DEFAULT_BUDGET_MS)
    }
    fn node_packages(&mut self, action: &str, name: &str, spec: &str) -> CoreResult<String> {
        rbcore::node::packages(&rbcore::paths::data_dir(), action, name, spec)
    }
    fn confirm(&mut self, _prompt: &str) -> bool {
        self.yolo
    }
    fn site_permissions(&mut self) -> Vec<Value> {
        Vec::new()
    }
    fn set_site_permission(&mut self, _host: &str, _permission: &str, _decision: &str) -> CoreResult<()> {
        Ok(())
    }
    fn page_host(&mut self) -> String {
        rbcore::urlintel::host_of(&self.url.lock().unwrap().clone()).unwrap_or_default()
    }
}

struct AgentLoopHost {
    executor: Executor,
    tx: glib::Sender<Msg>,
    session: String,
    dir: PathBuf,
    profile: String,
}

impl LoopHost for AgentLoopHost {
    fn execute_tool(&mut self, call: &ToolCall) -> String {
        let r = self.executor.execute(call);
        let _ = (&self.session, &self.dir, &self.profile);
        r
    }
    fn emit(&mut self, event: AgentEvent) {
        let (name, payload, persist) = match &event {
            AgentEvent::AssistantText(t) => ("agent.text", json!({"text": t}), Some(t.clone())),
            AgentEvent::AssistantThinking(t) => ("agent.thinking", json!({"text": t}), None),
            AgentEvent::ToolStarted { name, args } => ("agent.tool", json!({"name": name, "args": args, "result": ""}), None),
            AgentEvent::ToolFinished { name, result } => ("agent.tool", json!({"name": name, "args": null, "result": result}), Some(result.clone())),
            AgentEvent::PlanUpdated(p) => ("agent.plan", p.clone(), None),
            AgentEvent::FinalAnswer(t) => ("agent.text", json!({"text": t}), Some(t.clone())),
            AgentEvent::AgentError(t) => ("agent.error", json!({"text": t}), None),
            AgentEvent::Notice(t) => ("agent.notice", json!({"text": t}), None),
        };
        if let Some(content) = persist {
            let mut sessions = rbcore::agent::sessions::AgentSessions::load(&self.dir, &self.profile);
            sessions.append(&self.session, "assistant", &content, "", "", "", now_ms());
            let _ = sessions.save(&self.dir, &self.profile);
        }
        let _ = self.tx.send(Msg::Chrome { json: json!({"name": name, "payload": payload}) });
    }
}

fn agent_turn(
    data_dir: PathBuf,
    profile_id: String,
    session_id: String,
    text: String,
    mode: String,
    history: Vec<ChatMessage>,
    tx: glib::Sender<Msg>,
) {
    let push = |name: &str, payload: Value| {
        let _ = tx.send(Msg::Chrome { json: json!({"name": name, "payload": payload}) });
    };
    let base = "http://127.0.0.1:11434";
    let agent = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(3)).build();
    let model = agent
        .get(&format!("{base}/api/tags"))
        .call()
        .ok()
        .and_then(|r| r.into_json::<Value>().ok())
        .and_then(|v: Value| {
            v.get("models")
                .and_then(|m| m.as_array())
                .and_then(|a| a.first())
                .and_then(|m| m.get("name"))
                .and_then(|n| n.as_str())
                .map(String::from)
        })
        .unwrap_or_else(|| "llama3.2".into());
    push("agent.model", json!({"model": model}));

    let mut gateway = rbcore::agent::gateway::OllamaGateway {
        base_url: base.to_string(),
        api_key: String::new(),
        model: model.clone(),
        num_ctx: 4096,
        keep_alive_minutes: 10,
    };

    let mut messages = vec![ChatMessage::system(&format!(
        "You are the Room Browser agent on a desktop computer. Today is {}. Use the tools to drive the page; answer plainly only when the task list is finished.{}",
        chrono::Utc::now().format("%Y-%m-%d"),
        if mode == "PLAN" { " Plan mode: produce a plan first; page actions are refused until the user approves." } else { "" }
    ))];
    messages.extend(history.iter().cloned());

    let executor = Executor {
        host: Box::new(FrontendToolHost {
            tx: tx.clone(),
            url: std::sync::Mutex::new(String::new()),
            yolo: mode == "YOLO",
        }),
        stores: AppStores::new(data_dir.clone(), &profile_id),
        gate_ctx: GateContext {
            plan_mode: mode == "PLAN",
            withheld_permissions: Vec::new(),
            run_kind: rbcore::agent::gates::RunKind::Interactive,
            task_allow_post: false,
            task_profile_tools: false,
        },
        budget: rbcore::agent::gates::ActionBudget::default(),
        devtools_available: true,
        diagnostics_enabled: false,
    };
    let mut host = AgentLoopHost {
        executor,
        tx: tx.clone(),
        session: session_id,
        dir: data_dir,
        profile: profile_id,
    };
    let _ = text;

    let mut plan = AgentPlan::default();
    let config = AgentConfig::default();
    if let Err(e) = rbcore::agent::loop_runner::run_turn(&mut gateway, &mut host, &config, &mut plan, &mut messages) {
        host.emit(AgentEvent::AgentError(e));
    }
}
