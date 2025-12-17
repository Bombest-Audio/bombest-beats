import Foundation
import Combine

struct PlayEvent: Codable {
    let track_id: Int
    let timestamp: String
}

struct BatchPlayRequest: Codable {
    let events: [PlayEvent]
}

class MetricsService: ObservableObject {
    static let shared = MetricsService()
    
    // Config
    private let batchSize = 10
    private let queueKey = "metrics_queue"
    private let baseURL = "https://bom.best/beats/api"
    
    // State
    private var queue: [PlayEvent] = []
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        loadQueue()
    }
    
    func logPlay(trackId: Int) {
        let formatter = ISO8601DateFormatter()
        let timestamp = formatter.string(from: Date())
        let event = PlayEvent(track_id: trackId, timestamp: timestamp)
        
        queue.append(event)
        saveQueue()
        
        print("[Metrics] Logged play for \(trackId). Queue: \(queue.count)")
        
        if queue.count >= batchSize {
            flush()
        }
    }
    
    func flush() {
        guard !queue.isEmpty else { return }
        
        // Take snapshot
        let batch = queue
        
        // Prepare Request
        guard let url = URL(string: "\(baseURL)/metrics/batch") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        if let token = UserDefaults.standard.string(forKey: "authToken") {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        let payload = BatchPlayRequest(events: batch)
        
        do {
            request.httpBody = try JSONEncoder().encode(payload)
        } catch {
            print("[Metrics] Encoding error: \(error)")
            return
        }
        
        // Send
        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            guard let self = self else { return }
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 || httpResponse.statusCode == 201 {
                print("[Metrics] Batch upload success")
                DispatchQueue.main.async {
                    // Remove uploaded events
                    // Simple approach: remove the first N where N is batch count. 
                    // But if queue grew in meantime?
                    // Better: Filter out events? Or just lock/sync?
                    // Since specific events were sent, we can just remove them.
                    // But events are struct without ID.
                    // Risk of data loss if we clear all vs duplicates.
                    // Correct approach: Remove first batch.count elements
                    if self.queue.count >= batch.count {
                        self.queue.removeFirst(batch.count)
                        self.saveQueue()
                    }
                }
            } else {
                print("[Metrics] Upload failed: \(String(describing: error))")
            }
        }.resume()
    }
    
    // MARK: - Persistence
    
    private func saveQueue() {
        if let data = try? JSONEncoder().encode(queue) {
            UserDefaults.standard.set(data, forKey: queueKey)
        }
    }
    
    private func loadQueue() {
        if let data = UserDefaults.standard.data(forKey: queueKey),
           let loaded = try? JSONDecoder().decode([PlayEvent].self, from: data) {
            queue = loaded
        }
    }
}
