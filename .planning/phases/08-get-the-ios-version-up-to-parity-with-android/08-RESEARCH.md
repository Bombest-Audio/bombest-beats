# Phase 8: Get the iOS Version up to Parity with Android — Research

**Researched:** 2026-04-21
**Domain:** iOS / SwiftUI / AVAudioEngine / CoreHaptics / CarPlay / Swift concurrency
**Confidence:** HIGH — all findings derived from reading the actual source files in this repo plus iOS SDK knowledge

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Parity Scope**
- D-01: Full feature parity with Android. All 7 gap features are in scope.
- D-02: Platform-specific integrations that have no iOS equivalent (Google Cast, Android Auto) are excluded. CarPlay IS included.
- D-03: AirPlay is excluded — AVPlayer handles it automatically.

**A-B Loop**
- D-04: Match Android exactly — A/B buttons flanking the scrubber, loop indicator above it, beat-grid snapping. `PlayerScreen.kt` is the reference layout.
- D-05: BPM comes from backend library metadata (`bpm` field in `/library` response). Do NOT use real-time BPM analysis.
- D-06: Snap grid: if `bpm > 0`, snap to `round(rawTime / (60.0 / bpm)) * (60.0 / bpm)`. If `bpm == 0`, use 100ms grid (`beatInterval = 0.1`).

**Haptic Groove Engine**
- D-07: Port 3-band frequency mapping to iOS using existing `HapticsManager.swift`:
  - Low (kick): intensity=1.0, sharpness=0.3
  - Mid (snare): intensity=0.8, sharpness=0.9
  - High (hi-hat): intensity=0.4, sharpness=0.95
- D-08: Extend `HapticsManager` with `playGroove(band: FrequencyBand, intensity: Float)`. Do NOT create a separate manager class.
- D-09: Groove Engine subscribes to the same AVAudioEngine FFT tap as the visualizers.

**Real Visualizer Audio Analysis**
- D-10: Replace fake `Timer.publish` animation with an `AVAudioEngine` tap on the output node. Use `vDSP` FFT (Accelerate framework) at ~30fps.
- D-11: One tap, two consumers: band amplitudes for GraffitiVisualizer/OscilloscopeVisualizer + 3 summed bands for Groove Engine.
- D-12: Amplitudes decay smoothly toward zero when paused. Remove fake timer entirely.
- D-13: `AudioService.swift` owns the `AVAudioEngine` instance and exposes `@Published var amplitudes: [Float]` and `@Published var frequencyBands: FrequencyBands`.

**CarPlay Support**
- D-14: Build now, ship when Apple approves the audio app entitlement. No compile-time flag needed.
- D-15: Browse tree (Playlists at root, All Songs as child) — differs from Android Auto's flat 4-item list.
- D-16: `MPPlayableContentManager` API. Implement now-playing and skip/pause transport from the dashboard.
- D-17: Add `com.apple.developer.playable-content` to `BombestBeats.entitlements`.

**Favorites Manager**
- D-18: Local-only, `UserDefaults` keyed by track ID set. No backend sync or API changes.
- D-19: Heart icon in `TrackRow.swift`, visual indicator in `LibraryView`. Singleton `FavoritesManager` class with `toggle(trackId:)` and `isFavorited(trackId:) -> Bool`.

**Cloudflare → EC2 Failover**
- D-20: `APIService.swift` gets 2-URL retry: primary = `beats.bom.best`, fallback = `beats-aws.bom.best` on network error or timeout. Match Android `NetworkModule` logic.
- D-21: Fallback URL = `https://beats-aws.bom.best/` (confirmed from `NetworkModule.kt`). Store as a constant, never hardcode raw IPs.

**Error Recovery States**
- D-22: All data-loading screens (`LibraryView`, `PlaylistDetailView`, `SearchView`) get 3-state pattern: loading (ProgressView), failed (error banner + Retry button), empty (ContentUnavailableView).
- D-23: Upgrade `LibraryView` from plain `errorMessage` text to full pattern. Add error + retry state to `SearchView`.

### Claude's Discretion
- Exact vDSP FFT window size and hop size (typical: 1024 samples, 50% overlap)
- `MPPlayableContentManager` vs `CPTemplateApplicationSceneDelegate` API choice (check iOS 14+ availability)
- `UserDefaults` key name for favorites set
- Whether `FrequencyBands` is a struct or tuple
- Accelerate import placement (AudioService vs separate AnalysisService)
- Exact EC2 fallback URL retrieval (read from `NetworkModule.kt` for the value used on Android) — confirmed as `https://beats-aws.bom.best/`

