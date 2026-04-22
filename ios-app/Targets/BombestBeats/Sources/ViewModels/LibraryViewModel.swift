import Foundation
import Combine

class LibraryViewModel: ObservableObject {
    @Published var songs: [Track] = []
    @Published var playlists: [Playlist] = []
    @Published var loadState: LoadState = .idle

    private let api = APIService.shared

    func refreshData() {
        Task { @MainActor in
            loadState = .loading

            do {
                let libraryResponse: LibraryResponse = try await api.request("/library")
                self.songs = libraryResponse.items

                let playlistsResponse: PlaylistsResponse = try await api.request("/playlists")
                self.playlists = playlistsResponse.playlists

                // Save to cache on success
                if let cacheURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
                    .first?.appendingPathComponent("library_cache.json"),
                   let data = try? JSONEncoder().encode(self.songs) {
                    try? data.write(to: cacheURL)
                }

                loadState = songs.isEmpty ? .empty : .loaded
            } catch {
                print("Library Fetch Error: \(error)")
                // Load from cache as fallback
                if let cacheURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
                    .first?.appendingPathComponent("library_cache.json"),
                   let data = try? Data(contentsOf: cacheURL),
                   let cachedSongs = try? JSONDecoder().decode([Track].self, from: data),
                   !cachedSongs.isEmpty {
                    self.songs = cachedSongs
                    loadState = .loaded  // cached data is "loaded" state
                } else {
                    loadState = .failed(error.localizedDescription)
                }
            }
        }
    }

    func retry() {
        refreshData()
    }
}
