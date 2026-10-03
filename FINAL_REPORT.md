# Room Browser — Android Audit & Fix Report

Audit date: 2026-10-02
Scope: `android/` (app module + `core/domain`, `core/wallet`)
Baseline: `08fd87e` · Head after fixes: `bb6cd8d`
Build/test loop: GitHub Actions (`.github/workflows/ci.yml`) — no local builds, per project directive.

---

## 1. Executive summary

217 Kotlin source files, 70 test files, 777 `@Test` methods, minSdk 28 /
target+compileSdk 35, two processes (default and `:browser`).

**52 defects were found and fixed** across five commits. The audit was run as
one pass with all fixes applied immediately, then a single batch of testing on
CI — not fix-one-test-one. That batch came back **green on the first round:
777 of 777 tests passed**, lint clean, and `v1.0.105` shipped signed APKs split
per ABI.

The three findings that mattered most were not cosmetic:

1. **Every site behind HTTP Basic/Digest auth was unreachable.**
   `onReceivedHttpAuthRequest` called `handler.cancel()` unconditionally. No
   dialog, no callback, no message — the user saw a blank 401 with nowhere to
   type the credentials the server had just asked for.
2. **Exact alarms could not work on any Android 12+ device, and the fallback
   was deferrable by Doze.** Both engine-restart paths called
   `setExactAndAllowWhileIdle` without holding `SCHEDULE_EXACT_ALARM` (and the
   app deliberately does not hold the Play-restricted `USE_EXACT_ALARM`), so
   the exact call threw `SecurityException` **every time** and the `onFailure`
   branch — a plain `set()` — was the only path that ever ran. A profile
   switch kills `:browser`; a deferred alarm therefore left a dead browser
   surface on screen for as long as Doze felt like it.
3. **Three destructive actions had no confirmation.** Chief among them
   Download ▸ Delete, which sits one row below "Copy link" and calls
   `DownloadEngine.delete()` — that unlinks the published file off the device.
   It was never "remove from list".

Two findings were deliberately **not** fixed because they would reverse a
documented product promise or a stated security policy. Both are listed in
§5 and need a decision, not an edit.

The largest remaining debt is not a bug: **433+ user-visible string literals
are inline rather than in `strings.xml`**, and the 118 `By.text(...)` UiAutomator
selectors in the e2e suite assert those literals directly. Extracting them is a
mechanical but repo-wide change that must land with the matching test update.

---

## 2. Feature inventory

Every feature traced UI → ViewModel → Repository → DataSource.