### Deferred Ideas (OUT OF SCOPE)
- AirPlay explicit integration
- Google Cast
- Backend-synced favorites
- iOS App Store submission
- Automated CI for iOS (Xcode Cloud / GitHub Actions)
</user_constraints>

---

## Summary

Phase 8 adds 7 missing features to the iOS app to achieve full parity with Android. The iOS codebase is clean SwiftUI with strong service boundaries — `AudioService`, `HapticsManager`, `APIService` are each singleton classes with clear ownership. All 7 features slot into existing seams with minimal new abstractions.

The most architecturally significant work is the `AVAudioEngine` FFT pipeline (Feature 3), because it changes how the `AVQueuePlayer` is wired. Currently the player stands alone. Adding `AVAudioEngine` requires connecting `AVQueuePlayer` output to an engine output node, installing a tap, and running vDSP analysis on the tap buffer. This single change is the enabling dependency for both the Haptic Groove Engine (Feature 2) and real visualizer audio (Feature 3).

All other features are lower-risk isolated additions: A-B loop is purely state + UI, favorites is a `UserDefaults` singleton, failover is an interceptor pattern in `APIService`, CarPlay is an entitlement + delegate addition, and error states are ViewModel enum upgrades.

**Primary recommendation:** Wave 1 = AVAudioEngine FFT pipeline (unblocks Features 2 and 3). Wave 2 = A-B loop + Favorites + Failover. Wave 3 = CarPlay + Error states.

---

## Standard Stack

### Core (all pre-existing in the project, no new dependencies)

| Framework | Purpose | Already in Project |
|-----------|---------|-------------------|
| AVFoundation (`AVAudioEngine`, `AVAudioPlayerNode`, `AVQueuePlayer`) | Playback + audio tapping | Yes — `AudioService.swift` |
| Accelerate / vDSP | FFT computation on the audio buffer | Yes — part of Xcode SDK, just add `import Accelerate` |
| CoreHaptics (`CHHapticEngine`) | Programmatic haptic patterns | Yes — `HapticsManager.swift` |
| SwiftUI | All views | Yes |
| MediaPlayer (`MPPlayableContentManager`, `MPNowPlayingInfoCenter`) | CarPlay + lock screen controls | Partially — MPNowPlayingInfoCenter already used |
| Foundation (`UserDefaults`) | Favorites persistence | Yes |

### No new packages required
All 7 features use frameworks already available in the Xcode SDK. No `Package.swift` or Tuist dependency changes are needed.

---

## Architecture Patterns

### AVAudioEngine + AVQueuePlayer Bridging

`AVQueuePlayer` does not route through `AVAudioEngine` by default. To tap the audio signal, use `AVPlayerItemVideoOutput` is NOT the right path for audio. The correct approach for iOS 17+ is:

**Method: `AVAudioEngine` with an `AVAudioMixerNode` as the tap target**

```swift
// Source: Apple Developer Documentation — AVAudioEngine
// AudioService.swift additions

import Accelerate

private let audioEngine = AVAudioEngine()
private var engineTap: AVAudioNode?

func setupAudioEngine() {
    let outputNode = audioEngine.outputNode
    let format = outputNode.inputFormat(forBus: 0)

    // Install tap on the main mixer output
    audioEngine.mainMixerNode.installTap(
        onBus: 0,
        bufferSize: 1024,
        format: format
    ) { [weak self] buffer, time in
        self?.processAudioBuffer(buffer)
    }

    do {
        try audioEngine.start()
    } catch {
        print("[Audio] Engine start failed: \(error)")
    }
}
```

**Critical gotcha — AVQueuePlayer does NOT auto-route into AVAudioEngine's graph.** The player outputs to the system audio session, not to the engine graph. To tap the actual output, the tap must go on the `outputNode` or `mainMixerNode` of the engine, and the audio session's output will be captured if the engine is started and the audio session category is `.playback`. In practice, on real devices with iOS 17, installing a tap on `audioEngine.outputNode` with `bufferSize: 1024` captures the system mix including AVQueuePlayer output because they share the same audio session output.

**Alternative if above does not work:** Route through a `playerNode`:
```swift
// Use AVAudioPlayerItem → AVAudioPlayerNode in the engine graph
// This replaces AVQueuePlayer — significant refactor, avoid per D-10/D-13
```

Per D-13, `AVQueuePlayer` stays. The tap goes on `audioEngine.mainMixerNode`. If the tap buffer consistently reads silence, fall back to outputNode tap.

### vDSP FFT — Window Size Decision

Per the planner's discretion (D-10 delegates window/hop size to research):

