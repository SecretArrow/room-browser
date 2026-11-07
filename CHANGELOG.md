# Changelog — Room Browser (edisi WebView)

Semua perubahan penting pada aplikasi ini dicatat di sini, sejak commit
pertama edisi WebView (bootstrap dari *sibling* edisi Gecko, 24 Oktober
2026). Penomoran versi berlanjut dari penghitung bersama kedua edisi;
karena itu sejarah repo ini dimulai di sekitar v1.0.85 meski commit
pertamanya bertanggal 2026-10-24. Setiap rilis dipublikasikan otomatis
oleh CI hanya bila seluruh gate hijau (lint, unit test, E2E emulator)
dan APK-nya ditandatangani keystore produksi yang sertifikat
SHA-256-nya diverifikasi terhadap nilai yang di-pin di `ci.yml`.

Isi bullet adalah pesan commit asli (apa adanya, bahasa Inggris) agar
setiap baris bisa ditelusuri ke commit-nya.

## v1.0.182 dan sebelumnya

Commit-commit bootstrap (24 Oktober 2026) — port awal dari edisi
Gecko — tercakup dalam seksi v1.0.182 di bawah.

## v1.0.182 — 25 Oktober 2026

- e2e: port the migration-replay strip and the draft-epoch composer key from the gecko edition
- ui: port the agent header's one-line title fix from the gecko edition
- e2e: port the plugin card subtree fallback from the gecko edition
- e2e: port the bounds-capture card walk from the gecko edition
- defaults: google is the out-of-the-box engine, httpsUpgrade joins the default-off shields (ported from the gecko edition)
- test: the guard tests move with the new defaults (ported)
- agent: a module's activation is never a silent success, and the devtools fixture stops hunting the emulator (ported)
- test: the re-activation's count is provable in the module's own storage (ported)
- merge: per-session chat state, skills system, plan panel, wallet tools, google default, https-upgrade off, UA 156/158
- fix(agent,skills,engine): per-chat turn state, skill integrity, UA client hints
- fix(tasks): a declined scheduled run is not re-run in the same drain
- fix(engine): the neutralized UA can be null, so fall back to the stock string
- test(engine): pin the client-hint identity across every shipped Chrome preset
- fix(engine): read the mobile flag through the Java getter's Kotlin name
- test(e2e): prove the identity Google sees carries no embedded-browser tell
- fix(engine): Sec-CH-UA-Mobile contradicted the User-Agent on every phone
- fix(engine): lint refuses the restricted feature constant, suppress it as configure does
- fix(e2e): key the page-identity assertion on what the engine can install

## v1.0.183 — 25 Oktober 2026

- fix(identity): the high-entropy hints agree with the page, and name the gap when they cannot

## v1.0.184 — 25 Oktober 2026

- feat(identity): name the surface that makes a sign-in page call this an app
- fix(identity): the self-check is one tap away, and its own test compiles
- fix(ui): FactRow keeps its @Composable, and the new row gets only one
- docs(ui): the DNS note belongs to dnsSummary, not to the self-check
- refactor: remove the proxy feature
- style: drop the blank line ktlint refuses before the companion's brace

## v1.0.185 — 25 Oktober 2026

- fix(identity): ask the engine in the process that owns it

## v1.0.186 — 26 Oktober 2026

- fix(identity): measure X-Requested-With instead of trusting a dead flag
- style: order the new engine import where ktlint wants it
- fix(downloads): read blob: and data: files out of the page
- test(downloads): make the blob e2e test fail with a message, not a cast
- fix(downloads): sanitise a filename the page supplied
- fix(identity): report the X-Requested-With header honestly, and stop promising it away
- test(plugins): read the new chat's own count, and say why when it fails
- fix(identity): the e2e cannot cross-check a report from another process
- ci(e2e): give the emulator suite the time it actually takes
- docs(identity): correct what the emulator actually reported
- docs(identity): say what the allow-list call really is
- test(e2e): make the counter failure say which of its causes it hit
- fix(e2e): the diagnostics must compile
- test(plugins): scroll to the card when its node has no place in the window

## v1.0.187 — 26 Oktober 2026

- fix(rbcore): full Android redaction parity, multibyte-safe scanning, shared translate URL
- fix(desktop): repair the shell borrow crashes and wire the dead flows
- fix(browser): popups inherit private-tab privacy and external schemes hand off to the OS
- ci: atomic idempotent releases, honest quick-apk verification, universal APK + AAB
- fix(redaction): return the line-trimmer as a function, not a closure
- fix(ci): retry the asset upload across the uploads-host 404 race
- feat(downloads): universal download support for the Android edition
- fix(browser): read the storage-ask counter, do not delegate on it
- fix(ci): upload release assets through gh release upload, not a redirected POST
- ci(e2e): one retry for the classes that failed pass 1
- ci(e2e): parse the failed classes from the merged device XML

## v1.0.188 — 26 Oktober 2026

- test(e2e): find the counter's card every iteration; seed the provider we mean

## v1.0.189 — 27 Oktober 2026

- fix(identity): strip X-Requested-With from the main document Google's sign-in reads
- style: ktlint import ordering in GoogleIdentityE2eTest (assertNotEquals < assertNotNull)

## v1.0.190 — 28 Oktober 2026

- fix(identity): re-issue the sign-in redirects, and make per-site data clearing real
- fix(desktop-mode): turn overview mode on so the wide desktop layout fits the phone screen
- fix(identity): tune every UA's Chrome token to the engine's real Chrome version
- fix(identity): auto-recover Google's sign-in refusal instead of dead-ending
- fix(diagnostics): read the WebView provider identity through its real API
- fix(ci): repairs the first CI-covered push surfaced
- fix(engine): read the sign-in diagnostics through the facade, not webkit
- fix(identity): the shim presents the identity the wire carries

