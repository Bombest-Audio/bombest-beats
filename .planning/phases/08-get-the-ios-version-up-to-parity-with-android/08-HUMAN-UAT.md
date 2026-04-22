---
status: partial
phase: 08-get-the-ios-version-up-to-parity-with-android
source: [08-VERIFICATION.md]
started: 2026-04-22T06:10:42Z
updated: 2026-04-22T06:10:42Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. AVAudioEngine tap captures real audio on device
expected: GraffitiVisualizer animates in sync with playback on a physical device. If silent, swap tap from `outputNode` to `mainMixerNode` per plan guidance.
result: [pending]

### 2. A-B loop visual and functional check
expected: A and B buttons light NeonPurple when set; "Loop Active" indicator appears above scrubber; playback loops between the correct timestamps. Tapping either armed button while active clears the loop.
result: [pending]

### 3. CarPlay Simulator walkthrough + entitlement submission
expected: Browse tree renders in Xcode's CarPlay Simulator with Playlists root and All Songs leaf. Apple audio app entitlement submitted at developer.apple.com/contact/request/ for App Store distribution.
result: [pending]

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0
blocked: 0

## Gaps
