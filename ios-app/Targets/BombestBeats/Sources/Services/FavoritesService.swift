import Foundation
import Combine

class FavoritesService: ObservableObject {
    static let shared = FavoritesService()
    
    @Published var favoriteTrackIds: Set<String> = []
    
    private let key = "favorite_track_ids"
    
    private init() {
        if let saved = UserDefaults.standard.array(forKey: key) as? [String] {
            favoriteTrackIds = Set(saved)
        }
    }
    
    func isFavorite(_ trackId: String) -> Bool {
        return favoriteTrackIds.contains(trackId)
    }
    
    func toggleFavorite(_ trackId: String) {
        if favoriteTrackIds.contains(trackId) {
            favoriteTrackIds.remove(trackId)
        } else {
            favoriteTrackIds.insert(trackId)
        }
        save()
    }
    
    private func save() {
        UserDefaults.standard.set(Array(favoriteTrackIds), forKey: key)
    }
}
