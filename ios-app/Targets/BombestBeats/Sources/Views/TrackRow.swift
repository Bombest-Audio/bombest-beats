import SwiftUI

struct TrackRow: View {
    let track: Track
    let action: () -> Void

    @ObservedObject private var favorites = FavoritesManager.shared

    var body: some View {
        // Row tap uses onTapGesture to avoid nested-Button conflict with the heart
        HStack(spacing: 16) {
            CachedImage(
                url: track.album_id != nil
                    ? URL(string: "https://beats.bom.best/album/\(track.album_id!)/art")
                    : URL(string: "https://beats.bom.best/track/\(track.id)/art"),
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

            Button {
                favorites.toggle(trackId: track.id)
            } label: {
                Image(systemName: favorites.isFavorited(trackId: track.id) ? "heart.fill" : "heart")
                    .foregroundColor(favorites.isFavorited(trackId: track.id) ? Color("NeonPurple") : .gray)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
    }
}
