import SwiftUI

struct PlayerView: View {
    @EnvironmentObject var audioService: AudioService
    @Binding var isPresented: Bool
    @State private var showControls = true
    @State private var isScrubbing = false
    @State private var wasPlayingBeforeScrub = false

    @AppStorage("isVisualizerEnabled") private var isVisualizerEnabled = true
    @AppStorage("isHapticGrooveEnabled") private var isHapticGrooveEnabled = true

    var body: some View {
        ZStack {
            // 1. Background
            LinearGradient(
                colors: [Color("DeepNavy"), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
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

                // 2. Artwork
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

                CachedImage(url: artURL, placeholder: "music.note")
                    .frame(width: 200, height: 200)
                    .cornerRadius(100)
                    .clipped()
                    .padding(.top, 8)

                // 3. Visualizer
                if isVisualizerEnabled {
                    GraffitiVisualizer(amplitudes: audioService.amplitudes)
                        .frame(height: 48)
                        .opacity(0.8)
                        .padding(.top, 12)
                } else {
                    Spacer().frame(height: 48 + 12)
                }

                // 4. Metadata
                VStack(spacing: 4) {
                    Text(audioService.currentTrack?.displayTitle ?? "Not Playing")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .lineLimit(1)

                    Text(audioService.currentTrack?.displayArtist ?? "Unknown Artist")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .lineLimit(1)
                }
                .padding(.top, 12)

                // Loop active indicator
                if audioService.loopStartTime != nil && audioService.loopEndTime != nil {
                    HStack(spacing: 4) {
                        Image(systemName: "repeat")
                            .font(.caption)
                            .foregroundColor(Color("NeonPurple"))
                        Text("Loop Active")
                            .font(.caption)
                            .foregroundColor(Color("NeonPurple"))
                        Button("Clear") {
                            audioService.deactivateLoop()
                        }
                        .font(.caption)
                        .foregroundColor(.gray)
                    }
                    .padding(.top, 6)
                }

                // A-B Loop Controls + Scrubber
                HStack(spacing: 12) {
                    // A button
                    Button(action: {
                        if audioService.loopStartTime != nil && audioService.loopEndTime != nil {
                            audioService.deactivateLoop()
                        } else {
                            let bpm = audioService.currentTrack?.bpm ?? 0
                            audioService.loopStartTime = audioService.snapToBeat(audioService.currentTime, bpm: bpm)
                            if audioService.loopEndTime != nil { audioService.activateLoop() }
                        }
                    }) {
                        VStack(spacing: 2) {
                            Text("A")
                                .font(.caption.bold())
                                .foregroundColor(audioService.loopStartTime != nil ? Color("NeonPurple") : .gray)
                            Circle()
                                .fill(audioService.loopStartTime != nil ? Color("NeonPurple") : Color.gray.opacity(0.3))
                                .frame(width: 8, height: 8)
                        }
                    }
                    .buttonStyle(.plain)
                    .frame(width: 36)

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
                        size: 240,
                        onEditingChanged: { editing in
                            isScrubbing = editing
                            if editing {
                                wasPlayingBeforeScrub = audioService.isPlaying
                                audioService.pause()
                            } else {
                                if wasPlayingBeforeScrub { audioService.resume() }
                            }
                        }
                    )

                    // B button
                    Button(action: {
                        if audioService.loopStartTime != nil && audioService.loopEndTime != nil {
                            audioService.deactivateLoop()
                        } else {
                            let bpm = audioService.currentTrack?.bpm ?? 0
                            let snapTime = audioService.snapToBeat(audioService.currentTime, bpm: bpm)
                            if let start = audioService.loopStartTime, snapTime > start {
                                audioService.loopEndTime = snapTime
                                audioService.activateLoop()
                            }
                        }
                    }) {
                        VStack(spacing: 2) {
                            Text("B")
                                .font(.caption.bold())
                                .foregroundColor(audioService.loopEndTime != nil ? Color("NeonPurple") : .gray)
                            Circle()
                                .fill(audioService.loopEndTime != nil ? Color("NeonPurple") : Color.gray.opacity(0.3))
                                .frame(width: 8, height: 8)
                        }
                    }
                    .buttonStyle(.plain)
                    .frame(width: 36)
                }
                .padding(.top, 8)

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
                .padding(.top, 12)
                .padding(.bottom, 32)
            }
            .padding(.horizontal, 24)
        }
        .gesture(
            DragGesture()
                .onEnded { value in
                    if value.translation.height > 100 {
                        isPresented = false
                    }
                }
        )
        .onReceive(audioService.$amplitudes) { _ in
            // View re-renders automatically via @EnvironmentObject — this sink ensures refresh
        }
    }
}
