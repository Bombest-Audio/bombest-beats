import Foundation

class MusicService: ObservableObject {
    @Published var tracks: [Track] = []
    
    private let baseUrl = "http://100.69.137.108:8338" // Tailscale IP
    
    func fetchLibrary() {
        guard let url = URL(string: "\(baseUrl)/library") else { return }
        
        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            if let error = error {
                print("Error fetching library: \(error)")
                return
            }
            
            guard let data = data else { return }
            
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let decoder = JSONDecoder()
                    let response = try decoder.decode(LibraryResponse.self, from: data)
                    
                    DispatchQueue.main.async {
                        self?.tracks = response.items
                        print("Fetched \(response.items.count) tracks")
                    }
                } catch {
                    print("Error decoding library: \(error)")
                }
            }
        }.resume()
    }
}
