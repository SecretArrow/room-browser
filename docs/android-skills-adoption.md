# Android Skills Adoption

What we track from [`github.com/android/skills`](https://github.com/android/skills)
(the Android team's agent-skills repository, Apache-2.0), what our codebase
already satisfies, and what remains open. Audited 2026-11-07.

## Why

The repo's automation surface is unusual: the E2E suite and the macro recorder
both read the **accessibility tree**, the app is **multi-process** with
one-shot WebView profile binding, and CI is the only build loop. The skills
below were selected because they speak to exactly those constraints.

## Skills reviewed (35 total; these 6 in depth)

| Skill | What it covers |
|---|---|
| `testing/testing-setup` | Test strategy: JVM vs instrumented split, fakes over mocks, screenshot matrix, E2E budget (±5%), back-handling and "exit through home" coverage |
| `jetpack-compose/jetpack-compose-performance` | Recomposition/stability, lazy list `key` + `contentType`, avoiding `BoxWithConstraints` in hot paths, baseline profiles |
| `security/android-intent-security` | PendingIntent immutability, nested-intent sanitizing (`IntentSanitizer`), `RECEIVER_NOT_EXPORTED` |
| `security/android-permissions-security` | IPC/provider hardening: split read/write permissions, no wildcard URI grants, caller-UID verification |
| `performance/anr-audit-and-fix` | Static audit for main-thread blocking; off-thread View mutation as sync-barrier-leak ANR risk |
| `devtools/android-cli` | `android layout` dump semantics: only elements with text/content-desc/interactions appear; WebView content does not always appear in the dump |

## Practices verified as ALREADY satisfied (evidence, 2026-11-07 audit)

1. **Lazy list keys — `jetpack-compose-performance`.** All 12 `items(…)` call
   sites in `app/src/main` pass `key =`. Stable identity for tabs/history
   lists matters doubly here because the macro recorder references items by
   what the a11y tree shows.
2. **PendingIntent immutability — `android-intent-security`.** 8 PendingIntent
   creations, all `FLAG_IMMUTABLE` (`FLAG_UPDATE_CURRENT`/`FLAG_NO_CREATE` are
   behavior flags, not mutability). No mutable PendingIntent exists.
3. **Exported surface — `android-intent-security`.** Manifest: 33 components
   `exported="false"`, 1 `exported="true"` (the launcher activity that must
   accept `ACTION_VIEW` — this is a browser). Dynamic receivers are
   `RECEIVER_NOT_EXPORTED`.
4. **No main-thread webkit calls in app code — `anr-audit-and-fix`.**
   `CookieManager.getInstance` and friends appear only inside `:engine`
   behind the facade, never in `app/src/main`.
5. **JVM-first assertions — `testing-setup`.** Catalog/protocol/partition
   logic (e.g. `HeadlessToolCatalogTest`, `OllamaDtosTest`) lives in JVM
   `test` source sets; `androidTest` is reserved for what genuinely needs a
   device (engine binding, process isolation, system back).
6. **Back-handling and exit contract coverage — `testing-setup`.** The E2E
   suite asserts the three-way exit dialog on both page and homepage, plus
   "second launch opens its URL in the running engine" (deep-link semantics).

## Adopted going forward

1. **contentDescription discipline** (from `android-cli` dump semantics):
   every interactive Compose control gets a non-null `contentDescription`;
   decorative art gets an explicit `null`. The UiAutomator dump — and
   therefore our E2E suite and macro recorder — only sees elements with
   text, content-desc or interactions.
2. **WebView content is not reliably in the a11y dump** (explicit warning in
   `android-cli`): page-level assertions in E2E and macro playback prefer
   the page's own text elements served by our test server, with
   screenshot-based probes as the fallback.
3. **`AGENTS.md` convention** (from `testing-setup`): this file plus the
   root `AGENTS.md` are the standing record for agents working in this repo.
4. **Screenshot matrix at sizes × font scale 1.5** (from `testing-setup`):
   tracked as an open item below — cheapest future regression net for
   edge-to-edge/inset bugs in the browser chrome.

## Known gaps (honest)

- `android/skills` has **no WebView-hardening skill** (both security skills
  explicitly exclude web integration) and **no CI/CD skill**. Our WebView
  rules (no `handler.proceed()`, user trust anchors only — see root
  `AGENTS.md`) remain the source of truth here.
- Baseline Profiles for cold start + main scroll journeys are **not yet**
  generated; the release builds run R8 but ship no baseline profile.
- Screenshot testing (Compose Preview Screenshot / Dropshots) is **not**
  wired into CI yet.

## Installation notes (for future agents)

The skills install via `android skills add <name> --project=.` (Google's
`android` CLI) or `/plugin marketplace add android/skills` in Claude Code;
as plain `SKILL.md` folders they can also be vendored directly. We chose
**documentation over vendoring**: this file carries the practices that
survived contact with this codebase, so agents do not re-read 35 upstream
skills per session.
