# Phase 7: Android Espresso E2E Test Suite with CI Integration — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-16
**Phase:** 07-android-espresso-e2e-test-suite-with-ci-integration
**Areas discussed:** Emulator strategy, Credential injection, Failure artifacts, Flaky test policy

---

## Emulator Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| reactivecircus/android-emulator-runner | Community GitHub Action; boots AVD, runs connectedAndroidTest, shuts down. Zero cost beyond GH Actions minutes. | ✓ |
| Gradle Managed Devices | Declare AVD in build.gradle.kts; AGP 7.3+ feature; more Gradle-native. | |
| Self-hosted runner + physical Pixel | Pixel 9 (47070DLAQ0014L) on a self-hosted runner. No emulator flakiness but requires always-on machine. | |

**User's choice:** `reactivecircus/android-emulator-runner`

---

### AVD Spec

| Option | Description | Selected |
|--------|-------------|----------|
| Pixel 5 API 34 arm64 | Matches compileSdk 34; arm64 on GitHub ARM runners avoids x86 KVM issues. | ✓ |
| Pixel 2 API 31 x86_64 | Covers minSdk floor better; well-tested on GH Actions. | |

**User's choice:** Pixel 5, API 34, arm64

---

## Credential Injection

| Option | Description | Selected |
|--------|-------------|----------|
| GitHub Actions secrets → testInstrumentationRunnerArguments | Direct injection at Gradle invocation. Cleanest approach. | |
| Secrets → synthetic local.properties | Write secrets to file before Gradle runs. Fragile path assumptions. | |
| Dedicated CI test account | Separate user on beats.bom.best; isolates CI data from personal library. | ✓ |

**User's choice:** Dedicated CI test account (`ci`)

---

### Library Content for CI Account

| Option | Description | Selected |
|--------|-------------|----------|
| Seed with 2–3 real tracks from S3 | One-time manual admin setup. Playback test needs at least one track. | ✓ |
| Reuse thomas account | Simpler setup; CI playlist creates/deletes appear in personal library. | |

**User's choice:** Seed with 2–3 real tracks

---

### Credential Storage & Injection

| Option | Description | Selected |
|--------|-------------|----------|
| GitHub Actions secrets → testInstrumentationRunnerArguments | CI_TEST_USERNAME / CI_TEST_PASSWORD secrets, injected via -P flags. | ✓ |
| Secrets → synthetic local.properties | Works without build.gradle.kts changes but fragile. | |

**User's choice:** GitHub Actions secrets → testInstrumentationRunnerArguments

---

## Failure Artifacts

| Option | Description | Selected |
|--------|-------------|----------|
| Test XML report + logcat | JUnit XML + adb logcat dump uploaded as GH Actions artifacts on failure. | ✓ |
| XML + logcat + on-device screenshots | Adds TestWatcher to BaseE2ETest for per-failure screenshots. More diagnostic; requires code changes. | |
| Just Gradle output | No upload step; read from workflow log. | |

**User's choice:** Test XML report + logcat

---

### PR Comment Summary

| Option | Description | Selected |
|--------|-------------|----------|
| Post pass/fail summary as PR comment | Fits existing emoji-trigger workflow; no need to dig into Actions UI. | ✓ |
| Rely on check status only | Green/red checkmark is enough; no comment clutter. | |

**User's choice:** Post pass/fail summary as PR comment

---

## Flaky Test Policy

| Option | Description | Selected |
|--------|-------------|----------|
| Fail immediately, no retries | Simple; flaky failures re-triggered manually. Right size for a 3-flow personal app. | ✓ |
| Retry whole run once on failure | Adds 5–10 min worst-case but saves manual re-triggers. | |
| Gradle maxRetries per test method | Most granular; requires ANDROIDX_TEST_ORCHESTRATOR + maxRetries config. | |

**User's choice:** Fail immediately, no retries

---

### Merge Gate

| Option | Description | Selected |
|--------|-------------|----------|
| Block merge | Consistent with how assembleDebug failures are treated today. | ✓ |
| Warn only | Post warning but allow merge. | |

**User's choice:** Block merge

---

## Claude's Discretion

- Exact action version to pin for `reactivecircus/android-emulator-runner`
- Logcat capture mechanism
- JUnit XML comment formatter choice
- Gradle cache / AVD cache configuration
