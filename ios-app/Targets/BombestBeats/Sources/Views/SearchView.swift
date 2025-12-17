import SwiftUI

struct SearchView: View {
    @StateObject private var viewModel = SearchViewModel()
    @EnvironmentObject var audioService: AudioService
    
    var body: some View {
        NavigationStack {
            VStack {
                if viewModel.isLoading {
                    ProgressView()
                        .tint(Color("NeonPurple"))
                        .scaleEffect(1.2)
                        .padding()
                }
                
                if viewModel.searchText.isEmpty {
                    ContentUnavailableView(
                        "Search Music",
                        systemImage: "magnifyingglass",
                        description: Text("Find your favorite tracks, artists, and albums.")
                    )
                } else if viewModel.results.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView.search(text: viewModel.searchText)
                } else {
                    List {
                        ForEach(viewModel.results) { track in
                            TrackRow(track: track) {
                                audioService.play(track, queue: viewModel.results)
                            }
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .background(Color("DeepNavy").ignoresSafeArea())
            .searchable(text: $viewModel.searchText, prompt: "Songs, Artists, Albums")
            .navigationTitle("Search")
            .toolbarBackground(Color("DeepNavy"), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }
}
