import Foundation
import AVFoundation
import MediaPlayer
import Combine

class AudioController: ObservableObject {
    static let shared = AudioController()
    
    private var player: AVQueuePlayer?

    
    @Published var isPlaying = false
    @Published var currentTrack: Track?
    @Published var duration: Double = 0.0
    @Published var currentTime: Double = 0.0
    
    private var timeObserver: Any?
    
    // Playback State
    @Published var isShuffleEnabled = false
    @Published var repeatMode: RepeatMode = .off
    @Published var currentTrackIndex: Int?

    // Private Queue Management
    // Public Queue for UI (List)
    @Published var originalTracks: [Track] = []
    private var shuffledIndices: [Int] = []
    private var cancellables = Set<AnyCancellable>()
    
    // ...
    
    func reorder(from source: IndexSet, to destination: Int) {
        // Only allow reordering if shuffle is OFF for simplicity
        guard !isShuffleEnabled else { return }
        
        originalTracks.move(fromOffsets: source, toOffset: destination)
        
        // If current track moved, update index
        if let current = currentTrack, let newIndex = originalTracks.firstIndex(where: { $0.id == current.id }) {
            currentTrackIndex = newIndex
        }
    }
    
    enum RepeatMode {
        case off, all, one
    }
    
    init() {
        setupAudioSession()
        setupRemoteCommandCenter()
    }
    
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Failed to set audio session category: \(error)")
        }
    }
    
    func play(tracks: [Track], startingAt index: Int = 0) {
        self.originalTracks = tracks
        self.currentTrackIndex = index
        self.shuffledIndices = Array(0..<tracks.count) // Reset shuffle
        
        if isShuffleEnabled {
            shuffledIndices.shuffle()
            // Ensure started track is first if we just started playing
            if let newIndex = shuffledIndices.firstIndex(of: index) {
                shuffledIndices.swapAt(0, newIndex)
            }
        }
        
        loadTrack(at: index)
        isPlaying = true
    }
    
    private func loadTrack(at index: Int) {
        guard let track = originalTracks[safe: index] else { return }
        currentTrack = track
        currentTrackIndex = index
        
        // Fix URL Construction for Tailscale
        guard let url = track.getStreamUrl(baseUrl: "http://100.69.137.108:8338") else { return }
        
        let item = AVPlayerItem(url: url)
        
        // Remove old observer
        cancellables.removeAll()
        
        // Create new player if needed or replace item
        if player == nil {
            player = AVQueuePlayer(playerItem: item)
        } else {
            player?.replaceCurrentItem(with: item)
        }
        
        setupTimeObserver()
        setupItemObservers(item: item)
        
        player?.play()
    }
    
    private func setupItemObservers(item: AVPlayerItem) {
        // Duration
        item.publisher(for: \.status)
            .filter { $0 == .readyToPlay }
            .sink { [weak self] _ in
                self?.duration = item.duration.seconds
            }
            .store(in: &cancellables)
            
        // Did finish playing
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item)
            .sink { [weak self] _ in
                self?.onTrackFinished()
            }
            .store(in: &cancellables)
    }
    
    private func onTrackFinished() {
        if repeatMode == .one {
            player?.seek(to: .zero)
            player?.play()
        } else {
            next()
        }
    }
    
    func next() {
        guard let currentIndex = currentTrackIndex else { return }
        
        var nextIndex = -1
        
        if isShuffleEnabled {
            // Find current in shuffled list
            if let shufflePos = shuffledIndices.firstIndex(of: currentIndex) {
                let nextShufflePos = shufflePos + 1
                if nextShufflePos < shuffledIndices.count {
                    nextIndex = shuffledIndices[nextShufflePos]
                } else if repeatMode == .all {
                    nextIndex = shuffledIndices[0]
                }
            }
        } else {
            // Normal order
            if currentIndex + 1 < originalTracks.count {
                nextIndex = currentIndex + 1
            } else if repeatMode == .all {
                nextIndex = 0
            }
        }
        
        if nextIndex != -1 {
            loadTrack(at: nextIndex)
        } else {
            isPlaying = false // End of playlist
        }
    }
    
    func previous() {
        // If > 3 seconds, replay current
        if currentTime > 3 {
             seek(to: 0)
             return
        }
        
        guard let currentIndex = currentTrackIndex else { return }
         var prevIndex = -1
        
        if isShuffleEnabled {
            if let shufflePos = shuffledIndices.firstIndex(of: currentIndex) {
                let prevShufflePos = shufflePos - 1
                if prevShufflePos >= 0 {
                    prevIndex = shuffledIndices[prevShufflePos]
                } else if repeatMode == .all {
                     prevIndex = shuffledIndices.last ?? 0
                }
            }
        } else {
            if currentIndex - 1 >= 0 {
                prevIndex = currentIndex - 1
            } else if repeatMode == .all {
                prevIndex = originalTracks.count - 1
            }
        }
        
        if prevIndex != -1 {
            loadTrack(at: prevIndex)
        }
    }
    
    func toggleShuffle() {
        isShuffleEnabled.toggle()
        if isShuffleEnabled {
            shuffledIndices = Array(0..<originalTracks.count).shuffled()
            // Keep current playing
            if let current = currentTrackIndex, let newIndex = shuffledIndices.firstIndex(of: current) {
                shuffledIndices.swapAt(0, newIndex)
            }
        }
    }
    
    func toggleRepeat() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    func seek(to time: Double) {
        player?.seek(to: CMTime(seconds: time, preferredTimescale: 1000))
    }

    func togglePlayPause() {
        if isPlaying {
            player?.pause()
        } else {
            player?.play()
        }
        isPlaying.toggle()
    }
    
    private func setupTimeObserver() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            self?.currentTime = time.seconds
        }
    }
    
    private func setupRemoteCommandCenter() {
        // Todo: Implement MPRemoteCommandCenter
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        return indices.contains(index) ? self[index] : nil
    }
}
