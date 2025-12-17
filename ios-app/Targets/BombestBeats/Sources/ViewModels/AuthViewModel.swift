import Foundation
import Combine
import AuthenticationServices

class AuthViewModel: NSObject, ObservableObject {
    @Published var isAuthenticated: Bool = false
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil
    
    private var loginId: String? // Stored during ceremony
    private let api = APIService.shared
    
    override init() {
        super.init()
        // Check if token exists
        if UserDefaults.standard.string(forKey: "authToken") != nil {
            isAuthenticated = true
        }
    }
    
    @Published var username = ""
    @Published var password = ""
    @Published var currentUser: User? // Store user info
    @Published var passkeyRegistrationSuccess = false // For UI refresh callback
    
    func loginWithPassword() {
        Task {
            @MainActor in
            isLoading = true
            errorMessage = nil
            
            do {
                let body: [String: String] = ["username": username, "password": password]
                let bodyData = try JSONEncoder().encode(body)
                
                let authResponse: AuthResponse = try await api.request("/auth/login", method: "POST", body: bodyData)
                
                api.setToken(authResponse.access_token)
                isAuthenticated = true
                currentUser = authResponse.user
                isLoading = false
            } catch {
                isLoading = false
                errorMessage = "Login Failed: \(error.localizedDescription)"
                print("Login Error: \(error)")
            }
        }
    }

    func loginWithPasskey() {
        Task {
            @MainActor in
            isLoading = true
            errorMessage = nil
            
            do {
                // 1. Get Options
                print("Fetching Passkey Options...")
                let emptyBody = "{}".data(using: .utf8)
                let options: PasskeyLoginOptions = try await api.request("/auth/passkey/login/options", method: "POST", body: emptyBody)
                self.loginId = options.loginId
                
                // 2. Begin Ceremony
                let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: options.rpId)
                
                guard let challengeData = Data.base64UrlDecoded(options.challenge) else {
                    throw APIError.serverError("Invalid Challenge Base64")
                }
                
                let request = provider.createCredentialAssertionRequest(challenge: challengeData)
                
                let controller = ASAuthorizationController(authorizationRequests: [request])
                controller.delegate = self
                controller.presentationContextProvider = self
                controller.performRequests()
                
            } catch {
                isLoading = false
                errorMessage = error.localizedDescription
                print("Passkey Error: \(error)")
            }
        }
    }
    
    func logout() {
        api.clearToken()
        isAuthenticated = false
        currentUser = nil
    }

    func registerPasskey() {
        Task {
            @MainActor in
            isLoading = true
            errorMessage = nil
            do {
                 // 1. Get Options
                 let options = try await api.getPasskeyRegisterOptions()
                
                 // 2. Decode Challenge & User ID
                 guard let challengeData = Data.base64UrlDecoded(options.challenge) else {
                     throw APIError.serverError("Invalid Challenge Base64")
                 }
                 guard let userIdData = Data.base64UrlDecoded(options.user.id) else {
                     throw APIError.serverError("Invalid User ID Base64")
                 }

                 // 3. Create Request
                 let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: options.rp.id)
                 // Use currentUser.username or fallback to options.user.name
                 let userName = currentUser?.username ?? options.user.name
                 
                 let request = provider.createCredentialRegistrationRequest(challenge: challengeData, name: userName, userID: userIdData)

                 // 4. Perform
                 let controller = ASAuthorizationController(authorizationRequests: [request])
                 controller.delegate = self
                 controller.presentationContextProvider = self
                 controller.performRequests()
            } catch {
                 isLoading = false
                 errorMessage = error.localizedDescription
            }
        }
    }
}

// MARK: - ASAuthorizationControllerDelegate
extension AuthViewModel: ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        return UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?.windows.first ?? UIWindow()
    }
    
    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        
        // LOGIN (Assertion)
        if let credential = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion {
            Task {
                @MainActor in
                do {
                    // 3. Verify with Backend
                    let request = PasskeyVerifyRequest(
                        loginId: self.loginId ?? "",
                        id: credential.credentialID.base64UrlEncodedString(),
                        rawId: credential.credentialID.base64UrlEncodedString(),
                        type: "public-key",
                        response: PasskeyAssertionResponse(
                            authenticatorData: credential.rawAuthenticatorData.base64UrlEncodedString(),
                            clientDataJSON: credential.rawClientDataJSON.base64UrlEncodedString(),
                            signature: credential.signature.base64UrlEncodedString(),
                            userHandle: credential.userID.base64UrlEncodedString()
                        )
                    )
                    
                    let encoder = JSONEncoder()
                    let body = try encoder.encode(request)
                    let authResponse: AuthResponse = try await api.request("/auth/passkey/login/verify", method: "POST", body: body)
                    
                    // Success!
                    api.setToken(authResponse.access_token)
                    isAuthenticated = true
                    isLoading = false
                } catch {
                    isLoading = false
                    errorMessage = "Verification Failed: \(error.localizedDescription)"
                }
            }
        }
        
        // REGISTRATION
        else if let credential = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration {
             Task {
                @MainActor in
                do {
                    // Extract attestation object
                    guard let attestationObject = credential.rawAttestationObject else {
                        throw APIError.serverError("No attestation object")
                    }
                    
                    let request = PasskeyCredentialRequest(
                        id: credential.credentialID.base64UrlEncodedString(),
                        rawId: credential.credentialID.base64UrlEncodedString(),
                        type: "public-key",
                        response: PasskeyAttestationResponse(
                            attestationObject: attestationObject.base64UrlEncodedString(),
                            clientDataJSON: credential.rawClientDataJSON.base64UrlEncodedString()
                        )
                    )
                    
                    let success = try await api.verifyPasskeyRegistration(request)
                    if success {
                        print("Passkey registered!")
                        self.passkeyRegistrationSuccess = true
                        isLoading = false
                    } else {
                        errorMessage = "Registration Verification Failed"
                    }
                } catch {
                    isLoading = false
                    errorMessage = "Registration Failed: \(error.localizedDescription)"
                }
             }
        }
    }
    
    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        DispatchQueue.main.async {
            self.isLoading = false
            self.errorMessage = "Auth Canceled/Failed: \(error.localizedDescription)"
        }
    }
}

// MARK: - Helpers
extension Data {
    func base64UrlEncodedString() -> String {
        return self.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    
    static func base64UrlDecoded(_ string: String) -> Data? {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        
        while base64.count % 4 != 0 {
            base64.append("=")
        }
        
        return Data(base64Encoded: base64)
    }
}
