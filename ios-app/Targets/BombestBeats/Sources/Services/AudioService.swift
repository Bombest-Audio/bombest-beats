import Foundation
import AVFoundation
import MediaPlayer
import Combine
import Accelerate

class AudioService: NSObject, ObservableObject {
    static let shared = AudioService()
    
    enum RepeatMode {
        case off, all, one
    }
    
    // MARK: - Published Properties
    @Published var currentTrack: Track?
    @Published var isPlaying: Bool = false
    @Published var currentTime: TimeInterval = 0
    @Published var duration: TimeInterval = 0
    @Published var queue: [Track] = []
    
    @Published var isShuffleOn: Bool = false
    @Published var repeatMode: RepeatMode = .off

    @Published var amplitudes: [Float] = Array(repeating: 0, count: 30)
    @Published var frequencyBands: FrequencyBands = .zero
    @Published var loopStartTime: TimeInterval? = nil
    @Published var loopEndTime: TimeInterval? = nil

    // MARK: - Private Properties
    private var originalQueue: [Track] = [] // Backs the queue when shuffled

    private let player = AVQueuePlayer()
    private var timeObserver: Any?
    private var cancellables = Set<AnyCancellable>()

    private let audioEngine = AVAudioEngine()
    private var fftSetup: FFTSetup?
    private let fftSize = 1024
    private var loopEnforcementObserver: Any?
    
    // MARK: - API
    private let baseURL = "https://beats.bom.best"
    
    override init() {
        super.init()
        setupAudioSession()
        setupAudioEngine()
        setupRemoteCommands()
        setupTimeObserver()

        // Subscribe frequencyBands to Groove Engine (D-09: same FFT tap feeds both visualizer and haptics)
        $frequencyBands
            .receive(on: RunLoop.main)
            .sink { [weak self] bands in
                guard self?.isPlaying == true else { return }
                if bands.low > 0.5  { HapticsManager.shared.playGroove(band: .low,  intensity: bands.low) }
                if bands.mid > 0.5  { HapticsManager.shared.playGroove(band: .mid,  intensity: bands.mid) }
                if bands.high > 0.6 { HapticsManager.shared.playGroove(band: .high, intensity: bands.high) }
            }
            .store(in: &cancellables)

        // When item finishes, handle auto-advance
        NotificationCenter.default.addObserver(self, selector: #selector(playerDidFinishPlaying), name: .AVPlayerItemDidPlayToEndTime, object: nil)
    }

    deinit {
        if let observer = timeObserver { player.removeTimeObserver(observer) }
        if let loop = loopEnforcementObserver { player.removeTimeObserver(loop) }
        if let setup = fftSetup { vDSP_destroy_fftsetup(setup) }
    }
    
    @objc private func playerDidFinishPlaying(note: NSNotification) {
        if repeatMode == .one {
            // Replay current
            seek(to: 0)
            player.play()
        } else {
            playNext(auto: true)
        }
    }
    
    // MARK: - Playback Control
    
    func play(_ track: Track, queue: [Track] = []) {
        // Update queue
        if queue.isEmpty {
            self.queue = [track]
        } else {
            self.queue = queue
        }
        self.originalQueue = self.queue // Reset original
        
        // If shuffle was already on, we should reshuffle the new queue?
        // For simplicity, let's reset shuffle when starting new context, or apply it if sticky
        if isShuffleOn {
            shuffleQueue(keeping: track)
        }
        
        loadAndPlay(track)
    }
    
    func toggleShuffle() {
        isShuffleOn.toggle()
        if isShuffleOn {
            if let current = currentTrack {
                shuffleQueue(keeping: current)
            }
        } else {
            // Restore original order
            // We need to keep current track playing though.
            // Just restore queue. Current Item is derived from player.
            // But we need to ensure current track index logic works?
            queue = originalQueue
        }
    }
    
    func toggleRepeat() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }
    
    private func shuffleQueue(keeping track: Track) {
        // Create a shuffled version of originalQueue
        var remaining = originalQueue.filter { $0.id != track.id }
        remaining.shuffle()
        self.queue = [track] + remaining
    }
    
    func playNext(auto: Bool = false) {
        guard let current = currentTrack, let index = queue.firstIndex(where: { $0.id == current.id }) else { return }
        
        var nextIndex = index + 1
        
        if nextIndex >= queue.count {
            if repeatMode == .all {
                nextIndex = 0
            } else {
                // End of queue
                if auto { return } // Don't stop explicit user action? or do nothing?
                // If user pressed next at end, maybe cycle to 0 if they want? Standard behavior varies.
                // Let's stop if not repeat all.
                return
            }
        }
        
        loadAndPlay(queue[nextIndex])
    }
    