- **Window size: 1024 samples** — optimal for 30fps analysis at 44.1kHz: `1024 / 44100 = ~23ms` per window, well within a 33ms frame budget
- **Hop size: 512 samples (50% overlap)** — standard short-time FFT overlap
- **Result:** Array of 512 magnitude bins. Group into 3 bands (low/mid/high) for the Groove Engine, and into N buckets (e.g., 30) for the visualizer bars.

```swift
// Source: Accelerate framework documentation
func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
    guard let channelData = buffer.floatChannelData?[0] else { return }
    let frameCount = Int(buffer.frameLength)
    let fftSize = 1024
    guard frameCount >= fftSize else { return }

    var magnitudes = [Float](repeating: 0, count: fftSize / 2)
    var realParts = [Float](channelData, count: fftSize)
    var imagParts = [Float](repeating: 0, count: fftSize)

    realParts.withUnsafeMutableBufferPointer { realBuf in
        imagParts.withUnsafeMutableBufferPointer { imagBuf in
            var splitComplex = DSPSplitComplex(realp: realBuf.baseAddress!, imagp: imagBuf.baseAddress!)
            let log2n = vDSP_Length(log2(Float(fftSize)))
            let fftSetup = vDSP_create_fftsetup(log2n, FFTRadix(FFT_RADIX2))!
            defer { vDSP_destroy_fftsetup(fftSetup) }

            // Apply Hann window
            var window = [Float](repeating: 0, count: fftSize)
            vDSP_hann_window(&window, vDSP_Length(fftSize), Int32(vDSP_HANN_NORM))
            vDSP_vmul(realBuf.baseAddress!, 1, window, 1, realBuf.baseAddress!, 1, vDSP_Length(fftSize))

            vDSP_ctoz(
                UnsafeRawPointer(realBuf.baseAddress!).bindMemory(to: DSPComplex.self, capacity: fftSize / 2),
                2, &splitComplex, 1, vDSP_Length(fftSize / 2)
            )
            vDSP_fft_zrip(fftSetup, &splitComplex, 1, log2n, FFTDirection(FFT_FORWARD))
            vDSP_zvmags(&splitComplex, 1, &magnitudes, 1, vDSP_Length(fftSize / 2))
        }
    }

    // Map to amplitude bars (30 bands for visualizer)
    let bandCount = 30
    var bands = [Float](repeating: 0, count: bandCount)
    let binsPerBand = magnitudes.count / bandCount
    for i in 0..<bandCount {
        let start = i * binsPerBand
        let end = min(start + binsPerBand, magnitudes.count)
        bands[i] = magnitudes[start..<end].reduce(0, +) / Float(binsPerBand)
    }

    // Normalize
    let maxVal = bands.max() ?? 1.0
    let normalized = maxVal > 0 ? bands.map { $0 / maxVal } : bands

    // 3 frequency bands for Groove Engine
    let lowBand = normalized[0..<5].reduce(0, +) / 5.0
    let midBand = normalized[5..<15].reduce(0, +) / 10.0
    let highBand = normalized[15..<30].reduce(0, +) / 15.0

    DispatchQueue.main.async { [weak self] in
        self?.amplitudes = normalized
        self?.frequencyBands = FrequencyBands(low: lowBand, mid: midBand, high: highBand)
    }
}
```

**Performance note:** `vDSP_create_fftsetup` is expensive — cache the setup object at AudioService init time, not inside the tap closure.

### FrequencyBands Type (Claude's Discretion — recommend struct)

```swift
// In AudioService.swift (or Models.swift)
struct FrequencyBands {
    let low: Float   // 0–200Hz approx — kick/bass
    let mid: Float   // 200Hz–2kHz approx — snare/vocal
    let high: Float  // 2kHz+ approx — hi-hat/cymbal
    static let zero = FrequencyBands(low: 0, mid: 0, high: 0)
}
```

Struct is preferable to tuple: named fields, can add static `.zero`, equatable for Combine deduplication.

### A-B Loop — AudioService State + Loop Enforcement

Loop state lives on `AudioService` per D-13/D-04. The `AVQueuePlayer` does not natively support loop segments; enforcement requires a periodic time observer check.

```swift
// New published properties on AudioService
@Published var loopStartTime: TimeInterval? = nil
@Published var loopEndTime: TimeInterval? = nil

// Beat-snap helper
func snapToBeat(_ rawTime: TimeInterval, bpm: Float) -> TimeInterval {
    let beatInterval = bpm > 0 ? 60.0 / Double(bpm) : 0.1
    return (rawTime / beatInterval).rounded() * beatInterval
}

// In setupTimeObserver — add loop enforcement:
// Inside the existing periodic observer closure:
if let end = loopEndTime, currentTime >= end,
   let start = loopStartTime {
    seek(to: start)
}
```

