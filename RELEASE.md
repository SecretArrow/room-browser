# Release Guide

## Automated releases (GitHub Actions)

The repository ships CI/CD in `.github/workflows/ci.yml`:

- **Every push / PR** → `gradle lintDebug testDebugUnitTest assembleDebug`
  (quality gate; reports uploaded as artifacts).
- **Every tag `v*`** → full release pipeline:
  1. quality gate (lint + unit tests)
  2. signed release build of **per-ABI APKs + universal APK + AAB**
  3. SHA-256 checksums
  4. automatic **GitHub Release** with all artifacts attached

Creating a release:

```bash
git tag v1.0.0
git push origin v1.0.0          # CI builds and publishes the release
```

## Release artifacts (exact names)

```
room-browser-v<version>-arm64-v8a-release.apk      (priority 1 — modern devices)
room-browser-v<version>-armeabi-v7a-release.apk    (priority 2)
room-browser-v<version>-x86_64-release.apk         (priority 3)
room-browser-v<version>-x86-release.apk            (priority 4)
room-browser-v<version>-universal-release.apk      (all ABIs, larger)
room-browser-v<version>-release.aab                (Play Store upload)
checksums.sha256
```

## Signing

CI reads the keystore from GitHub repository secrets (never committed):

| Secret | Content |
|---|---|
| `ROOMBROWSER_KEYSTORE_B64` | base64 of the release `.jks` |
| `ROOMBROWSER_STORE_PASSWORD` | keystore password |
| `ROOMBROWSER_KEY_ALIAS` | key alias |
| `ROOMBROWSER_KEY_PASSWORD` | key password |

Configure once with the helper script:

```bash
tools/set-github-secrets.sh <owner/repo> /path/to/release.jks
```

If the secrets are absent, CI still produces debug-signed APKs so the
pipeline never breaks — the release notes state this clearly. Replace the
keystore before public distribution.

## Verification

- Verify checksums: `sha256sum -c checksums.sha256`
- Confirm signature: `apksigner verify --print-certs room-browser-v1.0.0-arm64-v8a-release.apk`
- Recommended: install each ABI artifact on an appropriate emulator/device
  and run the profile-isolation procedure from `TESTING.md` before
  distribution (the spec's "verify each ABI" step).
