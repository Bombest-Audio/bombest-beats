import Foundation

class DownloadService {
    static let shared = DownloadService()
    
    func download(track: Track) {
        guard let url = track.getStreamUrl(baseUrl: "http://100.69.137.108:8338") else { return }
        
        // Simulating download logic for now as full background downloads are complex
        // In a real app we'd use URLSession.shared.downloadTask
        print("Starting download for: \(track.title)")
        
        let task = URLSession.shared.downloadTask(with: url) { localUrl, response, error in
            guard let localUrl = localUrl, error == nil else {
                print("Download error: \(String(describing: error))")
                return
            }
            
            do {
                let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let destination = documents.appendingPathComponent("\(track.id).mp3")
                
                // Remove if exists
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                
                try FileManager.default.moveItem(at: localUrl, to: destination)
                print("Downloaded to: \(destination.path)")
            } catch {
                print("File move error: \(error)")
            }
        }
        task.resume()
    }
}
