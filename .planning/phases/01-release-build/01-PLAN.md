---
plan_id: 01-release-build
phase: 1
slug: release-build
title: Release signing, Gradle release builds, Pixel sideload smoke
wave: 1
depends_on: []
requirements:
  - SIGN-01
  - SIGN-02
  - SIGN-03
  - SIDE-01
  - SIDE-02
autonomous: true
files_modified:
  - android-app/app/build.gradle.kts
  - .gitignore
  - android-app/RELEASE_SIGNING.md
---

# Plan: Phase 1 — Release Build

## Goal

Release builds are signed with a production keystore whose secrets come only from environment variables; `./gradlew bundleRelease` and `./gradlew assembleRelease` succeed; APK installs on a Pixel and core features are smoke-tested.

## must_haves

1. `android-app/app/build.gradle.kts` defines `signingConfigs.release` reading `BOMBEST_RELEASE_STORE_FILE`, `BOMBEST_RELEASE_STORE_PASSWORD`, `BOMBEST_RELEASE_KEY_ALIAS`, `BOMBEST_RELEASE_KEY_PASSWORD`.
2. `buildTypes.release` uses that signing config when env is present; documented behavior when env is missing.
3. `.gitignore` ignores common keystore filenames patterns under `android-app/` and repo root.
4. `android-app/RELEASE_SIGNING.md` documents env vars, example `export` lines (no real secrets), and verify commands.
5. Manual verification: `bundleRelease` and `assembleRelease` complete; user confirms Pixel sideload (executor documents checklist in SUMMARY).

---

## Task 1 — signingConfigs + release buildType

<task id="T1" requirements="SIGN-01,SIGN-02">

<action>
Edit `android-app/app/build.gradle.kts` inside the existing `android { ... }` block:

1. After `buildTypes { ... }` block (or before it, per AGP style), add logic equivalent to:
   - Read `System.getenv("BOMBEST_RELEASE_STORE_FILE")` etc. in Kotlin DSL using `System.getenv()` or a small helper.
   - If **all four** env vars are non-null and non-blank **and** `file(storeFilePath).exists()`, create/configure `signingConfigs.create("release")` with:
     - `storeFile = file(storeFilePath)` (use `rootProject.file` or absolute `file()` as appropriate for paths)
     - `storePassword`, `keyAlias`, `keyPassword` from env.
   - Set `buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("release") }` **only** when the release signing config was successfully configured.
2. If env is incomplete, **do not** assign a broken signing config; optionally `println` a single clear message that release signing is skipped (debug signing would break release — so prefer: **fail** `assembleRelease` with `throw GradleException("...")` listing missing vars, OR only apply signing when complete — choose **fail with message** for release builds so CI does not produce accidentally unsigned release artifacts). Recommended: when any of the four is missing, throw `GradleException` listing required names when user runs `assembleRelease`/`bundleRelease`.

Concrete env var names (must match CONTEXT):

- `BOMBEST_RELEASE_STORE_FILE`
- `BOMBEST_RELEASE_STORE_PASSWORD`
- `BOMBEST_RELEASE_KEY_ALIAS`
- `BOMBEST_RELEASE_KEY_PASSWORD`
</action>

<read_first>
- `android-app/app/build.gradle.kts` (full file)
- `.planning/phases/01-release-build/01-CONTEXT.md`
</read_first>

<acceptance_criteria>
- `android-app/app/build.gradle.kts` contains the string `BOMBEST_RELEASE_STORE_FILE`
- Same file contains `BOMBEST_RELEASE_KEY_ALIAS`
- Same file contains `signingConfigs` and `release` in connection with signingConfig or documented failure
- `./gradlew :app:tasks` runs from `android-app/` without Gradle parse errors (run after edit)
</acceptance_criteria>

</task>

---

## Task 2 — gitignore keystore patterns

<task id="T2" requirements="SIGN-02">

<action>
Append to repo root `.gitignore` (if not already present):

```
# Release keystores (never commit)
*.jks
*.keystore
keystore.properties
**/release.keystore
```

Ensure no duplicate lines that would confuse maintainers; merge with existing "Local credentials" section if logical.
</action>

<read_first>
- `.gitignore`
</read_first>

<acceptance_criteria>
- `.gitignore` contains `*.jks` and `keystore.properties`
- `git check-ignore -v path/to/dummy.jks` would match when tested with a hypothetical path under repo (optional manual)
</acceptance_criteria>

</task>

---

## Task 3 — RELEASE_SIGNING.md

<task id="T3" requirements="SIGN-01,SIGN-02,SIGN-03,SIDE-01">

<action>
Create `android-app/RELEASE_SIGNING.md` with sections:

1. **Required environment variables** — table of the four `BOMBEST_RELEASE_*` names.
2. **Example** — shell snippet using `export BOMBEST_RELEASE_STORE_FILE=$HOME/.../upload.jks` with placeholder paths only.
3. **Build commands** — exact commands:
   - `cd android-app && ./gradlew bundleRelease`
   - `cd android-app && ./gradlew assembleRelease`
4. **Output paths** — typical locations under `app/build/outputs/` for AAB and APK.
5. **Pixel sideload** — `adb install -r app/build/outputs/apk/release/app-release.apk` (adjust artifact name if different).
6. **Smoke checklist** — bullet list: open app, log in (or skip if offline), play track, open player, Android Auto not required for minimal smoke.

No real passwords or keystore paths.
</action>

<read_first>
- `.planning/phases/01-release-build/01-CONTEXT.md`
- `android-app/app/build.gradle.kts` (after T1)
</read_first>

<acceptance_criteria>
- File `android-app/RELEASE_SIGNING.md` exists
- Contains string `./gradlew bundleRelease`
- Contains string `BOMBEST_RELEASE_STORE_PASSWORD`
- Contains `adb install` or equivalent sideload instruction
</acceptance_criteria>

</task>

---

## Task 4 — Verify release builds (manual / executor)

<task id="T4" requirements="SIGN-03,SIDE-01,SIDE-02">

<action>
With valid env vars and a real keystore on the machine:

1. From `android-app/`, run `./gradlew bundleRelease` — must complete SUCCESS.
2. Run `./gradlew assembleRelease` — must complete SUCCESS.
3. Install APK on physical Pixel per RELEASE_SIGNING.md.
4. Run smoke checklist; note any failures in phase SUMMARY when executing `$gsd-execute-phase`.

If keystore not yet available, document blocker in SUMMARY and list exact missing steps — do not fake SUCCESS.
</action>

<read_first>
- `android-app/RELEASE_SIGNING.md`
- `.planning/ROADMAP.md` (Phase 1 success criteria 1–6)
</read_first>

<acceptance_criteria>
- Command `./gradlew bundleRelease` exit code 0 from `android-app/` when secrets + keystore present (record in SUMMARY)
- Command `./gradlew assembleRelease` exit code 0 under same conditions
- SUMMARY.md (post-execute) states Pixel install result: success OR explicit blocker
</acceptance_criteria>

</task>

---

## Verification (phase)

| Requirement | Task |
|-------------|------|
| SIGN-01 | T1, T3 |
| SIGN-02 | T1, T2, T3 |
| SIGN-03 | T3, T4 |
| SIDE-01 | T3, T4 |
| SIDE-02 | T4 |

## PLANNING COMPLETE

Plans: 1 file (`01-PLAN.md`). Next: `$gsd-execute-phase 1 --auto` (or manual task execution).
