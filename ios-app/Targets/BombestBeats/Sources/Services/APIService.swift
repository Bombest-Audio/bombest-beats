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

    private let baseURLs = [
        "https://beats.bom.best",       // Primary (Cloudflare)
        "https://beats-aws.bom.best"    // Failover (EC2 direct) — per D-20/D-21
    ]
    private var currentURLIndex = 0
    private var failoverTimestamp: Date? = nil
    private let failoverCooldown: TimeInterval = 60.0  // matches Android FAILOVER_COOLDOWN_MS = 60000

    // Store token securely (Keychain in production, UserDefaults for prototype)
    private var token: String? {
        get { UserDefaults.standard.string(forKey: "authToken") }
        set { UserDefaults.standard.set(newValue, forKey: "authToken") }
    }

    private var baseURL: String {
        // After 60s cooldown, reset to primary
        if let ts = failoverTimestamp, Date().timeIntervalSince(ts) >= failoverCooldown {
            currentURLIndex = 0
            failoverTimestamp = nil
        }
        return baseURLs[currentURLIndex]
    }

    func request<T: Decodable>(_ endpoint: String, method: String = "GET", body: Data? = nil) async throws -> T {
        var lastError: Error?

        // Calling baseURL here runs the cooldown-reset check before each attempt
        for _ in 0..<baseURLs.count {
            let currentBase = baseURL   // may reset currentURLIndex to 0 via cooldown
            let urlIndex = currentURLIndex
            do {
                guard let url = URL(string: "\(currentBase)\(endpoint)") else {
                    throw APIError.invalidURL
                }
                var req = URLRequest(url: url, timeoutInterval: 10)
                req.httpMethod = method
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                if let t = token {
                    req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
                }
                req.httpBody = body

                let (data, response) = try await URLSession.shared.data(for: req)

                guard let http = response as? HTTPURLResponse else {
                    throw APIError.serverError("Invalid response")
                }
                guard (200...299).contains(http.statusCode) else {
                    if http.statusCode == 401 { throw APIError.unauthorized }
                    // 4xx/5xx are application errors — do NOT failover to EC2
                    throw APIError.serverError("Status code: \(http.statusCode)")
                }

                // Success: reset to primary
                currentURLIndex = 0
                failoverTimestamp = nil
                return try JSONDecoder().decode(T.self, from: data)

            } catch let error as APIError {
                throw error  // Auth/app errors — do not failover
            } catch {
                // Network error (timeout, connection refused) — try failover
                lastError = error
                if urlIndex < baseURLs.count - 1 {
                    currentURLIndex = urlIndex + 1
                    failoverTimestamp = Date()
                    print("[API] Primary failed, failing over to \(baseURLs[urlIndex + 1]): \(error.localizedDescription)")
                }
            }
        }

        throw lastError ?? APIError.serverError("All servers unreachable")
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
