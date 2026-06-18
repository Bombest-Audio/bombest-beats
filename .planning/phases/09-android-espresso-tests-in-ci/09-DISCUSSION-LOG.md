# Phase 9: Android Espresso Tests in CI — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-22
**Phase:** 09-android-espresso-tests-in-ci
**Areas discussed:** Completion criteria, AndroidAutoBrowseTest scope, Flakiness tolerance, Merge strategy

---

## Completion Criteria

| Option | Description | Selected |
|--------|-------------|----------|
| Green CI run verified | At least one PR triggers e2e-android and P0 tests pass | |
| Zero-flake threshold | All 4 tests must pass on 3 consecutive CI runs | ✓ |
| Workflow finalized only | Phase ends when YAML is merged — reliability tracked separately | |

**User's choice:** Zero-flake threshold — 3 consecutive CI runs, all 4 tests must pass

**Follow-up — applies to all 4 or P0 only?**

| Option | Description | Selected |
|--------|-------------|----------|
| All 4 tests | Auto Browse must also pass 3× — no assumeTrue skips | ✓ |
| P0 flows only | Login/Playback/Playlist must pass 3×; Auto Browse can skip | |
| You decide | Claude sets threshold based on practicality | |

**User's choice:** All 4 tests — assumeTrue skip no longer counts as a pass

---

## AndroidAutoBrowseTest Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Fix it — no skipping allowed | Remove assumeTrue; service must bind or test fails hard | ✓ |
| Move to its own CI step | Separate job — can fail without blocking P0 merge gate | |
| Remove from this phase | Pull Auto Browse out; P0 tests are the gate | |

**User's choice:** Fix it — remove the assumeTrue skip path; service binding failure = hard failure

---

## Flakiness Tolerance

| Option | Description | Selected |
|--------|-------------|----------|
| Accept it as-is | 15s sleep + 60s waits stay; suite ~6–8 min total | ✓ |
| More surgical fix | Investigate GC callback / heap measurement | |
| Try higher API level | Switch to API 31/33 for better ART GC | |

**User's choice:** Accept as-is — 15s sleep and 60s waits are the final solution

---

## Merge Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| PR with CI verification | 3× :run-test: android → green → merge | ✓ |
| Direct merge after local verify | Verify locally, skip waiting for 3 CI runs | |
| Squash and merge | Squash stabilization commits + 3 CI verifications | |

**User's choice:** PR with CI verification — 3 green runs, then merge preserving commit history

---

## Claude's Discretion

- Whether to add `timeout-minutes` cap on the `e2e-android` job
- Exact `reactivecircus/android-emulator-runner` version pin
- Whether to add AVD caching (`avd-cache: true`)

## Deferred Ideas

- More surgical GC fix (heap monitoring instead of sleep)
- API 29 → 31/33 upgrade
- AVD caching (left to Claude's discretion)
- iOS CI (out of scope, separate milestone)
