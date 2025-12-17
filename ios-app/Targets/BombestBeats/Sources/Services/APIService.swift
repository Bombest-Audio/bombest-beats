import Foundation

enum APIError: Error {
    case invalidURL
    case networkError(Error)
    case serverError(String)
    case decodingError(Error)
    case unauthorized
}

class APIService {
    static let shared = APIService()
    private let baseURL = "https://beats.bom.best"
    
    // Store token securely (Keychain in production, UserDefaults for prototype)
    private var token: String? {
        get { UserDefaults.standard.string(forKey: "authToken") }
        set { UserDefaults.standard.set(newValue, forKey: "authToken") }
    }
    
    func request<T: Decodable>(_ endpoint: String, method: String = "GET", body: Data? = nil) async throws -> T {
        // Ensure valid URL creation
        guard let url = URL(string: "\(baseURL)\(endpoint)") else {
            throw APIError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        if let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        request.httpBody = body
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            
            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.serverError("Invalid response")
            }
            
            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw APIError.unauthorized
                }
                throw APIError.serverError("Status code: \(httpResponse.statusCode)")
            }
            
            let decoded = try JSONDecoder().decode(T.self, from: data)
            return decoded
        } catch let error as APIError {
            print("API Error: \(error)")
            throw error
        } catch {
            print("Network/Unknown Error: \(error.localizedDescription)")
            throw APIError.networkError(error)
        }
    }
    
    // Auth specific
    func setToken(_ token: String) {
        self.token = token
    }
    
    func clearToken() {
        self.token = nil
    }

    // Passkey Management
    func fetchPasskeys() async throws -> [PasskeyItem] {
        return try await request("/auth/passkeys")
    }

    func deletePasskey(id: Int) async throws {
        let _: [String: Bool] = try await request("/auth/passkey/delete/\(id)", method: "DELETE")
    }

    func getPasskeyRegisterOptions() async throws -> PasskeyRegisterOptions {
         let emptyBody = "{}".data(using: .utf8)
         return try await request("/auth/passkey/register/options", method: "POST", body: emptyBody)
    }

    func verifyPasskeyRegistration(_ credentialRequest: PasskeyCredentialRequest) async throws -> Bool {
         let body = try JSONEncoder().encode(credentialRequest)
         let response: [String: Bool] = try await request("/auth/passkey/register/verify", method: "POST", body: body)
         return response["success"] ?? false
    }
    
    // Passkey Item for UI
    struct PasskeyItem: Codable, Identifiable {
        let id: Int
        let credential_id: String
        let created_at: String
    }
}
