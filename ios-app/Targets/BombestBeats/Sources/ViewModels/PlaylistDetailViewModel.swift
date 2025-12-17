import Foundation
import Combine

class PlaylistDetailViewModel: ObservableObject {
    let playlistId: Int
    @Published var name: String
    @Published var tracks: [Track] = []
    @Published var isLoading = false
    
    private let api = APIService.shared
    
    init(playlist: Playlist) {
        self.playlistId = playlist.id
        self.name = playlist.name
        // Do not auto-fetch, view will trigger it
    }
    
    func fetchTracks() {
        Task {
            @MainActor in
            isLoading = true
            do {
                let response: PlaylistTracksResponse = try await api.request("/playlists/\(playlistId)/tracks")
                self.tracks = response.items
            } catch {
                print("Playlist Fetch Error: \(error)")
            }
            isLoading = false
        }
    }
}