    func playPrevious() {
        // If > 3 seconds in, restart track
        if currentTime > 3.0 {
            seek(to: 0)
            return
        }
        
        guard let current = currentTrack, let index = queue.firstIndex(where: { $0.id == current.id }) else { return }
        let prevIndex = index - 1
        if prevIndex >= 0 {
            loadAndPlay(queue[prevIndex])
        } else {
            // If at start
            if repeatMode == .all {
                loadAndPlay(queue[queue.count - 1])
            } else {
                 seek(to: 0)
            }
        }
    }
    
    private func loadAndPlay(_ track: Track) {
        deactivateLoop() // prevents stale loop from prior track
        player.removeAllItems()
        
        // 1. Check Cache
        if let localURL = FileCacheService.shared.getLocalFile(for: track.id) {
            print("[Audio] Attempting local file: \(localURL.lastPathComponent)")
            
            // Validate file exists and has content
            if FileManager.default.fileExists(atPath: localURL.path),
               let attrs = try? FileManager.default.attributesOfItem(atPath: localURL.path),
               let size = attrs[.size] as? Int64, size > 1000 { // Minimum 1KB for valid audio
                
                let asset = AVURLAsset(url: localURL)
                
                // Async load to validate
                Task {
                    do {
                        let isPlayable = try await asset.load(.isPlayable)
                        if isPlayable {
                            await MainActor.run {
                                print("[Audio] Playing local file: \(localURL.lastPathComponent)")
                                self.playAsset(asset, for: track)
                            }
                        } else {
                            print("[Audio] Local file not playable, falling back to stream")
                            await self.playFromStream(track: track)
                        }
                    } catch {
                        print("[Audio] Local file error: \(error.localizedDescription), falling back to stream")
                        await self.playFromStream(track: track)
                    }
                }
                return
            } else {
                print("[Audio] Local file invalid or too small, using stream")
            }
        }
        
        // 2. Play Remote
        Task {
            await playFromStream(track: track)
        }
    }
    
    @MainActor
    private func playFromStream(track: Track) async {
        guard let url = URL(string: "\(baseURL)/stream/\(track.id)") else { return }
        print("[Audio] Playing remote: \(track.id)")
        
        let asset: AVURLAsset
        if let token = UserDefaults.standard.string(forKey: "authToken") {
            asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Authorization": "Bearer \(token)"]])
        } else {
            asset = AVURLAsset(url: url)
        }
        
        playAsset(asset, for: track)
        
        // Trigger Cache Download (Background)
        FileCacheService.shared.cacheTrack(trackId: track.id)
    }
    
    private func playAsset(_ asset: AVURLAsset, for track: Track) {
        let item = AVPlayerItem(asset: asset)
        
        // Observe for errors
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { notification in
            if let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error {
                print("[Audio] Playback error: \(error.localizedDescription)")
            }
        }
        
        player.insert(item, after: nil)
        
        self.currentTrack = track
        player.play()
        isPlaying = true
        
        // Reset tracking
        startTrackingMetrics(for: track)
        
        updateNowPlayingInfo()
    }
    
    // MARK: - Metrics Tracking
    private var trackingTimer: Timer?
    
