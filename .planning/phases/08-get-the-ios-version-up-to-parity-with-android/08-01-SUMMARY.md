---
phase: 08-get-the-ios-version-up-to-parity-with-android
plan: 01
subsystem: audio
tags: [ios, swift, avfoundation, accelerate, fft, haptics, avqueueplayer, corehaptics]

# Dependency graph
requires: []
provides:
  - Track.bpm field (Float, default 0) — safe JSON decoding from /library response
  - FrequencyBands struct (low/mid/high Float) — computed value type for FFT output
  - AudioService.amplitudes @Published [Float] (30 bands) driven by real AVAudioEngine FFT
  - AudioService.frequencyBands @Published FrequencyBands computed from same FFT tap
  - AudioService A-B loop state: loopStartTime, loopEndTime, activateLoop(), deactivateLoop(), snapToBeat()
  - HapticsManager.playGroove(band:intensity:) with band-specific CHHapticTransient profiles
  - Groove Engine Combine subscription in AudioService (frequencyBands → HapticsManager)
affects:
  - 08-02 (GraffitiVisualizer) — consumes AudioService.amplitudes
  - 08-03 (OscilloscopeVisualizer) — consumes AudioService.amplitudes
  - 08-04 (A-B Loop UI) — consumes loopStartTime, loopEndTime, activateLoop, deactivateLoop, snapToBeat

# Tech tracking
tech-stack:
  added: [Accelerate framework (vDSP FFT), CoreHaptics CHHapticTransient]
  patterns:
    - AVAudioEngine outputNode tap for real-time FFT without replacing AVQueuePlayer
    - Hann window applied before FFT to reduce spectral leakage
    - DispatchQueue.main.async wraps all @Published mutations from audio thread tap
    - Combine sink on @Published property to drive side effects (haptics) from audio data
    - var bpm with default value prevents JSON decode crash on old cached library data

key-files:
  created: []
  modified:
    - ios-app/Targets/BombestBeats/Sources/Models/Models.swift
    - ios-app/Targets/BombestBeats/Sources/Services/AudioService.swift
    - ios-app/Targets/BombestBeats/Sources/Services/HapticsManager.swift

key-decisions:
  - "FFT tap on AVAudioEngine.outputNode (not mainMixerNode) — captures system mix including AVQueuePlayer output without replacing the player"
  - "fftSetup created once in setupAudioEngine() and destroyed in deinit — never inside processAudioBuffer (performance)"
  - "30 amplitude bands for visualizer, 3 frequency bands (low/mid/high) for haptic Groove Engine from same FFT pass"
  - "FrequencyBands is NOT Codable — pure computed value type, never serialized"
  - "Groove haptic sink gated on isPlaying == true and band amplitude thresholds (0.5/0.5/0.6) to prevent constant firing"

patterns-established:
  - "Pattern: Audio thread → main thread publish via DispatchQueue.main.async in all @Published mutations from tap callbacks"
  - "Pattern: A-B loop cleanup in loadAndPlay() — deactivateLoop() called first to clear stale observer from prior track"

requirements-completed: [IOS-AUDIO-ANALYSIS, IOS-HAPTIC-GROOVE]

# Metrics
duration: 12min
completed: 2026-04-22
---

# Phase 8 Plan 01: Audio Analysis Foundation Summary

**AVAudioEngine FFT tap feeds 30-band visualizer amplitudes and 3-band Haptic Groove Engine, plus full A-B loop state infrastructure on AudioService**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-04-22T05:44:38Z
- **Completed:** 2026-04-22T05:47:28Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments

- Added real AVAudioEngine FFT pipeline to AudioService — Hann-windowed vDSP FFT on outputNode tap publishes 30-band amplitudes array and 3-band FrequencyBands on main thread
- Implemented Groove Engine: Combine sink on frequencyBands drives HapticsManager.playGroove() with kick/snare/hi-hat haptic profiles per D-07 band mapping
- Added complete A-B loop state infrastructure (loopStartTime, loopEndTime, activateLoop, deactivateLoop, snapToBeat) so Plan 04 can wire up UI without touching AudioService again
- Added Track.bpm field with `var` + default 0 to safely decode from old library_cache.json without crash

## Task Commits

Each task was committed atomically:

1. **Task 1: Add bpm field to Track and FrequencyBands struct** - `600d0373` (feat)
2. **Task 2: AVAudioEngine FFT pipeline + A-B loop state to AudioService** - `01d4d38d` (feat)
3. **Task 3: FrequencyBand enum and playGroove to HapticsManager** - `3b6a217e` (feat)

## Files Created/Modified

- `ios-app/Targets/BombestBeats/Sources/Models/Models.swift` - Added `var bpm: Float = 0` to Track struct and `FrequencyBands` struct (not Codable)
- `ios-app/Targets/BombestBeats/Sources/Services/AudioService.swift` - Added Accelerate import, @Published amplitudes/frequencyBands/loopStartTime/loopEndTime, AVAudioEngine FFT tap, processAudioBuffer, A-B loop methods, Groove Engine Combine sink
- `ios-app/Targets/BombestBeats/Sources/Services/HapticsManager.swift` - Added FrequencyBand enum and playGroove(band:intensity:) method

## Decisions Made

- FFT tap on `AVAudioEngine.outputNode` (not `mainMixerNode`) — RESEARCH.md flagged outputNode captures the full system mix including AVQueuePlayer audio; if tap produces silence on real device, fallback path is mainMixerNode, then AVAudioPlayerNode (requires architectural change, escalate first)
- `fftSetup` created once in `setupAudioEngine()`, destroyed in `deinit` — creating inside `processAudioBuffer` would be a severe performance regression (called at 44.1kHz buffer rate)
- Groove haptic thresholds: low/mid at 0.5, high at 0.6 — prevents constant haptic firing during quiet passages; only beats significantly above threshold trigger groove haptics

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None — all three files modified as specified without blocking issues.

## User Setup Required

**Human Gate HG-01 (post-implementation):** Validate AVAudioEngine tap captures AVQueuePlayer output on a real device (not simulator). Simulator routes audio differently and will always show non-zero. If `audioEngine.outputNode` tap produces all-zero buffers while music plays audibly on device: first try `mainMixerNode`; if still silent, escalate to architectural refactor (AVAudioPlayerNode in engine graph).

## Next Phase Readiness

- Plans 02 and 03 (visualizers) can now consume `AudioService.amplitudes` — 30 Float values published at AVAudioEngine buffer rate
- Plan 04 (A-B Loop UI) has all state and methods it needs on AudioService — no AudioService changes required in that plan
- Plan 03 (Groove Engine wiring in Views) is partially done — the Combine subscription is in AudioService.init(), the haptic firing is live; the remaining work is connecting visualizer views to the amplitudes publisher

---
*Phase: 08-get-the-ios-version-up-to-parity-with-android*
*Completed: 2026-04-22*
