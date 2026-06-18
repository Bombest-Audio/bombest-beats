# Phase 9: Android Espresso Tests in CI — Research

**Researched:** 2026-04-22
**Domain:** Android Espresso / UiAutomator E2E tests on GitHub Actions (ubuntu-latest + android-emulator-runner)
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Completion Criteria**
- **D-01:** Phase 9 is complete when all 4 tests pass on 3 consecutive CI runs triggered via `:run-test: android` PR comments on `test/verify-e2e-android-ci`. No exceptions — all 4 must pass every time.
- **D-02:** A CI run where `AndroidAutoBrowseTest` skips via `assumeTrue` does NOT count as a pass. Skips are treated as failures for the 3-run threshold.

**AndroidAutoBrowseTest**
- **D-03:** The `assumeTrue(false)` skip path in `AndroidAutoBrowseTest` must be removed. The service must bind within the 60s timeout or the test fails hard. A skip is no longer acceptable as a "soft pass."
- **D-04:** The `@Before launchApp()` warm-up in `AndroidAutoBrowseTest` (explicit `am startservice` + `am start` + 30s login UI wait) stays as-is — it's the mechanism that makes the 60s bind timeout achievable. No structural changes unless needed to hit the 3-run bar.

**Flakiness Tolerance**
- **D-05:** The 15s `Thread.sleep` in `BaseE2ETest.tearDown()` is the accepted final solution for inter-test GC pressure. Do NOT attempt to shorten it or replace it with a more surgical fix.
- **D-06:** The 60s `device.wait(Until.hasObject(By.pkg(pkg)), 60_000)` in `BaseE2ETest.setup()` and the 60s `device.wait(Until.hasObject(By.text("bombest beats")), 60_000)` in `LibraryPage.assertVisible()` are accepted as-is. No timeout changes unless a specific test is timing out beyond these.
- **D-07:** Emulator spec stays: API 29, x86_64, default target, swiftshader_indirect. No upgrade to API 31/33.

