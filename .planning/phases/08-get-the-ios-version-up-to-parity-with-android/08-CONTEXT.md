# Phase 8: Get the iOS Version up to Parity with Android — Context

**Gathered:** 2026-04-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Close all functional gaps between the iOS and Android apps. iOS already has the core stack (SwiftUI, AVPlayer, CoreHaptics, GraffitiVisualizer, FileCacheService, MetricsService, passkey auth). This phase adds the 7 capabilities iOS is missing: A-B loop, Haptic Groove Engine, real audio analysis for visualizers, CarPlay, favorites, Cloudflare→EC2 failover, and proper error recovery states.

Not in scope: iOS App Store submission (separate milestone), backend changes, new features not present on Android, AirPlay explicit integration (AVPlayer handles AirPlay automatically), Google Cast (Android-specific).

</domain>

<decisions>
## Implementation Decisions

### Parity Scope
- **D-01:** Full feature parity with Android. All 7 gap features are in scope for this phase.
- **D-02:** Platform-specific integrations that have no iOS equivalent (Google Cast, Android Auto) are excluded. CarPlay is the iOS equivalent of Android Auto and IS included.
- **D-03:** AirPlay is excluded — AVPlayer handles it automatically without explicit integration.

### A-B Loop
- **D-04:** Match Android exactly: A/B buttons flanking the scrubber, loop indicator above it, beat-grid snapping. `android-app/…/ui/screens/PlayerScreen.kt` is the reference layout to replicate in `PlayerView.swift`.
- **D-05:** BPM for beat-grid snapping comes from the backend library metadata (`bpm` field in `/library` response). iOS `Track` model must expose the `bpm` field (currently may be missing). Do NOT use real-time audio BPM analysis.
- **D-06:** Snap grid logic: if `track.bpm > 0`, snap loop points to nearest beat interval (`60.0 / bpm` seconds). If `bpm == 0`, snap to nearest 100ms as fallback.

### Haptic Groove Engine
- **D-07:** Port Android's 3-band frequency mapping to iOS using the existing `HapticsManager.swift` (which already owns `CHHapticEngine`):
  - Low band (kick) → strong dull thud: `intensity=1.0, sharpness=0.3`
  - Mid band (snare) → sharp crack: `intensity=0.8, sharpness=0.9`
  - High band (hi-hat) → light tick: `intensity=0.4, sharpness=0.95`
- **D-08:** Extend `HapticsManager` with a `playGroove(band: FrequencyBand, intensity: Float)` method. Do not create a separate manager class.
- **D-09:** Groove Engine subscribes to the same AVAudioEngine FFT tap as the visualizers — one shared audio analysis pipeline feeds both features.

### Real Visualizer Audio Analysis
- **D-10:** Replace fake `Timer.publish` amplitude animation in `PlayerView.swift` with an `AVAudioEngine` tap on the audio output node. Use `vDSP` FFT (Accelerate framework) to compute frequency bands at ~30fps.
- **D-11:** The FFT pipeline produces: an array of band amplitudes for `GraffitiVisualizer` + `OscilloscopeVisualizer`, and 3 summed frequency bands for the Groove Engine. One tap, two consumers.
- **D-12:** When playback is paused, amplitudes decay smoothly toward zero. Remove the fake timer entirely. Match Android's behavior.
- **D-13:** `AudioService.swift` owns the `AVAudioEngine` instance and exposes a `@Published var amplitudes: [Float]` and `@Published var frequencyBands: FrequencyBands` that views observe.

