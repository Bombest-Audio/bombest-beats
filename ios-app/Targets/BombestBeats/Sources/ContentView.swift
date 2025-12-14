import SwiftUI

struct ContentView: View {
    @StateObject private var musicService = MusicService()
    @StateObject private var audioController = AudioController.shared
    
    var body: some View {
        NavigationView {
            List(musicService.tracks) { track in
                HStack {
                    AsyncImage(url: track.getArtUrl(baseUrl: "http://100.69.137.108:8338")) { image in
                        image.resizable()
                    } placeholder: {
                        Color.gray
                    }
                    .frame(width: 50, height: 50)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    
                    VStack(alignment: .leading) {
                        Text(track.displayTitle)
                            .font(.headline)
                        Text(track.displayArtist)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    if let index = musicService.tracks.firstIndex(where: { $0.id == track.id }) {
                        audioController.play(tracks: musicService.tracks, startingAt: index)
                    }
                }
            }
            .navigationTitle("Bombest Beats")
            .onAppear {
                musicService.fetchLibrary()
            }
            .overlay(
                VStack {
                    Spacer()
                    if let currentTrack = audioController.currentTrack {
                        MiniPlayerView(track: currentTrack, audioController: audioController)
                            .onTapGesture {
                                isPlayerPresented = true
                            }
                    }
                }
            )
            .fullScreenCover(isPresented: $isPlayerPresented) {
                PlayerView(audioController: audioController)
            }
        }
    }
    
    @State private var isPlayerPresented = false
}

struct MiniPlayerView: View {
    let track: Track
    @ObservedObject var audioController: AudioController
    
    var body: some View {
        HStack(spacing: 12) {
            // Artwork
            AsyncImage(url: track.getArtUrl(baseUrl: "http://100.69.137.108:8338")) { image in
                image.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                ZStack {
                    Color(hex: "1E2034")
                    Image(systemName: "music.note")
                        .foregroundColor(.gray)
                }
            }
            .frame(width: 48, height: 48)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .shadow(radius: 2)
            
            // Info
            VStack(alignment: .leading, spacing: 2) {
                Text(track.displayTitle)
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .lineLimit(1)
                    .foregroundColor(.primary)
                Text(track.displayArtist)
                    .font(.caption)
                    .lineLimit(1)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            // Controls
            Button(action: { audioController.togglePlayPause() }) {
                Image(systemName: audioController.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .foregroundColor(.primary)
                    .padding(8)
            }
        }
        .padding(12)
        .background(
            UncertainBlurView(style: .systemThickMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .shadow(color: Color.black.opacity(0.15), radius: 10, x: 0, y: 5)
        )
        .padding(.horizontal)
        .padding(.bottom, 8) // Lift up slightly
    }
}

// Helper for Blur (Glassmorphism)
struct UncertainBlurView: UIViewRepresentable {
    var style: UIBlurEffect.Style
    
    func makeUIView(context: Context) -> UIVisualEffectView {
        return UIVisualEffectView(effect: UIBlurEffect(style: style))
    }
    
    func updateUIView(_ uiView: UIVisualEffectView, context: Context) {
        uiView.effect = UIBlurEffect(style: style)
    }
}
