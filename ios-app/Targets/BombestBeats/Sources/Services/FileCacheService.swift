import Foundation

class FileCacheService: ObservableObject {
    static let shared = FileCacheService()
    
    // Config
    private let cacheLimitBytes: Int64 = 1 * 1024 * 1024 * 1024 // 1 GB
    private let fileManager = FileManager.default
    private let baseURL = "https://beats.bom.best"
    
    // Directories
    private var cacheDir: URL? {
        fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("MediaCache")
    }
    
    private var downloadsDir: URL? {
        fileManager.urls(for: .documentDirectory, in: .userDomainMask).first?.appendingPathComponent("Downloads")
    }
    
    init() {
        createDirs()
    }
    
    private func createDirs() {
        guard let cache = cacheDir, let down = downloadsDir else { return }
        try? fileManager.createDirectory(at: cache, withIntermediateDirectories: true)
        try? fileManager.createDirectory(at: down, withIntermediateDirectories: true)
    }
    
    // MARK: - API
    
    func getLocalFile(for trackId: Int) -> URL? {
        // 1. Check Downloads (Pinned)
        if let down = downloadsDir?.appendingPathComponent("\(trackId).mp3"),
           fileManager.fileExists(atPath: down.path) {
            // Touch not needed for pinned files
            return down
        }
        
        // 2. Check Cache
        if let cache = cacheDir?.appendingPathComponent("\(trackId).mp3"),
           fileManager.fileExists(atPath: cache.path) {
            // Touch file to update modification date for LRU
            touchFile(at: cache)
            return cache
        }
        
        return nil
    }
    
    func cacheTrack(trackId: Int) {
        // Run in background
        Task {
            // Check if exists
            if getLocalFile(for: trackId) != nil { return }
            
            // Download
            guard let url = URL(string: "\(baseURL)/stream/\(trackId)") else { return }
            var request = URLRequest(url: url)
            if let token = UserDefaults.standard.string(forKey: "authToken") {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
            
            do {
                let (tempURL, _) = try await URLSession.shared.download(for: request)
                if let data = try? Data(contentsOf: tempURL) {
                    guard let dest = cacheDir?.appendingPathComponent("\(trackId).mp3") else { return }
                    try data.write(to: dest)
                    print("[Cache] Cached track \(trackId)")
                    cleanCache()
                }
            } catch {
                print("[Cache] Download failed: \(error)")
            }
        }
    }
    
    func pinTrack(trackId: Int) {
        // Move from cache if exists, or download
        Task {
             if let down = downloadsDir?.appendingPathComponent("\(trackId).mp3") {
                 if fileManager.fileExists(atPath: down.path) { return } // Already pinned
                 
                 // Check cache
                 if let cache = cacheDir?.appendingPathComponent("\(trackId).mp3"),
                    fileManager.fileExists(atPath: cache.path) {
                     try? fileManager.moveItem(at: cache, to: down)
                     print("[Cache] Pinned track \(trackId) (moved from cache)")
                     return
                 }
                 
                 // Download fresh
                  guard let url = URL(string: "\(baseURL)/stream/\(trackId)") else { return }
                  var request = URLRequest(url: url)
                  if let token = UserDefaults.standard.string(forKey: "authToken") {
                      request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                  }
                  
                  do {
                      let (tempURL, _) = try await URLSession.shared.download(for: request)
                      let data = try Data(contentsOf: tempURL)
                      try data.write(to: down)
                      print("[Cache] Pinned track \(trackId) (downloaded)")
                  } catch {
                      print("[Cache] Pin failed: \(error)")
                  }
             }
        }
    }
    
    // MARK: - Maintenance
    
    private func touchFile(at url: URL) {
        // Update access/modification time
        let now = Date()
        try? fileManager.setAttributes([.modificationDate: now], ofItemAtPath: url.path)
    }
    
    private func cleanCache() {
        guard let cache = cacheDir else { return }
        
        do {
            let props: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
            let files = try fileManager.contentsOfDirectory(at: cache, includingPropertiesForKeys: props, options: [])
            
            var totalSize: Int64 = 0
            var fileInfos: [(url: URL, size: Int64, date: Date)] = []
            
            for file in files {
                let res = try file.resourceValues(forKeys: Set(props))
                let size = Int64(res.fileSize ?? 0)
                let date = res.contentModificationDate ?? Date.distantPast
                totalSize += size
                fileInfos.append((file, size, date))
            }
            
            if totalSize > cacheLimitBytes {
                // Sort by date ascending (oldest first)
                fileInfos.sort { $0.date < $1.date }
                
                var deletedSize: Int64 = 0
                let targetSize = cacheLimitBytes / 2 // Cut to half when full
                
                for info in fileInfos {
                    try? fileManager.removeItem(at: info.url)
                    deletedSize += info.size
                    totalSize -= info.size
                    print("[Cache] Evicted \(info.url.lastPathComponent)")
                    
                    if totalSize < targetSize {
                        break
                    }
                }
            }
            
        } catch {
            print("[Cache] Clean error: \(error)")
        }
    }
    
    // MARK: - Download All Mode
    
    /// Download all tracks to local storage for offline playback
    func downloadAllTracks(_ tracks: [Track]) {
        Task {
            print("[Download] Starting download of \(tracks.count) tracks")
            var downloaded = 0
            var failed = 0
            
            for track in tracks {
                // Skip if already downloaded
                if getLocalFile(for: track.id) != nil {
                    downloaded += 1
                    continue
                }
                
                // Download to downloads folder (permanent)
                guard let destURL = downloadsDir?.appendingPathComponent("\(track.id).mp3"),
                      let streamURL = URL(string: "\(baseURL)/stream/\(track.id)") else {
                    failed += 1
                    continue
                }
                
                var request = URLRequest(url: streamURL)
                if let token = UserDefaults.standard.string(forKey: "authToken") {
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                }
                
                do {
                    let (tempURL, _) = try await URLSession.shared.download(for: request)
                    let data = try Data(contentsOf: tempURL)
                    try data.write(to: destURL)
                    downloaded += 1
                    print("[Download] \(downloaded)/\(tracks.count): \(track.displayTitle)")
                } catch {
                    failed += 1
                    print("[Download] Failed \(track.displayTitle): \(error.localizedDescription)")
                }
            }
            
            await MainActor.run {
                print("[Download] Complete: \(downloaded) downloaded, \(failed) failed")
            }
        }
    }
    
    /// Get count of downloaded tracks
    func getDownloadedCount() -> Int {
        guard let dir = downloadsDir else { return 0 }
        let files = (try? fileManager.contentsOfDirectory(atPath: dir.path)) ?? []
        return files.filter { $0.hasSuffix(".mp3") }.count
    }
    
    /// Get total storage used by downloads (in bytes)
    func getDownloadsSizeBytes() -> Int64 {
        guard let dir = downloadsDir else { return 0 }
        var total: Int64 = 0
        
        if let files = try? fileManager.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]) {
            for file in files {
                if let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    total += Int64(size)
                }
            }
        }
        return total
    }
    
    /// Format bytes as human-readable string
    func formattedStorageSize() -> String {
        let bytes = getDownloadsSizeBytes()
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
    
    /// Delete all downloads
    func clearAllDownloads() {
        guard let dir = downloadsDir else { return }
        try? fileManager.removeItem(at: dir)
        createDirs()
        print("[Download] Cleared all downloads")
    }
}
