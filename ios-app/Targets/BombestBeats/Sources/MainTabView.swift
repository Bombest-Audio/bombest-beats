import SwiftUI

struct MainTabView: View {
    @State private var selectedTab = 0
    @State private var isPlayerExpanded = false
    @EnvironmentObject var audioService: AudioService
    
    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                LibraryView()
                    .tabItem {
                        Label("Library", systemImage: "music.note.list")
                    }
                    .tag(0)
                
                SearchView()
                    .tabItem {
                        Label("Search", systemImage: "magnifyingglass")
                    }
                    .tag(1)
                
                SettingsView()
                    .tabItem {
                        Label("Settings", systemImage: "gearshape")
                    }
                    .tag(2)
            }
            .accentColor(Color("NeonPurple")) // Custom branded color
            
            // Mini Player Overlay
            if audioService.currentTrack != nil {
                MiniPlayerView(expandAction: { isPlayerExpanded = true })
                    .padding(.bottom, 49) // Height of TabBar approx
                    .transition(.move(edge: .bottom))
                    .onTapGesture {
                        isPlayerExpanded = true
                    }
            }
        }
        .fullScreenCover(isPresented: $isPlayerExpanded) {
            PlayerView(isPresented: $isPlayerExpanded)
        }
    }
}
