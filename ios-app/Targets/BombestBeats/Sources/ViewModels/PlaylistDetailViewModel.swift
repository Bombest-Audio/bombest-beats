import Foundation
import Combine

class PlaylistDetailViewModel: ObservableObject {
    let playlistId: Int
    @Published var name: String
    @Published var tracks: [Track] = []
    @Published var loadState: LoadState = .idle

    private let api = APIService.shared

    init(playlist: Playlist) {
        self.playlistId = playlist.id
        self.name = playlist.name
        // Do not auto-fetch, view will trigger it
    }

    func fetchTracks() {
        Task { @MainActor in
            loadState = .loading
            do {
                let response: PlaylistTracksResponse = try await api.request("/playlists/\(playlistId)/tracks")
                self.tracks = response.items
                loadState = tracks.isEmpty ? .empty : .loaded
            } catch {
                print("Playlist Fetch Error: \(error)")
                loadState = .failed(error.localizedDescription)
            }
        }
    }

    func retry() {
        fetchTracks()
    }
}
