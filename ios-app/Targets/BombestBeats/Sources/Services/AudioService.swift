import Foundation
import AVFoundation
import MediaPlayer
import Combine

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
    
    // MARK: - Private Properties
    private var originalQueue: [Track] = [] // Backs the queue when shuffled
    
    private let player = AVQueuePlayer()
    private var timeObserver: Any?
    private var cancellables = Set<AnyCancellable>()
    
    // MARK: - API
    private let baseURL = "https://beats.bom.best"
    
    override init() {
        super.init()
        setupAudioSession()
        setupRemoteCommands()
        setupTimeObserver()
        
        // When item finishes, handle auto-advance
        NotificationCenter.default.addObserver(self, selector: #selector(playerDidFinishPlaying), name: .AVPlayerItemDidPlayToEndTime, object: nil)
    }
    
    deinit {
        if let observer = timeObserver {
            player.removeTimeObserver(observer)
        }
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
        player.removeAllItems()
        
        // 1. Check Cache
        let asset: AVURLAsset
        if let localURL = FileCacheService.shared.getLocalFile(for: track.id) {
            print("[Audio] Playing local file: \(localURL.lastPathComponent)")
            asset = AVURLAsset(url: localURL)
        } else {
            // 2. Play Remote
            guard let url = URL(string: "\(baseURL)/stream/\(track.id)") else { return }
            print("[Audio] Playing remote: \(track.id)")
            
             if let token = UserDefaults.standard.string(forKey: "authToken") {
                  asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Authorization": "Bearer \(token)"]])
             } else {
                  asset = AVURLAsset(url: url)
             }
            
            // 3. Trigger Cache Download (Background)
            FileCacheService.shared.cacheTrack(trackId: track.id)
        }
        
        let item = AVPlayerItem(asset: asset)
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