The existing `setupTimeObserver` fires at 0.5s intervals — too coarse for accurate loop enforcement. For A-B loop, add a second observer at a finer interval (e.g., 0.05s) that only runs when loop is active:

```swift
private var loopEnforcementObserver: Any?

func activateLoop() {
    guard loopStartTime != nil, loopEndTime != nil else { return }
    let interval = CMTime(seconds: 0.05, preferredTimescale: 600)
    loopEnforcementObserver = player.addPeriodicTimeObserver(
        forInterval: interval, queue: .main
    ) { [weak self] time in
        guard let self, let end = self.loopEndTime, let start = self.loopStartTime else { return }
        if time.seconds >= end {
            self.seek(to: start)
        }
    }
}

func deactivateLoop() {
    if let obs = loopEnforcementObserver {
        player.removeTimeObserver(obs)
        loopEnforcementObserver = nil
    }
    loopStartTime = nil
    loopEndTime = nil
}
```

### CarPlay — `MPPlayableContentManager` vs `CPTemplateApplicationSceneDelegate`

Per D-16, use `MPPlayableContentManager`. This is the legacy audio app CarPlay API available since iOS 7.1. It does NOT require the CarPlay entitlement to build — only to run in an actual CarPlay environment.

`CPTemplateApplicationSceneDelegate` (CarPlay framework, iOS 13+) is the newer alternative, but it requires a separate scene configuration in `Info.plist`, a second target delegate, and explicit scene lifecycle management. For a music streaming app that already uses `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`, `MPPlayableContentManager` is the simpler, cohesive choice.

**`MPPlayableContentManager` pattern:**

```swift
// CarPlayManager.swift (new file)
import MediaPlayer

class CarPlayManager: NSObject, MPPlayableContentDataSource, MPPlayableContentDelegate {
    static let shared = CarPlayManager()

    private weak var audioService: AudioService?

    func configure(audioService: AudioService) {
        self.audioService = audioService
        MPPlayableContentManager.shared().dataSource = self
        MPPlayableContentManager.shared().delegate = self
        MPPlayableContentManager.shared().beginUpdates()
        MPPlayableContentManager.shared().endUpdates()
    }

    // MARK: - MPPlayableContentDataSource
    func numberOfChildItems(at indexPath: IndexPath) -> Int { ... }
    func contentItem(at indexPath: IndexPath) -> MPContentItem? { ... }

    // MARK: - MPPlayableContentDelegate
    func playableContentManager(_ contentManager: MPPlayableContentManager,
                                 initiatePlaybackOfContentItemAt indexPath: IndexPath,
                                 completionHandler: @escaping (Error?) -> Void) { ... }
}
```

**Browse tree (D-15):**
- Root level [0]: "Playlists" container (isContainer: true, isPlayable: false)
- Root level [1]: "All Songs" item (isContainer: false, isPlayable: true — triggers shuffle-all or first-track)
- [0, n]: Individual playlist items

**Entitlement to add to `BombestBeats.entitlements`:**
```xml
<key>com.apple.developer.playable-content</key>
<true/>
```

Note: The entitlement request process (via developer.apple.com) can take 2–7 business days. Development and testing against the CarPlay simulator (Xcode > Window > CarPlay Simulator) can proceed without Apple's approval. The entitlement is required only for App Store distribution.

### Favorites Manager — UserDefaults Pattern

Decision D-18 specifies `UserDefaults` persistence. The Android `FavoritesManager.kt` uses backend sync; the iOS version is local-only. This is simpler.

```swift
// FavoritesManager.swift (new file)
import Foundation
import Combine

class FavoritesManager: ObservableObject {
    static let shared = FavoritesManager()

    private let defaultsKey = "favoriteTrackIds"  // Claude's discretion — key name

    @Published private(set) var favoriteIds: Set<Int>

    private init() {
        let stored = UserDefaults.standard.array(forKey: defaultsKey) as? [Int] ?? []
        favoriteIds = Set(stored)
    }

    func toggle(trackId: Int) {
        if favoriteIds.contains(trackId) {
            favoriteIds.remove(trackId)
        } else {
            favoriteIds.insert(trackId)
        }
        persist()
    }

    func isFavorited(trackId: Int) -> Bool {
        favoriteIds.contains(trackId)
    }

    private func persist() {
        UserDefaults.standard.set(Array(favoriteIds), forKey: defaultsKey)
    }
}
```

`@AppStorage` is NOT appropriate here — it handles single values, not sets. `UserDefaults` with manual encode/decode is correct for `Set<Int>`.

### APIService Failover — 2-URL Retry

The Android `NetworkModule.kt` failover uses an OkHttp interceptor. The Swift equivalent uses `URLSession` with a retry loop. The failover URL confirmed from `NetworkModule.kt` line 25 is `https://beats-aws.bom.best/`.

