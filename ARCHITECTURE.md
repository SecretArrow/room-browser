# Architecture

## Modules

```
RoomBrowser
├── :app              Android application
│   ├── main/         MainActivity (profile picker, default process)
│   ├── browser/      BrowserActivity (:browser process) — engine + UI
│   ├── data/         Room entities/DAOs, repositories, filter list loader
│   ├── qr/           QR scanner (CameraX + ZXing) and generator
│   ├── security/     Biometric gate
│   └── work/         Retention cleanup worker (WorkManager)
└── :core:domain      Pure Kotlin JVM module — all decision logic
    ├── model/        Profile, settings, DNS/UA/search models
    ├── engine/       FilterEngine, IpConflictDetector, UrlIntelligence, DnsValidator
    ├── profile/      ProfileManager, ProfileSwitchStateMachine, directory layout
    └── export/       Profile backup (settings only — never secrets)
```

The domain module has **zero Android dependencies**, so every piece of
decision logic (blocking, IP-conflict warnings, profile switching, backup
validation, URL classification) is unit-tested on the JVM.

## Process & isolation architecture (the core)

Room Browser runs **two processes**:

| Process | Hosts | WebView? |
|---|---|---|
| default | `MainActivity`, QR scanner, profile management | never |
| `:browser` | `BrowserActivity`, the engine, downloads, DNS monitor | **only here** |

1. `MainActivity` selects a profile and launches `BrowserActivity` with the
   profile UUID.
2. `BrowserActivity.onCreate` calls
   `ProfileEngine.bindProcessToProfile(profileId)` **before any WebView exists**.
   This calls `WebView.setDataDirectorySuffix(<32-hex UUID>)`, which points the
   engine's on-disk state (cookies, localStorage, IndexedDB, service workers,
   HTTP cache, Web SQL, form data, HTTP auth) at a per-profile directory tree.
3. The suffix is **process-wide and immutable at runtime**, so one process can
   only ever host one profile's engine state.
4. **Profile switching** therefore runs the 7-step protocol
   (`ProfileSwitchStateMachine`): stop navigation → save tab state → destroy
   browser context → flush cookies → release resources → (restart) → restore
   the new profile's tabs. Steps 6–7 are executed by scheduling an
   AlarmManager restart of `:browser` with the new profile id and terminating
   the process. On relaunch the suffix differs → a physically different
   storage tree. Nothing is reused.
5. If a stale process is somehow bound to another profile (should never
   happen), `bindProcessToProfile` returns `false` and the activity schedules
   a clean restart instead of mixing profiles.

## Data layer

- **Room** (`room-browser.db`, `enableMultiInstanceInvalidation`) stores only
  metadata: profiles + settings JSON, tabs, bookmarks, history, downloads,
  site permissions, site settings, IP history, real block events, and a
  cross-process app-state KV (active profile, global settings).
- Both processes access the database; multi-instance invalidation keeps them
  consistent.
- **Browser-engine storage never enters Room** — cookies and site data stay in
  the per-profile WebView directories (see `PROFILE_ISOLATION.md`).
- **DataStore is intentionally NOT used for cross-process state** (it is not
  multi-process safe); the Room KV table is used instead.

## Engine request pipeline

```
WebView.shouldInterceptRequest (background thread)
  → FilterEngine.decide(host, pageHost, path, profile settings, site override)
  → Blocked?  record REAL BlockEvent(host, category) → empty response
shouldOverrideUrlLoading (main frame)
  → malicious host check → HTTPS upgrade → popup/redirect control
onCreateWindow → popup policy → transport WebView → new tab
```

All privacy-dashboard numbers are `COUNT(*)` aggregations over `block_events`
— real events only, no synthetic statistics.

## Downloads

`DownloadEngine` (OkHttp) with a 2-parallel queue, HTTP-Range resume, pause /
resume / cancel / retry, duplicate-name handling, MediaStore Downloads
publication (API 29+) or legacy public Downloads dir (API 28), and progress
notifications with actions.

## Network identity (IP conflict warning)

`NetworkIdentity` fetches the observed public IP from plain HTTPS endpoints
only when network protection (or diagnostics) is enabled, caches results with
a 10-minute cooldown, and runs `IpConflictDetector.check(...)` against the
local Room IP history. The warning is informational, dismissible, and
per-IP suppressible. All IP data stays on-device.

## Suggested sync architecture (future, optional)

The spec allows an optional sync module. Room Browser keeps `sync` as a
deliberately unimplemented seam: profile data would be serialized through the
same `ProfileBackup` format with end-to-end encryption. Nothing syncs today
and no backend exists in this codebase.
