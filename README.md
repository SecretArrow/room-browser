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
  third-party-cookie control, per-site overrides. Ad/tracker/cross-site/popup
  shields are **off by default** (compatibility-first); enable per profile.
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
  history and Allow/Deny action approvals. The composer supports
  **file attachments** (attach icon next to "Include page") — text files
  are inlined into the prompt so the agent can work with them. **Social automation tools**
  (`auto_like`, `auto_repost`, `auto_reply`, `auto_post`) complete
  like/repost/reply/post tasks on any social feed, and turns **keep
  running in the background** (foreground service + wake lock, with a
  progress notification and Stop action) when you leave the app or turn
  the screen off. Any OpenAI-compatible provider
  works (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Together,
  Ollama, LM Studio or custom) with **manual provider input** and
  **model lists fetched live from the provider's `/models` endpoint** — or
  point it at your own **OpenCode server** (`opencode serve`, LAN/VPN only)
  and pick a model from its live `/provider` list.
  Provider management, agent settings and chat history run as **dedicated
  activities** (their own windows with correct insets and keyboard
  handling), and the floating agent button is **hidden by default** —
  toggle it from Browser Settings → AI Agent → *Show AI Agent button*.
  API keys are encrypted with AndroidKeyStore. See `docs/AI_AGENTS.md`.
- **Local AI (Ollama)** — menu khusus untuk AI di perangkat sendiri:
  kelola model di server Ollama (Termux di ponsel ini atau PC di LAN) —
  koneksi + status server, katalog 19 preset model terbaik untuk ponsel
  (4 tier berdasarkan RAM), tombol **Find new models** yang me-refresh
  **library live ollama.com** untuk menemukan famili model baru yang cocok
  untuk ponsel (badge ukuran jadi tombol Install, famili embedding
  disembunyikan), unduh model dengan **pause/resume** (cache layer di sisi
  server), **tuning GPU/CPU/konteks/keep-alive** yang diterapkan ke chat
  protokol Ollama natif, serta **import/export setup** sebagai manifest
  JSON kecil. Kartu provider **Select model** kini 3 baris (Nama → Model →
  Base URL) agar tidak tumpang tindih. Semua trafik lokal — tanpa cloud,
  tanpa telemetry; model TIDAK dibundel di APK (Room Browser adalah klien
  manajemen). Entry: AI Agent Settings → *Local AI (Ollama)* atau chip
  *Ollama native* di provider editor. Lihat `docs/LOCAL_AI.md`.
- **Biometric profile lock** — fingerprint/face or device credential.
- **Material 3 UI (2026 redesign)** — premium Brave-inspired-but-original
  design language: floating pill omnibox that owns the full toolbar width
  (back/forward live in the bottom bar, Brave-style, with tabs / share /
  menu), glass bottom bar with tab-count badge, rounded cards everywhere,
  modern tab management, smooth animations, responsive layouts and
  excellent dark-mode support.
- **Per-profile Theme System** — 18 hand-tuned built-in themes (Obsidian,
  Arctic, Ocean, Emerald, Midnight, Aurora, Sunset, Cyber, Royal, Sakura,
  Forest, Aqua, Crimson, Golden, Slate, Lavender, Coffee, Rose), each with
  light + dark palettes and Light/Dark/AMOLED/Auto modes. The **Theme
  Studio** (own activity) edits background/surface/accents/text/address
  bar/tab bar/navigation bar/button/border/icon/selection colors, gradient
  style + direction, corner radius, transparency, blur and contrast with a
  **live preview**, plus save/duplicate/rename/reset and JSON
  import/export. Every profile owns a FULL independent theme snapshot — see
  `docs/THEMES.md`.
- **Compatibility-first privacy defaults** — mixed content runs in
  compatibility mode, third-party cookies are allowed, JavaScript is on,
  HTTPS upgrades fall back to http automatically when the secure version is
  unreachable, and tag-manager domains are not blocked (they gate page
  render on many sites). Ad, tracker, cross-site-tracker and popup blocking
  are **OFF out of the box** (opt-in per profile in settings) so pages
  render exactly as their authors intended; malicious-site blocking and
  HTTPS-First-with-fallback stay on. Every NEW profile also automatically
  identifies as a randomly picked common mobile browser User-Agent
  (Chrome / Firefox / Edge / Samsung Internet — fingerprint diversity
  between profiles; change it any time in Profile settings). Strict modes
  stay one toggle away in settings.
- **Proper system-UI integration** — edge-to-edge with explicit
  `WindowInsets` handling: the toolbar, omnibox, sheets and dialogs never
  overlap the 3-button navigation bar (Back / Home / Recents) or the status
  bar on any API level. The system **Back** button follows browser rules:
  exit fullscreen video → close reader/find bar → collapse the agent panel →
  return from sub-screens → walk web history → background the app (the
  engine process and all tabs stay alive).

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