| Feature | Before | After | Location | Fix applied |
|---|---|---|---|---|
| HTTP Basic/Digest auth | **Broken** — unconditional cancel | Working | `WebClients.kt`, `BrowserViewModel.kt`, `BrowserScreen.kt` | Callback → `pendingHttpAuth` → dialog; handler settled on every exit path |
| Profile switch | **Broken on failure** — dead engine, no restart | Working | `ProfileSwitchExecutor.kt`, `BrowserActivity.kt` | Caller's `lifecycleScope`, `onFailed` surfaced, schedule-before-kill |
| Engine restart alarm | **Broken on API 31+** | Working | `RestartAlarm.kt` (new) | `canScheduleExactAlarms()` gate, `setAndAllowWhileIdle` fallback |
| Periodic retention cleanup | Raced across processes; retried forever | Working | `RoomBrowserApp.kt`, `RetentionCleanupWorker.kt` | `setDefaultProcessName`, default-process gate, `UPDATE`, 3-attempt bound |
| Agent keep-alive FGS | No `onTimeout` (API 35 crash) | Working | `AgentKeepAliveService.kt` | `onTimeout` cancels + stops; `startForeground` failure no longer fatal |
| Engine name display | Dead code — unreachable elvis | Working | `ProfileEngine.kt`, `AndroidManifest.xml` | `<queries>` + `runCatching`; `getPackageInfo` throws, it does not return null |
| Download notification actions | Dropped silently for a dead engine | Working | `DownloadActionReceiver.kt`, `DownloadEngine.kt` | `process=":browser"`; orphan notification dismissed |
| Download delete | **No confirmation** — unlinks the file | Confirmed | `BrowserLibraryScreens.kt` | `ConfirmDialog`, resolved against the live list |
| Clear site data | **No confirmation; label implied per-site** | Confirmed + honest | `BrowserSheets.kt` | Subtitle + confirmation state the real whole-profile scope |
| Theme Studio reset | No confirmation | Confirmed | `ThemeStudioActivity.kt` | `AlertDialog` |
| Agent provider delete | No confirmation (key unrecoverable) | Confirmed | `AgentSettingsActivity.kt` | `AlertDialog` |
| Close other / left tabs | **Zero callers** — unreachable | Reachable | `BrowserLibraryScreens.kt` | Menu items + confirmation; select-then-act |
| Reader mode | Ignored the theme entirely | Themed | `BrowserSheets.kt` | `dark` is now an override of the real mode; hexes removed |
| Profile rename + restyle | **Lost one edit** (two round trips) | Atomic | `ProfileManager.kt`, `MainViewModel.kt`, `MainScreen.kt` | One `update()`; both old callers deleted |
| Profile lock / default / reset | Claimed success unconditionally | Honest | `MainViewModel.kt` | `runCatching`, conditional messages |
| POST_NOTIFICATIONS denial | Silently absorbed | Surfaced | `MainActivity.kt`, `MainViewModel.kt`, `MainScreen.kt` | Snackbar + action → notification settings, one ask per session |
| Vault passphrase retry | Kept the rejected secret from attempt 2 | Clears | `MainViewModel.kt`, `MainScreen.kt` | `PassphrasePrompt.id` |
| Password vault list | Decrypted while invisible | Lifecycle-scoped | `PasswordsActivity.kt` | `flowWithLifecycle(STARTED)` |
| QR scanner | Decoded on the main thread | Off-thread | `QrScannerActivity.kt` | Single-thread executor, `@Volatile`, `finally` close, ordered teardown |
| dApp request on a closed tab | Pending forever | Settled | `WalletBridge.kt`, `WalletEngine.kt`, `WalletContract.kt` | `dispose()` → `cancelDappRequests` → DISCONNECTED |
| `wallet_addEthereumChain` | Accepted `http://` RPC | TLS required | `WalletEngine.kt` | `requireTls` on the dApp path only |
| Wallet bind | Not idempotent — dropped the queue | Idempotent | `WalletEngine.kt` | Early return when the profile is unchanged |
| DNS DoH/DoT settings | Persisted malformed endpoints | Validated | `BrowserSettingsUi.kt` | `DnsValidator` + inline error, nothing committed when invalid |
| Quick-switcher Create | Silent no-op on blank; double-tappable | Guarded | `BrowserSheets.kt` | `enabled`, in-flight flag, inline error |
| Local AI model test | Held the GGUF handle after dismissal | Released | `LocalAiActivity.kt` | `unload()` on both dismissals + `onDestroy` |
| Provider editor form | Lost on rotation; saved on `GlobalScope` | Survives | `AgentProviderEditorActivity.kt` | `rememberSaveable` (API key excepted), `graph.appScope` |
| Search suggestions | Switch is inert (`fetchSuggestions` has 0 callers) | **Unchanged** — see §5 | `BrowserSettingsUi.kt` | Subtitle no longer overstates what leaves the device |
| History search | `searchHistory` has 0 callers | **Unchanged** — see §5 | `BrowserRepository.kt` | — |
| Tab thumbnails | Captured, never displayed | **Unchanged** — see §5 | `TabManager.kt` | — |

---

## 3. All fixes, by category

### 3.1 Features (broken or unreachable)

