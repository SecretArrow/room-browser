# Room Browser

**One Android browser, multiple completely isolated profiles.**

Room Browser is a privacy-focused Android web browser built around a
desktop-style **profile system**: every profile behaves like an independent
browser installation with its own cookies, storage, history, bookmarks,
downloads, permissions, search engine, user-agent and privacy settings.

```
Personal   →  4f4e…uuid  →  own cookie jar / storage / history
Work       →  92ac…uuid  →  own cookie jar / storage / history
Research   →  1b7d…uuid  →  own cookie jar / storage / history
```

## Feature highlights

- **Real profile isolation** — each profile binds the browser engine to its
  own WebView data directory; switching profiles restarts the engine process.
  No shared cookie jar, no shared localStorage/IndexedDB/cache/service workers.
- **Profile manager** — create / open / edit / duplicate / delete / rename /
  re-style / lock (biometric) / set-default / reset / export & import settings.
- **Privacy shields** — ad, tracker, cross-site tracker, popup and malicious-site
  blocking (bundled offline blocklist — never phones home), HTTPS upgrades,
  third-party-cookie control, per-site overrides.
- **Privacy dashboard** — statistics recorded exclusively from real blocking events.
- **Tabs** — grid/list, private tabs, groups, pin, move, duplicate, reopen closed,
  per-profile persistence and lazy restore.
- **Bookmarks / History / Downloads** — fully profile-scoped; downloads with
  pause/resume/retry and correct scoped-storage usage.
- **DNS privacy** — per-profile and global DoH/DoT configuration for app
  connections (see honest limitations below).
- **User-Agent manager** — per-profile Android/desktop presets or custom UA.
- **Profile Network Conflict Protection** — optional, informational warning when
  a profile is opened from a public IP previously associated with another profile.
- **Omnibox intelligence** — URL/host/IP/localhost/file-URI detection, search,
  optional search suggestions, QR scan, voice input.
- **Reader mode, find-in-page, translate, desktop mode, save page as PDF,
  add-to-home-screen, share sheets**.
- **AI Agents (autonomous browsing)** — Room Agent drives the real browser
  for you: navigate, read pages, click, fill forms and manage tabs while
  you watch. Chat panel with streaming answers, tool-step cards, session
  history and Allow/Deny action approvals. **Social automation tools**
  (`auto_like`, `auto_repost`, `auto_reply`, `auto_post`) complete
  like/repost/reply/post tasks on any social feed, and turns **keep
  running in the background** (foreground service + wake lock, with a
  progress notification and Stop action) when you leave the app or turn
  the screen off. Any OpenAI-compatible provider
  works (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Together,
  Ollama, LM Studio or custom) with **manual provider input** and
  **model lists fetched live from the provider's `/models` endpoint**.
  API keys are encrypted with AndroidKeyStore. See `docs/AI_AGENTS.md`.
- **Biometric profile lock** — fingerprint/face or device credential.
- **Material 3 UI** — light/dark/AMOLED themes, per-profile accent colors,
  error pages, empty states, accessibility semantics.

## Honest limitations (no false claims)

- WebView page-load DNS uses the Android OS resolver; DoH/DoT in Room Browser
  protects the app's own connections (public-IP checks, suggestions, downloads).
- A normal third-party app cannot fully control WebRTC IP handling; camera and
  microphone remain under explicit permission control.
- Private tabs share the profile's cookie jar while open; session cookies are
  purged when private tabs close (documented in `PRIVACY.md`).
- **Room Browser is not an anonymity tool.**

See `PROFILE_ISOLATION.md`, `SECURITY.md` and `PRIVACY.md` for full details.

## Repository layout

```
app/                 Android application (Compose UI, browser engine, Room DB)
core/domain/         Pure-Kotlin domain logic (JVM-testable, no Android deps)
tools/profile-test-site/   Local profile-isolation test website
.github/workflows/   CI/CD (quality gates + signed per-ABI release builds)
```

## Building

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew assembleDebug          # debug APKs (all ABIs)
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lint                   # static analysis
./gradlew assembleRelease        # release APKs (signed via env vars, see BUILD.md)
./gradlew bundleRelease          # AAB
```

Full instructions: `BUILD.md` · release artifacts & signing: `RELEASE.md` ·
testing guide: `TESTING.md`.

## License

Provided as-is for this project. All bundled filter lists are compiled from
publicly documented blocklist entries; no Brave/Google proprietary assets,
branding or code are included.
