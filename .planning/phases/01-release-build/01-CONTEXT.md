# Phase 1: Release Build — Context

**Gathered:** 2026-04-02  
**Status:** Ready for planning  
**Source:** discuss-phase + `$gsd-plan-phase 1 --auto`

## Phase boundary

Deliver release-signed **AAB** and **APK** for `android-app`, with secrets from environment variables only; verify installs and smokes on a **physical Pixel**. No Play Console upload in this phase (Phase 4).

## Implementation decisions

- **Keystore strategy:** User was undecided in discuss-phase. **Default for execution:** load signing exclusively from **environment variables** (no committed keystore paths in repo). Optional local `keystore.properties` path may be documented as an advanced alternative; happy path is `System.getenv()` for paths and passwords.
- **Env var names (locked for PLAN):**
  - `BOMBEST_RELEASE_STORE_FILE` — absolute path to `.jks` / `.keystore`
  - `BOMBEST_RELEASE_STORE_PASSWORD`
  - `BOMBEST_RELEASE_KEY_ALIAS`
  - `BOMBEST_RELEASE_KEY_PASSWORD`
- **Gradle:** `signingConfigs.release` + `buildTypes.release.signingConfig` when all four vars are non-empty; otherwise release build may fail fast with a clear message (or skip signing — prefer fail with message for CI clarity).
- **R8 / ProGuard:** Keep `isMinifyEnabled = false` for Phase 1 unless a task explicitly enables it later (risk reduction).
- **Device:** Physical Pixel for SIDE-02; emulator optional for compile-only checks.

## Canonical references

- [`.planning/ROADMAP.md`](../../ROADMAP.md) — Phase 1 goal and success criteria
- [`.planning/REQUIREMENTS.md`](../../REQUIREMENTS.md) — SIGN-*, SIDE-*
- [`android-app/app/build.gradle.kts`](../../../../android-app/app/build.gradle.kts) — current `release` buildType
- [`.gitignore`](../../../../.gitignore) — extend for keystore patterns

## Deferred

- CI/CD uploading AAB (v2 requirement DIST-01)
- Google Play internal/production track (Phase 4)

---

*Phase: 01-release-build*
