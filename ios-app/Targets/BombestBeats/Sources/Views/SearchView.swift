import SwiftUI

struct SearchView: View {
    @StateObject private var viewModel = SearchViewModel()
    @EnvironmentObject var audioService: AudioService

    var body: some View {
        NavigationStack {
            Group {
                switch viewModel.loadState {
                case .loading:
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
                        Button("Retry") { viewModel.retry() }
                            .buttonStyle(.borderedProminent)
                            .tint(Color("NeonPurple"))
                    }.padding()
                default:
                    if viewModel.searchText.isEmpty {
                        ContentUnavailableView(
                            "Search Music",
                            systemImage: "magnifyingglass",
                            description: Text("Find your favorite tracks, artists, and albums.")
                        )
                    } else if viewModel.results.isEmpty {
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
