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
    let length: Double?
    let path: String?
    let album_id: Int?
    var bpm: Float

    var displayTitle: String { title ?? path?.components(separatedBy: "/").last ?? "Unknown Track" }
    var displayArtist: String { artist ?? "Unknown Artist" }

    // Custom decoder: bpm absent from old library_cache.json and some backend responses
    enum CodingKeys: String, CodingKey {
        case id, title, artist, album, length, path, album_id, bpm
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id       = try c.decode(Int.self,    forKey: .id)
        title    = try c.decodeIfPresent(String.self, forKey: .title)
        artist   = try c.decodeIfPresent(String.self, forKey: .artist)
        album    = try c.decodeIfPresent(String.self, forKey: .album)
        length   = try c.decodeIfPresent(Double.self, forKey: .length)
        path     = try c.decodeIfPresent(String.self, forKey: .path)
        album_id = try c.decodeIfPresent(Int.self,    forKey: .album_id)
        bpm      = try c.decodeIfPresent(Float.self,  forKey: .bpm) ?? 0
    }

    // Memberwise init used by PlaylistTrack.asTrack and tests
    init(id: Int, title: String?, artist: String?, album: String?,
         length: Double?, path: String?, album_id: Int?, bpm: Float = 0) {
        self.id = id; self.title = title; self.artist = artist; self.album = album
        self.length = length; self.path = path; self.album_id = album_id; self.bpm = bpm
    }
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
    let tracks: [PlaylistTrack]
}

struct PlaylistTrack: Codable, Identifiable {
    let id: Int
    let title: String?
    let artist: String?
    let album: String?
    let duration: Double?
    let path: String?

    var asTrack: Track {
        Track(id: id, title: title, artist: artist, album: album, length: duration, path: path, album_id: nil)
    }
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
