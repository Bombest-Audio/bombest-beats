---
phase: 08-get-the-ios-version-up-to-parity-with-android
plan: "04"
subsystem: ios-player-ui
tags: [ios, swift, a-b-loop, visualizer, fft, player-view]
dependency_graph:
  requires: [08-01]
  provides: [IOS-AB-LOOP-UI]
  affects: [ios-app/Targets/BombestBeats/Sources/Views/PlayerView.swift]
tech_stack:
  added: []
  patterns: [onReceive-published-property, AppStorage, HStack-flanking-scrubber]
key_files:
  created: []
  modified:
    - ios-app/Targets/BombestBeats/Sources/Views/PlayerView.swift
decisions:
  - "SprayPaintProgress stays in its HStack alongside A/B buttons — circular scrubber moved out of artwork ZStack overlay into its own row, artwork now unobscured"
metrics:
  duration: "~8 minutes"
  completed: "2026-04-21"
  tasks_completed: 1
  tasks_total: 1
  files_modified: 1
---

# Phase 08 Plan 04: A-B Loop UI + Real FFT Visualizer Wiring Summary

**One-liner:** A-B loop controls flanking SprayPaintProgress scrubber with beat-grid snapping, loop active indicator, and GraffitiVisualizer wired to real FFT amplitudes from AudioService.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Remove fake timer, wire real amplitudes, add A-B loop UI | e4451063 | ios-app/Targets/BombestBeats/Sources/Views/PlayerView.swift |

## What Was Built

**Fake timer removed:**
- Deleted `@State private var amplitudes: [Float]` (the mock 0.2-filled array)
- Deleted `let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()`
- Deleted the entire `.onReceive(timer) { _ in ... }` block (fake random wave animation)

**Real FFT wired:**
- `GraffitiVisualizer(amplitudes: amplitudes)` → `GraffitiVisualizer(amplitudes: audioService.amplitudes)`
- Added `.onReceive(audioService.$amplitudes) { _ in }` on the outer ZStack for explicit refresh signaling

**A-B loop UI:**
- Loop active indicator: `Image(systemName: "repeat") + Text("Loop Active") + Button("Clear")` — shown only when both `loopStartTime` and `loopEndTime` are non-nil, with NeonPurple color
- HStack with A button + SprayPaintProgress + B button (per D-04 layout spec)
- A button: sets `audioService.loopStartTime` via `snapToBeat(currentTime, bpm: currentTrack?.bpm)`; auto-activates loop if B already set; clears loop if both already set
- B button: sets `audioService.loopEndTime` via `snapToBeat`; only accepts if snap result is after A; auto-activates loop; clears loop if both already set
- Both buttons show NeonPurple when their point is set, gray when unset
- Added `@AppStorage("isHapticGrooveEnabled")` property alongside existing `isVisualizerEnabled`

**Layout change (deviation from prior ZStack):**
- The `SprayPaintProgress` was previously overlaid on top of the album artwork in a `ZStack`. The plan spec called for placing it inside an HStack with A/B buttons. This required moving it out of the artwork ZStack, which means the circular scrubber no longer overlays the artwork — the artwork is now displayed clean and the scrubber row appears below it. This is the correct Android-parity layout (D-04 reference).

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written.

### Out-of-Scope Issues (deferred)

**Pre-existing build errors not caused by this plan:**
- `LibraryView.swift:6`: `cannot find 'FavoritesManager' in scope`
- `TrackRow.swift:7`: `cannot find 'FavoritesManager' in scope`

These errors exist from Plan 03's FavoritesManager work and are unrelated to PlayerView. Per deviation rules, not fixed here. Logged to deferred-items.md.

## Known Stubs

None — all A-B loop logic fully wired to AudioService methods from Plan 01. GraffitiVisualizer receives live FFT data.

## Human Gate Required

**HG-02:** Visual verification of A-B loop controls and loop enforcement accuracy on device/simulator.
- Open full player, set A at ~0:10, B at ~0:20
- Verify both buttons light up NeonPurple
- Verify "Loop Active" indicator appears
- Verify playback loops back to 0:10 when it hits 0:20
- Verify GraffitiVisualizer animates to music (not static/random)

## Self-Check: PASSED

- [x] `ios-app/Targets/BombestBeats/Sources/Views/PlayerView.swift` exists and modified
- [x] Commit e4451063 exists in git log
- [x] No `Timer.publish` in PlayerView
- [x] No `@State private var amplitudes` in PlayerView
- [x] No `.onReceive(timer)` in PlayerView
- [x] `GraffitiVisualizer(amplitudes: audioService.amplitudes)` present
- [x] `audioService.loopStartTime` present
- [x] `audioService.snapToBeat(` present
- [x] `audioService.activateLoop()` present
- [x] `audioService.deactivateLoop()` present
- [x] `Text("A")` and `Text("B")` present
- [x] `"Loop Active"` text present
- [x] `.onReceive(audioService.$amplitudes)` present
