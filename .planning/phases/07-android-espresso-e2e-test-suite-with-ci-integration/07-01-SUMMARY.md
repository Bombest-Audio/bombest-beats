---
phase: 07-android-espresso-e2e-test-suite-with-ci-integration
plan: 01
subsystem: testing
tags: [android, espresso, github-actions, ci, e2e, emulator, uiautomator]

# Dependency graph
requires:
  - phase: 05-e2e-ui-tests
    provides: "P0 Espresso test suite in android-app/app/src/androidTest/"
provides:
  - "e2e-android GitHub Actions job wired into pre-merge.yml"
  - "Credential guard preventing silent assumeTrue skips in CI"
  - "AVD provisioning with caching for API 34 Pixel 5 emulator"
  - "JUnit XML + logcat artifact upload on test failure"
  - "PR pass/fail comment after E2E job completes"
  - "Merge blocked by E2E failures (needs: [test, e2e-android])"
affects: [08-play-store-submission, future-ci-phases]

# Tech tracking
tech-stack:
  added:
    - reactivecircus/android-emulator-runner@v2
    - gradle/actions/setup-gradle@v4
    - actions/cache@v4 (AVD caching)
    - actions/upload-artifact@v4 (failure artifacts)
  patterns:
    - "Guard step validates secrets non-empty before Gradle invocation"
    - "AVD cache keyed on avd-pixel5-api34-x86_64 for warm-boot reuse"
    - "Two-phase emulator-runner: create-once (cache miss) + run"
    - "if: failure() for logcat capture + artifact upload"
    - "if: always() for PR comment posting"

key-files:
  created: []
  modified:
    - .github/workflows/pre-merge.yml

key-decisions:
  - "Used x86_64 arch (not arm64) — GitHub ubuntu-latest runners support KVM only on x86_64; arm64 Android emulation requires paid/enterprise ARM runners"
  - "AVD cache key includes arch (avd-pixel5-api34-x86_64) to invalidate if arch changes"
  - "Guard step exits 1 on empty secrets — prevents silent green build from assumeTrue skipping all tests"
  - "No retry logic per D-09 — manual re-trigger is simpler than retry management for a 3-flow live-backend suite"

patterns-established:
  - "CI credential guard: validate secrets non-empty before Gradle, fail fast with actionable error"
  - "Two-step AVD lifecycle: cache-conditional create + unconditional run"

requirements-completed: [TEST-01, TEST-02, TEST-03, TEST-04]

# Metrics
duration: 1min
completed: 2026-04-16
---

# Phase 7 Plan 01: CI Integration Summary

**e2e-android GitHub Actions job gates PR merge on Espresso tests: AVD provisioning, credential injection, artifact upload, and PR comment for Login/Playback/Playlist CRUD flows**

## Performance

- **Duration:** 1 min
- **Started:** 2026-04-16T07:13:50Z
- **Completed:** 2026-04-16T07:14:59Z
- **Tasks:** 1 of 1
- **Files modified:** 1

## Accomplishments
- Added `e2e-android` job to `pre-merge.yml` that runs the full P0 Espresso suite on emoji-trigger PR comments
- Credential guard step prevents the silent-green-zero-tests trap from `BaseE2ETest.assumeTrue` when secrets are missing
- AVD cache keyed on `avd-pixel5-api34-x86_64` eliminates 3-minute system-image download on warm runs
- Failure artifacts (JUnit XML + logcat) uploaded with 7-day retention so failures are debuggable without re-running
- PR comment posted unconditionally after job so test status is visible without navigating to Actions tab
- Merge job `needs: [test, e2e-android]` — E2E failures now block merge to main

## Task Commits

Each task was committed atomically:

1. **Task 1: Add e2e-android job to pre-merge.yml** - `a33f53ec` (ci)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified
- `.github/workflows/pre-merge.yml` — Added 105-line `e2e-android` job block; updated merge job `needs`

## Decisions Made
- **x86_64 over arm64 for emulator arch:** GitHub's standard `ubuntu-latest` runners support KVM hardware acceleration only on x86_64. ARM Android emulation requires paid/enterprise ARM runners. The CONTEXT.md designates arch choice as "Claude's Discretion" — x86_64 is the correct choice for functional CI on free runners.
- **AVD cache key includes arch:** `avd-pixel5-api34-x86_64` — if arch changes in future the old cache entry is invalidated automatically.
- **Guard step position:** First step in job, before checkout. If secrets are missing the job fails immediately with a clear actionable message, saving emulator boot time.
- **`if: always()` on PR comment:** Ensures the pass/fail comment posts even when the job fails — critical for developer feedback loop.

## Deviations from Plan

None — plan executed exactly as written, with one clarification applied per plan instructions:

**Arch choice (x86_64 vs arm64):** The plan noted that D-02 specifies arm64 but GitHub ubuntu-latest runners do not support arm64 KVM. The plan explicitly delegates this as "Claude's Discretion" and uses x86_64. Applied as specified. AVD cache key updated from `avd-pixel5-api34-arm64` to `avd-pixel5-api34-x86_64` to match actual arch.

## Issues Encountered

None — workflow edit was straightforward. YAML validated with `python3 -c "import yaml; yaml.safe_load(...)"`.

## User Setup Required

**One-time manual setup required before CI can run E2E tests:**

1. Create a `ci` user account on `beats.bom.best` (admin API or manual)
2. Seed the CI account's library with 2-3 tracks from S3 (playback test requires at least one track)
3. Add GitHub Actions repository secrets:
   - `CI_TEST_USERNAME` — username of the CI account
   - `CI_TEST_PASSWORD` — password of the CI account

Without these secrets, the guard step will fail with: `ERROR: CI_TEST_USERNAME and/or CI_TEST_PASSWORD secrets are not set.`

## Next Phase Readiness

- CI gate is wired and ready — the next PR comment with `🚀` or `:run-tests:` will trigger the E2E job
- User must complete the three manual setup steps above before the first real run
- Plan 02 (if any) can proceed immediately — no blocking items from this plan

---
*Phase: 07-android-espresso-e2e-test-suite-with-ci-integration*
*Completed: 2026-04-16*
