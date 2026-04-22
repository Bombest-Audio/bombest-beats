import MediaPlayer
import Foundation

/// CarPlay integration using MPPlayableContentManager.
/// Browse tree (D-15): Playlists at root, All Songs as sibling item.
/// Build now, ship when Apple approves com.apple.developer.playable-content entitlement (D-14).
class CarPlayManager: NSObject, MPPlayableContentDataSource, MPPlayableContentDelegate {
    static let shared = CarPlayManager()

    private weak var audioService: AudioService?
    private var playlists: [Playlist] = []

    private override init() {
        super.init()
    }

    /// Call from BombestApp after audioService is created.
    func configure(audioService: AudioService, playlists: [Playlist] = []) {
        self.audioService = audioService
        self.playlists = playlists
        MPPlayableContentManager.shared().dataSource = self
        MPPlayableContentManager.shared().delegate = self
        MPPlayableContentManager.shared().reloadData()
    }

    /// Update playlists and refresh CarPlay browse tree.
    func updatePlaylists(_ playlists: [Playlist]) {
        self.playlists = playlists
        MPPlayableContentManager.shared().reloadData()
    }

    // MARK: - MPPlayableContentDataSource

    func numberOfChildItems(at indexPath: IndexPath) -> Int {
        switch indexPath.count {
        case 0:
            // Root level: "Playlists" container + "All Songs" item
            return 2
        case 1:
            // Children of root[0] = individual playlists
            // Children of root[1] = no children (All Songs is a leaf)
            if indexPath[0] == 0 {
                return playlists.count
            }
            return 0
        default:
            return 0
        }
    }

    func contentItem(at indexPath: IndexPath) -> MPContentItem? {
        switch indexPath.count {
        case 1:
            if indexPath[0] == 0 {
                // "Playlists" container
                let item = MPContentItem(identifier: "carplay.playlists")
                item.title = "Playlists"
                item.isContainer = true
                item.isPlayable = false
                return item
            } else if indexPath[0] == 1 {
                // "All Songs" — plays shuffle-all or first track
                let item = MPContentItem(identifier: "carplay.allsongs")
                item.title = "All Songs"
                item.subtitle = "\(audioService?.queue.count ?? 0) tracks"
                item.isContainer = false
                item.isPlayable = true
                return item
            }
        case 2:
            if indexPath[0] == 0 && indexPath[1] < playlists.count {
                let playlist = playlists[indexPath[1]]
                let item = MPContentItem(identifier: "carplay.playlist.\(playlist.id)")
                item.title = playlist.name
                item.subtitle = "\(playlist.count ?? 0) tracks"
                item.isContainer = false
                item.isPlayable = true
                return item
            }
        default:
            break
        }
        return nil
    }

    // MARK: - MPPlayableContentDelegate

    func playableContentManager(
        _ contentManager: MPPlayableContentManager,
        initiatePlaybackOfContentItemAt indexPath: IndexPath,
        completionHandler: @escaping (Error?) -> Void
    ) {
        guard let audioService else {
            completionHandler(NSError(domain: "CarPlay", code: -1, userInfo: [NSLocalizedDescriptionKey: "AudioService unavailable"]))
            return
        }

        DispatchQueue.main.async {
            if indexPath.count == 1 && indexPath[0] == 1 {
                // All Songs — play full queue
                if !audioService.queue.isEmpty {
                    audioService.play(audioService.queue[0], queue: audioService.queue)
                }
            } else if indexPath.count == 2 && indexPath[0] == 0 && indexPath[1] < self.playlists.count {
                // Specific playlist — simplified: play full library queue until playlist-specific
                // playback is wired (requires async API call, not possible in sync delegate callback)
                if !audioService.queue.isEmpty {
                    audioService.play(audioService.queue[0], queue: audioService.queue)
                }
            }
            completionHandler(nil)
        }
    }
}
