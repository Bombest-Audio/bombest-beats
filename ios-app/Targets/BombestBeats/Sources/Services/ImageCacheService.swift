import SwiftUI
import Combine

class ImageCacheService {
    static let shared = ImageCacheService()
    
    // 1. Memory Cache
    private let memoryCache = NSCache<NSString, UIImage>()
    
    // 2. Disk Cache
    private let fileManager = FileManager.default
    private let cacheDirectory: URL
    
    private init() {
        // Setup cache directory
        let paths = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)
        cacheDirectory = paths[0].appendingPathComponent("ImageCache")
        
        try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }
    
    func image(for url: URL) async -> UIImage? {
        let key = NSString(string: url.absoluteString)
        let filename = key.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        let fileURL = cacheDirectory.appendingPathComponent(filename)
        
        // Check Memory
        if let cachedImage = memoryCache.object(forKey: key) {
            return cachedImage
        }
        
        // Check Disk
        if let data = try? Data(contentsOf: fileURL), let image = UIImage(data: data) {
            memoryCache.setObject(image, forKey: key)
            return image
        }
        
        // Download
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            if let image = UIImage(data: data) {
                // Save to caches
                memoryCache.setObject(image, forKey: key)
                try? data.write(to: fileURL)
                return image
            }
        } catch {
            print("Image download error: \(error)")
        }
        
        return nil
    }
}
