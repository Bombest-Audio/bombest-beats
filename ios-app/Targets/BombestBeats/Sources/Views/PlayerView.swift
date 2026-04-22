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
                }

                Spacer()

                // 3. Visualizer
                if isVisualizerEnabled {
                    GraffitiVisualizer(amplitudes: audioService.amplitudes)
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

                // Loop active indicator (shown only when both A and B are set)
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
                    .padding(.horizontal)
                }

                // A-B Loop Controls + Scrubber (per D-04: A/B buttons flanking the scrubber)
                HStack(spacing: 12) {
                    // A button
                    Button(action: {
                        if audioService.loopStartTime != nil && audioService.loopEndTime != nil {
                            // Both set — tapping A clears the loop
                            audioService.deactivateLoop()
                        } else {
                            let bpm = audioService.currentTrack?.bpm ?? 0
                            audioService.loopStartTime = audioService.snapToBeat(audioService.currentTime, bpm: bpm)
                            // If both A and B are now set, activate
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

                    // Scrubber (unchanged SprayPaintProgress)
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
                        size: 300,
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
                            // Both set — tapping B clears the loop
                            audioService.deactivateLoop()
                        } else {
                            let bpm = audioService.currentTrack?.bpm ?? 0
                            let snapTime = audioService.snapToBeat(audioService.currentTime, bpm: bpm)
                            // B must be after A
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
        .onReceive(audioService.$amplitudes) { _ in
            // View re-renders automatically via @EnvironmentObject — this sink ensures refresh
        }
    }
}
