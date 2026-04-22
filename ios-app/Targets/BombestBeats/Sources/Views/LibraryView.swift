import SwiftUI

struct LibraryView: View {
    @StateObject private var viewModel = LibraryViewModel()
    @EnvironmentObject var audioService: AudioService
    @ObservedObject private var favorites = FavoritesManager.shared

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {

                    // Header
                    Text("Library")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .padding(.horizontal)

                    // State guard — show loading/error/empty before content
                    switch viewModel.loadState {
                    case .idle, .loading:
                        ProgressView()
                            .tint(Color("NeonPurple"))
                            .scaleEffect(1.2)
                            .padding()
                    case .failed(let message):
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.largeTitle).foregroundColor(.orange)
                            Text(message)
                                .foregroundColor(.gray)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                            Button("Retry") { viewModel.refreshData() }
                                .buttonStyle(.borderedProminent)
                                .tint(Color("NeonPurple"))
                        }.padding()
                    case .empty:
                        ContentUnavailableView("No Songs Found", systemImage: "music.note")
                    case .loaded:
                        EmptyView()  // fall through to content below
                    }

                    // Quick Actions / Sections
                    VStack(alignment: .leading) {
                        Text("Playlists")
                            .font(.title2)
                            .fontWeight(.bold)
                            .padding(.horizontal)

                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 16) {
                                ForEach(viewModel.playlists) { playlist in
                                    NavigationLink(destination: PlaylistDetailView(playlist: playlist)) {
                                        LibraryCard(
                                            title: playlist.name,
                                            icon: "music.note.list",
                                            color: Color("NeonPurple")
                                        )
                                    }
                                }

                                if viewModel.playlists.isEmpty {
                                    Text("No playlists yet")
                                        .foregroundColor(.gray)
                                        .padding()
                                }
                            }
                            .padding(.horizontal)
                        }
                    }

                    // All Songs
                    VStack(alignment: .leading) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("All Songs")
                                .font(.title2)
                                .fontWeight(.bold)
                                .padding(.horizontal)

                            if !favorites.favoriteIds.isEmpty {
                                Text("\(favorites.favoriteIds.count) favorited")
                                    .font(.caption)
                                    .foregroundColor(Color("NeonPurple").opacity(0.8))
                                    .padding(.horizontal)
                            }
                        }

                        LazyVStack(spacing: 0) {
                            ForEach(viewModel.songs) { track in
                                TrackRow(track: track) {
                                    audioService.play(track, queue: viewModel.songs)
                                }
                                Divider()
                                    .padding(.leading, 64)
                            }
                        }
                    }
                }
                .padding(.vertical)
            }
            .background(Color("DeepNavy").ignoresSafeArea())
            .refreshable {
                viewModel.refreshData()
            }
            .onAppear {
                if viewModel.songs.isEmpty {
                    viewModel.refreshData()
                }
            }
        }
    }
}

// MARK: - Subviews

// Moved to Shared Components
