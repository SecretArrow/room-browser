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

One engine instance exists per `BrowserActivity` — that is, per profile, since
a profile switch restarts the `:browser` process. It is a plain class owned by
the ViewModel, not a Service: there is nothing to keep alive between
transfers, and the queue is rebuilt on startup by `recover()`, which re-queues
any row still marked `RUNNING` (the orphan of a process that died mid-download)
instead of leaving it to lie about its state and hold a queue slot forever.
`pump()` is profile-scoped (`activeFor(profileId)`) so one profile's engine can
never start another profile's queued transfer.

Correctness rules that are easy to get wrong and are therefore pinned by tests
in `core/domain`:

- **Resume offsets are planned, not assumed.** `DownloadPlanner.plan(...)`
  decides the append offset from the response code and `Content-Range` before
  the part file is opened. The file is opened with `append = plan.appendAt > 0`
  — the old code opened it for truncation first and chose the mode afterwards,
  which is why resuming used to restart the file from nothing.
- **A server that ignores the range restarts the transfer** (`200` to a Range
  request), and a `206` whose `Content-Range` is missing or starts somewhere
  other than the requested offset is refetched rather than spliced.
- **Progress and status are separate writes.** `updateProgress` carries only
  byte counters and `updateStatus` only the status, so a progress tick racing a
  pause cannot resurrect a stale status (a whole-row `copy()` would).
- **A cancelled job still records its outcome.** The terminal write runs under
  `withContext(NonCancellable)`, and `recordFailure` only writes when the row
  still says `RUNNING`, so pause/cancel always win the race.
- **Pause and cancel are cooperative.** OkHttp's blocking `read()` is not
  interruptible, so the loop checks the `paused` / `cancelled` sets on every
  progress tick and throws to unwind; the sets are `ConcurrentHashMap.newKeySet`
  because the UI thread and the IO coroutines both touch them.

Notification actions (Pause / Resume / Cancel / Retry / Open) are delivered by
`DownloadActionReceiver`. A notification action can only arrive as a broadcast,
so without that receiver the buttons were inert. The receiver is not exported —
the PendingIntents name the package — and it no-ops when no engine is alive,
which is the honest outcome for a notification left over from a dead process.

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
