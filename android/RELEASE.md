# Release Guide

## Automated releases (GitHub Actions)

The repository ships CI/CD in `.github/workflows/ci.yml`:

1. **auto-fix** — `lintFix` quickfixes are committed & pushed automatically.
2. **quality gate (every push / PR)** — lint + unit tests + debug build.
3. **e2e gate** — instrumented tests + cross-process E2E flow on an emulator.
4. **auto-release** — after quality + e2e are green:
   - **every push to `main`** → pre-release tagged `v1.0.<next>`, where
     `<next>` is one past the highest existing `v1.0.*` tag (the `versionCode`
     is a separate, much larger number: the CI run number)
   - **every tag `v*`** → stable release with the exact tag name
   - signed **per-ABI APKs + universal APK + AAB** + SHA-256 checksums
   - automatic **GitHub Release** with all artifacts attached

Creating a stable release:

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
- Confirm signature: `apksigner verify --print-certs room-browser-v<version>-arm64-v8a-release.apk`
  where `<version>` is the release tag without its `v` (`v1.0.96` -> `1.0.96`).
  The release workflow stamps the tag into `versionName` through
  `RB_VERSION_NAME`, so the filename, the version Android's app info reports
  and the About screen all name the release the build came from. A local
  `assembleRelease` has not been told a version and keeps the `1.0.0`
  fallback.
- Recommended: install each ABI artifact on an appropriate emulator/device
  and run the profile-isolation procedure from `TESTING.md` before
  distribution (the spec's "verify each ABI" step).
