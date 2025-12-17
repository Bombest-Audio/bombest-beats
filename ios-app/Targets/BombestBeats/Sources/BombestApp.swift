import SwiftUI
import AVFoundation

@main
struct BombestApp: App {
    // Shared Audio Service
    @StateObject private var audioService = AudioService.shared
    @StateObject private var authViewModel = AuthViewModel()
    
    init() {
        // Configure global appearance
        configureAppearance()
    }
    
    var body: some Scene {
        WindowGroup {
            Group {
                if authViewModel.isAuthenticated {
                    MainTabView()
                        .transition(.opacity.animation(.easeInOut))
                } else {
                    LoginView()
                        .transition(.opacity.animation(.easeInOut))
                }
            }
            .environmentObject(audioService)
            .environmentObject(authViewModel)
            .preferredColorScheme(.dark) // Force dark mode for that premium feel
        }
    }
    
    private func configureAppearance() {
        // Custom Tab Bar appearance
        let appearance = UITabBarAppearance()
        appearance.configureWithDefaultBackground()
        appearance.backgroundColor = UIColor(named: "DeepNavy") ?? UIColor.black
        
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
}
