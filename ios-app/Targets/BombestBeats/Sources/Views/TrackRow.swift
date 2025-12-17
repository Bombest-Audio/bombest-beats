import SwiftUI

struct TrackRow: View {
    let track: Track
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                // Album Artwork
                CachedImage(
                    url: track.album_id != nil ? URL(string: "https://bom.best/beats/api/album/\(track.album_id!)/art") : URL(string: "https://bom.best/beats/api/track/\(track.id)/art"),
                    placeholder: "music.note"
                )
                .frame(width: 48, height: 48)
                .cornerRadius(6)
                .clipped()
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(track.displayTitle)
                        .font(.body)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                        .lineLimit(1)
                    
                    Text(track.displayArtist)
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .lineLimit(1)
                }
                
                Spacer()
                
                Image(systemName: "ellipsis")
                    .foregroundColor(.gray)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
