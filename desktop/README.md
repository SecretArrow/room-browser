# Room Browser — Desktop

Room Browser Desktop is a **Brave-inspired** dark browser written in **pure C11**
by **Maragung**. It is inspired by Brave's visual language (dark chrome, purple
accent `#A78BFA`) but contains **zero Brave code or assets** — the entire
browser is original C: a portable core plus one thin platform layer per OS.

| Layer | Tech | Files |
|---|---|---|
| Core (portable) | plain C11, no third-party deps | `src/core/*.c` |
| Windows chrome | Win32 + WebView2 (plain-C COM) | `src/win/*.c` |
| Linux chrome | GTK 3 + WebKitGTK 4.1 | `src/gtk/*.c` |

- Company / builder: **Maragung** · License: **MIT** (see `LICENSE`).
- Project policy: **JavaScript is never disabled by default** (`javascript=1`).
- Default search: **DuckDuckGo** (omnibox heuristics in `src/core/rb_url.c`).

## Features

- **Tabs** — add / switch / close, per-tab back/forward state.
  - *Windows:* owner-drawn Win32 buttons (tab strip rebuilt on every change).
  - *Linux:* `GtkNotebook` whose pages are the per-tab `WebKitWebView`s.
- **Omnibox** — one field for addresses and searches. Enter runs the core
  heuristic `rb_url_decide()`: hosts/URLs are normalized (`example.com` →
  `https://example.com/`), anything else becomes a DuckDuckGo search with
  percent-encoded query. Escape restores the current URL.
- **Bookmarks** — star button (☆/★) toggles the current page; deduplicated by
  URL; persisted as JSON lines.
- **History** — most-recent-first, consecutive duplicates collapsed, bounded at
  10 000 entries, persisted as JSON lines; the menu offers the 10 latest.
- **Settings** — plain `key=value` lines: `home`, `search_engine`,
  `javascript` (default **1**).
- **Dark chrome everywhere** — tab strip `#202124`, toolbar `#292A2D`,
  omnibox `#3C3D41`, text `#E8EAED`, accent `#A78BFA` (GTK via a
  `GtkCssProvider` stylesheet, Windows via owner-draw + dark title bar).
- **Honest failures** — if the WebView2 runtime is missing on Windows the
  window stays open and a one-time message explains what to install.

### Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+T` | new tab |
| `Ctrl+W` | close tab |
| `Ctrl+L` | focus omnibox (selects its contents) |
| `F5` / `Ctrl+R` | reload (or stop while loading) |
| `Alt+Left` / `Alt+Right` | back / forward |
| `Enter` (omnibox) | navigate or search |
| `Escape` (omnibox) | restore current URL |

On Linux the shortcuts are real `GSimpleAction` accelerators
(`gtk_application_set_accels_for_action`) and keep working while web content
has focus. On Windows they are handled in the window procedure — see
*Limitations*.

## Data locations

| OS | Settings / history / bookmarks | Web-engine profile |
|---|---|---|
| Windows | `%APPDATA%\RoomBrowser` (`history.jsonl`, `bookmarks.jsonl`, `settings.txt`) | `%LOCALAPPDATA%\RoomBrowser\WebView2` |
| Linux | `$HOME/.config/RoomBrowser` (same files) | managed by WebKitGTK |

Delete the directory to reset the browser. Files are saved on every change and
once more at shutdown.

## Building

### Linux (Ubuntu / Debian)

```bash
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev cmake
cmake -S desktop -B build -DRB_BUILD_TESTS=ON
cmake --build build
./build/rb_tests          # core unit tests (tabs/history/bookmarks/url/settings)
./build/roombrowser       # run the browser
```

### Windows (Visual Studio 2022)