**Merge Strategy**
- **D-08:** Merge path: open PR from `test/verify-e2e-android-ci` → trigger `:run-test: android` 3 times → all 3 runs green → merge. Phase ends on merge commit.
- **D-09:** No squash — preserve stabilization commit history (it's valuable for debugging future flakiness regressions).

### Claude's Discretion
- Whether to add a `timeout-minutes` cap on the `e2e-android` job (reasonable: 30–40 min)
- Exact `reactivecircus/android-emulator-runner` version to pin if not already pinned
- Whether to add AVD caching (`avd-cache: true`) to speed up subsequent runs

### Deferred Ideas (OUT OF SCOPE)
- More surgical GC fix — replacing 15s sleep with GC-callback or heap-measurement mechanism
- API 29 → 31/33 upgrade — too risky to change emulator spec mid-stabilization
- AVD caching — Claude can add this at discretion (listed in Claude's Discretion above)
- iOS CI — out of scope
</user_constraints>

---

## Summary

This phase has one goal: get all 4 Android Espresso tests (LoginFlowTest, PlaybackFlowTest, PlaylistFlowTest, AndroidAutoBrowseTest) passing on 3 consecutive CI runs on PR #36 (`test/verify-e2e-android-ci` → `main`). Significant stabilization work is already committed to that branch. **The primary blocker is that the CI workflow is missing the KVM permissions step**, causing x86_64 emulator hardware acceleration to be disabled — confirmed directly from CI logs. Without KVM, the emulator runs in pure software emulation mode, causing two failure modes: (1) intermittent "No compatible devices connected" errors where the emulator appears online but Gradle rejects it, and (2) severe GC pressure when hardware emulation is too slow to keep up with the Compose rendering demands of 4 sequential E2E tests.

The second remaining code gap is in `AndroidAutoBrowseTest`: the `assumeTrue(false)` skip path is still present (D-03 requires removal). With KVM enabled, the service bind within 60s should be achievable, making the hard-failure path acceptable.

The test architecture itself (single Gradle invocation, 15s tearDown sleep, service stop between tests, warm LoginActivity pre-render) is sound and reflects extensive trial-and-error documented in the commit history. The plan should be surgical: add KVM permissions, remove the assumeTrue skip, then trigger 3 verification runs.

**Primary recommendation:** Add the KVM permissions step before the emulator runner step, remove the `assumeTrue` skip from `AndroidAutoBrowseTest`, and add a `timeout-minutes` cap. Then trigger 3 consecutive `:run-test: android` runs on PR #36.

---

## Current State: What's Already Done vs What Remains

This is not a greenfield phase. The stabilization branch (`test/verify-e2e-android-ci`) has 10+ CI-related commits. Understanding what's already solved prevents re-solving it.

### Already solved (DO NOT change)
| Problem | Solution (on branch) | Location |
|---------|---------------------|----------|
| GC pressure from BombestMediaService heap | `am stopservice` in `BaseE2ETest.setup()` and `tearDown()` | `BaseE2ETest.kt` lines 54, 88 |
| Cold JIT recompilation killing test suite | Single `./gradlew connectedAndroidTest` (not two Gradle phases) | `.github/workflows/pre-merge.yml` |
| Library screen not appearing | 60s wait in `LibraryPage.assertVisible()` | `LibraryPage.kt` line 13 |
| Package not appearing after launch | 60s wait in `BaseE2ETest.setup()` | `BaseE2ETest.kt` line 76 |
| Login EditText not appearing (GC thrash) | 15s sleep in `tearDown()` + warm LoginActivity | `BaseE2ETest.kt` lines 94, 101 |
| Service leak after AndroidAutoBrowseTest | `am stopservice` in `BaseE2ETest.setup()` stops it before P0 tests | `BaseE2ETest.kt` line 54 |
| APK OOM during emulator startup | Pre-build APKs before emulator starts | `pre-merge.yml` "Pre-build debug and test APKs" step |
| adb logcat hang on failure | `timeout 30 adb logcat -d` | `pre-merge.yml` "Capture logcat" step |
| Two-phase execution re-cold-starting JVM | Reverted to single-phase | Latest commit `b964cedc` |

### Remaining gaps (this phase must fix)

| Gap | Root Cause | Location | Decision |
|-----|-----------|---------|----------|
| KVM permissions step missing | `disable-linux-hw-accel: auto` finds no accessible `/dev/kvm` and disables HW acceleration | `.github/workflows/pre-merge.yml` | Claude's discretion to add |
| `assumeTrue` skip path in `AndroidAutoBrowseTest` | Skip is still in `browseRoot_returnsExpectedCategories()` catch block | `AndroidAutoBrowseTest.kt` lines 77-80 | D-03: must remove |
| No `timeout-minutes` on `e2e-android` job | Job can run indefinitely if emulator hangs | `.github/workflows/pre-merge.yml` | Claude's discretion: add 30-40 min |
| `android-emulator-runner` pinned to `@v2` (not specific minor) | Version float risk | `.github/workflows/pre-merge.yml` line 168 | Claude's discretion: pin to `@v2.37.0` |

---

## Root Cause Analysis: CI Failures

**Evidence from CI runs today (2026-04-22):**

All 10 recent CI runs on PR #36 failed. Two distinct failure modes observed:

### Failure Mode A: "No compatible devices connected" (latest run: `24767044593`)
```
WARNING | x86_64 emulation may not work without hardware acceleration!
INFO    | You're running a Linux VM where hardware acceleration is not available.
         Please consider using a macOS VM instead...
disable Linux hardware acceleration: true
...
Emulator booted.
...
> : No compatible devices connected.[TestRunner] FAILED
Found 1 connected device(s), 0 of which were compatible.
```

The emulator boots but x86_64 emulation without hardware acceleration puts the device in an unstable state where Gradle's connected test runner rejects it as incompatible. This is a direct consequence of the missing KVM permissions step.

### Failure Mode B: Tests run but all fail with "Username field not found" (run: `24765429752`)
```
LoginFlowTest > validCredentials_navigatesToLibrary  FAILED
    java.lang.IllegalStateException: Username field not found
    at com.bombest.music.pages.LoginPage.enterUsername(LoginPage.kt:15)

PlaybackFlowTest > loginThenSelectTrack_playerControlsVisible  FAILED
    java.lang.IllegalStateException: Username field not found

PlaylistFlowTest > createAndDeletePlaylist_endToEnd  FAILED
    java.lang.IllegalStateException: Username field not found
```

`LoginPage.enterUsername()` waits 60s for `By.clazz("android.widget.EditText")` — if the emulator is under severe software-emulation GC pressure, Compose never puts the login form in the accessibility tree within 60s. Root cause: same missing KVM. The software emulator is too slow to handle the Compose rendering demands.

Note: this failure run (`4e13f9e5`) was on the two-phase execution commit (already reverted in `b964cedc`).

### Fix: KVM permissions step

The `reactivecircus/android-emulator-runner` README explicitly requires this step before the emulator runner on ubuntu-latest:

```yaml
- name: Enable KVM group perms
  run: |
    echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
    sudo udevadm control --reload-rules
    sudo udevadm trigger --name-match=kvm
```

GitHub announced KVM support on 2-vCPU ubuntu-latest runners in April 2024. Without this step, the `/dev/kvm` device node doesn't have accessible permissions, `disable-linux-hw-accel: auto` detects this and sets `disable Linux hardware acceleration: true`, and x86_64 emulation runs in pure software mode — unreliable for Espresso tests.

**Confidence: HIGH** — confirmed from CI log output and official android-emulator-runner README.

---

## Architecture Patterns

### CI Job Structure (current, after all stabilization commits)

```
e2e-android job:
  1. Post "started" PR comment
  2. Validate CI credentials (fail fast if secrets missing)
  3. Checkout PR head
  4. Setup Java 17
  5. Setup Gradle with caching
  6. Pre-build debug + test APKs (before emulator — avoids OOM)
  7. [MISSING] Enable KVM permissions  ← must add
  8. Run emulator + tests (single Gradle invocation — preserves JIT warmth)
  9. Capture logcat on failure
  10. Upload test artifacts on failure
  11. Post result PR comment (always)
```

### Test Execution Order

Gradle runs tests alphabetically by class name within a single instrumented test run:

1. `AndroidAutoBrowseTest` (A) — runs first
2. `LoginFlowTest` (L)
3. `PlaybackFlowTest` (P)
4. `PlaylistFlowTest` (P)

`AndroidAutoBrowseTest` does NOT extend `BaseE2ETest` and has no `@After`. When it passes (or fails), `BombestMediaService` may still be running. `BaseE2ETest.setup()` handles this with `am stopservice` as its first action, which stops the service before Login renders.

### Inter-test Isolation Pattern (BaseE2ETest)

```kotlin
@Before fun setup() {
    // 1. Clear auth datastore
    // 2. am stopservice (releases Media3/ExoPlayer heap from AutoBrowse)
    // 3. pressHome + am start LoginActivity
    // 4. Dismiss Android 16 compat warning if present
    // 5. device.wait(By.pkg, 60_000)
}

@After fun tearDown() {
    // 1. Clear auth datastore
    // 2. am stopservice (prevent heap retention into next test)
    // 3. Thread.sleep(15_000)  ← GC drain
    // 4. pressHome + am start (warm LoginActivity for next test's setup)
}
```

This pattern is **locked** (D-05, D-06). Do not change sleep duration or timeout values.

### AndroidAutoBrowseTest Pattern

The test does its own warm-up in `@Before launchApp()`:
- `am startservice` (explicit service pre-start)
- `am start LoginActivity`  
- 15s wait for package
- 30s wait for EditText (confirms process fully initialized before binding)

After warm-up, `browseRoot_returnsExpectedCategories()` binds `MediaBrowser` with 60s timeout. Per D-03, the `TimeoutException` catch block must become a hard failure (no `assumeTrue`).

---

## Code Changes Required

### Change 1: Add KVM permissions step to `pre-merge.yml`

**File:** `.github/workflows/pre-merge.yml`  
**Where:** Before the `Run E2E tests` step (which uses `reactivecircus/android-emulator-runner@v2`)

```yaml
- name: Enable KVM group perms
  run: |
    echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
    sudo udevadm control --reload-rules
    sudo udevadm trigger --name-match=kvm
```

**Source:** [reactivecircus/android-emulator-runner README](https://github.com/ReactiveCircus/android-emulator-runner/blob/main/README.md) — "Running hardware accelerated emulators on Linux runners" section.

### Change 2: Remove `assumeTrue` skip from `AndroidAutoBrowseTest`

**File:** `android-app/app/src/androidTest/java/com/bombest/music/flows/AndroidAutoBrowseTest.kt`

Current code (lines 73-82):
```kotlin
val browser = try {
    browserFuture.get(60, TimeUnit.SECONDS)
} catch (e: TimeoutException) {
    browserFuture.cancel(true)
    assumeTrue(
        "BombestMediaService did not bind within 60 s on this runner — skipping Auto browse test",
        false,
    )
    return
}
```

Must become (per D-03):
```kotlin
val browser = try {
    browserFuture.get(60, TimeUnit.SECONDS)
} catch (e: TimeoutException) {
    browserFuture.cancel(true)
    throw AssertionError("BombestMediaService did not bind within 60 s on this runner")
}
```

Also: remove the now-unused `import org.junit.Assume.assumeTrue` in `AndroidAutoBrowseTest.kt`.

**Note:** `AndroidAutoBrowseTest` also has no `@After` method. When the test passes (not just when it times out), `BombestMediaService` is still running. This is handled by `BaseE2ETest.setup()` via `am stopservice` before Login renders. No `@After` is needed in `AndroidAutoBrowseTest` per existing design.

### Change 3 (Claude's Discretion): Add `timeout-minutes` to `e2e-android` job

**File:** `.github/workflows/pre-merge.yml`  
**Where:** On the `e2e-android` job definition

```yaml
e2e-android:
  name: E2E Android tests
  needs: test
  timeout-minutes: 35
  if: ...
```

Rationale: The total expected runtime is:
- APK pre-build (cached): ~2 min
- KVM setup: <1 min
- Emulator boot (with KVM): ~2 min  
- Test suite (4 tests × avg 3-4 min each including 15s sleep + 60s waits): ~15 min
- Total: ~20 min
- 35 minutes gives 75% headroom before triggering a hard failure

### Change 4 (Claude's Discretion): Pin emulator-runner version

**File:** `.github/workflows/pre-merge.yml`  

Current: `uses: reactivecircus/android-emulator-runner@v2`  
Recommended: `uses: reactivecircus/android-emulator-runner@v2.37.0`

Latest release is `v2.37.0` (March 2024). Pinning to a specific version prevents unexpected breakage from future minor releases. The major version float `@v2` is fine for stability but pinning gives determinism.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| KVM permissions for emulator | Custom shell script | Standard 3-line udev rule from README | This is the documented canonical approach; deviating from it risks subtle permission issues |
| Test result PR comments | Custom notification service | The existing curl-based PR comment approach in `pre-merge.yml` | Already working; don't change |
| Emulator lifecycle | Direct emulator CLI invocation | `reactivecircus/android-emulator-runner@v2` | Handles AVD creation, boot detection, adb sync, cleanup |

---

## Common Pitfalls

### Pitfall 1: Missing KVM Permissions Step (CURRENT BLOCKER)
**What goes wrong:** `disable-linux-hw-accel: auto` detects `/dev/kvm` is inaccessible (wrong group permissions) and sets `disable Linux hardware acceleration: true`. x86_64 runs in pure QEMU software mode — either the emulator boots but Gradle finds 0 compatible devices, or tests run but GC pressure makes all Compose UI inaccessible within 60s timeouts.

**Why it happens:** The `kvm` device node exists on ubuntu-latest but defaults to `root:kvm` ownership. The GitHub Actions runner runs as `runner` user which is not in the `kvm` group by default. Without the udev rule, `@v2` of the action cannot enable hardware acceleration.

**How to avoid:** Add the 3-line KVM udev rule step **before** the emulator runner step. This is required even though GitHub announced KVM support in April 2024 — KVM availability doesn't automatically mean the permissions are correct.

**Warning signs:** CI log shows `disable Linux hardware acceleration: true` and/or `WARNING | x86_64 emulation may not work without hardware acceleration!`

### Pitfall 2: Two-Phase Gradle Execution (Already Tried, Reverted)
**What goes wrong:** Running two separate `./gradlew connectedAndroidTest` invocations (one for AutoBrowse, one for P0 tests) forces JIT recompilation of Compose classes. On this swiftshader emulator, cold JIT takes 18-30s — longer than the 60s `By.pkg()` and `EditText` waits combined, causing all P0 tests to fail.

**Why it happened:** The intent was to isolate AutoBrowse's service heap via `am force-stop` between phases. But force-stop kills the instrumentation process, forcing a cold restart.

**How to avoid:** Keep single Gradle invocation (current `b964cedc` state). `BaseE2ETest.tearDown()` handles service heap isolation via `am stopservice` (not force-stop) + 15s sleep.

### Pitfall 3: `assumeTrue` Skip Hiding Failures
**What goes wrong:** `assumeTrue(false)` in `AndroidAutoBrowseTest` causes JUnit to report the test as "skipped" rather than "failed". The Gradle task exits 0 even though the test never ran. This makes CI appear green when the Auto browse functionality is actually broken.

**How to avoid:** Replace `assumeTrue(false)` with `throw AssertionError(...)` (D-03). With KVM enabled, the 60s bind timeout should be achievable.

### Pitfall 4: AndroidAutoBrowseTest Service Leak
**What goes wrong:** `AndroidAutoBrowseTest` has no `@After`. When it passes, `BombestMediaService` is still running when `LoginFlowTest.setup()` fires. Without the `am stopservice` at the start of `BaseE2ETest.setup()`, 15-39 MB of Media3/ExoPlayer heap remains — causing continuous GC that blocks Compose rendering for 60+s.

**How to avoid:** Do NOT add `@After` to `AndroidAutoBrowseTest` — it's not needed. `BaseE2ETest.setup()` already calls `am stopservice` as its first action, which stops any residual service from AutoBrowse. D-04 explicitly says the current warm-up structure stays as-is.

### Pitfall 5: 3-Run Threshold Counting
**What goes wrong:** Triggering 3 CI runs but counting a run that includes a "skipped" AutoBrowse test as a pass.

**How to avoid:** Per D-01 and D-02: all 4 tests must show as `PASSED` in the JUnit XML output. A "skipped" result does not count. After removing `assumeTrue`, there are only two outcomes: PASSED or FAILED. Trigger 3 runs via `:run-test: android` PR comments on `test/verify-e2e-android-ci`. Count only runs where all 4 are green.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| GitHub Actions ubuntu-latest | CI runner | ✓ (prod) | ubuntu-22.04 | — |
| KVM on ubuntu-latest | Hardware-accelerated emulator | ✓ (requires udev rule) | Linux KVM | — |
| `reactivecircus/android-emulator-runner@v2` | Emulator lifecycle | ✓ | v2.37.0 latest | — |
| Android SDK system-images;android-29;default;x86_64 | API 29 emulator | ✓ (confirmed from CI logs) | android-29 | — |
| Java 17 (Temurin) | Gradle build | ✓ | 17 | — |
| `gradle/actions/setup-gradle@v4` | Gradle caching | ✓ | v4 | — |
| CI secrets `CI_TEST_USERNAME`, `CI_TEST_PASSWORD` | Test credential injection | ✓ (secret validation step passes) | — | — |
| `beats.bom.best` backend | E2E test login + data | ✓ (confirmed from API calls in tests) | live | — |
| PR #36 (`test/verify-e2e-android-ci`) | Verification runs | Open → main | — | — |

**Missing dependencies with no fallback:** None — all are available.

**Note:** The KVM device node (`/dev/kvm`) IS present on ubuntu-latest runners but requires the udev permissions rule before the emulator-runner action can use it.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| macOS runners for Android emulator | ubuntu-latest with KVM | April 2024 | 2-3x faster; requires KVM udev rule |
| Two-phase Gradle execution (force-stop between) | Single Gradle invocation | `b964cedc` (today) | Preserves JIT warmth; no process restart |
| 10s `By.pkg()` timeout | 60s `By.pkg()` timeout | `6e0a34f7` | Handles GC-induced cold starts |
| No service cleanup before tests | `am stopservice` in setup + tearDown | `96852330` | Releases 15-39 MB heap; prevents GC thrash |
| No inter-test sleep | 15s `Thread.sleep` in tearDown | `5afbe9da` | GC drain; prevents EditText accessibility failures |

**Deprecated/outdated:**
- Two-phase test execution (`4e13f9e5`): reverted in `b964cedc` — do not re-apply
- `assumeTrue(false)` skip path in `AndroidAutoBrowseTest`: must be removed per D-03

---

## Open Questions

1. **Will removing `assumeTrue` + adding KVM be sufficient for 3-run reliability?**
   - What we know: With KVM, the emulator runs at hardware speed. The `@Before launchApp()` warm-up (explicit `am startservice` + 30s login UI wait) was designed to make the 60s bind achievable. The previous CI runs that got to test execution phase had the service bind succeed (the `assumeTrue` skip was only in the TimeoutException catch, not unconditional).
   - What's unclear: Whether the `MediaBrowser.buildAsync().get(60, TimeUnit.SECONDS)` will consistently succeed on an API 29 KVM emulator within 60s.
   - Recommendation: Proceed with KVM + assumeTrue removal. If AutoBrowse still times out with KVM, investigate `launchApp()` warm-up — but D-04 says the structure stays unless needed for reliability.

2. **Does `AndroidAutoBrowseTest` need a browser.release() in an `@After`?**
   - What we know: The test calls `browser!!.release()` at the end of the happy path. If the test throws `AssertionError` (new behavior after removing assumeTrue), `release()` is not called.
   - What's unclear: Whether an unreleased `MediaBrowser` causes resource leaks that affect subsequent tests.
   - Recommendation: Wrap `browser.release()` in a `try/finally` inside the test, or add a class-level `@After` that calls `release()` if browser is non-null. This is a correctness fix, not a stability risk.

---

## Sources

### Primary (HIGH confidence)
- Direct CI log inspection via `gh run view` — failure modes, error messages, KVM warnings confirmed from production CI output
- [ReactiveCircus android-emulator-runner README](https://github.com/ReactiveCircus/android-emulator-runner/blob/main/README.md) — KVM setup step, `disable-linux-hw-accel` input, action.yml inputs
- Source files read directly from the repository: `BaseE2ETest.kt`, `AndroidAutoBrowseTest.kt`, `LoginPage.kt`, `LibraryPage.kt`, `pre-merge.yml`, `build.gradle.kts`

### Secondary (MEDIUM confidence)
- [GitHub Actions KVM announcement (April 2024)](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/) — confirms KVM available on 2-vCPU ubuntu-latest, requires udev rule
- [android-emulator-runner CHANGELOG v2.37.0](https://github.com/ReactiveCircus/android-emulator-runner/blob/main/CHANGELOG.md) — latest version confirmed v2.37.0

### Tertiary (LOW confidence)
- WebSearch results re: "No compatible devices" error pattern — consistent with KVM/hardware acceleration root cause

---

## Metadata

**Confidence breakdown:**
- Root cause identification (missing KVM): HIGH — confirmed from CI log `disable Linux hardware acceleration: true` and `WARNING | x86_64 emulation may not work without hardware acceleration!`
- Code change for assumeTrue removal: HIGH — exact location identified, straightforward replacement
- 3-run success prediction after fix: MEDIUM — KVM should resolve instability, but software emulators always carry some residual flakiness

**Research date:** 2026-04-22
**Valid until:** 2026-05-22 (stable ecosystem; GitHub Actions runner image changes are the main risk)
