# Testing Guide

## Test pyramid

| Level | Command | Runs where | Status |
|---|---|---|---|
| Domain unit tests (72) | `./gradlew :core:domain:test` | JVM | ✅ run in CI and locally |
| App logic unit tests | `./gradlew :app:testDebugUnitTest` | JVM | ✅ run in CI and locally |
| Android Lint | `./gradlew lintDebug` | JVM | ✅ run in CI and locally |
| Instrumented tests | `./gradlew connectedDebugAndroidTest` | device/emulator | written; requires hardware |
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

## The critical profile-isolation test (spec section 60)

### Automated parts (device required)

`app/src/androidTest/kotlin/com/roombrowser/ProfileIsolationTest.kt`
- distinct suffixes per profile UUID
- suffix format within WebView's contract
- **process binding is profile-exclusive** (the guard against stale reuse)

`DatabaseIsolationTest.kt`
- tabs/bookmarks/history scoped per profile
- delete cascades only within one profile
- rename keeps storage identity

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

## CI quality gate

`.github/workflows/ci.yml` runs lint + unit tests + debug build on every push
and the full signed release matrix on tags. Failures block merging and
releasing.