- **HTTP auth, end to end.** New `Callbacks.onHttpAuthRequest`; the handler is
  single-shot behind an `answered` guard; a background engine's challenge is
  refused outright rather than raised over the page the user is reading; a
  second challenge arriving while one is up is refused rather than silently
  replacing the first (which would have stranded the first handler); and
  destroying the tab under the dialog cancels it. `useHttpAuthUsernamePassword`
  is deliberately not consulted — this browser keeps no WebView credential
  database, the vault is the password manager.
- **`closeOtherTabs` wired up** behind a confirmation, with `selectTab` first
  because the sweep is relative to the active tab.
- **`ProfileManager.update()`** replacing the `rename` + `restyle` pair.
- **Engine name** now resolved through `WebView.getCurrentWebViewPackage()`
  first, then a `runCatching` `getPackageInfo`, then a literal fallback.

### 3.2 Edge cases

- Download action for an engine that no longer exists → dismiss the orphaned
  notification instead of doing nothing.
- `RetentionCleanupWorker` bounded at 3 attempts (`runAttemptCount`).
- Profile switch failure → state machine rolls back, process stays alive on the
  old profile, UI re-arms, user is told.
- `AgentKeepAliveService.onTimeout` (API 35 dataSync 6h/24h cap) and a
  `startForeground` that can throw `ForegroundServiceStartNotAllowedException`.
- chainId hex conversion: a decimal id is now parsed as decimal before the
  `0x`-strip fallback, instead of the other way round.
- `ImageProxy` closed in a `finally` — an escaped decode exception used to
  starve the CameraX pipeline, so the scanner silently stopped scanning.
- `HttpsUpgradeFallback.Registry` bounded at 64 entries, drop-oldest, under a
  lock (it was an unbounded `ConcurrentHashMap`).
- Quick-switcher create: blank guard, in-flight guard, both outcomes re-arm.
- DNS fields: invalid input commits nothing and says why.

### 3.3 UI

- 48dp minimum touch targets: `QuietIconButton` (close tab, delete bookmark,
  delete history — its `clickable` was *inside* a 40dp box, so 40dp was the
  real hit rect) and the shields chip (24dp), with the pill's compensating
  padding dropped so the row height is unchanged.
- Reader mode: hardcoded `#FCF8F0` / `#101014` / `Color.Gray` / `#DDDDDD` /
  `#222222` replaced with `LocalRoomExtras` + `MaterialTheme.colorScheme`
  values; `dark` is an override of the real mode rather than a flat `false`.
- `RoomBrowserTheme` restores `isAppearanceLightStatusBars` /
  `…NavigationBars` on dispose instead of leaving them changed.
- Landscape / short screens: the QR dialog, export, export-delivery, vault
  passphrase and backup passphrase dialogs all scroll; the paste-JSON field is
  `heightIn(min, max)` rather than a fixed 160dp; the agent panel takes 94% of
  a window under 480dp tall instead of a flat 72%.
- Preset subtitles capped at 2 lines with ellipsis (the DNS presets' IPv6 line
  wrapped to four lines at 320dp).
- Send glyph mirrors in RTL (`Icons.AutoMirrored.Filled.Send`).

### 3.4 UX

- Confirmation before Download ▸ Delete, Clear site data, Theme reset, provider
  removal, Close other tabs, Close tabs to the left.
- "Clear site data" states its true scope — it is a whole-profile wipe in a
  sheet headed by the current host, next to two rows that really are per-site.
- Forms keep input on error, and show the error inline: profile name (edit
  dialog), DoH URL, DoT hostname.
- A denied notification permission is explained, with a one-tap route to the
  system setting (falling back to app details, so it is never a dead end).
- The vault passphrase field clears on every rejected attempt, not just the
  first.
- The search-suggestions subtitle no longer claims queries are sent, because
  nothing calls the fetch (§5).

### 3.5 Architecture

- `ProfileSwitchExecutor` takes the caller's `CoroutineScope` instead of
  allocating one it never cancelled, so the coroutine — which captures the
  Activity and its ViewModel through `Host` — has an owner that can stop it.