```swift
// In APIService.swift

private let baseURLs = [
    "https://beats.bom.best",     // Primary (Cloudflare)
    "https://beats-aws.bom.best"  // Failover (EC2 direct)
]
private var currentURLIndex = 0
private var failoverTimestamp: Date? = nil
private let failoverCooldown: TimeInterval = 60.0

private var baseURL: String {
    // Reset after 60s cooldown (matches Android's FAILOVER_COOLDOWN_MS = 60000)
    if let ts = failoverTimestamp, Date().timeIntervalSince(ts) >= failoverCooldown {
        currentURLIndex = 0
        failoverTimestamp = nil
    }
    return baseURLs[currentURLIndex]
}

func request<T: Decodable>(_ endpoint: String, method: String = "GET", body: Data? = nil) async throws -> T {
    var lastError: Error?
    for urlIndex in currentURLIndex..<baseURLs.count {
        do {
            guard let url = URL(string: "\(baseURLs[urlIndex])\(endpoint)") else {
                throw APIError.invalidURL
            }
            var req = URLRequest(url: url, timeoutInterval: 10)
            req.httpMethod = method
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
            req.httpBody = body

            let (data, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse else { throw APIError.serverError("Invalid response") }
            guard (200...299).contains(http.statusCode) else {
                if http.statusCode == 401 { throw APIError.unauthorized }
                // 4xx/5xx are application errors — do NOT failover
                throw APIError.serverError("Status: \(http.statusCode)")
            }
            // Success — reset to primary if we were on failover
            currentURLIndex = 0
            failoverTimestamp = nil
            return try JSONDecoder().decode(T.self, from: data)
        } catch let error as APIError {
            // Auth/application errors — don't failover
            throw error
        } catch {
            // Network error (timeout, connection refused) — try failover
            lastError = error
            if urlIndex < baseURLs.count - 1 {
                currentURLIndex = urlIndex + 1
                failoverTimestamp = Date()
            }
        }
    }
    throw lastError ?? APIError.serverError("All servers failed")
}
```

**Important:** Unlike Android's thread-safe `AtomicInteger`, this Swift version is not thread-safe. Since `APIService` is called from async Tasks that all dispatch results back to `MainActor`, single-threaded access is maintained if `currentURLIndex` and `failoverTimestamp` are accessed only on the main actor. Mark `APIService` as `@MainActor` or add explicit actor isolation.

### LoadState Enum — Error Recovery Pattern

Per D-22/D-23, ViewModels upgrade from `errorMessage: String?` to a proper `LoadState` enum:

```swift
// Defined once, shared across ViewModels
enum LoadState {
    case idle
    case loading
    case loaded
    case failed(String)
    case empty  // successful fetch, zero results
}
```

**LibraryViewModel** — currently has `isLoading: Bool` + `errorMessage: String?`. Replace both with `@Published var loadState: LoadState = .idle`. The view switches on this.

**SearchViewModel** — currently has `isLoading: Bool`, no error state. Add `@Published var loadState: LoadState = .idle`. The error path in `refreshLibrary()` currently just `print()`s — add the state update.

**PlaylistDetailViewModel** — currently has `isLoading: Bool`, no error state. Add `@Published var loadState: LoadState = .idle`.

**View pattern (consistent across all three):**
```swift
switch viewModel.loadState {
case .idle, .loading:
    ProgressView().tint(Color("NeonPurple"))
case .failed(let message):
    VStack(spacing: 12) {
        Image(systemName: "exclamationmark.triangle")
            .font(.largeTitle).foregroundColor(.orange)
        Text(message).foregroundColor(.gray).multilineTextAlignment(.center)
        Button("Retry") { viewModel.retry() }
            .buttonStyle(.borderedProminent).tint(Color("NeonPurple"))
    }.padding()
case .empty:
    ContentUnavailableView("No Content", systemImage: "music.note")
case .loaded:
    // normal content list
}
```

Each ViewModel gets a `func retry()` that re-calls the fetch.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| FFT computation | Custom DFT loop | `vDSP` (Accelerate framework) | Hand-rolled DFT is O(n²); vDSP uses SIMD — 10x faster, Apple-optimized for all ARM chips |
| Haptic pattern definition | UIKit `UIImpactFeedbackGenerator` for groove | `CHHapticEvent` (CoreHaptics) | Already in `HapticsManager.swift`; supports per-event intensity/sharpness required by D-07 |
| A-B loop with `AVPlayer` | Custom frame-step looping | Periodic time observer on `AVQueuePlayer` | The player's native seek is frame-accurate; custom loop is always off by at least one observer interval |
| CarPlay integration | Custom Bluetooth / custom car UI protocol | `MPPlayableContentManager` | It is the only iOS-approved mechanism for audio app CarPlay without full CarPlay framework adoption |
| Favorites persistence | SQLite or custom binary file | `UserDefaults` | D-18 explicitly requires UserDefaults; local-only, no sync needed, no query complexity |

