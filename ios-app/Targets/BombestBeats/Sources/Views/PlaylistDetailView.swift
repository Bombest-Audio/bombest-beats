import SwiftUI

struct PlaylistDetailView: View {
    let playlist: Playlist
    @StateObject private var viewModel: PlaylistDetailViewModel
    @EnvironmentObject var audioService: AudioService
    
    init(playlist: Playlist) {
        self.playlist = playlist
        _viewModel = StateObject(wrappedValue: PlaylistDetailViewModel(playlist: playlist))
    }
    
    var body: some View {
        VStack {
            if viewModel.isLoading {
                ProgressView()
                    .tint(Color("NeonPurple"))
                    .scaleEffect(1.2)
                    .padding()
            } else if viewModel.tracks.isEmpty {
                ContentUnavailableView("Empty Playlist", systemImage: "music.note.list", description: Text("No tracks in this playlist yet."))
            } else {
                List {
                    ForEach(viewModel.tracks) { track in
                        TrackRow(track: track) {
                            audioService.play(track, queue: viewModel.tracks)
                        }
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(Color("DeepNavy").ignoresSafeArea())
        .navigationTitle(viewModel.name)
        .toolbarBackground(Color("DeepNavy"), for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onAppear {
            if viewModel.tracks.isEmpty {
                viewModel.fetchTracks()
            }
        }
    }
}
