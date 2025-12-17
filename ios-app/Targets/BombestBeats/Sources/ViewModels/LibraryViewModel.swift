import Foundation
import Combine

class LibraryViewModel: ObservableObject {
    @Published var songs: [Track] = []
    @Published var playlists: [Playlist] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    
    private let api = APIService.shared
    
    func refreshData() {
        Task {
            @MainActor in
            isLoading = true
            
            do {
                // Fetch Library
                let libraryResponse: LibraryResponse = try await api.request("/library")
                self.songs = libraryResponse.items
                
                // Fetch Playlists
                let playlistsResponse: PlaylistsResponse = try await api.request("/playlists")
                self.playlists = playlistsResponse.playlists
            } catch {
                print("Library Fetch Error: \(error)")
                self.errorMessage = error.localizedDescription
                // Load from Cache
                if let cachedUrl = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.appendingPathComponent("library_cache.json"),
                   let data = try? Data(contentsOf: cachedUrl),
                   let cachedSongs = try? JSONDecoder().decode([Track].self, from: data) {
                    self.songs = cachedSongs
                }
            }
            
            // Save to Cache on success
            if !self.songs.isEmpty {
                 if let cachedUrl = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.appendingPathComponent("library_cache.json"),
                    let data = try? JSONEncoder().encode(self.songs) {
                     try? data.write(to: cachedUrl)
                 }
            }
            
            isLoading = false
        }
    }
}

// Temporary Playlist model until API confirmed
// Playlist struct moved to Models.swift