- Scheduling moved before the process kill; the kill is reached only on the
  success path.
- `BrowserActivity`'s network-gate collector is `repeatOnLifecycle(STARTED)`:
  a bare collector stayed subscribed while STOPPED, consumed the gate it could
  not act on, and the value was gone by the time the activity returned.
- `PasswordsActivity`'s vault collector is `flowWithLifecycle(STARTED)` —
  `LaunchedEffect` is scoped to the composition, which outlives visibility.
- `GlobalScope` removed; the graph owns a documented `appScope`.
- `renderAttachments` is `internal`, not public (it is called from unit tests).

### 3.6 Background work

- `WorkManager.Configuration` gained `setDefaultProcessName(packageName)`, and
  scheduling is gated on the default process — `:browser` was racing it for the
  same singleton.
- `ExistingPeriodicWorkPolicy.UPDATE` instead of `KEEP`: a `KEEP`'d schedule
  ignored every constraint change shipped after it was first enqueued.
- `setRequiresBatteryNotLow(true)` on the cleanup constraints.
- `DownloadActionReceiver` declares `android:process=":browser"` so the
  broadcast lands in the process that owns the transfer.
- Exact alarms: see §1.2.

### 3.7 Memory

- The dApp bridge is disposed when its engine is destroyed. A `WeakHashMap`
  entry was not enough — an in-flight relay coroutine is a strong reference, so
  the bridge of every closed or LRU-evicted tab outlived its engine, still
  holding a Handler and still trying to respond into a destroyed WebView.
- One shared `OkHttpClient` across bridges instead of one connection pool and
  dispatcher thread per tab.
- The popup transport WebView is reaped on a timeout. It only died when it
  *navigated*, so a `window.open()` with no URL left a live renderer per popup
  for the life of the process.
- `FullscreenMediaHost`'s `AndroidView` releases its container on dispose.
- The local-AI try dialog unloads its GGUF handle on both dismissal paths and
  in `onDestroy` — a multi-GB native allocation was held until the next unload.
- A throwaway `WebView(context).clearCache(true)` in `clearEngineStorage` is
  destroyed in a `finally`.
- `HttpsUpgradeFallback.Registry` bounded (above).
- Deferred engine actions are dropped when their engine is destroyed.

---

## 4. Testing