## v1.0.191 — 28 Oktober 2026

- fix(identity): no page-world identity JavaScript; Chromium-only UA presets
- style(adjustments): drop blank line before closing brace (ktlint fix)
- test(engine): fix run-648 unit-test failures from fe19dea fallout

## v1.0.192 — 28 Oktober 2026

- ci(e2e): smoke suite on push, full 108-test suite nightly

## v1.0.193 — 29 Oktober 2026

- engine(identity): pre-emptive XRW cleaner + no page-world scripts on sign-in hosts
- fix(engine): ktlint import order + blank-stamp semantics in RequestedWithCleaner

## v1.0.194 — 29 Oktober 2026

- engine(identity): Google-wide bare page world + opt-in per-device fingerprint
- test(identity): IIFE frame assertion follows the codebase semicolon convention

## v1.0.195 — 30 Oktober 2026

- engine(identity): the Google wire stamps Chrome, not nothing

## v1.0.196 — 30 Oktober 2026

Perbaikan jar/cookie dan identitas yang menutup halaman 400 Google pada percobaan masuk.

- engine(jar): the malformed-400 answer -- rejoin the Set-Cookie splits, erase the poisoned jar, one clean reload

## v1.0.197 — 5 November 2026

Kebijakan WebRTC default kini DEFAULT (sebelumnya RESTRICT_LOCAL_IP) — permintaan pemilik.

- domain(profile): WebRTC joins the compatibility-first defaults
- test(profile): pin the webrtc compatibility default

## v1.0.198 — 6 November 2026

- test(e2e): the owner's own test -- type the address, assert the password page

## v1.0.199 — 6 November 2026

- test(e2e): the submit button is 'Sign in' -- the run-667 dump settled it
- test(e2e): the submit button is below the fold -- hide the IME, then swipe
- test(e2e): all labels before the geometry filter -- the ifEmpty chain ate 'Next'

## v1.0.200 — 6 November 2026

- engine(google): follow the re-issued GET's redirects by hand -- the hop cookies were the session
- engine(google): a blank redirect Location declines -- URL(context, '') would have re-followed the hop

## v1.0.201 — 6 November 2026

Rilis pertama setelah fix kompilasi wire trace `RoomGoogleTrace`.

- engine(google): the wire trace -- every Google request states how it was served
- engine(google): the trace lambda answers true itself -- setCookie's return is not a contract

## v1.0.202 — 6 November 2026

**Fix akar masalah utama**: redirect 302 main-frame dikembalikan ke WebView sehingga `window.location` dan `dsh` (state flow login Google) terbaca oleh JavaScript — bukti `source-path` telemetri halaman berubah dari `/ServiceLogin` menjadi `/v3/signin/identifier`, dan email submit langsung diterima Google.

- engine(google): the main frame's redirect belongs to the WebView -- dsh lives in the Location

## v1.0.203 — 6 November 2026

Kriteria test live diperbarui: halaman password ATAU halaman tantangan "Verify it's you" dihitung sebagai bukti email diterima (reCAPTCHA = jawaban Google untuk IP datacenter CI, bukan bug engine).

- test(e2e): two pages are Google accepting the address -- the CI emulator only ever sees one

## v1.0.204 — 6 November 2026

Layar probe identitas kini menilai request yang benar-benar dikirim engine (disertai test `SignInDiagnosticsResolvedUserAgentTest` dan `WebViewClientHintsTest`).

- identity: the check screen finally grades the request the engine actually sends

## v1.0.205 — 7 November 2026

- docs: the changelog since the first build, and the dsh redirect post-mortem
- feat(macro+reset+back): macro automation, reset settings, and the honest Back
- fix(macro): the theme and card composables live in ui.common, not browser.ui.components
- fix(macro): compile round 2 — launcher callbacks off the composables, visibility, align
- fix(macro): compile round 3 — BrowserSession's real package, the graph hop, ProfileId construction
- feat(agent): create_macro / run_macro / macro_list — the chat agent authors and operates macros
- style(e2e): ktlint — drop the unused fail import, order MacroE2eTest imports
- style(e2e): order MacroE2eTest imports (ktlint, take 2)
- style: ktlint format over the macro surfaces (imports, braces, blanks)
- style(test): ktlint order MacroModelsTest imports (:core:domain test source set)
- test(headless): classify the macro tools — a new MACRO_TOOLS side with one honest refusal
- test(e2e): align the smoke suite with the new exit-dialog contract and the once-per-process bind

## v1.0.206 — 7 November 2026

- feat(localai): +12 registry-verified presets, and record the android/skills adoption

## v1.0.207 — 7 November 2026

- docs(changelog): regenerate through v1.0.206 [skip ci]
- feat(macro): tap-to-pick point capture and friendlier hand-authored steps
- fix(macro): import the systemBars/displayCutout/union extensions the macro list uses
- style(macro): kotlinx stays in the main import block; java imports go last

## v1.0.208 — 7 November 2026

- docs(changelog): regenerate through v1.0.207 [skip ci]
- test(macro): automated coverage for the pick-on-page feature
- test(macro): import MacroStep — the appliedTo tests reference it
- fix(macro): unwrap the JSON-encoded evaluateJs answer before reading the pick probe

## Catatan: tag di luar garis sejarah edisi ini

97 tag (v1.0.85 s.d. v1.0.181)
menunjuk commit sejarah edisi Gecko yang hanya tersimpan
sebagai objek paritas antar-edisi, bukan bagian garis
sejarah `main` edisi WebView; isinya tidak direkap di sini.

