import SwiftUI
import Combine

enum VisualizerType {
    case multiWave, bar, singleWave
    
    mutating func toggle() {
        switch self {
        case .multiWave: self = .bar
        case .bar: self = .singleWave
        case .singleWave: self = .multiWave
        }
    }
}

struct PlayerView: View {
    @ObservedObject var audioController: AudioController
    @Environment(\.presentationMode) var presentationMode
    
    @State private var visualizerType: VisualizerType = .multiWave
    @State private var showQueue = false
    
    // Gradient Background State (could be dynamic later)
    let backgroundGradient = RadialGradient(
        gradient: Gradient(colors: [Color(hex: "1E2034"), Color(hex: "050712")]),
        center: .center,
        startRadius: 5,
        endRadius: 600
    )
    
    var body: some View {
        ZStack {
            // Background
            backgroundGradient
                .ignoresSafeArea()
            
            VStack(spacing: 24) {
                // Top Row
                HStack {
                    Button(action: { presentationMode.wrappedValue.dismiss() }) {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(Color(hex: "F470FF"))
                            .padding(12)
                            .background(Circle().fill(Color.white.opacity(0.1)))
                    }
                    Spacer()
                    Button(action: { showQueue = true }) {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(Color(hex: "F470FF"))
                            .padding(12)
                            .background(Circle().fill(Color.white.opacity(0.1)))
                    }
                    .sheet(isPresented: $showQueue) {
                        QueueView()
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 16)
                
                // Artwork & Scrubber
                if let track = audioController.currentTrack {
                    ArtworkWithScrubber(
                        artworkUrl: track.getArtUrl(baseUrl: "http://100.69.137.108:8338"),
                        duration: audioController.duration,
                        currentTime: audioController.currentTime,
                        onSeek: { time in audioController.seek(to: time) }
                    )
                    .frame(height: 320)
                } 
                else {
                    Spacer().frame(height: 320)
                }
                
                // Time
                HStack {
                    Text(formatTime(audioController.currentTime))
                    Spacer()
                    Text(formatTime(audioController.duration))
                }
                .font(.caption)
                .monospacedDigit()
                .foregroundColor(.white.opacity(0.7))
                .padding(.horizontal, 48)
                
                // Mid Controls (Heart, DL, Share)
                MidControlsRow()
                    .padding(.vertical, 8)
                
                // Visualizer
                VisualizerView(type: visualizerType)
                    .frame(height: 100)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        withAnimation(.spring()) {
                            visualizerType.toggle()
                        }
                    }
                
                Spacer()
                
                // Song Info
                VStack(spacing: 4) {
                    Text(audioController.currentTrack?.displayTitle ?? "Not Playing")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(1)
                    
                    Text(audioController.currentTrack?.displayArtist ?? "Unknown Artist")
                        .font(.body)
                        .fontWeight(.medium)
                        .foregroundColor(.white.opacity(0.7))
                }
                .padding(.horizontal)
                
                // Transport Controls
                TransportControls(audioController: audioController)
                    .padding(.bottom, 48)
            }
        }
    }
    
    func formatTime(_ seconds: Double) -> String {
        guard !seconds.isNaN && !seconds.isInfinite else { return "0:00" }
        let min = Int(seconds) / 60
        let sec = Int(seconds) % 60
        return String(format: "%d:%02d", min, sec)
    }
}

// MARK: - Subviews

struct MidControlsRow: View {
    @ObservedObject var audioController = AudioController.shared
    @ObservedObject var favoritesService = FavoritesService.shared
    @State private var showingShareSheet = false
    
    var body: some View {
        HStack(spacing: 40) {
            // Heart
            Button(action: {
                if let track = audioController.currentTrack {
                    favoritesService.toggleFavorite(track.id)
                }
            }) {
                Image(systemName: favoritesService.isFavorite(audioController.currentTrack?.id ?? "") ? "heart.fill" : "heart")
                    .font(.title2)
                    .foregroundColor(Color(hex: "F48FFF"))
            }

            // Download
            Button(action: {
                if let track = audioController.currentTrack {
                    DownloadService.shared.download(track: track)
                }
            }) {
                Image(systemName: "arrow.down.circle")
                    .font(.title2)
                    .foregroundColor(Color(hex: "F48FFF"))
            }

            // Share
            Button(action: { showingShareSheet = true }) {
                Image(systemName: "square.and.arrow.up")
                    .font(.title2)
                    .foregroundColor(Color(hex: "F48FFF"))
            }
            .sheet(isPresented: $showingShareSheet) {
                if let track = audioController.currentTrack {
                    ShareSheet(activityItems: ["Check out \(track.title) by \(track.artist) on Bombest Beats!"])
                }
            }
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    var activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: activityItems, applicationActivities: applicationActivities)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct TransportControls: View {
    @ObservedObject var audioController: AudioController
    
    var body: some View {
        HStack(spacing: 30) {
            // Shuffle
            Button(action: { audioController.toggleShuffle() }) {
                Image(systemName: "shuffle")
                    .font(.system(size: 20))
                    .foregroundColor(audioController.isShuffleEnabled ? Color(hex: "C27CFF") : .gray)
            }
            
            // Previous
            Button(action: { audioController.previous() }) {
                Image(systemName: "backward.end.fill")
                    .font(.system(size: 30))
                    .foregroundColor(Color(hex: "C27CFF"))
            }
            
            // Play/Pause
            Button(action: { audioController.togglePlayPause() }) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                gradient: Gradient(colors: [Color(hex: "FFB86C"), Color(hex: "FF6B81"), Color(hex: "C34CFF")]),
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 72, height: 72)
                        .shadow(color: Color(hex: "FF6B81").opacity(0.4), radius: 10, x: 0, y: 5)
                    
                    Circle()
                        .fill(Color(hex: "050712"))
                        .frame(width: 58, height: 58)
                    
                    Image(systemName: audioController.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                }
            }
            
            // Next
            Button(action: { audioController.next() }) {
                Image(systemName: "forward.end.fill")
                    .font(.system(size: 30))
                    .foregroundColor(Color(hex: "C27CFF"))
            }
            
            // Repeat
            Button(action: { audioController.toggleRepeat() }) {
                Image(systemName: audioController.repeatMode == .one ? "repeat.1" : "repeat")
                    .font(.system(size: 20))
                    .foregroundColor(audioController.repeatMode != .off ? Color(hex: "C27CFF") : .gray)
            }
        }
    }
}