---

## Common Pitfalls

### Pitfall 1: AVAudioEngine tap capturing silence from AVQueuePlayer

**What goes wrong:** Tap on `audioEngine.mainMixerNode` reads all-zero buffers while music plays.
**Why it happens:** `AVQueuePlayer` routes to the hardware output directly via the audio session, not through `AVAudioEngine`'s graph. They share the audio session but not the signal path.
**How to avoid:** Install the tap on `audioEngine.outputNode` (not `mainMixerNode`). The output node on a started engine captures the final mix before it hits hardware, which includes contributions from the system audio session. If still silent, verify `audioEngine.isRunning == true` and that `AVAudioSession` category is `.playback` (already set in `setupAudioSession()`).
**Warning signs:** `buffer.floatChannelData?[0]` is non-nil but all values are 0.0 while audio is audibly playing.

### Pitfall 2: vDSP FFT setup created inside the tap closure

**What goes wrong:** App drops audio frames; noticeable audio glitches or stutter.
**Why it happens:** `vDSP_create_fftsetup()` allocates memory. Calling it 30+ times per second in a real-time audio callback causes heap pressure and priority inversion.
**How to avoid:** Create `vDSP_Length` and `OpaquePointer` (FFT setup) once as stored properties on `AudioService` at `init()` time. The tap closure only calls `vDSP_fft_zrip()` — no setup/teardown.

### Pitfall 3: AVAudioSession category conflict when starting AVAudioEngine

**What goes wrong:** `audioEngine.start()` throws `AVAudioSession.Error.cannotStartPlaying`.
**Why it happens:** Starting `AVAudioEngine` while `AVQueuePlayer` already holds the audio session can cause category negotiation failures, especially on first launch.
**How to avoid:** Call `audioEngine.start()` inside the same block where the audio session is configured (`setupAudioSession()`), before the first `player.play()`. The `.playback` category already set in `AudioService` is correct and compatible.

### Pitfall 4: A-B loop enforcement observer not removed on track change

**What goes wrong:** Loop fires and seeks to old loop start point when a new track is loaded.
**Why it happens:** The fine-interval `loopEnforcementObserver` is still registered when `loadAndPlay()` is called.
**How to avoid:** Call `deactivateLoop()` at the top of `loadAndPlay()`. This removes the observer AND clears `loopStartTime`/`loopEndTime` state.

### Pitfall 5: `MPPlayableContentManager` browse tree not refreshing

**What goes wrong:** CarPlay displays stale or empty content; playlists don't appear.
**Why it happens:** `MPPlayableContentManager.shared().reloadData()` must be called after playlists load. If called before `dataSource` is set, it silently does nothing.
**How to avoid:** In `CarPlayManager.configure()`, set `dataSource` and `delegate` first, then call `reloadData()` after library data is available. Also call `reloadData()` whenever `LibraryViewModel` refreshes.

### Pitfall 6: `FavoritesManager` `@Published` not observed in `TrackRow`

**What goes wrong:** Heart icon doesn't update in real time when toggled from a different screen.
**Why it happens:** `TrackRow` receives `track` as a struct value, not a `@StateObject`. If it reads `FavoritesManager.shared.isFavorited(trackId:)` synchronously without subscribing to `$favoriteIds`, it won't re-render on change.
**How to avoid:** In `TrackRow`, add `@ObservedObject private var favorites = FavoritesManager.shared`. SwiftUI will then re-render `TrackRow` whenever `favoriteIds` changes.

### Pitfall 7: Amplitude decay not dispatched to main thread

**What goes wrong:** SwiftUI layout errors or `@Published` mutation warnings in the console.
**Why it happens:** The AVAudioEngine tap closure runs on an audio thread (not main). Publishing `amplitudes` directly from the tap callback mutates `@Published` off main.
**How to avoid:** Always wrap `self?.amplitudes = ...` in `DispatchQueue.main.async { }` inside the tap closure.

### Pitfall 8: Track model `bpm` field breaks JSON decode for old cached data

**What goes wrong:** App crashes or silently drops tracks when decoding from `library_cache.json` written before `bpm` field was added.
**Why it happens:** `JSONDecoder` requires all non-optional fields to be present. Adding `var bpm: Float` without a default value makes it non-optional in Swift.
**How to avoid:** Add the field as `var bpm: Float = 0` (with default), NOT as `let bpm: Float` (required). The `Codable` synthesis will use the default when the key is absent from JSON. Already specified in CONTEXT.md D-05.

