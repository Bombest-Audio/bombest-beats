# Phase 1 — RESEARCH.md

## RESEARCH COMPLETE

**Question:** What do we need to know to plan release signing for this Android app?

### Android Gradle Plugin (AGP) signing

- Use `android.signingConfigs.create("release")` / `signingConfigs.getByName("release")` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- **Never** hardcode secrets; read via `System.getenv("...")` or `providers.environmentVariable` (Gradle 6+).
- **Conditional signing:** Only assign `buildTypes.release.signingConfig` when the keystore file exists and env vars are set, so `debug` builds stay unchanged and CI without secrets can still run `assembleDebug`.
- **AAB vs APK:** `bundleRelease` produces AAB; `assembleRelease` produces APK — both use the same release signing when configured.

### Play App Signing (FYI only for Phase 1)

- First Play upload uses an **upload key**; Google may hold the **app signing key**. Generating a new upload keystore is fine for sideload testing before upload. Document in release doc that **upload certificate** must be registered in Play Console when moving to Phase 4.

### Verification commands

- `cd android-app && ./gradlew bundleRelease`
- `cd android-app && ./gradlew assembleRelease`
- `jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk` (or `apksigner` via build-tools)

### Pitfalls

- **Wrong working directory:** Gradle commands must run from `android-app/` (where `app` module lives).
- **local.properties:** SDK path only; not for release secrets.

---

*Research for planning — 2026-04-02*