struct VisualizerView: View {
    let type: VisualizerType
    @State private var phase: CGFloat = 0.0
    
    var body: some View {
        TimelineView(.animation(minimumInterval: 0.04)) { timelineContext in
            Canvas { context, size in
                let width = size.width
                let height = size.height
                let centerY = height / 2
                let time = timelineContext.date.timeIntervalSinceReferenceDate
                
                // Simulating amplitudes for a cool effect since we don't have real FFT data easily
                let count = 40
                var amplitudes: [CGFloat] = []
                for i in 0..<count {
                    let x = Double(i) * 0.2 + time * 2.0
                    let noise = sin(x) * 0.5 + 0.5
                    amplitudes.append(CGFloat(noise))
                }
                
                switch type {
                case .multiWave:
                    drawMultiWave(context: context, size: size, time: time)
                case .bar:
                    drawBars(context: context, size: size, amplitudes: amplitudes)
                case .singleWave:
                    drawSingleWave(context: context, size: size, time: time)
                }
            }
        }
    }
    
    func drawMultiWave(context: GraphicsContext, size: CGSize, time: Double) {
        let colors = [Color(hex: "C34CFF"), Color(hex: "4F7BFF"), Color(hex: "FF6B81")]
        let speeds = [1.0, 0.7, 1.2]
        let scales = [1.0, 0.8, 1.2]
        
        for i in 0..<3 {
            var path = Path()
            let width = size.width
            let height = size.height
            let centerY = height / 2
            
            path.move(to: CGPoint(x: 0, y: centerY))
            
            for x in stride(from: 0, to: width, by: 5) {
                let relX = x / width
                let sine = sin(relX * 8 + time * speeds[i] * 3)
                let y = centerY + sine * (20 * scales[i])
                path.addLine(to: CGPoint(x: x, y: y))
            }
            
            context.stroke(path, with: .color(colors[i].opacity(0.8)), lineWidth: 3)
        }
    }
    
    func drawBars(context: GraphicsContext, size: CGSize, amplitudes: [CGFloat]) {
        let width = size.width
        let height = size.height
        let centerY = height / 2
        let count = amplitudes.count
        let stepX = width / CGFloat(count)
        let barWidth = stepX * 0.6
        
        for i in 0..<count {
            let x = CGFloat(i) * stepX + (stepX - barWidth) / 2
            let amp = amplitudes[i]
            let barHeight = amp * height * 0.8
            
            let rect = CGRect(x: x, y: centerY - barHeight / 2, width: barWidth, height: barHeight)
            let path = Path(roundedRect: rect, cornerRadius: 4)
            
            context.fill(path, with: .linearGradient(
                Gradient(colors: [Color(hex: "C34CFF"), Color(hex: "4F7BFF")]),
                startPoint: CGPoint(x: x, y: centerY - barHeight/2),
                endPoint: CGPoint(x: x, y: centerY + barHeight/2)
            ))
        }
    }
    
    func drawSingleWave(context: GraphicsContext, size: CGSize, time: Double) {
        var path = Path()
        let width = size.width
        let height = size.height
        let centerY = height / 2
        
        path.move(to: CGPoint(x: 0, y: centerY))
        
        for x in stride(from: 0, to: width, by: 2) {
            let relX = x / width
            let sine = sin(relX * 12 + time * 5)
            // Add some "jitter" for oscilloscope feel
            let jitter = sin(relX * 50 + time * 20) * 0.1
            let y = centerY + (sine + jitter) * 30
            path.addLine(to: CGPoint(x: x, y: y))
        }
        
        context.stroke(path, with: .linearGradient(
            Gradient(colors: [Color(hex: "FFB86C"), Color(hex: "FF6B81"), Color(hex: "C34CFF")]),
            startPoint: .zero,
            endPoint: CGPoint(x: width, y: 0)
        ), lineWidth: 4)
    }
}
