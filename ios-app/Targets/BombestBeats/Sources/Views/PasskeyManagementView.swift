import SwiftUI
import AuthenticationServices

struct PasskeyManagementView: View {
    @EnvironmentObject var authViewModel: AuthViewModel
    @State private var passkeys: [APIService.PasskeyItem] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    
    var body: some View {
        List {
            Section {
                Button(action: {
                    registerPasskey()
                }) {
                    if isLoading {
                        ProgressView()
                    } else {
                        Label("Register New Passkey", systemImage: "person.badge.key.fill")
                    }
                }
                .disabled(isLoading)
            } footer: {
               Text("This will create a passkey on this device for faster login. Old passkeys from previous sessions may need to be deleted.")
            }
            
            Section("Your Passkeys") {
                if passkeys.isEmpty {
                    Text("No passkeys registered.")
                        .foregroundColor(.gray)
                } else {
                    ForEach(passkeys) { passkey in
                        HStack {
                            VStack(alignment: .leading) {
                                Text("Passkey \(passkey.id)")
                                    .font(.headline)
                                Text("Created: \(passkey.created_at)")
                                    .font(.caption)
                                    .foregroundColor(.gray)
                            }
                            Spacer()
                            Image(systemName: "key")
                                .foregroundColor(.blue)
                        }
                    }
                    .onDelete(perform: deletePasskey)
                }
            }
        }
        .navigationTitle("Passkeys")
        .onAppear {
            fetchPasskeys()
        }
        .onChange(of: authViewModel.passkeyRegistrationSuccess) { _, success in
            if success {
                fetchPasskeys()
                authViewModel.passkeyRegistrationSuccess = false // Reset flag
            }
        }
        .alert("Error", isPresented: Binding(get: { errorMessage != nil }, set: { _ in errorMessage = nil })) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(errorMessage ?? "Unknown error")
        }
    }
    
    private func fetchPasskeys() {
        Task {
            do {
                passkeys = try await APIService.shared.fetchPasskeys()
            } catch {
                // Squelch error if empty or 404
                print("Passkey fetch error: \(error)")
            }
        }
    }
    
    private func deletePasskey(at offsets: IndexSet) {
        offsets.forEach { index in
            let passkey = passkeys[index]
            Task {
                do {
                    try await APIService.shared.deletePasskey(id: passkey.id)
                    fetchPasskeys()
                } catch {
                    errorMessage = "Failed to delete: \(error.localizedDescription)"
                }
            }
        }
    }
    
    private func registerPasskey() {
        authViewModel.registerPasskey()
    }
}
