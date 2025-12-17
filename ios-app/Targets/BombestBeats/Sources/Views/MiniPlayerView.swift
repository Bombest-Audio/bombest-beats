import SwiftUI

struct MiniPlayerView: View {
    var expandAction: () -> Void
    @EnvironmentObject var audioService: AudioService
    
    var body: some View {
        VStack(spacing: 0) {
            // Progress Bar (Thin)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Rectangle()
                        .fill(Color.gray.opacity(0.3))
                    
                    Rectangle()
                        .fill(Color("NeonPurple"))
                        .frame(width: geometry.size.width * CGFloat(audioService.currentTime / max(audioService.duration, 1.0)))
                }
            }
            .frame(height: 2)
            
            HStack(spacing: 12) {
                // Artwork
                if let track = audioService.currentTrack {
                    let artURL: URL? = {
                        if let albumId = track.album_id {
                            return URL(string: "https://bom.best/beats/api/album/\(albumId)/art")
                        } else {
                            return URL(string: "https://bom.best/beats/api/track/\(track.id)/art")
                        }
                    }()
                    
                    CachedImage(url: artURL, placeholder: "music.note")
                        .frame(width: 44, height: 44)
                        .cornerRadius(4)
                } else {
                    Rectangle()
                        .fill(Color.gray.opacity(0.2))
                        .frame(width: 44, height: 44)
                        .cornerRadius(4)
                }
                
                // Info
                VStack(alignment: .leading) {
                    Text(audioService.currentTrack?.displayTitle ?? "Not Playing")
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                        .lineLimit(1)
                    
                    Text(audioService.currentTrack?.displayArtist ?? "Unknown Artist")
                        .font(.caption)
                        .foregroundColor(.gray)
                        .lineLimit(1)
                }
                
                Spacer()
                
                // Controls
                Button(action: { 
                    HapticsManager.shared.playImpact()
                    audioService.togglePlayPause() 
                }) {
                    Image(systemName: audioService.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                        .foregroundColor(.white)
                        .padding(8)
                }
                
                Button(action: { 
                    HapticsManager.shared.playImpact()
                    audioService.playNext() 
                }) {
                    Image(systemName: "forward.fill")
                        .font(.title2)
                        .foregroundColor(.white)
                        .padding(8)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(
                VisualEffectBlur(blurStyle: .systemMaterialDark)
                    .ignoresSafeArea()
            )
        }
    }
}

struct VisualEffectBlur: UIViewRepresentable {
    var blurStyle: UIBlurEffect.Style
    
    func makeUIView(context: Context) -> UIVisualEffectView {
        return UIVisualEffectView(effect: UIBlurEffect(style: blurStyle))
    }
    
    func updateUIView(_ uiView: UIVisualEffectView, context: Context) {
        uiView.effect = UIBlurEffect(style: blurStyle)
    }
}
