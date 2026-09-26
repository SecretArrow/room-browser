# Build Guide

## Requirements

- JDK 17+ (Temurin 21 recommended)
- Android SDK: `platforms;android-35`, `build-tools;35.0.0`
- ~4 GB RAM for comfortable builds

## Standard commands

```bash
./gradlew assembleDebug            # debug APKs for all ABIs
./gradlew testDebugUnitTest        # JVM unit tests (domain + app logic)
./gradlew lintDebug                # Android Lint static analysis
./gradlew assembleRelease          # signed release APKs per ABI + universal
./gradlew bundleRelease            # Android App Bundle (.aab)
./gradlew connectedDebugAndroidTest  # instrumented tests (device/emulator required)
```

Debug artifacts appear in `app/build/outputs/apk/debug/`:

```
room-browser-v1.0.0-arm64-v8a-debug.apk
room-browser-v1.0.0-armeabi-v7a-debug.apk
room-browser-v1.0.0-x86-debug.apk
room-browser-v1.0.0-x86_64-debug.apk
room-browser-v1.0.0-universal-debug.apk
```

## Build types

| Type | Purpose | Notes |
|---|---|---|
| `debug` | development | `.debug` applicationId suffix |
| `release` | production | R8 minify + resource shrink, signed via env vars |
| `benchmark` | perf testing | non-debuggable, release signing |

## Release signing (no secrets in Git)

Release signing is configured **exclusively from environment variables**:

```bash
export ROOMBROWSER_KEYSTORE=/path/to/room-browser-release.jks
export ROOMBROWSER_STORE_PASSWORD=…
export ROOMBROWSER_KEY_ALIAS=…
export ROOMBROWSER_KEY_PASSWORD=…

./gradlew assembleRelease
```

If the variables are absent, the build falls back to debug signing (useful for
local experiments only — never for distribution). CI injects the same
variables from repository secrets; the keystore itself never enters Git.

Generating your own keystore:

```bash
keytool -genkeypair -v \
  -keystore room-browser-release.jks \
  -alias roombrowser \
  -keyalg RSA -keysize 4096 -validity 10000
```

## Version codes per ABI

`arm64-v8a` = 4xxx, `armeabi-v7a` = 3xxx, `x86_64` = 2xxx, `x86` = 1xxx,
`universal` = 0xxx — each ABI gets a distinct, ordered version code so stores
treat them as separate upgradable artifacts.

## Toolchain versions

Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 ·
compileSdk 35 · minSdk 28 · targetSdk 35 · Compose BOM 2024.12.01 ·
Room 2.6.1 (multi-instance invalidation enabled).
