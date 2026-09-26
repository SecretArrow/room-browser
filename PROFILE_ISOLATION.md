# Profile Isolation

This document explains **exactly what is isolated, how, and what the
remaining platform limitations are**.

## Isolation mechanism

Android System WebView exposes exactly one per-context storage switch to
normal apps:

```java
WebView.setDataDirectorySuffix(suffix)   // must run before ANY WebView exists
```

Room Browser derives the suffix from the profile's **immutable UUID** (32 hex
chars — within WebView's limits):

```
Profile "Personal"  (uuid 4f4e…)  →  suffix 4f4e…  →  app_webview_4f4e…/
Profile "Work"      (uuid 92ac…)  →  suffix 92ac…  →  app_webview_92ac…/
```

The suffix scopes, per profile, on disk:

| Storage | Isolated per profile |
|---|---|
| Cookies (incl. session cookies) | ✅ separate cookie jar |
| LocalStorage | ✅ |
| SessionStorage | ✅ (also process-lifetime) |
| IndexedDB | ✅ |
| Cache / HTTP cache | ✅ |
| Service Workers | ✅ |
| Web SQL databases | ✅ |
| Form data / HTTP auth state | ✅ |
| WebView "safe browsing" state | ✅ |

Because the suffix is **process-wide**, the engine process hosts exactly one
profile at a time and profile switches **restart the process** (see
`ARCHITECTURE.md`). This is the strongest isolation a non-system-app browser
can achieve on Android with the WebView engine; it is the same principle
desktop browsers use for separate profile directories.

What is **not** engine-level state is scoped by `profile_id` rows in Room:
tabs, bookmarks, history, downloads, permissions, site settings, IP history,
blocking statistics. Every query is keyed by profile id.

## Storage identity

- Profile names are **never** used as storage identifiers — only UUIDs.
- Renaming, re-styling or editing a profile never changes its identity.
- Duplicating a profile creates a **new UUID** (new namespace) and copies only
  metadata (settings/bookmarks/history optionally). Cookies, cache, sessions
  and site data are never copied.

## Survivability

Isolation survives: app restart, device reboot, engine process death,
low-memory kills, and Android background restrictions, because the storage
trees are on-disk and the process binds its suffix before any WebView exists
on every cold start (active profile is re-read from Room when no intent
extra is present).

## Known limitations (documented honestly)

1. **WebView is a single shared system component.** The Android System WebView
   package (and its renderer processes) is shared with other apps; Room
   Browser isolates *its own* per-profile state, not the WebView code itself.
2. **Private tabs** run inside the same profile engine context while open.
   Cookies set by a private tab land in that profile's jar. When the last
   private tab closes, Room Browser purges session cookies and form data
   (`ProfileEngine.clearSessionArtifacts`), but persistent cookies set during
   the private session may remain in the profile jar. This is a platform
   limitation of the WebView engine (Chromium's true incognito profile is not
   exposed to apps). Users who need stronger guarantees should use a dedicated
   profile as a "private profile".
3. **Per-site data clearing** for a single site is best-effort: WebView exposes
   global (per-profile) clearing; Room Browser clears the profile engine state
   and records the site setting, but cannot surgically delete one site's
   IndexedDB rows inside the profile's tree.
4. **Memory-resident engine state** (e.g. a decrypted value held by a live
   page) is beyond any app's control.

## Verification

- Automated: `app/src/androidTest/…/ProfileIsolationTest.kt` and
  `DatabaseIsolationTest.kt` (see `TESTING.md`).
- End-to-end manual/CI procedure with the bundled test page:
  `tools/profile-test-site/index.html` (cookie, session cookie, localStorage,
  sessionStorage, IndexedDB, CacheStorage, ServiceWorker).
