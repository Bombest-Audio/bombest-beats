import SwiftUI

struct PlayerView: View {
    @EnvironmentObject var audioService: AudioService
    @Binding var isPresented: Bool
    @State private var showControls = true
    @State private var isScrubbing = false
    @State private var wasPlayingBeforeScrub = false
    
    @AppStorage("isVisualizerEnabled") private var isVisualizerEnabled = true
    
    // Mock amplitudes for visualized
    @State private var amplitudes: [Float] = Array(repeating: 0.2, count: 30)
    let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()
    
    var body: some View {
        ZStack {
            // 1. Background
            LinearGradient(
                colors: [Color("DeepNavy"), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            
            VStack(spacing: 32) {
                // Header
                HStack {
                    Button(action: {
                        isPresented = false
                    }) {
                        Image(systemName: "chevron.down")
                            .foregroundColor(.white)
                            .padding()
                    }
                    Spacer()
                    Text("Now Playing")
                        .font(.headline)
                        .foregroundColor(.white.opacity(0.8))
                    Spacer()
                    Image(systemName: "ellipsis")
                        .foregroundColor(.white)
                        .padding()
                }
                
                Spacer()
                
                // 2. Artwork & Progress
                ZStack {
                    // Artwork
                    let artURL: URL? = {
                        if let track = audioService.currentTrack {
                            if let albumId = track.album_id {
                                return URL(string: "https://bom.best/beats/api/album/\(albumId)/art")
                            } else {
                                return URL(string: "https://bom.best/beats/api/track/\(track.id)/art")
                            }
                        }
                        return nil
                    }()
                    
                    CachedImage(
                        url: artURL,
                        placeholder: "music.note"
                    )
                    .frame(width: 280, height: 280)
                    .cornerRadius(140) // Circle
                    .clipped()
                        
                    // Circular Progress
                    SprayPaintProgress(
                        progress: Binding(
                            get: {
                                guard audioService.duration > 0 else { return 0 }
                                return Float(audioService.currentTime / audioService.duration)
                            },
                            set: { newValue in
                                let time = Double(newValue) * audioService.duration
                                audioService.seek(to: time)
                            }
                        ),
                        size: 300, // Slightly larger than artwork
                        onEditingChanged: { editing in
                            isScrubbing = editing
                            if editing {
                                wasPlayingBeforeScrub = audioService.isPlaying
                                audioService.pause()
                            } else {
                                if wasPlayingBeforeScrub {
                                    audioService.resume()
                                }
                            }
                        }
                    )
                }
                
                Spacer()
                
                // 3. Visualizer
                if isVisualizerEnabled {
                    GraffitiVisualizer(amplitudes: amplitudes)
                        .frame(height: 120)
                        .opacity(0.8)
                } else {
                     Spacer().frame(height: 120)
                }
                
                // 4. Metadata
                VStack(spacing: 8) {
                    Text(audioService.currentTrack?.displayTitle ?? "Not Playing")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Text(audioService.currentTrack?.displayArtist ?? "Unknown Artist")
                        .font(.title3)
                        .foregroundColor(.gray)
                }
                
                // 5. Controls
                HStack(spacing: 30) {
                    Button(action: {
                        audioService.toggleShuffle()
                    }) {
                        Image(systemName: "shuffle")
                            .font(.title2)
                            .foregroundColor(audioService.isShuffleOn ? Color("NeonPurple") : .gray)
                    }
                    
                    Button(action: {
                        HapticsManager.shared.playImpact()
                        audioService.playPrevious()
                    }) {
                        Image(systemName: "backward.fill")
                            .font(.title)
                            .foregroundColor(.white)
                    }
                    
                    Button(action: {
                        HapticsManager.shared.playImpact()
                        audioService.togglePlayPause()
                    }) {
                        Image(systemName: audioService.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 72))
                            .foregroundColor(Color("NeonPurple"))
                            .shadow(color: Color("NeonPurple").opacity(0.5), radius: 20)
                    }
                    .scaleEffect(audioService.isPlaying ? 1.0 : 0.95)
                    .animation(.spring(response: 0.3), value: audioService.isPlaying)
                    
                    Button(action: {
                        HapticsManager.shared.playImpact()
                        audioService.playNext()
                    }) {
                        Image(systemName: "forward.fill")
                            .font(.title)
                            .foregroundColor(.white)
                    }
                    
                    Button(action: {
                        audioService.toggleRepeat()
                    }) {
                        Image(systemName: audioService.repeatMode == .one ? "repeat.1" : "repeat")
                            .font(.title2)
                            .foregroundColor(audioService.repeatMode != .off ? Color("NeonPurple") : .gray)
                    }
                }
                }
        }
        .gesture(
            DragGesture()
                .onEnded { value in
                    if value.translation.height > 100 {
                        isPresented = false
                    }
                }
        )
        .onReceive(timer) { _ in
            // Simulate visualizer with more dynamic wave-like motion
            if audioService.isPlaying && !isScrubbing {
                // Shift amplitudes left and add new random value (scrolling effect)
                var newAmps = amplitudes.dropFirst()
                // Random with some coherence to audio feel
                let newValue = Float.random(in: 0.2...0.9)
                newAmps.append(newValue)
                // Smooth out
                amplitudes = Array(newAmps)
            } else {
                amplitudes = amplitudes.map { max($0 * 0.85, 0.1) } // Decay to steady low state
            }
        }
    }
}
