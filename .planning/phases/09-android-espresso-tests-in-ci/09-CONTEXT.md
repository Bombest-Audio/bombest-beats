# Phase 9: Android Espresso Tests in CI — Context

**Gathered:** 2026-04-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Get all 4 Android Espresso tests (LoginFlowTest, PlaybackFlowTest, PlaylistFlowTest, AndroidAutoBrowseTest) passing reliably in GitHub Actions CI — defined as 3 consecutive green runs on a PR. The CI workflow already exists in `.github/workflows/pre-merge.yml` (`e2e-android` job). The stabilization branch `test/verify-e2e-android-ci` has significant in-progress work. This phase completes when that branch is merged to `main` after verified CI runs.

Not in scope: writing new test cases, changing the backend, modifying page objects beyond what's needed for reliability, iOS CI, Play Store submission.

</domain>

<decisions>
## Implementation Decisions

### Completion Criteria
- **D-01:** Phase 9 is **complete** when all 4 tests pass on **3 consecutive CI runs** triggered via `:run-test: android` PR comments on `test/verify-e2e-android-ci`. No exceptions — all 4 must pass every time.
- **D-02:** A CI run where `AndroidAutoBrowseTest` skips via `assumeTrue` does **not** count as a pass. Skips are treated as failures for the 3-run threshold.

### AndroidAutoBrowseTest
- **D-03:** The `assumeTrue(false)` skip path in `AndroidAutoBrowseTest` must be **removed**. The service must bind within the 60s timeout or the test fails hard. A skip is no longer acceptable as a "soft pass."
- **D-04:** The `@Before launchApp()` warm-up in `AndroidAutoBrowseTest` (explicit `am startservice` + `am start` + 30s login UI wait) stays as-is — it's the mechanism that makes the 60s bind timeout achievable. No structural changes unless needed to hit the 3-run bar.

### Flakiness Tolerance
- **D-05:** The 15s `Thread.sleep` in `BaseE2ETest.tearDown()` is the accepted final solution for inter-test GC pressure. Do NOT attempt to shorten it or replace it with a more surgical fix.
- **D-06:** The 60s `device.wait(Until.hasObject(By.pkg(pkg)), 60_000)` in `BaseE2ETest.setup()` and the 60s `device.wait(Until.hasObject(By.text("bombest beats")), 60_000)` in `LibraryPage.assertVisible()` are accepted as-is. No timeout changes unless a specific test is timing out beyond these.
- **D-07:** Emulator spec stays: **API 29, x86_64, default target, swiftshader_indirect**. No upgrade to API 31/33.

### Merge Strategy
- **D-08:** Merge path: open PR from `test/verify-e2e-android-ci` → trigger `:run-test: android` 3 times → all 3 runs green → merge. Phase ends on merge commit.
- **D-09:** No squash — preserve stabilization commit history (it's valuable for debugging future flakiness regressions).

### Claude's Discretion
- Whether to add a `timeout-minutes` cap on the `e2e-android` job (reasonable: 30–40 min)
- Exact `reactivecircus/android-emulator-runner` version to pin if not already pinned
- Whether to add AVD caching (`avd-cache: true`) to speed up subsequent runs

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### CI workflow (the integration target)
- `.github/workflows/pre-merge.yml` — Full `e2e-android` job definition; emulator spec, credential injection, artifact upload, PR comment reporting

### Test suite (all 4 tests that must pass)
- `android-app/app/src/androidTest/java/com/bombest/music/base/BaseE2ETest.kt` — Base class; 15s sleep tearDown, 60s setup wait, service stop between tests, credential injection
- `android-app/app/src/androidTest/java/com/bombest/music/flows/AndroidAutoBrowseTest.kt` — Auto browse test; `assumeTrue` skip path must be removed (D-03)
- `android-app/app/src/androidTest/java/com/bombest/music/flows/LoginFlowTest.kt` — TEST-01
- `android-app/app/src/androidTest/java/com/bombest/music/flows/PlaybackFlowTest.kt` — TEST-02
- `android-app/app/src/androidTest/java/com/bombest/music/flows/PlaylistFlowTest.kt` — TEST-03

### Page objects
- `android-app/app/src/androidTest/java/com/bombest/music/pages/LibraryPage.kt` — 60s assertVisible timeout (By.text "bombest beats")

### Build config
- `android-app/app/build.gradle.kts` — `buildConfigField` for TEST_USERNAME/TEST_PASSWORD, `testInstrumentationRunner`

### Requirements
- `.planning/REQUIREMENTS.md` — TEST-01 through TEST-04 (Login, Playback, Playlist CRUD, page object pattern)
- `.planning/ROADMAP.md` — Phase 9 goal

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BaseE2ETest.kt` — All flow tests inherit this. Service stop, auth clear, 60s pkg wait, 15s sleep are all here. Changes here affect all 3 P0 tests simultaneously.
- `AndroidAutoBrowseTest.kt` — Does NOT extend `BaseE2ETest`. Has its own `@Before`/`@Test` without tearDown. If it leaves service running, it creates GC pressure for Login/Playback/Playlist which run after it.

### Established Patterns
- Test warm-up: `am stopservice` → `pressHome` → `am start` → 60s `Until.hasObject(By.pkg())` — proven pattern in BaseE2ETest
- Service leak prevention: `am stopservice` in both setup() and tearDown() of BaseE2ETest
- Credential injection: instrumentation runner args (`test.username`, `test.password`) override `BuildConfig` values

### Integration Points
- `e2e-android` job in `pre-merge.yml` runs after the `test` job succeeds. The merge job requires both `test` AND `e2e-android` to succeed.
- `AndroidAutoBrowseTest` runs first (alphabetically before Login/Playback/Playlist in Gradle's test ordering). If it leaves `BombestMediaService` running, it triggers the GC pressure that `BaseE2ETest.setup()` guards against with the service stop.
- `assumeTrue` skip path in `AndroidAutoBrowseTest` currently prevents a timeout from blocking the P0 tests. Once removed (D-03), a service binding failure there will fail the entire CI run.

</code_context>

<specifics>
## Specific Ideas

- `AndroidAutoBrowseTest` doesn't have a `@After` tearDown. Once the `assumeTrue` is removed, if the test *passes* the service is still running when `LoginFlowTest.setup()` fires. The `BaseE2ETest.setup()` already handles this with `am stopservice` — but confirm the 15s GC drain timing still applies when Auto Browse finishes vs. fails.
- The 3-run verification method: trigger `:run-test: android` comment on the PR three separate times (or use `:run-tests:` if all jobs are needed). Each comment creates a separate workflow run. All 3 must show green `e2e-android` status.

</specifics>

<deferred>
## Deferred Ideas

- **More surgical GC fix** — Replacing the 15s sleep with a GC-callback or heap-measurement mechanism could speed up the suite. Not worth the scope risk.
- **API 29 → 31/33 upgrade** — Newer ART runtime might reduce GC pressure. Deferred — too risky to change emulator spec mid-stabilization.
- **AVD caching** — `avd-cache: true` on `android-emulator-runner` could reduce emulator boot time. Claude can add this at discretion (listed in Claude's Discretion above).
- **iOS CI** — Deferred from Phase 8 context. Still out of scope.

</deferred>

---

*Phase: 09-android-espresso-tests-in-ci*
*Context gathered: 2026-04-22*
