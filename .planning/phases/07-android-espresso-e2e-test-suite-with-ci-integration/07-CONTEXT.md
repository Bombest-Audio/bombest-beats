# Phase 7: Android Espresso E2E Test Suite with CI Integration — Context

**Gathered:** 2026-04-16
**Status:** Ready for planning

<domain>
## Phase Boundary

Wire the existing P0 Espresso test suite (login, playback, playlist flows) into GitHub Actions CI so they run as a pre-merge gate on `main` and on-demand via PR comment emoji. All test code already exists in `android-app/app/src/androidTest/`. This phase is purely the CI integration: virtual device provisioning, credential injection, artifact upload, and PR reporting.

Not in scope: writing new test cases, modifying page objects, adding new flows, or changing the backend.

</domain>

<decisions>
## Implementation Decisions

### Emulator Strategy
- **D-01:** Use `reactivecircus/android-emulator-runner` GitHub Action to provision an AVD on `ubuntu-latest` runners.
- **D-02:** AVD spec: **Pixel 5, API 34, arm64**. Matches `compileSdk 34`; arm64 on GitHub's ARM runners avoids x86 KVM quirks.
- **D-03:** Run `connectedAndroidTest` inside the emulator-runner action. Extend the existing `pre-merge.yml` workflow — do not create a separate file.

### Credential Injection
- **D-04:** Create a **dedicated CI test account** (e.g., username `ci`) on `beats.bom.best` — separate from the `thomas` personal account. Isolates CI playlist creates/deletes from the personal library.
- **D-05:** Seed the CI account's library with **2–3 real tracks from S3** (one-time manual admin setup). The playback test taps the first track — it must not be empty.
- **D-06:** Store CI account credentials as **GitHub Actions repository secrets** (`CI_TEST_USERNAME`, `CI_TEST_PASSWORD`). Inject at Gradle invocation time via `-Pandroid.testInstrumentationRunnerArguments.test.username=${{ secrets.CI_TEST_USERNAME }} -P...test.password=${{ secrets.CI_TEST_PASSWORD }}`. This bypasses `local.properties` entirely — `build.gradle.kts` already reads `localProps.getProperty("test.username", "")` which falls through to the runner arg override.

### Failure Artifacts
- **D-07:** Upload **JUnit XML test report** (`build/outputs/androidTest-results/`) and **logcat dump** as GitHub Actions artifacts on test failure (using `if: failure()` upload step).
- **D-08:** Post a **pass/fail summary comment on the PR** after the E2E job completes — fits the existing emoji-trigger PR comment workflow. Use a GitHub Script step (or `android-test-report-action`) to format results from the JUnit XML.

### Flaky Test Policy
- **D-09:** **Fail immediately, no retries.** If a test fails, it fails — no automatic re-run. For a 3-flow suite hitting a live backend, a flaky failure is easier to re-trigger manually than to manage retry logic.
- **D-10:** E2E test failures **block merge** — consistent with how the existing CI treats `assembleDebug` failures.

### Claude's Discretion
- Exact `reactivecircus/android-emulator-runner` action version to pin
- Logcat capture mechanism (adb logcat -d vs workflow log parsing)
- JUnit XML report comment formatter choice (GitHub Script vs dedicated action)
- Gradle cache configuration for the new E2E job
- AVD caching strategy (`avd-cache` parameter)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Test suite (existing code to integrate)
- `android-app/app/src/androidTest/java/com/bombest/music/base/BaseE2ETest.kt` — Base class; `testUsername`/`testPassword` from `BuildConfig`; `setup()` clears auth, launches `LoginActivity`; `deletePlaylistsByPrefix()` API teardown helper
- `android-app/app/src/androidTest/java/com/bombest/music/flows/LoginFlowTest.kt` — TEST-01 login flow
- `android-app/app/src/androidTest/java/com/bombest/music/flows/PlaybackFlowTest.kt` — TEST-02 playback flow
- `android-app/app/src/androidTest/java/com/bombest/music/flows/PlaylistFlowTest.kt` — TEST-03 playlist CRUD flow

### Build config (credential injection target)
- `android-app/app/build.gradle.kts` — `buildConfigField` for `TEST_USERNAME`/`TEST_PASSWORD` from `localProps.getProperty()`; `testOptions` block; `testInstrumentationRunner`

### CI workflow (to extend)
- `.github/workflows/pre-merge.yml` — Existing emoji-trigger workflow; Android job currently runs `assembleDebug --no-daemon`; has PR comment merge logic — E2E job must integrate here

### Requirements
- `.planning/REQUIREMENTS.md` — TEST-01 through TEST-04 (login, playback, playlist CRUD, page object pattern)
- `.planning/ROADMAP.md` — Phase 7 goal and depends-on Phase 6

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BaseE2ETest.kt` — Full UiAutomator setup: clears auth DataStore, launches LoginActivity via `am start`, dismisses Android 16 compat dialog, waits for package foreground. All flows inherit this.
- `BaseE2ETest.loginApi()` / `deletePlaylistsByPrefix()` — OkHttp API helpers for test teardown without UI. CI test account teardown can reuse these.
- Page objects in `pages/` — method-chaining, AAA pattern already implemented for Login, Library, Player, Playlists screens.

### Established Patterns
- Tests use **UiAutomator** (`By`, `Until`, `UiDevice`) — not Compose Test rules. Requires a connected device/emulator at test time.
- `assumeTrue` in `BaseE2ETest.setup()` skips tests gracefully when credentials are empty — CI must inject non-empty values or tests are silently skipped (not failed).
- Pre-merge workflow uses `ubuntu-latest`; the new E2E job must also target `ubuntu-latest` (not macOS, which costs more).

### Integration Points
- Pre-merge workflow `test` job → add an `e2e-android` job that depends on the emulator-runner and uses the same `checkout@v4` + `setup-java@v4` steps.
- JUnit XML output lands at `android-app/build/outputs/androidTest-results/connected/` — standard Gradle path.
- Backend is live at `https://beats.bom.best` — tests make real network calls; CI runner must have outbound HTTPS access (GitHub-hosted runners do by default).

</code_context>

<specifics>
## Specific Ideas

- The `assumeTrue` guard in `BaseE2ETest` means misconfigured credentials cause silent skips rather than failures. The CI workflow should validate that secrets are non-empty before running Gradle, or the test run will show 0 tests executed (green but wrong).
- Pre-merge workflow already posts PR comments for merge (`🚀`). The E2E summary comment can follow the same `gh pr comment` pattern already in the file.
- The CI test account (`ci`) needs to be created via the admin API or manually on the backend — this is a one-time setup task, not a Gradle change.

</specifics>

<deferred>
## Deferred Ideas

- None — discussion stayed within phase scope.

</deferred>

---

*Phase: 07-android-espresso-e2e-test-suite-with-ci-integration*
*Context gathered: 2026-04-16*
