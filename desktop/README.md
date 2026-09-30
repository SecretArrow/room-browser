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
- **Bookmarks bar** — a row of the bookmarks, under the toolbar, on both
  editions. Its two shapes come from the store's own ordering: a run of
  contiguous rows sharing a folder name becomes one button that pops the
  folder's contents, and a folder-less row becomes a button that opens it, so
  both bars list the same things in the same order. On Linux a middle-click
  opens a bookmark in a new tab and a right-click offers *Open in new tab* and
  *Remove bookmark*; on Windows, middle-click and right-click do the same.
  *Menu → Show bookmarks bar* toggles it, and the setting is per profile like
  the switches in Preferences — a profile switch re-reads it. The row is
  chrome, so *Text size* scales it, and the page starts below whatever height
  it ends up.
- **History** — most-recent-first, consecutive duplicates collapsed, bounded at
  10 000 entries, persisted as JSON lines; the menu offers the 10 latest.
- **Downloads** — the active profile's records, newest first, each row a file
  name over its status (with progress while one is running and the error when
  one failed), plus "Clear list". Both editions have the window and show the
  same core strings, so they cannot describe one download differently. Clearing
  removes the *records* of that profile only; the files on disk are the user's
  and are never touched. Neither edition opens the file from the list.
- **Settings** — plain `key=value` lines: `home`, `search_engine`,
  `javascript` (default **1**).  The preferences window (menu → Preferences,
  or <kbd>Ctrl</kbd>+<kbd>,</kbd>) edits the profile's full setting set —
  theme, text size, search, privacy and blocking, device, User-Agent, DNS,
  homepage and the clear-data actions — the same keys the Android edition
  stores.
- **Device identity manager** — every profile presents a real machine from a
  bundled catalogue of 523 laptops, desktops and workstations (2022–2025,
  Windows, macOS and Linux), not just a User-Agent string. A device sets the
  UA *and*
  everything a page can ask about the machine — `navigator.platform`, the
  client hints (`architecture`, `bitness`, `formFactor`, `platformVersion`,
  `uaFullVersion`), `deviceMemory`, `hardwareConcurrency` and the WebGL
  vendor/renderer strings — so the two can never disagree. A new profile is
  given one at random, and never a machine another profile already presents;
  the Device row in Preferences changes it, and choosing "No device" hands
  the identity back to the User-Agent settings (which are greyed out while a
  machine is chosen, rather than silently ignored). The catalogue is
  all-distinct: no two entries are the same fingerprint. Screen geometry is
  deliberately left alone, because the page really is laid out on this
  screen; see `SECURITY.md` for exactly what changes and what does not.
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

- **Text size** scales the chrome on both editions, but not identically.
  Linux sizes a widget from its content, so the scale rides on
  `gtk-font-name` alone. Windows places every control at an absolute
  coordinate, so the layout is scaled with the font — otherwise a larger font
  would spill out of a box that did not move with it. The settings window
  itself keeps a fixed size on both editions; only the chrome scales.
- **Reduce motion** stops both editions animating. Linux hands the switch to
  GTK (`gtk-enable-animations`), which covers every transition the toolkit
  draws; Windows has no such lever and only animates one thing, the page-load
  strip. The strip keeps working either way: with the animation off it parks
  as a stationary segment instead of sweeping, because a load still has to be
  visible and a full-width bar would read as "finished".
- **WebRTC policy** offers the Android edition's three values, and each
  desktop edition can express a different part of the range. Linux has one
  on/off switch and no notion of an IP-handling policy, so only *Disabled*
  changes anything there — it turns WebRTC off, while *Default* and *Restrict
  local IP exposure* both leave it on. Windows has no WebRTC switch at all,
  but Chromium has an IP-handling policy, so both non-default values map onto
  `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`: candidates stay
  off the machine's own interfaces. That switch is read when the browser
  process starts, so on Windows a change takes effect at the next launch
  rather than on the pages already open. Neither edition can remove
  `RTCPeerConnection` outright, and the preferences screen says which of these
  applies where the choice is made.
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
- No private/incognito mode, context-menu customization, or per-site shields;
  the "Brave-inspired" part is strictly the chrome design. Content blocking is
  real on both editions (the bundled host list, filtered by each profile's
  switches), and both editions show the active profile's downloads — the record
  of what was fetched, newest first, with "Clear list". Neither window opens the
  file: the list is a record, and the file belongs to the user.
- History is capped at 10 000 entries; bookmarks are a flat list in insertion
  order.
- The omnibox heuristic treats any input with interior whitespace or
  non-ASCII bytes as a search query.
