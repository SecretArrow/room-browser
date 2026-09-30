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
  name over a status line: the state, the percentage and both sizes ("1.4 MB /
  3.1 MB"), or the error when one failed, or "3.2 MB so far" when the server
  never sent a length. Selecting a row enables the actions on it — **Open**,
  **Show in folder**, **Copy link**, **Details** and **Remove from list** —
  with Open and Show in folder offered only once the download has actually
  finished, so a partial file is never handed to the shell as if it were whole.
  Details shows every recorded field (source, MIME type, destination, both
  sizes, timestamps, error). Remove from list forgets the *record*; the file
  stays where it is, which is also what "Clear list" does for the whole
  profile. The window is live — both editions re-read the store once a second,
  so a transfer that is still arriving keeps counting up while it is on screen.
  Sizes go through one core formatter, so the same download cannot read "1.4 MB"
  on one edition and "1468006 bytes" on the other, and both editions show the
  *same profile's* rows, which is what the "Clear list" button has always
  cleared: the list and the button now agree about what "the list" is.
- **Find in page** (*Menu → Find in page*, <kbd>Ctrl</kbd>+<kbd>F</kbd>) — the
  same bar Android's FindInPageBar is: a field, previous, next and close, with
  the search re-running on every keystroke, <kbd>Enter</kbd> stepping forward
  and <kbd>Shift</kbd>+<kbd>Enter</kbd> back, <kbd>Ctrl</kbd>+<kbd>G</kbd> and
  <kbd>F3</kbd> (<kbd>Shift</kbd> for backward) stepping from anywhere, and
  <kbd>Esc</kbd> closing it. On Linux the bar sits under the page — this
  edition's toolbar is at the top — and shows WebKit's own match count; on
  Windows it sits at the bottom of the client area, and its count is taken
  from the page's text, because the WebView2 surface this edition targets has
  no engine-side find. Closing ends the search, so the page keeps no highlight.
- **Translate this page** (*Menu → Translate this page*) — opens Android's
  Google Translate web wrapper for the current page in a new tab:
  `https://translate.google.com/translate?sl=auto&tl=<target>&u=<page>`. The
  target language is the profile's **Translate target language** setting
  (default `id`), the same row Preferences already had; the URL is built by
  one core function, so both editions send the identical request. It refuses
  the browser's own `about:` pages and says so, which is the guard Android's
  dialog makes. The wrapper is Google's page, not ours: it sees the URL you
  asked it to translate, and some sites do not survive it — Android's dialog
  carries the same warning and this edition is no different.
- **Settings** — plain `key=value` lines: `home`, `search_engine`,
  `javascript` (default **1**).  The preferences window (menu → Preferences,
  or <kbd>Ctrl</kbd>+<kbd>,</kbd>) edits the profile's full setting set —
  theme, text size, search, privacy and blocking, device, screen size,
  User-Agent, DNS, homepage and the clear-data actions — the same keys the
  Android edition stores.
- **Device identity manager** — every profile presents a real machine from a
  bundled catalogue of 1041 laptops, desktops and workstations (2022–2025,
  Windows, macOS and Linux), not just a User-Agent string. A device sets the
  UA *and*
  everything a page can ask about the machine — `navigator.platform`, the
  client hints (`architecture`, `bitness`, `formFactor`, `platformVersion`,
  `uaFullVersion`), `deviceMemory`, `hardwareConcurrency` and the WebGL
  vendor/renderer strings — so the two can never disagree. A new profile is
  given one at random, and never a machine another profile already presents;
  the Device row in Preferences changes it — through a search over the whole
  catalogue in both editions, since a thousand machines is more than a
  drop-down can usefully show, with every device another profile already
  presents marked as such rather than hidden — and choosing "No device"
  hands the identity back to the User-Agent settings (which are greyed out
  while a machine is chosen, rather than silently ignored). The catalogue is
  all-distinct: no two entries are the same fingerprint.
- **Screen size** — the row under the device, per profile, with the same two
  modes the Android edition offers: **This display** (the default, and the
  only mode in which nothing is claimed — a page is told the truth about the
  screen the page is really being laid out on) or **Custom**, where the
  profile states a size in CSS pixels. "This display" fills the two numbers in
  from the monitor in front of you. A claim replaces `screen.width`,
  `screen.height` and the two available-area values, and derives
  `screen.orientation` from the shape claimed, so a landscape claim cannot
  also answer `portrait-primary`. It does **not** touch `innerWidth`,
  `innerHeight` or `devicePixelRatio`: those are the page's real size in this
  window and the ratio the compositor renders at, and moving them would
  re-lay the page out at a size the display does not have. The consequence is
  real and is stated on the row and in `SECURITY.md`: a claimed size that
  differs from this display's disagrees with the viewport, and a script can
  find that out. What the row is for is the other direction — a profile
  presenting a 4K workstation should not be reporting a laptop panel.
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
- A download that was still running when the browser closed, or when the profile
  was switched, is recorded as FAILED with the reason ("Interrupted", "Profile
  switched") rather than resumed. The transfer belongs to the platform engine —
  WebKitDownload on GTK, WebView2's DownloadOperation on Windows — and neither
  survives the process that created it; unlike Android, whose engine issues the
  HTTP request itself and can resume with a Range header, there is nothing left
  here to resume. Marking the row failed is the honest outcome; a row that
  claimed to be running forever would be the lie.
- History is capped at 10 000 entries; bookmarks are a flat list in insertion
  order.
- The omnibox heuristic treats any input with interior whitespace or
  non-ASCII bytes as a search query.
