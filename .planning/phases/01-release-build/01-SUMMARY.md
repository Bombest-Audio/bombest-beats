# Phase 1 — Release Build (execution summary)

## Requirements

| ID | Status | Notes |
|----|--------|--------|
| SIGN-01 | Done | `signingConfigs.release` from four `BOMBEST_RELEASE_*` env vars + existing keystore file; `buildTypes.release` uses it when present |
| SIGN-02 | Done | `.gitignore` patterns for `*.jks`, `*.keystore`, `keystore.properties`, `**/release.keystore` |
| SIGN-03 | Done | `android-app/RELEASE_SIGNING.md` — env table, example exports, Gradle commands, outputs, `adb install`, smoke checklist |
| SIDE-01 | Pending | Pixel sideload smoke not run in this session (no production keystore in CI env) |
| SIDE-02 | Pending | Full smoke checklist in RELEASE_SIGNING.md; execution awaits signed APK on device |

## Gradle verification

| Command | Result |
|---------|--------|
| `./gradlew :app:tasks --all` | SUCCESS — script compiles |
| `./gradlew :app:assembleDebug` | SUCCESS |
| `./gradlew :app:bundleRelease` (no signing env) | FAIL immediately via `gradle.taskGraph.whenReady` with message pointing to `RELEASE_SIGNING.md` |
| `./gradlew :app:bundleRelease` / `assembleRelease` (with all env + keystore path) | Not run here — expected SUCCESS when store file exists and passwords match |

## Other fixes

- **`studio_dust_placeholder.png`:** File was JPEG data with a `.png` extension; AAPT failed on release resource merge. Converted to a valid PNG (`sips -s format png`).
- **Signing guard:** Replaced `afterEvaluate` + `doFirst` on `bundleRelease` / `assembleRelease` with `gradle.taskGraph.whenReady` so release tasks fail **before** compile/sign work when signing is missing (avoids long failed builds).
- **`build.gradle.kts`:** Renamed env locals to avoid shadowing `storePassword` / `keyPassword` in `signingConfigs.create("release")`.

## Pixel sideload

**Status:** Not performed. After exporting the four variables and building `app-release.apk`, install with `adb install -r app/build/outputs/apk/release/app-release.apk` and follow the checklist in `android-app/RELEASE_SIGNING.md`.
