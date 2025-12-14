import Foundation

struct Track: Identifiable, Codable {
    let id: Int
    let title: String?
    let artist: String?
    let album: String?
    let length: Double
    let path: String?
    let albumId: Int?
    
    enum CodingKeys: String, CodingKey {
        case id
        case title
        case artist
        case album
        case length
        case path
        case albumId = "album_id"
    }
    
    var displayTitle: String {
        title ?? (path as NSString?)?.lastPathComponent ?? "Unknown Track"
    }
    
    var displayArtist: String {
        artist ?? "Unknown Artist"
    }
    
    func getStreamUrl(baseUrl: String) -> URL? {
        URL(string: "\(baseUrl)/stream/\(id)")
    }
    
    func getArtUrl(baseUrl: String) -> URL? {
        guard let albumId = albumId else { return nil }
        return URL(string: "\(baseUrl)/album/\(albumId)/art")
    }
}

struct LibraryResponse: Codable {
    let items: [Track]
}
