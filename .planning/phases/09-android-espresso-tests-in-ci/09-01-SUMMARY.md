---
phase: 09-android-espresso-tests-in-ci
plan: 01
subsystem: testing
tags: [android, espresso, ci, github-actions, kvm, emulator]

# Dependency graph
requires: []
provides:
  - KVM hardware acceleration enabled in e2e-android GitHub Actions job (root-cause fix for 10/10 CI failures)
  - Hard-fail test behavior on service bind timeout (no more silent assumeTrue skips)
  - Safe MediaBrowser.release() in try/finally on assertion failure
  - Job timeout cap (35 min) and pinned emulator-runner version (v2.37.0)
affects: [e2e-android CI job, AndroidAutoBrowseTest]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "KVM udev rule via echo+tee before android-emulator-runner step"
    - "Hard-fail pattern: throw AssertionError instead of assumeTrue(false)/return for CI skip suppression"
    - "try/finally MediaBrowser lifecycle: declare var before try, release in finally"

key-files:
  created: []
  modified:
    - .github/workflows/pre-merge.yml
    - android-app/app/src/androidTest/java/com/bombest/music/flows/AndroidAutoBrowseTest.kt

key-decisions:
  - "KVM permissions step added without if: condition — must always run unconditionally"
  - "timeout-minutes: 35 chosen based on expected ~20 min runtime with 75% headroom"
  - "android-emulator-runner pinned to @v2.37.0 for reproducible CI behavior"
  - "No @After method added to AndroidAutoBrowseTest — BaseE2ETest.setup() handles service cleanup via am stopservice"

patterns-established:
  - "CI: KVM udev rule must precede android-emulator-runner step — missing KVM causes 'No compatible devices connected' or GC-induced UI timeouts"
  - "Tests: assumeTrue(false)/return is a silent skip anti-pattern in CI — always throw AssertionError for true failures"

requirements-completed: []

# Metrics
duration: 15min
completed: 2026-04-22
---

# Phase 9 Plan 1: Android Espresso CI Fix Summary

**KVM hardware acceleration enabled in e2e-android job and assumeTrue skip replaced with hard-fail AssertionError, fixing the root cause of 10/10 CI failures on PR #36**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-04-22T17:15:00Z
- **Completed:** 2026-04-22T17:30:12Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Added `Enable KVM group perms` step to `pre-merge.yml` e2e-android job — the confirmed root cause of all 10 CI failures (missing KVM causes `disable-linux-hw-accel: true`, leading to "No compatible devices connected" or GC-induced 60s+ UI timeouts)
- Replaced `assumeTrue(false)/return` skip path in `AndroidAutoBrowseTest.browseRoot_returnsExpectedCategories` with `throw AssertionError(...)` — service bind failures now produce visible hard failures instead of silent green runs
- Wrapped test body in `try/finally` so `browser?.release()` always executes even when assertions fail — prevents resource leaks
- Added `timeout-minutes: 35` to e2e-android job and pinned emulator-runner to `@v2.37.0` for reproducible, bounded CI behavior

## Task Commits

1. **Task 1: Add KVM permissions, job timeout, and pinned emulator-runner** - `34954b1a` (ci)
2. **Task 2: Remove assumeTrue skip and fix browser.release() try/finally** - `de9841e9` (fix)

## Files Created/Modified

- `.github/workflows/pre-merge.yml` — Added `Enable KVM group perms` step (3-line udev rule), `timeout-minutes: 35` on e2e-android job, pinned `android-emulator-runner@v2.37.0`
- `android-app/app/src/androidTest/java/com/bombest/music/flows/AndroidAutoBrowseTest.kt` — Removed `import org.junit.Assume.assumeTrue`, replaced skip with `throw AssertionError`, restructured with `try/finally` for `browser?.release()`

## Decisions Made

- KVM step added without `if:` condition — must always run unconditionally so hardware acceleration is available every run
- `timeout-minutes: 35` — expected runtime ~20 min (APK build + emulator boot + 4 tests × ~3-4 min); 35 min is 75% headroom
- Pinned to `@v2.37.0` not just `@v2` — reproducible builds, protects against runner regressions
- No `@After` method in `AndroidAutoBrowseTest` — confirmed that `BaseE2ETest.setup()` calls `am stopservice` as its first action, handling service cleanup for subsequent tests (D-04 preserved)

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

- Local `assembleDebugAndroidTest` initially failed due to Java 11 active in shell (Android requires Java 17) and missing `ANDROID_HOME`. Re-ran with `JAVA_HOME` and `ANDROID_HOME` set explicitly — BUILD SUCCESSFUL in 31s. This is a pre-existing local environment condition; CI correctly provisions JDK 17 via `actions/setup-java@v4`. No code changes required.

## User Setup Required

None — no external service configuration required. Changes take effect when the next PR comment triggers the CI workflow.

## Next Phase Readiness

- Plan 09-01 complete. Both surgical fixes are committed and ready for CI validation.
- Next step: trigger a CI run on a PR branch with `🚀` or `:run-tests:` comment to verify the emulator boots with KVM and the Espresso suite passes.
- If the Auto browse test fails (service doesn't bind), it now produces a visible `AssertionError` instead of a silent skip — which is the correct behavior for debugging.

---
*Phase: 09-android-espresso-tests-in-ci*
*Completed: 2026-04-22*
