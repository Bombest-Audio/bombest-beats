import SwiftUI

struct LibraryView: View {
    @StateObject private var viewModel = LibraryViewModel()
    @EnvironmentObject var audioService: AudioService
    
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    
                    // Header
                    Text("Library")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .padding(.horizontal)
                    
                    if let error = viewModel.errorMessage {
                        Text("Error: \(error)")
                            .foregroundColor(.red)
                            .padding()
                            .background(Color.red.opacity(0.1))
                            .cornerRadius(8)
                            .padding(.horizontal)
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
                        Text("All Songs")
                            .font(.title2)
                            .fontWeight(.bold)
                            .padding(.horizontal)
                        
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
