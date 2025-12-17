import SwiftUI

struct LoginView: View {
    @EnvironmentObject var authViewModel: AuthViewModel
    
    var body: some View {
        ZStack {
            // Background - Deep Navy with subtle gradient
            LinearGradient(
                gradient: Gradient(colors: [Color("DeepNavy"), Color.black]),
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            VStack(spacing: 40) {
                Spacer()
                
                // Logo / Title
                VStack(spacing: 8) {
                    Image("AppLogo")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 100, height: 100)
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                    
                    Text("Bombest Beats")
                        .font(.system(size: 42, weight: .black, design: .rounded))
                        .foregroundStyle(.white)
                    
                    Text("Music for the Soul")
                        .font(.title3)
                        .fontWeight(.medium)
                        .foregroundStyle(.gray)
                }
                
                Spacer()
                
                if authViewModel.isLoading {
                    ProgressView()
                        .tint(Color("NeonPurple"))
                        .scaleEffect(1.5)
                } else {
                    VStack(spacing: 16) {
                        // Password Login Fields
                        VStack(spacing: 12) {
                            TextField("Username", text: $authViewModel.username)
                                .textFieldStyle(.plain)
                                .padding()
                                .background(Color.white.opacity(0.1))
                                .cornerRadius(12)
                                .foregroundColor(.white)
                                .autocapitalization(.none)
                            
                            SecureField("Password", text: $authViewModel.password)
                                .textFieldStyle(.plain)
                                .padding()
                                .background(Color.white.opacity(0.1))
                                .cornerRadius(12)
                                .foregroundColor(.white)
                            
                            Button(action: {
                                authViewModel.loginWithPassword()
                            }) {
                                Text("Log In")
                                    .fontWeight(.bold)
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color("NeonPurple"))
                                    .foregroundColor(.white)
                                    .cornerRadius(12)
                            }
                        }
                        .padding(.horizontal, 40)
                        
                        Text("OR")
                            .foregroundColor(.gray)
                            .font(.caption)
                        
                        // Passkey Button
                        Button(action: {
                            authViewModel.loginWithPasskey()
                        }) {
                            HStack {
                                Image(systemName: "faceid")
                                    .font(.title2)
                                Text("Sign in with Passkey")
                                    .font(.headline)
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(
                                LinearGradient(
                                    colors: [Color("NeonBlue"), Color("NeonPink")], // Changed for contrast
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .foregroundColor(.white)
                            .cornerRadius(16)
                            .shadow(color: Color("NeonPink").opacity(0.5), radius: 10, x: 0, y: 5)
                        }
                        .padding(.horizontal, 40)
                    }
                }
                
                if let error = authViewModel.errorMessage {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding()
                }
                
                Spacer()
                    .frame(height: 50)
            }
        }
    }
}

// Preview Provider
struct LoginView_Previews: PreviewProvider {
    static var previews: some View {
        LoginView()
            .environmentObject(AuthViewModel())
    }
}