### CarPlay Support
- **D-14:** Build CarPlay code in this phase; ship when Apple approves the audio app entitlement. Request the entitlement concurrently with development. No compile-time flag needed.
- **D-15:** Browse tree structure (user's preference — differs from Android Auto): Playlists at root, All Songs as a child item. Matches a structured library usage pattern.
- **D-16:** CarPlay implementation uses `MPPlayableContentManager` (the standard iOS audio app CarPlay API). Implement now-playing, skip/pause transport controls from the dashboard.
- **D-17:** The `BombestBeats.entitlements` file must be updated with `com.apple.developer.playable-content` entitlement key.

### Favorites Manager
- **D-18:** Local-only favorites — no backend sync required. Persist in `UserDefaults` (keyed by track ID set). No API changes.
- **D-19:** Heart icon in `TrackRow.swift` toggles favorites. Favorited tracks also get a visual indicator in `LibraryView`. Mirror Android's `FavoritesManager.kt` pattern: a singleton `FavoritesManager` class with `toggle(trackId:)` and `isFavorited(trackId:) -> Bool`.

### Cloudflare → EC2 Failover
- **D-20:** `APIService.swift` gets a 2-URL retry: primary = `beats.bom.best` (Cloudflare), fallback = direct EC2 hostname/IP on 5xx response or timeout. Match Android's `NetworkModule` logic.
- **D-21:** The direct EC2 URL must be stored as a constant or environment variable — never hardcoded in a committed file if it's a raw IP. Check `android-app` `NetworkModule.kt` for the fallback URL value.

### Error Recovery States
- **D-22:** All data-loading screens (`LibraryView`, `PlaylistDetailView`, `SearchView`) get a full 3-state pattern:
  1. Loading: `ProgressView()` while request is in flight
  2. Failed: styled error banner + "Retry" button that re-calls the fetch
  3. Empty: `ContentUnavailableView` when response is successful but data is empty
- **D-23:** `LibraryView` already shows error text — upgrade it to the full pattern. `SearchView` already has `ContentUnavailableView` for empty state — add the error + retry state.

### Claude's Discretion
- Exact `vDSP` FFT window size and hop size (typical: 1024 samples, 50% overlap)
- `MPPlayableContentManager` vs `CPTemplateApplicationSceneDelegate` API choice (check iOS 14+ availability)
- `UserDefaults` key name for favorites set
- Whether `FrequencyBands` is a struct or tuple
- Accelerate import placement (AudioService vs separate AnalysisService)
- Exact EC2 fallback URL retrieval (read from `NetworkModule.kt` for the value used on Android)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Android reference implementations (port targets)
- `android-app/app/src/main/java/com/bombest/music/ui/screens/PlayerScreen.kt` — A-B loop UI layout, BPM snap indicator, loop controls positioning
- `android-app/app/src/main/java/com/bombest/music/haptics/HapticGrooveEngine.kt` — 3-band frequency mapping logic to port
- `android-app/app/src/main/java/com/bombest/music/haptics/HapticPatternLibrary.kt` — Haptic pattern definitions (intensity/sharpness per band)
- `android-app/app/src/main/java/com/bombest/music/data/FavoritesManager.kt` — Favorites persistence pattern to mirror
- `android-app/app/src/main/java/com/bombest/music/data/NetworkModule.kt` — Cloudflare→EC2 failover logic + direct EC2 URL value
- `android-app/app/src/main/java/com/bombest/music/car/` — Android Auto browse tree structure (reference for CarPlay tree design)
- `android-app/app/src/main/java/com/bombest/music/service/BombestMediaService.kt` — Browse tree content (All Songs, Playlists, Shuffle, Recently Played items)

### iOS files being extended
- `ios-app/Targets/BombestBeats/Sources/Services/AudioService.swift` — Owns AVPlayer; add AVAudioEngine tap, amplitudes, frequencyBands here
- `ios-app/Targets/BombestBeats/Sources/Services/HapticsManager.swift` — Already owns CHHapticEngine; add `playGroove(band:intensity:)` here
- `ios-app/Targets/BombestBeats/Sources/Views/PlayerView.swift` — Add A-B loop UI, remove fake Timer.publish, wire real amplitudes
- `ios-app/Targets/BombestBeats/Sources/Views/TrackRow.swift` — Add heart/favorites toggle
- `ios-app/Targets/BombestBeats/Sources/Views/LibraryView.swift` — Upgrade to 3-state error pattern
- `ios-app/Targets/BombestBeats/Sources/Views/SearchView.swift` — Add error + retry state
- `ios-app/Targets/BombestBeats/Sources/Models/Models.swift` — Add `bpm: Float` field to Track model
- `ios-app/BombestBeats.entitlements` — Add CarPlay entitlement key

### Backend (read-only reference)
- `beets-backend/upload_server.py` lines ~595–604, ~1707–1736 — BPM field in library response; confirms `bpm` is available in `/library` and `/track/<id>` responses

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `HapticsManager.swift` — Already owns `CHHapticEngine`, already has `playTick()` / `playImpact()` / `playSuccess()`. Groove Engine extends this, does NOT replace it.
- `GraffitiVisualizer.swift` + `OscilloscopeVisualizer.swift` — Both already accept `amplitudes: [Float]` as input. Only the data source changes (FFT replaces fake timer). No view changes needed.
- `FileCacheService.swift` — Unchanged. Already handles 1GB LRU + persistent downloads.
- `APIService.swift` — Add 2-URL retry logic here. Everything else in the service stays.
- `SprayPaintProgress.swift` — Scrubber component; A-B loop markers will be overlaid on this or positioned adjacent to it.

### Established Patterns
- iOS uses `@EnvironmentObject var audioService: AudioService` throughout — all new published state (`amplitudes`, `frequencyBands`, `loopStartTime`, `loopEndTime`) should live on `AudioService`.
- `@AppStorage("isVisualizerEnabled")` pattern is already used in `PlayerView` — use `@AppStorage` for user preferences (e.g., haptics enabled, groove enabled).
- Error handling: `LibraryViewModel` already has an `errorMessage: String?` pattern — upgrade to enum `LoadState { loading, loaded, failed(String) }` to support retry.

### Integration Points
- `MainTabView.swift` — Tab bar; CarPlay does not require changes here but the `AudioService` instance injected as `@EnvironmentObject` must be accessible to the CarPlay manager.
- `BombestApp.swift` — App entry point; `AVAudioSession` is configured in `AudioService.setupAudioSession()`. Ensure `.playback` category is maintained when adding `AVAudioEngine`.
- `PlayerView.swift` timer removal — The `let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()` and `.onReceive(timer)` block are the fake animation; both must be removed and replaced with `.onReceive(audioService.$amplitudes)`.

</code_context>

<specifics>
## Specific Ideas

- Android Auto browse tree has 4 items (All Songs, Playlists, Shuffle All, Recently Played). iOS CarPlay uses a **Playlists-first** structure instead: Playlists at root, All Songs as a child item. This is a deliberate divergence from Android — not a mistake.
- The `bpm` field is already in the backend `/library` response. The iOS `Track` model in `Models.swift` may not yet have this field — add it as `var bpm: Float = 0` with a default so existing JSON decoding doesn't break.
- Beat-grid snap formula: `snapTime = round(rawTime / beatInterval) * beatInterval` where `beatInterval = 60.0 / bpm`. If `bpm == 0`, use `beatInterval = 0.1` (100ms grid).
- The A-B loop currently loops on the `AVQueuePlayer`. With `AVAudioEngine` added for analysis, the audio graph changes — confirm the player is connected to the engine's output node correctly so both playback and analysis work together.

</specifics>

<deferred>
## Deferred Ideas

- **AirPlay explicit integration** — AVPlayer handles AirPlay automatically. Explicit AirPlay routing UI (AirPlay picker button) could be added but is not needed for parity.
- **Google Cast** — Android-only. No iOS equivalent needed since AirPlay covers the use case.
- **Backend-synced favorites** — Favorites are local-only this phase. Cross-device sync requires a `/favorites` backend endpoint — defer to a future phase.
- **iOS App Store submission** — Separate milestone. This phase is about feature parity, not distribution.
- **Automated CI for iOS (Xcode Cloud / GitHub Actions)** — iOS CI is harder than Android CI. Deferred unless explicitly added to scope.

</deferred>

---

*Phase: 08-get-the-ios-version-up-to-parity-with-android*
*Context gathered: 2026-04-21*