Requirements: VS2022 (MSVC, desktop C workload), CMake 3.16+, and the
**WebView2 SDK headers** (from the
[`Microsoft.Web.WebView2`](https://www.nuget.org/packages/Microsoft.Web.WebView2)
NuGet package — extract and point CMake at `build/native/include`):

```bat
cmake -S desktop -B build -DWEBVIEW2_INCLUDE_DIR=C:\path\to\WebView2\build\native\include
cmake --build build --config Release
```

`WebView2Loader.dll` (from the same NuGet package, `build\native\x64\`) must be
copied next to `RoomBrowser.exe`; the loader is resolved at runtime via
`LoadLibraryW`, so nothing links against an import library.

### Windows cross-check from Linux (verified)

The Windows backend was verified with
[llvm-mingw](https://github.com/mstorsjo/llvm-mingw) + the WebView2 headers:

```bash
CC=x86_64-w64-mingw32-clang
$CC -std=c11 -Wall -Wextra -municode -c -I<webview2-include> -Isrc -Isrc/win -I<dir-with-rb_version.h> src/win/*.c
llvm-rc -I<configured-dir> -Isrc/win -I<mingw-include> app.rc -Fo app.res
$CC -municode -mwindows -o RoomBrowser.exe main.o chrome.o webview.o <core-objs> app.res \
    -lole32 -loleaut32 -luser32 -lgdi32 -lcomctl32 -ldwmapi -lshell32
```

This produced a clean **PE32+ GUI x64** executable with **zero warnings**
(the full command sequence used for verification lives in the worklog).

### `--version` smoke contract

Both binaries answer `--version` **before** creating any window, printing
exactly one line to stdout (Windows attaches the parent console first; output
falls back to raw UTF-8 for redirected pipes/files):

```
Room Browser <version> (windows x86_64)
Room Browser <version> (linux x86_64)
```

`--out <path>` additionally writes the same line (plus newline) to a file.
Exit code 0 in both cases. The version comes from the CMake cache variable
`ROOMBROWSER_VERSION` (default `1.0.86`) via `src/rb_version.h.in`.

## Packaging

CI calls these scripts directly; both **fail loudly** when the build inputs
are missing:

```bash
# Windows (pwsh or Windows PowerShell 5.1) — BuildDir must already contain
# RoomBrowser.exe + WebView2Loader.dll:
pwsh ./desktop/scripts/package-windows.ps1 -BuildDir build/Release -Version 1.0.86 -DistDir dist

# Linux — BUILD_DIR must contain ./roombrowser:
./desktop/scripts/package-linux.sh build 1.0.86 dist
```

Outputs:

- **Windows:** `dist/RoomBrowser-<version>-windows-x64.zip` (RoomBrowser.exe,
  WebView2Loader.dll, LICENSE.txt, README.txt) + printed SHA-256.
- **Linux:** `dist/roombrowser_<version>_amd64.deb` (depends on
  `libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37` and `libgtk-3-0t64 | libgtk-3-0`,
  installs `/usr/bin/roombrowser`, desktop entry, hicolor icon, copyright) and
  `dist/RoomBrowser-<version>-linux-x64.tar.gz` (binary + `install.sh` +
  README + LICENSE; `install.sh` has a root check, tries
  `apt-get install libwebkit2gtk-4.1-dev libgtk-3-0` and copies the binary to
  `/usr/local/bin`).

## Security notes

- The Windows executable carries **full VERSIONINFO metadata**
  (CompanyName `Maragung`, ProductName `Room Browser`, File/ProductVersion,
  OriginalFilename, MIT copyright) and a manifest that only requests
  `asInvoker` — no elevation, no compatibility shims.
- The builds are **NOT code-signed**. Verify the SHA-256 checksum of the
  archive before running, and expect Windows **SmartScreen** to show an
  "unknown publisher" warning on the first launch.
- JavaScript is **on** by default (project-wide policy); disabling it is an
  explicit, persisted user setting (`javascript=0` in `settings.txt`).
- Everything is plain C11 with no third-party code in the browser itself.

## Limitations (v1, honest)

- **Windows accelerators are inactive while web content has focus** —
  shortcuts are handled in the main window procedure, and the WebView2 child
  consumes keys first. Click the toolbar/tab strip first, or use the
  buttons/menu. (Linux does not have this limitation.)
- **Windows tab strip:** tab buttons are destroyed and recreated on every tab
  change (`rb_tabs_rebuild`), so per-tab identity is positional; tab width is
  `(window-40)/n`, clamped to 40–180 px; there is no drag-reordering.
- **Windows webviews:** controllers are created one at a time (a creation
  chain guarded by an in-flight flag); event tokens are kept but never
  removed (the static handlers live for the whole process — teardown happens
  by closing/releasing controllers at quit).
- **Windows JS toggle:** the persisted `javascript` setting is applied when a
  webview is created (i.e. to new tabs) — there is no live menu toggle on
  Windows; edit `settings.txt` and restart. On Linux the menu check item
  toggles it live through `WebKitSettings`.
- **Windows dark title bar** uses `DwmSetWindowAttribute` loaded at runtime;
  on Windows versions without it the title bar simply stays light (silent
  fallback).
- No private/incognito mode, downloads UI, context-menu customization, or
  content blocking — the "Brave-inspired" part is strictly the chrome design.
- History is capped at 10 000 entries; bookmarks are a flat list in insertion
  order.
- The omnibox heuristic treats any input with interior whitespace or
  non-ASCII bytes as a search query.
