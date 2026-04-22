# Phase 8: Get the iOS Version up to Parity with Android — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-21
**Phase:** 08-get-the-ios-version-up-to-parity-with-android
**Areas discussed:** Parity scope, A-B loop, Visualizer audio analysis, CarPlay, Favorites, Failover, Error recovery

---

## Parity Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Core playback parity | A-B loop, visualizer, haptics, error recovery only. Skip CarPlay/AirPlay/Cast. | |
| Full feature parity | Everything Android has: all 7 gap features | ✓ |
| Defined gap list | User enumerates specific features | |

**Follow-up — feature confirmation (multi-select):**

| Feature | Selected |
|---------|----------|
| A-B loop (beat-grid snapping) | ✓ |
| Haptic Groove Engine | ✓ |
| Real visualizer audio analysis | ✓ |
| CarPlay support | ✓ |
| Favorites manager | ✓ |
| Failover (Cloudflare → EC2) | ✓ |
| Error recovery states | ✓ |

**User's choice:** All 7 features in scope.
**Notes:** AirPlay (AVPlayer auto-handles) and Google Cast (Android-only) are explicitly excluded.

---

## A-B Loop

| Option | Description | Selected |
|--------|-------------|----------|
| Match Android exactly | A/B buttons flanking scrubber, loop indicator, beat-grid snapping | ✓ |
| Simplified (no beat-grid) | A/B buttons at current position only, no BPM math | |
| Long-press gesture | Long-press scrubber to place markers | |

**BPM source follow-up:**

| Option | Description | Selected |
|--------|-------------|----------|
| Backend BPM metadata | Read `bpm` from `/library` response — same as Android | ✓ |
| AVAudioEngine BPM detection | Real-time analysis, more accurate | |

**Note:** User initially selected AVAudioEngine but reversed when informed Android uses backend metadata. Final decision: backend `bpm` field.

---

## Visualizer Audio Analysis

| Option | Description | Selected |
|--------|-------------|----------|
| AVAudioEngine + Accelerate FFT | vDSP FFT tap at ~30fps, feeds both visualizers and Groove Engine | ✓ |
| AVPlayer periodicTimeObserver | Still fake but synchronized | |

**Pause behavior follow-up:**

| Option | Description | Selected |
|--------|-------------|----------|
| Real data only, decay on pause | Remove fake timer, amplitudes decay to zero | ✓ |
| Keep timer as paused fallback | Idle animation while paused | |

---

## CarPlay Support

| Option | Description | Selected |
|--------|-------------|----------|
| Build now, ship when approved | Implement fully, request entitlement concurrently | ✓ |
| Defer until entitlement approved | Don't build until Apple approves | |
| Build behind compile-time flag | Gate with flag until approved | |

**Browse tree follow-up:**

| Option | Description | Selected |
|--------|-------------|----------|
| Match Android Auto | All Songs, Playlists, Shuffle All, Recently Played at root | |
| Minimal (All Songs + Shuffle) | Two items only | |
| Playlists-first | Playlists at root, All Songs as child item | ✓ |

**User's choice:** Playlists-first — deliberate divergence from Android Auto structure.

---

## Favorites

| Option | Description | Selected |
|--------|-------------|----------|
| Local-only, no backend sync | UserDefaults, no API changes | ✓ |
| Backend-synced favorites | POST/DELETE to /favorites endpoint | |

---

## Failover

| Option | Description | Selected |
|--------|-------------|----------|
| Same 2-URL retry in APIService | beats.bom.best → direct EC2 on 5xx/timeout | ✓ |
| Single URL, user-visible error | Keep current single-URL, surface error | |

---

## Error Recovery States

| Option | Description | Selected |
|--------|-------------|----------|
| Full 3-state on all screens | ProgressView → error + retry → ContentUnavailableView on Library, Playlists, Search | ✓ |
| Error + retry only | Replace error text with styled error + retry button | |

---

## Claude's Discretion

- vDSP FFT window size and hop size
- MPPlayableContentManager vs CPTemplateApplicationSceneDelegate API choice
- UserDefaults key name for favorites
- FrequencyBands type (struct vs tuple)
- Accelerate import placement
- Exact EC2 fallback URL value (read from Android NetworkModule.kt)

## Deferred Ideas

- AirPlay explicit UI (AVPlayer handles automatically)
- Google Cast (Android-only)
- Backend-synced favorites (requires /favorites endpoint)
- iOS App Store submission (separate milestone)
- iOS CI automation (Xcode Cloud / GitHub Actions)
