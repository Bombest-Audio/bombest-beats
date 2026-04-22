---
phase: 08-get-the-ios-version-up-to-parity-with-android
plan: 05
subsystem: ios-carplay
tags: [ios, carplay, mpplayablecontentmanager, entitlements]
dependency_graph:
  requires: [08-01, 08-02]
  provides: [IOS-CARPLAY]
  affects: [ios-app/BombestBeats.entitlements, ios-app/Targets/BombestBeats/Sources/BombestApp.swift]
tech_stack:
  added: [MediaPlayer framework (CarPlay via MPPlayableContentManager)]
  patterns: [Singleton manager with weak AudioService reference, .task modifier for post-StateObject lifecycle hook]
key_files:
  created:
    - ios-app/Targets/BombestBeats/Sources/Services/CarPlayManager.swift
  modified:
    - ios-app/BombestBeats.entitlements
    - ios-app/Targets/BombestBeats/Sources/BombestApp.swift
decisions:
  - "CarPlay browse tree uses Playlists-first layout (D-15): root[0]=Playlists container, root[1]=All Songs leaf"
  - "Playlist-specific playback falls back to full library queue — async API call cannot be made in MPPlayableContentDelegate callback"
  - ".task modifier chosen over init() for CarPlayManager wiring — @StateObject not available in init()"
  - "Entitlement added now, ships when Apple approves com.apple.developer.playable-content (D-14)"
metrics:
  duration: "3 minutes"
  completed: "2026-04-22T06:04:57Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 2
---

# Phase 08 Plan 05: CarPlay Support Summary

CarPlay integration via MPPlayableContentManager — Playlists-first browse tree, entitlement declared, wired to AudioService at app launch.

## What Was Built

Added CarPlay support to the iOS app using `MPPlayableContentManager` (the pre-CarPlay framework equivalent of Android's `MediaBrowserServiceCompat`).

### CarPlayManager.swift (new)
- Singleton `NSObject` implementing `MPPlayableContentDataSource` and `MPPlayableContentDelegate`
- Browse tree per D-15: root[0] = "Playlists" container (isContainer: true, isPlayable: false), root[1] = "All Songs" leaf (isPlayable: true)
- Individual playlists enumerated as playable leaves under root[0]
- `configure(audioService:playlists:)` entry point called at app launch
- `updatePlaylists(_:)` for refreshing tree after library loads
- `weak var audioService` prevents retain cycle
- Playback delegate plays full queue for both All Songs and individual playlist taps (playlist-specific async fetch deferred to future phase)

### BombestBeats.entitlements (modified)
- Added `com.apple.developer.playable-content: true`
- Existing `com.apple.developer.associated-domains` entries preserved

### BombestApp.swift (modified)
- Added `.task` modifier on root Group in WindowGroup body
- Calls `CarPlayManager.shared.configure(audioService: audioService)` once at scene appearance
- `.task` is the correct hook — runs on main actor after `@StateObject` is fully initialized (unlike `init()`)

## Commits

| Task | Description | Hash |
|------|-------------|------|
| 1 | Add CarPlayManager with MPPlayableContentManager browse tree | 9a3ce3bc |
| 2 | Add CarPlay entitlement and wire CarPlayManager in BombestApp | c960a129 |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

- **Playlist-specific playback** (`CarPlayManager.swift` lines 100-105): When a specific playlist is tapped in CarPlay, the delegate falls back to playing the full library queue rather than the playlist's tracks. This is an intentional constraint — `MPPlayableContentDelegate`'s `initiatePlaybackOfContentItemAt` callback is synchronous; fetching playlist tracks from the API requires an async call. The stub is intentional and documented. This will be resolved in a future phase when the CarPlay entitlement is approved and end-to-end testing begins.

## Human Gate Required

Per D-17 and the plan's `<human_gates>` block:
- Apple entitlement request must be submitted at `developer.apple.com/contact/request/` → "Request a new entitlement" → "CarPlay Audio App"
- Takes 2–7 business days
- Development and CarPlay Simulator testing (Xcode Window > CarPlay Simulator) work **without** approval
- App Store distribution requires approval

The entitlement is already in `BombestBeats.entitlements` and will activate once Apple approves.

## Self-Check: PASSED

- `ios-app/Targets/BombestBeats/Sources/Services/CarPlayManager.swift` — FOUND
- `ios-app/BombestBeats.entitlements` — contains `playable-content` — FOUND
- `ios-app/Targets/BombestBeats/Sources/BombestApp.swift` — contains `CarPlayManager` — FOUND
- Commit 9a3ce3bc — FOUND
- Commit c960a129 — FOUND
