import SwiftUI

struct SearchView: View {
    @StateObject private var viewModel = SearchViewModel()
    @EnvironmentObject var audioService: AudioService

    var body: some View {
        NavigationStack {
            VStack {
                if case .loading = viewModel.loadState {
                    ProgressView()
                        .tint(Color("NeonPurple"))
                        .scaleEffect(1.2)
                        .padding()
                }
                if case .failed(let message) = viewModel.loadState {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle).foregroundColor(.orange)
                        Text(message)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                        Button("Retry") { viewModel.retry() }
                            .buttonStyle(.borderedProminent)
                            .tint(Color("NeonPurple"))
                    }.padding()
                }

                if viewModel.searchText.isEmpty {
                    ContentUnavailableView(
                        "Search Music",
                        systemImage: "magnifyingglass",
                        description: Text("Find your favorite tracks, artists, and albums.")
                    )
                } else if viewModel.results.isEmpty {
                    // Only show "no results" when not loading
                    if case .loading = viewModel.loadState {
                        EmptyView()
                    } else {
                        ContentUnavailableView.search(text: viewModel.searchText)
                    }
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
