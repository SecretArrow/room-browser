# AGENTS.md — Room Browser (WebView edition)

This repository is ONE of TWO sibling projects. Read this before touching anything.

| | This repo | Sibling repo |
|---|---|---|
| Directory | `/home/dev/room-browser/room-browser-webview` | `/home/dev/room-browser/room-browser-geckoview` |
| GitHub | `SecretArrow/room-browser` | `SecretArrow/room-browser-geckoview` |
| **Engine** | **Android WebView** | **Mozilla GeckoView** |
| Engine artifact | `android.webkit.WebView` + `androidx.webkit` (provided by the device) | `org.mozilla.geckoview:geckoview` (bundled in the APK) |

**This is the original "Room Browser" repository, and it keeps WebView.** It is not being
migrated, frozen, or turned into the GeckoView project. Both repositories are developed in
parallel and both ship — the owner is deliberately building one browser on two different
engines.

## The one rule that matters most

> Never edit the sibling repository from a session working in this one, and never copy a
> file between them without deciding, on purpose, whether the file is **shared** or
> **engine-specific**.

If you are asked to change this repo, change only this repo. If the change is not
engine-specific, say so and tell the owner it also belongs in the sibling — do not go and
apply it there yourself unless explicitly told to.

**Especially important here:** when asked to work on "the GeckoView one", stop. That is a
different directory and a different GitHub remote. `SecretArrow/room-browser` (this repo)
is the WebView edition.

## Shared vs engine-specific

The long-term target is that the two repositories are **byte-identical outside the engine
module**, so a feature that has nothing to do with the engine can be applied to both by
copying files. The facade described below is what makes that true.

**Shared — must stay identical in both repos.** If you change one of these here, the same
change belongs in the sibling:

- `android/core/domain/` — pure Kotlin: profiles, devices, user agents, filters, agent
  protocol, credentials, URL intelligence.
- `android/core/wallet/` — chain adapters, HD keys, RPC transport. No engine types.
- `android/app/src/main/kotlin/com/roombrowser/browser/wallet/` — wallet
  contract/engine/repo/UI. Everything here is shared except `dapp/WalletBridge.kt`.
- `android/app/src/main/kotlin/com/roombrowser/agent/` — the AI agent. It drives pages
  through the engine facade, never through a raw engine type.
- `android/app/src/main/kotlin/com/roombrowser/data/`, `theme/`, `qr/`, `ui/` (except the
  engine host), `localai/`.
- `android/app/src/main/kotlin/com/roombrowser/browser/WebClients.kt` — `RoomSessionListener`
  and `RoomVaultBridge`, written against the facade's `EngineSessionListener`. No engine type.
- `desktop/` — the C desktop edition is engine-independent and identical in both.
- Everything under `android/app/src/main/assets/room_bridge/` that is page-side JavaScript
  with no engine API in it.

**Engine-specific — deliberately different. Do not "fix" one to match the other:**

- `android/engine/` — the entire facade *implementation*, which exists here now. This is the
  only place allowed to name the engine's own types.
- `android/app/src/main/kotlin/com/roombrowser/browser/engine/` — profile binding, settings
  application, storage wipe (the *shapes* match; the calls do not).
- `android/app/build.gradle.kts` — the engine dependency, ABI splits, native packaging.
- `android/gradle/libs.versions.toml` — `webkit` here, `geckoview` there.
- `android/app/src/main/AndroidManifest.xml` — the `<queries>` WebView-provider probe (this
  repo needs it; the sibling does not) and `windowSoftInputMode`.
- Engine-shaped tests: `BrowserNavigationE2eTest`, `WalletE2eTest`, `ProfileIsolationTest`,
  the `TabsE2eTest` live-engine probe.
- `README.md`, `SECURITY.md` where they name the engine.

**The facade is the boundary.** Both repos converge on an app-owned facade in
`android/engine/` (`EngineSession`, `EngineHost`) that names **no** engine type. `:app`
depends on `implementation(project(":engine"))`, so the engine's classes are not on
`:app`'s compile classpath.

**It has landed here.** `android/settings.gradle.kts` includes `:engine`, and no file under
`android/app/src/main/` names `android.webkit.` or `org.mozilla.geckoview` any more. The two
repos are therefore near-identical outside the engine module. Measured at `0572801`, the
whole-repo delta against the sibling was the manifest, the GeckoView-only assets
(`engine/gecko/*`, the `roombridge` extension, two `engine` tests) and build files — **no
shared file under `android/app/src/main/kotlin/com/roombrowser/` differed at all.**

