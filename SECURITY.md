# Security

## Threat model

Room Browser defends against **casual cross-profile data mixing on one
device**: cookies, storage, history and permissions of one profile must never
leak into another. It does **not** defend against a fully compromised OS, a
malicious keyboard/screen-reader, or forensic disk analysis of an unlocked
device. It is **not** an anonymity tool.

## Controls

| Area | Implementation |
|---|---|
| Profile isolation | Per-profile WebView data directory (UUID suffix) + process restart on switch — see `PROFILE_ISOLATION.md` |
| Storage identity | Immutable UUIDs; names never used as identifiers |
| Profile lock | AndroidX BiometricPrompt (BIOMETRIC_WEAK + DEVICE_CREDENTIAL); locked profiles gate on open |
| Certificate errors | `onReceivedSslError` always cancels; the user sees an error page. Room Browser never auto-proceeds |
| Mixed content | `MIXED_CONTENT_NEVER_ALLOW` |
| File access | `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` all disabled |
| New windows | Blocked unless the user allows popups; non-gesture windows always refused |
| JavaScript | Per-profile default, per-site override |
| Cookies | Third-party cookies blockable per profile/site |
| Downloads | Filename sanitization (path traversal / separators / double-dot collapse), explicit notifications |
| Malicious sites | Bundled blocklist + heuristics (http, IP-literal URLs, punycode) surfaced as warnings/blocks |
| Credentials | **Never stored by Room Browser.** Autofill is delegated to the Android autofill framework; no plaintext (or any) credential storage exists in the app |
| Secrets in repo | None. Signing comes exclusively from environment variables / CI secrets |
| Backups | `allowBackup=false` + data-extraction rules exclude the DB, profile dirs and DataStore — isolated state cannot leak into cloud backups |
| Logging | Production logs never contain URLs, cookies, credentials, page contents or PII |

## Android Keystore usage

Room Browser deliberately keeps no app-managed password/key material (no
credential storage, no sync keys). The Android Keystore is therefore not
required for the current feature set; the profile lock relies on the
platform's own Keystore-backed BiometricPrompt. If optional E2E-encrypted
sync is added later, keys MUST live in the Android Keystore and never leave
the device.

## WebView / engine limitations (no over-claiming)

- Page-load DNS for WebView traffic is resolved by the OS network stack; the
  in-app DoH/DoT configuration protects the app's own connections. Users who
  need DNS privacy for page loads should also enable Android's system-wide
  Private DNS (DoT) — the DNS settings screen explains this.
- Full WebRTC local-IP hiding is not controllable by normal apps. Camera and
  microphone access remain permission-gated.
- User-Agent spoofing changes only the UA string — never real platform
  capabilities. No fingerprinting-bypass claims are made anywhere in the app.

### What a profile's device presents, exactly

A profile may be assigned one of the bundled real Android devices. That
assignment is a *claim*, not an emulation, and the difference is the whole
point of this section. What changes:

- the `User-Agent` header and `navigator.userAgent`
- `navigator.userAgentData` — `brands`, `mobile`, `platform`, and the
  high-entropy values `model`, `platformVersion`, `uaFullVersion`,
  `fullVersionList` and `formFactor`, which are the client hints that would
  otherwise name the real handset
- `navigator.platform`, `navigator.deviceMemory`, `navigator.hardwareConcurrency`
- the WebGL `UNMASKED_VENDOR_WEBGL` / `UNMASKED_RENDERER_WEBGL` strings

What changes only when the profile asks for it, through the **Screen size** row
in its own settings:

- **The reported screen.** Off by default, and off means nothing here changes:
  a profile reports this phone's own `screen.width`, `screen.height`,
  `screen.availWidth`, `screen.availHeight` and `screen.orientation`, because
  the page really is laid out on this phone's screen. A profile may instead
  claim a size — see *Claiming a screen size* below, which is the one place in
  this document where the app deliberately introduces a disagreement.

What deliberately does **not** change, because it would be both a lie and a
detectable one:

- **The layout viewport and the pixel ratio.** `innerWidth`, `innerHeight` and
  `devicePixelRatio` are the page's real width and height on this display, and
  the ratio the compositor actually renders at. No script can move them without
  re-laying the page out at a size the screen does not have.
