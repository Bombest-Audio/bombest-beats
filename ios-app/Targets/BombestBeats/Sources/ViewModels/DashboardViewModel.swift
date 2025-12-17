import Foundation

@MainActor
class DashboardViewModel: ObservableObject {
    @Published var stats: DashboardResponse?
    @Published var isLoading = false
    @Published var error: String?
    
    // User filter (optional)
    @Published var selectedUserId: Int?
    
    private let baseURL = "https://beats.bom.best"
    
    func fetchDashboard() async {
        isLoading = true
        error = nil
        
        defer { isLoading = false }
        
        do {
            var urlString = "\(baseURL)/metrics/dashboard"
            if let userId = selectedUserId {
                urlString += "?user_id=\(userId)"
            }
            
            guard let url = URL(string: urlString) else { return }
            
            var request = URLRequest(url: url)
            if let token = UserDefaults.standard.string(forKey: "authToken") {
                request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
            
            let (data, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
                if httpResponse.statusCode == 530 || httpResponse.statusCode == 503 {
                    self.error = "Server is currently unavailable (Status: \(httpResponse.statusCode))"
                } else if httpResponse.statusCode == 401 {
                    self.error = "Session expired. Please log in again."
                } else {
                    self.error = "Server Error: \(httpResponse.statusCode)"
                }
                return
            }
            
            do {
                let decoded = try JSONDecoder().decode(DashboardResponse.self, from: data)
                self.stats = decoded
            } catch is DecodingError {
                // Determine if it was actually HTML (server error page) or just bad JSON
                if let str = String(data: data, encoding: .utf8), str.trimmingCharacters(in: .whitespaces).starts(with: "<") {
                     self.error = "Server returned HTML instead of data. It might be down or misconfigured."
                } else {
                     self.error = "Received invalid data format from server."
                }
            } catch {
                self.error = "Data Error: \(error.localizedDescription)"
            }

        } catch {
             // Network errors (offline, etc)
            self.error = "Connection Error: \(error.localizedDescription)"
        }
    }
}