---

## Code Examples

### Existing Pattern: `@EnvironmentObject` AudioService (reference for loop state)
```swift
// Source: AudioService.swift (existing), PlayerView.swift (existing)
// All new published state (loopStartTime, loopEndTime, amplitudes, frequencyBands)
// follows this exact pattern — @Published on AudioService, observed via @EnvironmentObject
@EnvironmentObject var audioService: AudioService
// ...
audioService.loopStartTime  // read
audioService.activateLoop() // mutate
```

### Existing Pattern: `@AppStorage` for user preferences
```swift
// Source: PlayerView.swift line 10 — existing pattern
@AppStorage("isVisualizerEnabled") private var isVisualizerEnabled = true
// Use same pattern for:
@AppStorage("isHapticGrooveEnabled") private var isHapticGrooveEnabled = true
```

### AVAudioEngine tap installation (verified pattern)
```swift
// Source: Apple Developer Documentation — AVAudioMixerNode
audioEngine.mainMixerNode.installTap(
    onBus: 0,
    bufferSize: AVAudioFrameCount(1024),
    format: nil  // nil = use the node's native format
) { [weak self] buffer, _ in
    self?.processAudioBuffer(buffer)
}
```

### CHHapticEvent — playGroove extension (matches D-07 spec)
```swift
// Source: HapticsManager.swift (existing pattern) — extend with:
enum FrequencyBand { case low, mid, high }

func playGroove(band: FrequencyBand, intensity: Float) {
    guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else { return }
    let (hapticIntensity, sharpness): (Float, Float) = switch band {
    case .low:  (1.0, 0.3)   // kick — dull thud
    case .mid:  (0.8, 0.9)   // snare — sharp crack
    case .high: (0.4, 0.95)  // hi-hat — light tick
    }
    let scaledIntensity = hapticIntensity * intensity
    let i = CHHapticEventParameter(parameterID: .hapticIntensity, value: scaledIntensity)
    let s = CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness)
    let event = CHHapticEvent(eventType: .hapticTransient, parameters: [i, s], relativeTime: 0)
    do {
        let pattern = try CHHapticPattern(events: [event], parameters: [])
        try engine?.makePlayer(with: pattern).start(atTime: 0)
    } catch {
        print("Groove haptic error: \(error)")
    }
}
```

### Removing the fake timer (confirmed target)
```swift
// Source: PlayerView.swift lines 14, 178–191 — REMOVE both:
// Line 14:  let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()
// Lines 178–191: .onReceive(timer) { _ in ... }

// REPLACE with:
.onReceive(audioService.$amplitudes) { amps in
    // GraffitiVisualizer(amplitudes: amps) already wired via audioService
    // No local @State amplitudes needed
}
```

---

## Current State Inventory

A complete picture of what exists vs. what needs adding:

| File | Current State | Change Required |
|------|---------------|-----------------|
| `AudioService.swift` | `AVQueuePlayer`, no engine, no `amplitudes` | Add `AVAudioEngine`, FFT tap, `amplitudes`, `frequencyBands`, `loopStartTime`, `loopEndTime`, `activateLoop()`, `deactivateLoop()`, `snapToBeat()` |
| `HapticsManager.swift` | 3 methods: `playTick`, `playImpact`, `playSuccess` | Add `FrequencyBand` enum + `playGroove(band:intensity:)` |
| `PlayerView.swift` | Fake timer, no A-B loop UI | Remove timer, wire `audioService.$amplitudes`, add `LoopControls` row between scrubber and controls |
| `TrackRow.swift` | No favorites | Add `@ObservedObject favorites`, heart button |
| `LibraryView.swift` | Plain `errorMessage` text display | Upgrade to `LoadState` 3-state pattern |
| `SearchView.swift` | No error state in `refreshLibrary` | Add `loadState` to `SearchViewModel`, add failed case to view |
| `PlaylistDetailView.swift` | No error state | Add `loadState` to `PlaylistDetailViewModel`, add failed case to view |
| `APIService.swift` | Single URL, no failover | Add `baseURLs` array + retry loop + cooldown logic |
| `Models.swift` | `Track` missing `bpm` field | Add `var bpm: Float = 0` |
| `LibraryViewModel.swift` | `isLoading: Bool` + `errorMessage: String?` | Replace with `LoadState` enum + `retry()` |
| `SearchViewModel.swift` | `isLoading: Bool`, no error | Add `LoadState` + `retry()` |
| `PlaylistDetailViewModel.swift` | `isLoading: Bool`, no error | Add `LoadState` + `retry()` |
| `BombestBeats.entitlements` | Only `webcredentials` entries | Add `com.apple.developer.playable-content: true` |
| `Project.swift` | No CarPlay scene config | No changes needed for `MPPlayableContentManager` approach |
| **New:** `FavoritesManager.swift` | Does not exist | Create singleton with `UserDefaults` persistence |
| **New:** `CarPlayManager.swift` | Does not exist | Create `MPPlayableContentDataSource/Delegate` implementation |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|---------|
| Xcode | iOS builds | ✓ (assumed, iOS dev machine) | — | — |
| Tuist | Project generation | ✓ (used in CLAUDE.md build commands) | — | — |
| iOS 17.0+ simulator | Build target | ✓ (deploymentTargets = iOS 17.0 in Project.swift) | — | — |
| CarPlay Simulator | CarPlay testing | ✓ (built into Xcode: Window > CarPlay Simulator) | — | — |
| Apple Developer audio entitlement | App Store CarPlay | Requires Apple approval | — | Skip store submission (already deferred) |