    private func startTrackingMetrics(for track: Track) {
        trackingTimer?.invalidate()
        // Check after 30 seconds
        trackingTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: false) { _ in
            print("[Audio] 30s passed, logging play for \(track.id)")
            MetricsService.shared.logPlay(trackId: track.id)
        }
    }
    
    func pause() {
        player.pause()
        isPlaying = false
        updateNowPlayingInfo()
    }
    
    func resume() {
        player.play()
        isPlaying = true
        updateNowPlayingInfo()
    }
    
    func togglePlayPause() {
        if isPlaying {
            pause()
        } else {
            resume()
        }
    }
    
    func seek(to time: TimeInterval) {
        let cmTime = CMTime(seconds: time, preferredTimescale: 600)
        player.seek(to: cmTime)
    }
    
    // MARK: - Setup

    private func setupAudioEngine() {
        let log2n = vDSP_Length(log2(Float(fftSize)))
        fftSetup = vDSP_create_fftsetup(log2n, FFTRadix(FFT_RADIX2))

        let outputNode = audioEngine.outputNode
        let format = outputNode.inputFormat(forBus: 0)

        audioEngine.outputNode.installTap(
            onBus: 0,
            bufferSize: AVAudioFrameCount(fftSize),
            format: format
        ) { [weak self] buffer, _ in
            self?.processAudioBuffer(buffer)
        }

        do {
            try audioEngine.start()
        } catch {
            print("[Audio] AVAudioEngine start failed: \(error.localizedDescription)")
        }
    }

    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let setup = fftSetup,
              let channelData = buffer.floatChannelData?[0] else { return }
        let frameCount = Int(buffer.frameLength)
        guard frameCount >= fftSize else { return }

        var magnitudes = [Float](repeating: 0, count: fftSize / 2)
        var realParts = [Float](UnsafeBufferPointer(start: channelData, count: fftSize))
        var imagParts = [Float](repeating: 0, count: fftSize)

        // Apply Hann window
        var window = [Float](repeating: 0, count: fftSize)
        vDSP_hann_window(&window, vDSP_Length(fftSize), Int32(vDSP_HANN_NORM))
        vDSP_vmul(&realParts, 1, window, 1, &realParts, 1, vDSP_Length(fftSize))

        realParts.withUnsafeMutableBufferPointer { realBuf in
            imagParts.withUnsafeMutableBufferPointer { imagBuf in
                var splitComplex = DSPSplitComplex(realp: realBuf.baseAddress!, imagp: imagBuf.baseAddress!)
                let log2n = vDSP_Length(log2(Float(fftSize)))
                vDSP_ctoz(
                    UnsafeRawPointer(realBuf.baseAddress!).bindMemory(to: DSPComplex.self, capacity: fftSize / 2),
                    2, &splitComplex, 1, vDSP_Length(fftSize / 2)
                )
                vDSP_fft_zrip(setup, &splitComplex, 1, log2n, FFTDirection(FFT_FORWARD))
                vDSP_zvmags(&splitComplex, 1, &magnitudes, 1, vDSP_Length(fftSize / 2))
            }
        }

        // Map to 30 amplitude bands for visualizer
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
        let normalized = maxVal > 0.001 ? bands.map { $0 / maxVal } : [Float](repeating: 0, count: bandCount)

        // 3 frequency bands for Groove Engine (D-07)
        let lowBand = normalized[0..<5].reduce(0, +) / 5.0
        let midBand = normalized[5..<15].reduce(0, +) / 10.0
        let highBand = normalized[15..<30].reduce(0, +) / 15.0

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.amplitudes = normalized
            self.frequencyBands = FrequencyBands(low: lowBand, mid: midBand, high: highBand)
        }
    }

    // MARK: - A-B Loop

    func snapToBeat(_ rawTime: TimeInterval, bpm: Float) -> TimeInterval {
        let beatInterval = bpm > 0 ? 60.0 / Double(bpm) : 0.1
        return (rawTime / beatInterval).rounded() * beatInterval
    }

    func activateLoop() {
        guard loopStartTime != nil, loopEndTime != nil else { return }
        deactivateLoop() // remove any prior observer
        let interval = CMTime(seconds: 0.05, preferredTimescale: 600)
        loopEnforcementObserver = player.addPeriodicTimeObserver(
            forInterval: interval, queue: .main
        ) { [weak self] time in
            guard let self,
                  let end = self.loopEndTime,
                  let start = self.loopStartTime else { return }
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

    private func setupAudioSession() {
        do {
            // Configure audio session - Category: Playback, Mode: Default
            // Options: AllowBluetooth, AllowAirPlay (default in playback)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Audio Session Error: \(error.localizedDescription)")
        }
    }
    
    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.resume()
            return .success
        }
        
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.playNext()
            return .success
        }
        
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.playPrevious()
            return .success
        }
        
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self = self, let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self.seek(to: event.positionTime)
            return .success
        }
    }
    
    private func setupTimeObserver() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            self?.currentTime = time.seconds
            
            if let duration = self?.player.currentItem?.duration.seconds, !duration.isNaN {
                self?.duration = duration
                self?.updateNowPlayingInfo() // Consider updating less frequently
            }
        }
    }
    
    private func updateNowPlayingInfo() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        
        let info: [String: Any] = [
            MPMediaItemPropertyTitle: track.displayTitle,
            MPMediaItemPropertyArtist: track.displayArtist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player.currentTime().seconds,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        
        // Artwork
        let artURL: URL? = {
            if let albumId = track.album_id {
                return URL(string: "https://beats.bom.best/album/\(albumId)/art")
            } else {
                return URL(string: "https://beats.bom.best/track/\(track.id)/art")
            }
        }()
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        
        if let url = artURL {
            Task {
                if let image = await ImageCacheService.shared.image(for: url) {
                    await MainActor.run {
                        let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                        var currentInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                        currentInfo[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = currentInfo
                    }
                } else {
                    // Fallback to placeholder when artwork fails to load
                    await MainActor.run {
                        if let placeholder = UIImage(named: "DefaultAlbumArt") {
                            let artwork = MPMediaItemArtwork(boundsSize: placeholder.size) { _ in placeholder }
                            var currentInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                            currentInfo[MPMediaItemPropertyArtwork] = artwork
                            MPNowPlayingInfoCenter.default().nowPlayingInfo = currentInfo
                        }
                    }
                }
            }
        } else {
            // No artwork URL - use placeholder
            if let placeholder = UIImage(named: "DefaultAlbumArt") {
                let artwork = MPMediaItemArtwork(boundsSize: placeholder.size) { _ in placeholder }
                var currentInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                currentInfo[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = currentInfo
            }
        }
    }
}
