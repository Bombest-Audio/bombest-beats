import SwiftUI
import PhotosUI

struct SettingsView: View {
    @EnvironmentObject var authViewModel: AuthViewModel
    @StateObject private var libraryViewModel = LibraryViewModel()
    @State private var showDevOptions = false
    
    // Persistent Settings
    @AppStorage("isVisualizerEnabled") private var isVisualizerEnabled = true
    @AppStorage("selectedTheme") private var selectedTheme = 0
    @AppStorage("userAvatarData") private var userAvatarData: Data = Data()
    @AppStorage("autoDownloadEnabled") private var autoDownloadEnabled = false
    
    // Photo Picker
    @State private var selectedItem: PhotosPickerItem?
    @State private var selectedImageData: Data? = nil
    
    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        // Avatar Picker
                        PhotosPicker(selection: $selectedItem, matching: .images) {
                            if let data = selectedImageData, let uiImage = UIImage(data: data) {
                                Image(uiImage: uiImage)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 60, height: 60)
                                    .clipShape(Circle())
                            } else if let stored = UIImage(data: userAvatarData) {
                                Image(uiImage: stored)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 60, height: 60)
                                    .clipShape(Circle())
                            } else {
                                Image(systemName: "person.circle.fill")
                                    .font(.system(size: 60))
                                    .foregroundColor(Color("NeonBlue"))
                            }
                        }
                        .onChange(of: selectedItem) { newItem in
                            Task {
                                if let data = try? await newItem?.loadTransferable(type: Data.self) {
                                    selectedImageData = data
                                    userAvatarData = data // Persist
                                }
                            }
                        }
                        
                        VStack(alignment: .leading) {
                            Text(UserDefaults.standard.string(forKey: "username") ?? "User")
                                .font(.headline)
                            Text("Music Lover")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                    .padding(.vertical, 8)
                }
                
                Section("Account") {
                    NavigationLink {
                        PasskeyManagementView()
                    } label: {
                        Label("Passkeys", systemImage: "key.fill")
                    }
                    
                    NavigationLink {
                        DashboardView()
                    } label: {
                        Label("Listening Stats", systemImage: "chart.bar.fill")
                    }
                }
                
                Section("Downloads") {
                    Toggle(isOn: $autoDownloadEnabled) {
                        Label("Auto-Download All Tracks", systemImage: "arrow.down.circle.fill")
                    }
                    .onChange(of: autoDownloadEnabled) { _, enabled in
                        if enabled {
                            triggerDownloadAll()
                        }
                    }
                    
                    HStack {
                        Label("Storage Used", systemImage: "internaldrive")
                        Spacer()
                        Text(FileCacheService.shared.formattedStorageSize())
                            .foregroundColor(.secondary)
                    }
                    
                    HStack {
                        Label("Downloaded Tracks", systemImage: "music.note")
                        Spacer()
                        Text("\(FileCacheService.shared.getDownloadedCount())")
                            .foregroundColor(.secondary)
                    }
                    
                    Button(action: {
                        triggerDownloadAll()
                    }) {
                        Label("Download All Now", systemImage: "arrow.down.to.line")
                    }
                    
                    Button(role: .destructive, action: {
                        FileCacheService.shared.clearAllDownloads()
                    }) {
                        Label("Clear All Downloads", systemImage: "trash")
                    }
                }
                
                Section("App") {
                    Toggle(isOn: $isVisualizerEnabled) {
                        Label("Visualizer Enabled", systemImage: "sparkles")
                    }
                    
                    Picker("Theme", selection: $selectedTheme) {
                        Text("Graffiti").tag(0)
                        Text("Studio Dust").tag(1)
                    }
                }
                
                Section {
                    Button(role: .destructive, action: {
                        authViewModel.logout()
                    }) {
                        Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                }
            }
            .navigationTitle("Settings")
            .listStyle(.insetGrouped)
            .onAppear {
                libraryViewModel.fetchLibrary()
            }
        }
    }
    
    // MARK: - Helpers
    
    private func triggerDownloadAll() {
        FileCacheService.shared.downloadAllTracks(libraryViewModel.tracks)
    }
}