- **Real capabilities.** Camera, microphone, sensors, codecs, battery, and
  every permission stay the hardware's own.

This raises the cost of the cheap, scripted checks that compare a UA against
a handful of obvious properties. It does **not** make a profile
undetectable, and the app does not claim it does: a page that inspects
`Function.prototype.toString` on the patched accessors, times a WebGL draw
call, or correlates dozens of unrelated signals can still tell. Profiles are
for keeping separate identities separate, not for evading a determined
fingerprinter.

#### Claiming a screen size

The Screen size row is per profile and off by default. Turning it on replaces
`screen.*` and derives `screen.orientation` from the shape claimed, so a profile
claiming a landscape screen does not also answer `portrait-primary` — that
pairing is the contradiction the shim exists to remove.

It cannot move the viewport, for the reason above. A claimed size that differs
from this display's screen therefore leaves `screen.width` and `innerWidth`
disagreeing, and that disagreement is exactly the sort of thing a fingerprinting
script looks for. The settings row says so where the choice is made, and the
fields open on the real size so that the first thing the user sees is the truth.

It exists because leaving it out is not neutrality. A profile presenting a
Galaxy S24 Ultra already reports a screen that handset never had; the setting is
how that becomes a choice the user made and can see, rather than an accident the
app never mentioned, and it is the only way to make the claim match the device.
Set it to the size the presented handset actually has. If you do not know that
size, leave it on the real screen: claiming nothing is safer than claiming a
guess.

The two desktop editions offer the same row with the same two modes, and the
claim behaves the same way there, with three differences that come from the
platform rather than from policy:

- The **available** rectangle is derived from the real one rather than set equal
  to the claimed screen. A desktop has a taskbar or a dock, and Chrome subtracts
  it; the shim measures that inset from the real display at document start and
  carries it over, so the claimed screen has an available area that differs from
  it by as much as a real one does. A claim of no inset at all would be its own
  tell.
- `screen.orientation.angle` stays `0`. The angle is the device's rotation, not
  the display's shape: a phone held sideways is 90, and a monitor is 0 whatever
  its aspect ratio, because nothing rotated it. The shape still decides
  `orientation.type`.
- The two numbers are the profile's own, so they are what a desktop browser
  window would have to be resized to for the page to agree with them. A desktop
  window can be any size, so `screen.width` disagreeing with the viewport is
  ordinary here in a way it is not on a phone — which makes the claim a weaker
  signal, not a stronger one.

#### The desktop editions

Both desktop editions present a machine from the same idea, with the rules a
desktop needs rather than the ones a phone does. A profile may be assigned
one of 1041 real laptops, desktops and workstations (2022–2025, Windows,
macOS and Linux), and what it presents is the same list as above, with these
differences:

- `mobile` is always `false` and `formFactor` is `"Desktop"`.
- `model` is the **empty string**. Chrome reports no model on a desktop, so
  a profile that invented one would be the only desktop in the world with a
  model name.
- `architecture` and `bitness` follow the machine's CPU: Apple Silicon
  reports `arm`, everything else `x86`, always 64-bit.
- `navigator.platform` and `userAgentData.platform` follow the OS —
  `Win32`/`Windows`, `MacIntel`/`macOS`, `Linux x86_64`/`Linux`.
- The User-Agent carries the **major** Chrome version only
  (`Chrome/131.0.0.0`), which is the reduced form Chrome has sent since
  2022; the build number lives in the `uaFullVersion` client hint.
- `deviceMemory` is never above 8, because that is where Chromium caps it —
  a 64 GB workstation honestly reports 8, exactly as a real one does.

The catalogue is also **all-distinct**: no two machines in it present the
same fingerprint, and a curated machine that could not be told apart from
one already listed was left out rather than shipped as a second name for the
same identity. Two profiles assigned such a pair would present the same
machine, which is the thing the catalogue exists to prevent. Real capabilities
are excluded here for the same reasons as on Android, and so is screen geometry
by default — but the Screen size row above is offered by both editions, so a
desktop profile may claim one, under the rules stated there.

- Safe Browsing status follows the system WebView component.

## Reporting

Security issues should be reported privately to the repository owner. Please
do not open public issues for vulnerabilities.