So a shared change is usually a **cherry-pick, not a re-authoring**. Measure before assuming
a port is needed:

```bash
git diff --name-status <this-main> <sibling-main>
```

## Engine facts for THIS repo

- WebView comes from the **device**, so the APK stays small (~16 MB per ARM ABI) and the
  engine version varies by device. `installedWebViewEngines` and the manifest `<queries>`
  block exist to report which provider is actually installed.
- `WebView.setDataDirectorySuffix` is **process-wide and one-shot** — it is what
  `bindProcessToProfile` relies on, and it is why a profile switch restarts the process.
  Do not call it twice in one process.
- Release ABIs are `arm64-v8a` and `armeabi-v7a`; the e2e emulator runs `x86_64`.
- The `runWhenAttached` load deferral in `BrowserViewModel` works around a real WebView 83
  defect on the CI emulator. Do not delete it as "unnecessary" — it is load-bearing here.

## Build, test and release — CI only

**Never build, test, assemble or release locally.** All of it happens in GitHub Actions:

```bash
git push                      # main or a branch
gh run list --limit 5         # find the run
gh run watch <id>             # follow it
```

Push with the workaround the stale credential store requires:

```bash
HOME=/tmp/rbhome GH_CONFIG_DIR=/home/dev/.config/gh git push origin <branch>
```

`pull_request` runs `quality` + `e2e` but **not** `release`. A green push to `main`
**auto-releases** (see the `if:` on the `release` job), so do not push half-finished work to
`main`.

Keep CI fast — it is the only build loop there is:

- Docs-only changes are already skipped by `paths-ignore`; keep `.github/**` out of that
  list so pipeline edits are validated by the pipeline.
- `.github/workflows/ci.yml` runs `autofix`, `quality` and `e2e` concurrently, not in a
  chain. Do not add `needs:` edges into them.
- The NDK/CMake install block is duplicated across the Android jobs and is copied verbatim
  on purpose; keep the copies identical.
- A failure in the fast jobs (`autofix`, `quality`) is worth far more than one in `e2e` — it
  costs a minute instead of an hour. Put engine-independent assertions in JVM unit tests so
  they fail there.
- `androidTest` compiles **only** in the `e2e` job. `quality` can be green while test code
  does not compile, so a typo in `androidTest` costs a full e2e cycle. Compile-check test
  sources by inspection before pushing.
- `quality` retries once, and the retry warning is echoed into every CI log — grepping for
  it is not evidence that a retry happened. To prove a new test actually ran, count `@Test`
  locally instead.

## Secrets, credentials and safety

- The GitHub token is used from the environment; it is **never** committed, echoed, pasted
  into a file, or sent anywhere. Do not print it.
- `~/room-browser-signing-backup/PASSPHRASE.txt` is never printed. The signing backup
  archive IS tracked and **both repos are public**.
- When inspecting `~/.git-credentials` or `~/.netrc`, usernames only.
- Never disable TLS or certificate validation globally, and never add a certificate-error
  bypass — the existing fallback paths around `onReceivedSslError` are deliberately narrow.
- Never upload wallet files or private keys anywhere.
- Do not introduce artificial delays or aggressive blocking.
- Fix root causes, not symptoms. A comment explaining a workaround is a reason to re-check
  whether the defect still exists, not a reason to keep the code forever.

## Repo conventions

- Reply to the owner in Indonesian. **No Chinese characters in terminal output** — their
  terminal cannot render them.
- Commit messages end with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- PR descriptions end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- `androidTest` method names must be **snake_case only**. minSdk 28 means DEX below 040, and
  a backticked name with spaces dies at dexing. A JVM test guards this in `quality`.
- JUnit4 tests that assert with Truth must end in `runBlocking<Unit>` or an explicit
  statement — a Truth assertion as the last expression makes the method non-void and JUnit
  then runs none of the class.
- Only `Exactly`/`AtLeast` take `...ElementsIn`; the none/any/all families are
  `containsNoneIn`/`containsAnyIn`/`containsAllIn`.
- UiAutomator sees only the active window: a modal sheet hides the whole WebView from
  `By.text`, so assert the sheet first.
- `visibleBounds` lies about half-clipped nodes — a partly scrolled LazyColumn item reports
  a non-empty rect. Test containment in its container instead.
- `executeShellCommand` has no shell pipe: `| grep` arrives as arguments, so a "filtered"
  probe may be filtering nothing.
