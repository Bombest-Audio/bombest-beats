import SwiftUI

struct PlayerView: View {
    @EnvironmentObject var audioService: AudioService
    @Binding var isPresented: Bool
    @State private var isScrubbing = false
    @State private var wasPlayingBeforeScrub = false

    @AppStorage("isVisualizerEnabled") private var isVisualizerEnabled = true

    private let artworkSize: CGFloat = 300
    private let artworkInset: CGFloat = 24  // matches Android's imageSize - 24.dp

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color("DeepNavy"), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                // ── Scrollable content ──
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 0) {
                        // Header
                        HStack {
                            Button { isPresented = false } label: {
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

                        Spacer().frame(height: 16)

                        // Artwork inside scrubber ring
                        ZStack {
                            SprayPaintProgress(
                                progress: Binding(
                                    get: {
                                        guard audioService.duration > 0 else { return 0 }
                                        return Float(audioService.currentTime / audioService.duration)
                                    },
                                    set: { newValue in
                                        audioService.seek(to: Double(newValue) * audioService.duration)
                                    }
                                ),
                                size: artworkSize,
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

                            let artURL: URL? = {
                                guard let track = audioService.currentTrack else { return nil }
                                if let albumId = track.album_id {
                                    return URL(string: "https://beats.bom.best/album/\(albumId)/art")
                                }
                                return URL(string: "https://beats.bom.best/track/\(track.id)/art")
                            }()

                            CachedImage(url: artURL, placeholder: "music.note")
                                .frame(width: artworkSize - artworkInset,
                                       height: artworkSize - artworkInset)
                                .clipShape(Circle())
                        }
                        .frame(width: artworkSize, height: artworkSize)

                        Spacer().frame(height: 24)

                        // Time row
                        HStack {
                            Text(formatTime(audioService.currentTime))
                                .font(.caption)
                                .foregroundColor(.gray)
                            Spacer()
                            Text(formatTime(audioService.duration))
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                        .frame(maxWidth: artworkSize)

                        Spacer().frame(height: 12)

                        // Loop controls
                        LoopControlsRow()
                            .environmentObject(audioService)

                        Spacer().frame(height: 24)

                        // Visualizer
                        if isVisualizerEnabled {
                            GraffitiVisualizer(
                                amplitudes: audioService.amplitudes,
                                isPlaying: audioService.isPlaying
                            )
                            .frame(maxWidth: .infinity)
                            .frame(height: 120)
                            .opacity(0.85)
                        } else {
                            Spacer().frame(height: 120)
                        }

                        Spacer().frame(height: 16)
                    }
                    .padding(.horizontal, 24)
                }

                // ── Pinned bottom ──
                VStack(spacing: 0) {
                    // Song info
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
                    .padding(.horizontal, 24)

                    Spacer().frame(height: 16)

                    // Transport controls
                    HStack(spacing: 30) {
                        Button { audioService.toggleShuffle() } label: {
                            Image(systemName: "shuffle")
                                .font(.title2)
                                .foregroundColor(audioService.isShuffleOn ? Color("NeonPurple") : .gray)
                        }
                        Button {
                            HapticsManager.shared.playImpact()
                            audioService.playPrevious()
                        } label: {
                            Image(systemName: "backward.fill")
                                .font(.title)
                                .foregroundColor(.white)
                        }
                        Button {
                            HapticsManager.shared.playImpact()
                            audioService.togglePlayPause()
                        } label: {
                            Image(systemName: audioService.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 72))
                                .foregroundColor(Color("NeonPurple"))
                                .shadow(color: Color("NeonPurple").opacity(0.5), radius: 20)
                        }
                        .scaleEffect(audioService.isPlaying ? 1.0 : 0.95)
                        .animation(.spring(response: 0.3), value: audioService.isPlaying)
                        Button {
                            HapticsManager.shared.playImpact()
                            audioService.playNext()
                        } label: {
                            Image(systemName: "forward.fill")
                                .font(.title)
                                .foregroundColor(.white)
                        }
                        Button { audioService.toggleRepeat() } label: {
                            Image(systemName: audioService.repeatMode == .one ? "repeat.1" : "repeat")
                                .font(.title2)
                                .foregroundColor(audioService.repeatMode != .off ? Color("NeonPurple") : .gray)
                        }
                    }
                    .padding(.horizontal, 24)

                    Spacer().frame(height: 8)
                }
            }
        }
        .gesture(
            DragGesture().onEnded { value in
                if value.translation.height > 100 { isPresented = false }
            }
        )
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

// MARK: - Loop Controls Row
private struct LoopControlsRow: View {
    @EnvironmentObject var audioService: AudioService
    private let accent = Color("NeonPurple")
    private let inactive = Color.white.opacity(0.5)

    var body: some View {
        let loopActive = audioService.loopStartTime != nil && audioService.loopEndTime != nil

        VStack(spacing: 6) {
            if loopActive, let a = audioService.loopStartTime, let b = audioService.loopEndTime {
                Text("Loop: \(formatTime(a)) – \(formatTime(b))")
                    .font(.system(size: 11))
                    .foregroundColor(accent)
            }

            HStack(spacing: 10) {
                // Set A button
                Button {
                    if loopActive {
                        audioService.deactivateLoop()
                    } else {
                        let bpm = audioService.currentTrack?.bpm ?? 0
                        audioService.loopStartTime = audioService.snapToBeat(audioService.currentTime, bpm: bpm)
                        if audioService.loopEndTime != nil { audioService.activateLoop() }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "backward.end")
                            .font(.system(size: 11))
                        Text(audioService.loopStartTime != nil ? "A: \(formatTime(audioService.loopStartTime!))" : "Set A")
                            .font(.system(size: 11))
                    }
                    .foregroundColor(audioService.loopStartTime != nil ? accent : inactive)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .overlay(
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(audioService.loopStartTime != nil ? accent : inactive, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)

                // Set B button
                Button {
                    if loopActive {
                        audioService.deactivateLoop()
                    } else {
                        let bpm = audioService.currentTrack?.bpm ?? 0
                        let snapTime = audioService.snapToBeat(audioService.currentTime, bpm: bpm)
                        if let start = audioService.loopStartTime, snapTime > start {
                            audioService.loopEndTime = snapTime
                            audioService.activateLoop()
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(audioService.loopEndTime != nil ? "B: \(formatTime(audioService.loopEndTime!))" : "Set B")
                            .font(.system(size: 11))
                        Image(systemName: "forward.end")
                            .font(.system(size: 11))
                    }
                    .foregroundColor(audioService.loopEndTime != nil ? accent : inactive)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .overlay(
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(audioService.loopEndTime != nil ? accent : inactive, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)

                if loopActive {
                    Button("Clear") { audioService.deactivateLoop() }
                        .font(.system(size: 11))
                        .foregroundColor(.gray)
                }
            }
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
