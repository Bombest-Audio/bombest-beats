import Foundation
import Combine

class SearchViewModel: ObservableObject {
    @Published var searchText = ""
    @Published var results: [Track] = []
    @Published var isLoading = false
    
    private var allTracks: [Track] = []
    private let api = APIService.shared
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        // Setup Search Debounce
        $searchText
            .debounce(for: .milliseconds(300), scheduler: RunLoop.main)
            .removeDuplicates()
            .sink { [weak self] text in
                self?.performSearch(query: text)
            }
            .store(in: &cancellables)
            
        // Initial Fetch
        refreshLibrary()
    }
    
    func refreshLibrary() {
        Task {
            @MainActor in
            isLoading = true
            do {
                let libraryResponse: LibraryResponse = try await api.request("/library")
                self.allTracks = libraryResponse.items
                // Re-run search if text exists
                if !searchText.isEmpty {
                    performSearch(query: searchText)
                }
            } catch {
                print("Search Library Fetch Error: \(error)")
            }
            isLoading = false
        }
    }
    
    private func performSearch(query: String) {
        guard !query.isEmpty else {
            results = []
            return
        }
        
        let lowerQuery = query.lowercased()
        results = allTracks.filter { track in
            (track.title?.lowercased().contains(lowerQuery) ?? false) ||
            (track.artist?.lowercased().contains(lowerQuery) ?? false) ||
            (track.album?.lowercased().contains(lowerQuery) ?? false)
        }
    }
}