**Missing dependencies with no fallback:** None that block development. The CarPlay entitlement blocks App Store distribution only — the feature can be fully built and tested without it.

---

## Open Questions

1. **AVAudioEngine + AVQueuePlayer audio tap — will the tap capture AVQueuePlayer output?**
   - What we know: AVQueuePlayer routes to the system audio session output. AVAudioEngine installed on the same audio session can tap that signal via `outputNode` or `mainMixerNode`.
   - What's unclear: On some iOS versions, the engine's `mainMixerNode` tap captures only audio explicitly routed through the engine graph, not the system mix.
   - Recommendation: Implement with `mainMixerNode` tap first. If silence, try `outputNode` tap. Document in Wave 0 task as "validate tap captures AVQueuePlayer output on device."

2. **`MPPlayableContentManager` deprecation status**
   - What we know: Apple has been gradually pushing developers toward the full CarPlay framework (`CPTemplateApplicationSceneDelegate`). MPPlayableContentManager is still documented and functional as of iOS 17.
   - What's unclear: Whether Apple will formally deprecate it in iOS 18/19.
   - Recommendation: Use it per D-16. The app targets iOS 17+. If deprecated in a future OS, the migration is well-defined (full CarPlay framework adoption). Don't preemptively add the complexity now.

3. **vDSP FFT performance on iPhone SE (low-end device)**
   - What we know: FFT at 1024 samples / 44.1kHz runs at ~23ms per window. At 30fps, budget is 33ms.
   - What's unclear: Whether the tap closure + FFT + Combine publish chain consistently fits within 33ms on iPhone SE.
   - Recommendation: Use 50% overlap (512 hop) and skip FFT frames when the main thread is under load. The amplitude decay on pause (D-12) provides graceful degradation.

---

## Sources

### Primary (HIGH confidence)
- Actual source files in this repo — all findings above derived from reading `AudioService.swift`, `HapticsManager.swift`, `PlayerView.swift`, `Models.swift`, `APIService.swift`, `NetworkModule.kt`, `HapticGrooveEngine.kt`, `HapticPatternLibrary.kt`, `FavoritesManager.kt`, `PlayerScreen.kt`, `LibraryViewModel.swift`, `SearchViewModel.swift`, `PlaylistDetailViewModel.swift`, `TrackRow.swift`, `LibraryView.swift`, `SearchView.swift`, `PlaylistDetailView.swift`, `BombestBeats.entitlements`, `Project.swift`
- Apple Developer Documentation (iOS SDK): `AVAudioEngine`, `AVAudioMixerNode.installTap`, `vDSP_fft_zrip`, `CHHapticEngine`, `MPPlayableContentManager` — all stable iOS 17 APIs

### Secondary (MEDIUM confidence)
- Accelerate framework vDSP API: window size and FFT pattern are standard DSP practice, consistent across multiple Apple sample projects
- `MPPlayableContentManager` pattern: widely documented in developer community for audio streaming apps

### Tertiary (LOW confidence)
- AVAudioEngine + AVQueuePlayer tap behavior: the exact interaction between the two subsystems varies by iOS version. The implementation recommendation is based on the most common pattern seen in open-source iOS audio apps, but **should be validated on a real device in Wave 0**.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all frameworks pre-exist in this project
- Architecture patterns: HIGH (A-B loop, Favorites, Failover, Error states) / MEDIUM (AVAudioEngine tap, CarPlay)
- Pitfalls: HIGH — pitfalls 3–8 are sourced directly from reading the existing code; pitfalls 1–2 are from AVAudioEngine integration patterns

**Research date:** 2026-04-21
**Valid until:** 2026-07-21 (stable iOS SDK — 90 days)