Run on GitHub Actions, one batch, per the project's build loop. Run
[`37005591214`](https://github.com/SecretArrow/room-browser/actions/runs/37005591214)
on `bb6cd8d` — **green on the first round**, so rounds 2 and 3 of the FASE 6
budget were not needed.

| FASE 5 gate | Where it ran | Result |
|---|---|---|
| `lintFix` (auto-fix) | job `autofix` | ✅ no changes to make — the batch needed no mechanical cleanup |
| Android Lint | `Static analysis (Android Lint)` | ✅ pass (`abortOnError = true`, so this is zero lint errors) |
| ktlint / detekt | — | **not configured in this project** (see §6.6) |
| `testDebugUnitTest` | `Unit tests (domain + app)` | ✅ **745 / 745** — 0 failures, 0 ignored |
| `assembleDebug` | `Debug build (all ABIs)` | ✅ pass |
| `connectedDebugAndroidTest` | job `e2e`, API 30 emulator | ✅ **32 / 32** — 0 failures, 0 skipped |
| `assembleRelease` | `Release build validation (unsigned)` | ✅ pass |
| Signed release | job `release` | ✅ pass, signature verified |

**777 tests, 777 passed, 0 failed, 0 skipped** — which is every `@Test` in the
repository (745 unit across `app` 255 / `core:domain` 431 / `core:wallet` 59,
plus 32 instrumented).

Per-module unit results:

| Module | Tests | Failures | Ignored |
|---|---|---|---|
| `app` | 255 | 0 | 0 |
| `core/domain` | 431 | 0 | 0 |
| `core/wallet` | 59 | 0 | 0 |

Notes on the run:

- The `TabsE2eTest.closeCardByTitle()` risk called out in §6.8 **passed**. The
  40dp → 48dp growth of `QuietIconButton` moves the close button's centre by
  ~4dp, and the assertion only requires that centre to fall inside the card's
  bounds — which are an order of magnitude wider. No test change was needed.
- The e2e emulator is API 30, below the `SDK_INT >= 33` guard, so the new
  POST_NOTIFICATIONS denial path is **not** covered by CI. It was verified by
  reading, not by test.
- The `lint-report` artifact uploaded empty, so no warning count is quoted
  above. The lint *step* passing under `abortOnError = true` is the real
  signal; `warningsAsErrors` is `false`, so warnings would not have failed it
  either way.
- The two desktop jobs were skipped by design (they are tag/dispatch gated) and
  the release job's `!cancelled()` condition means that did not hold up the
  Android release.

### Release

**[v1.0.105](https://github.com/SecretArrow/room-browser/releases/tag/v1.0.105)** — published 2026-10-02 12:51 UTC, not a draft, not a prerelease.

| Asset | Size |
|---|---|
| `room-browser-v1.0.105-arm64-v8a-release.apk` | 16 MB |
| `room-browser-v1.0.105-armeabi-v7a-release.apk` | 15 MB |
| `checksums.sha256` | — |

Split per ABI as requested. `x86` / `x86_64` are deliberately not shipped:
CI sets `RB_RELEASE_ABIS="arm64-v8a,armeabi-v7a"`, which also disables the
universal APK — those two ABIs are emulator-only targets. Both APKs carry the
release signature (verified by a dedicated step, not assumed). Desktop stays
pending, as instructed.

---

## 5. Known issues

### 5.1 Needs your decision (deliberately not changed)

1. **Re-showing the wallet recovery phrase — DECIDED, implemented.** You
   approved the row. `engine.revealMnemonic()` is now reachable from the UI as
   **Manage ▸ Show recovery phrase**, behind the unlocked session, masked until
   a second tap, with no copy affordance (a clipboard outlives the sheet; the
   password-sealed export remains the only way to take the phrase out of the
   app). The onboarding note no longer claims "this screen never appears again",
   because that would now be false — it says instead that the paper copy is the
   only one that survives losing the phone. The original objection, that the
   row would break a promise the product makes on screen, was resolved by
   fixing the promise rather than keeping the gap.
2. **Exposing public keys over the dApp bridge.** Cosmos (`keplrKeyResult`,
   `signArbitrary`, `getOfflineSigner().getAccounts()`), Aptos `account()` and
   Bitcoin `connect()` all want a public key. The bridge currently returns none,
   which is why some dApps on those chains cannot complete a connection. The
   documented policy is "no key material leaves the engine", and a public key is
   not a secret — but it is a documented reversal, so it is yours to make.

### 5.2 Deferred, with reasons

| Item | Why not now |
|---|---|
| Extract 433+ literals into `strings.xml` | 17,915 lines of Compose, and 118 `By.text(...)` e2e selectors assert the literals. Must land with the test update in one change. |
| Migrate 61 test-id `contentDescription`s (`agent_send`, `omni_field`, …) to readable labels | The same 69 `By.desc(...)` selectors depend on them. Separate commit, both halves together. |
| `collectAsState` → `collectAsStateWithLifecycle` (25 sites) | The dependency is already on the classpath; this is mechanical but touches 25 screens and deserves its own review. |
| Downloads and the multi-GB `.gguf` fetch → `CoroutineWorker` + FGS | Both currently die with the process. A real redesign, not a fix. |
| `BrowserActivity:73-75` `runBlocking` on the main thread | Removing it means making the cold-restore profile read async, which changes the `setContent` contract. |
| Refcounted `WalletEngine.unbind()` from `onCleared` / `onDestroy` | The engine is shared with the wallet dashboard; unbinding under a live second owner is worse than the leak. Needs refcounting first. |
| `android:taskAffinity=""` on wallet/passwords activities | Changes recents behaviour app-wide; wants a deliberate decision. |
| `areNotificationsEnabled()` banner | The *denial* path is now handled; the "revoked later in Settings" path needs new plumbing. |
| Search suggestions dropdown | `BrowserViewModel.fetchSuggestions` has zero callers; the setting is inert. Needs a UI, not a patch. |
| History search box | `BrowserRepository.searchHistory` has zero callers. A client-side filter over `recentHistory` would silently miss older history — worse than no box. |
| Tab thumbnails | Real bitmaps are captured and stored, but `tabManager` is private and `viewModel.tabs` is `List<TabEntity>` with no bitmap field. Needs an accessor on the ViewModel. |
| Undo on destructive snackbars | `snackbar` is `MutableStateFlow<String?>` consumed with no action label, and there is no inverse for `deleteHistoryItem` / bookmark delete. Needs a repo restore API. |
| `snackbar` / `quickSwitcherSignal` / agent `messages` as replayable `StateFlow`s | They can re-deliver on a config change. Converting them to consumed events touches every collector. |
| `AgentComposer`'s own `imePadding()` in landscape | With the keyboard up it can exceed the panel height. The fix hoists `imePadding()` above the height modifier, which changes the layout contract that `agent_composer_field` / `agent_send` are typed into. |
| Find-in-page match count, QR scanner entry point, bookmark edit UI, IP-history UI | Missing features, not defects. |

### 5.3 Pre-existing, found but out of scope

- `BrowserViewModel:1627-1630` calls `clearEngineStorage` inside
  `withContext(Dispatchers.IO)`, where `WebView(context)` construction throws
  and is swallowed by the surrounding `runCatching`. That path's HTTP-cache
  clear was already a silent no-op and still is; the other caller
  (`:1751-1754`, on `Dispatchers.Main`) is where the leak actually bit.
- `ProfileEngine.wipeWebViewDirs` has zero callers anywhere in the repo.
- `AgentProviderEditorActivity` uses the Compose-1.7-deprecated
  `androidx.compose.ui.platform.LocalLifecycleOwner` (as `AgentPanel.kt:148`
  already does). Warning only — `warningsAsErrors = false`.

---

## 6. Recommended next steps

1. **Rotate the GitHub token** in `PAT_TOKEN` — it was shared in chat.
2. **Move the signing-keystore backup passphrase into a password manager.**
   The encrypted keystore backup under `backup/` is tracked and this repository
   is public, so that passphrase is the only thing protecting it — it should not
   live in a plaintext file on a workstation. (The exact local path was given
   separately; it is deliberately not written down here.)
3. **Rotate the leaked AgentRouter API token.**
4. **Decide the two §5.1 policy questions** (recovery-phrase reveal, dApp
   public keys). The second one is what currently blocks Cosmos, Aptos and
   Bitcoin dApp connections.
5. **Land the string-resource extraction and the `contentDescription` migration
   as two dedicated commits**, each updating its e2e selectors in the same
   change. These are the two biggest sources of future test breakage.
6. **Add `ktlint` or `detekt` to the build.** There is neither today, and
   `warningsAsErrors = false`, so style and deprecation drift is invisible to
   CI.
7. **Add a unit test for the new `requireTls` split** in `WalletEngineTest` —
   `http://` must be rejected on the dApp path and accepted on the user's own
   Add-network path.
8. **One geometric e2e assertion is worth knowing about.**
   `TabsE2eTest.closeCardByTitle()` locates the close button by whether its
   *centre* falls inside the card bounds; growing it 40dp → 48dp shifted that
   centre ~4dp. It passed on this run, but it is the one assertion in the suite
   that is sensitive to touch-target sizing — keep it in mind the next time a
   row's controls are resized.
