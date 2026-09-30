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

What deliberately does **not** change, because it would be both a lie and a
detectable one:

- **Screen geometry.** `screen.width/height`, `innerWidth/innerHeight`,
  `devicePixelRatio`, `screen.orientation`, and the layout that follows from
  them. A page is really laid out on this phone's screen; a claimed viewport
  would render it wrongly *and* be contradicted by the viewport itself.
- **Real capabilities.** Camera, microphone, sensors, codecs, battery, and
  every permission stay the hardware's own.

This raises the cost of the cheap, scripted checks that compare a UA against
a handful of obvious properties. It does **not** make a profile
undetectable, and the app does not claim it does: a page that inspects
`Function.prototype.toString` on the patched accessors, times a WebGL draw
call, or correlates dozens of unrelated signals can still tell. Profiles are
for keeping separate identities separate, not for evading a determined
fingerprinter.
- Safe Browsing status follows the system WebView component.

## Reporting

Security issues should be reported privately to the repository owner. Please
do not open public issues for vulnerabilities.
