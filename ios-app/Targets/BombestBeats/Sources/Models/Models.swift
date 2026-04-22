import Foundation

// MARK: - Core User
struct User: Codable, Identifiable {
    let id: Int
    let username: String
    let role: String
}

struct AuthResponse: Codable {
    let access_token: String
    let user: User
}

struct GenericResponse: Codable {
    let success: Bool
    let message: String?
    let error: String?
}

// MARK: - Music Library
struct Track: Codable, Identifiable {
    let id: Int
    let title: String?
    let artist: String?
    let album: String?
    let length: Double
    let path: String?
    let album_id: Int? // JSON snake_case
    var bpm: Float = 0 // defaults to 0 when absent from JSON — prevents crashes on old library_cache.json

    var displayTitle: String { title ?? path?.components(separatedBy: "/").last ?? "Unknown Track" }
    var displayArtist: String { artist ?? "Unknown Artist" }
}

struct LibraryResponse: Codable {
    let items: [Track]
}

struct FrequencyBands {
    let low: Float   // 0–200Hz approx — kick/bass
    let mid: Float   // 200Hz–2kHz approx — snare/vocal
    let high: Float  // 2kHz+ approx — hi-hat/cymbal
    static let zero = FrequencyBands(low: 0, mid: 0, high: 0)
}


// MARK: - Playlists
struct Playlist: Codable, Identifiable, Hashable {
    let id: Int
    let name: String
    let created_at: String? // SQLite timestamp string
    let count: Int?
}

struct PlaylistsResponse: Codable {
    let playlists: [Playlist]
}

struct PlaylistTracksResponse: Codable {
    let items: [Track]
}
struct PasskeyLoginOptions: Codable {
    let challenge: String
    let rpId: String
    let timeout: Int64
    let userVerification: String
    let allowCredentials: [CredentialDescriptor]?
    let loginId: String
}

struct CredentialDescriptor: Codable {
    let id: String
    let type: String
    let transports: [String]?
}

struct PasskeyVerifyRequest: Codable {
    let loginId: String
    let id: String
    let rawId: String
    let type: String
    let response: PasskeyAssertionResponse
}

struct PasskeyAssertionResponse: Codable {
    let authenticatorData: String
    let clientDataJSON: String
    let signature: String
    let userHandle: String?
}

// MARK: - Passkey Register Models
struct PasskeyRegisterOptions: Codable {
    let challenge: String
    let rp: RelyingParty
    let user: PasskeyUser
    let pubKeyCredParams: [PubKeyCredParam]
    let timeout: Int64
    let authenticatorSelection: AuthenticatorSelection?
    let attestation: String?
}

struct RelyingParty: Codable {
    let id: String
    let name: String
}

struct PasskeyUser: Codable {
    let id: String
    let name: String
    let displayName: String
}

struct PubKeyCredParam: Codable {
    let type: String
    let alg: Int
}

struct AuthenticatorSelection: Codable {
    let residentKey: String?
    let userVerification: String?
}

struct PasskeyCredentialRequest: Codable {
    let id: String
    let rawId: String
    let type: String
    let response: PasskeyAttestationResponse
}

struct PasskeyAttestationResponse: Codable {
    let attestationObject: String
    let clientDataJSON: String
}

struct PasskeyRegisterResponse: Codable {
    let success: Bool
    let message: String?
}

// MARK: - View State
enum LoadState {
    case idle
    case loading
    case loaded
    case failed(String)
    case empty  // successful fetch, zero results
}

// MARK: - Dashboard
struct DashboardResponse: Codable {
    let total_plays: Int
    let top_tracks: [TrackStats]
    let daily_plays: [DailyPlay]
    let users: [UserStats]?
}

struct TrackStats: Codable, Identifiable {
    let id: Int
    let title: String?
    let artist: String?
    let plays: Int
}

struct DailyPlay: Codable {
    let date: String
    let count: Int
}

struct UserStats: Codable, Identifiable {
    let id: Int
    let username: String
}
