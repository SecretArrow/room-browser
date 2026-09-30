# Testing Guide

## Test pyramid

| Level | Command | Runs where | Status |
|---|---|---|---|
| Domain unit tests (72) | `./gradlew :core:domain:test` | JVM | ✅ run in CI and locally |
| App logic unit tests | `./gradlew :app:testDebugUnitTest` | JVM | ✅ run in CI and locally |
| Android Lint | `./gradlew lintDebug` | JVM | ✅ run in CI and locally |
| Instrumented tests (DB + engine + E2E UI) | `./gradlew connectedDebugAndroidTest` | emulator | ✅ automated in CI (API 30 x86_64 emulator) |
| Profile isolation E2E | manual/CI procedure below | device/emulator | procedure + tooling provided |

## What the unit tests cover (spec section 59)

- Profile creation / deletion / renaming / UUID persistence
- Settings updates, default-profile promotion
- IP history, warning logic, suppression, retention windows
- Filter engine: exact + subdomain blocking, cross-site classification,
  malicious priority, keyword rules, suspicious signals
- URL intelligence: URL vs search vs IP literals vs localhost vs file URIs,
  HTTPS upgrades
- Profile switch state machine: full order, illegal transitions, failures
- Backup format: round-trip, forward-compat, secret-free guarantee
- DNS validation & effective-mode resolution
- Download filename extraction/sanitization
- Download resume planning (`DownloadPlannerTest`): a `200` to a Range request
  falls back to a fresh transfer instead of splicing, a `206` starting at any
  offset other than the one requested is refetched, a `206` with a missing or
  malformed `Content-Range` is refetched rather than trusted, and the total is
  read from `Content-Range` rather than from a partial `Content-Length`
- Download formatting (`DownloadFormatTest`): byte sizes stay in the right
  unit past 1024 boundaries (`a two gigabyte file does not read as two million
  kilobytes`), percentage is absent rather than wrong when the total is
  unknown, and speed/ETA degrade to absent instead of to a fabricated number

## The critical profile-isolation test (spec section 60)

### Automated parts (run in CI on every push)

`app/src/androidTest/kotlin/com/roombrowser/ProfileIsolationTest.kt`
- distinct suffixes per profile UUID
- suffix format within WebView's contract
- **process binding is profile-exclusive** (the guard against stale reuse)

`DatabaseIsolationTest.kt`
- tabs/bookmarks/history scoped per profile
- delete cascades only within one profile
- rename keeps storage identity

`E2EBrowseFlowTest.kt` (UiAutomator, cross-process)
- cold start → first-run welcome → create profile via the real dialog
- tap OPEN → the separate `:browser` engine process boots → omnibox visible

### End-to-end storage isolation (the full spec procedure)

Serve the bundled test site (any static server works):

```bash
cd tools/profile-test-site && python3 -m http.server 8080
# on emulator: adb reverse tcp:8080 tcp:8080
```

Then in Room Browser:

1. **Profile A** → open `http://localhost:8080` → press **Set test data**.
2. Switch to **Profile B** (use the quick switcher — this restarts the engine
   process) → open the same URL → press **Inspect test data**.
3. Expected: every row (Cookie, Session cookie, LocalStorage, SessionStorage,
   IndexedDB, CacheStorage, ServiceWorker) reads **(empty) / isolated**.
4. Repeat after: app restart, profile switch back and forth, engine process
   kill (`adb shell am kill com.roombrowser`), device reboot.

The page keeps a timestamped log for evidence; screenshots of all-green
"isolated" tables are the acceptance record.

## Process-death & lifecycle testing

```bash
adb shell am start -n com.roombrowser/.browser.BrowserActivity   # cold start
adb shell am kill com.roombrowser                                # kill engine process
# relaunch: active profile + tab set must be restored from Room
adb shell cmd uiautomator dump   # verify UI after each kill
```

Configuration changes: rotate the device, split-screen, dark-mode toggle —
the activity handles config changes without recreating engine state.

## CI quality gate & auto-release

`.github/workflows/ci.yml` runs on every push to `main` (and `v*` tags):

1. **auto-fix** — `lintFix` quickfixes are committed and pushed automatically
   (the PAT_TOKEN secret re-triggers a clean pipeline run on the fixed tree)
2. **quality** — Android Lint + unit tests (domain + app) + debug build
3. **e2e** — instrumented tests + cross-process E2E flow on a real emulator
   (API 30, x86_64, KVM)
4. **auto-release** — after quality + e2e are green on `main`: signed per-ABI
   APKs (arm64-v8a, armeabi-v7a, x86_64, x86, universal) + AAB + checksums
   are published as a GitHub Release (tag `v1.0.<run_number>`; `v*` tags get
   stable releases). Failures block merging and releasing.
