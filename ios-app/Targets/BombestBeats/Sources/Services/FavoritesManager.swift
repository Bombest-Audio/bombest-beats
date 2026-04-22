import Foundation
import Combine

/// Local-only favorites manager. Persists track IDs in UserDefaults.
/// Mirrors Android's FavoritesManager.kt API (toggle/isFavorited) but without backend sync (D-18).
class FavoritesManager: ObservableObject {
    static let shared = FavoritesManager()

    private let defaultsKey = "favoriteTrackIds"

    @Published private(set) var favoriteIds: Set<Int>

    private init() {
        let stored = UserDefaults.standard.array(forKey: defaultsKey) as? [Int] ?? []
        favoriteIds = Set(stored)
    }

    /// Toggles the favorite status of the given track ID and persists immediately.
    func toggle(trackId: Int) {
        if favoriteIds.contains(trackId) {
            favoriteIds.remove(trackId)
        } else {
            favoriteIds.insert(trackId)
        }
        persist()
    }

    /// Returns true if the track ID is in the favorites set.
    func isFavorited(trackId: Int) -> Bool {
        favoriteIds.contains(trackId)
    }

    private func persist() {
        UserDefaults.standard.set(Array(favoriteIds), forKey: defaultsKey)
    }
}
