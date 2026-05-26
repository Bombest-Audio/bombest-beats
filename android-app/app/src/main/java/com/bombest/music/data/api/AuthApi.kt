package com.bombest.music.data.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @GET("auth/me")
    suspend fun getMe(@Header("Authorization") token: String): UserResponse

    /** Exchange a refresh token for a fresh access token. Called by JwtAuthenticator on 401. */
    @POST("auth/refresh")
    suspend fun refresh(@Header("Authorization") refreshToken: String): RefreshResponse
    
    @POST("auth/passkey/login/options")
    suspend fun getPasskeyLoginOptions(@Body request: PasskeyOptionsRequest = PasskeyOptionsRequest()): PasskeyLoginOptions
    
    @POST("auth/passkey/login/verify")
    suspend fun verifyPasskeyLogin(@Body request: PasskeyVerifyRequest): AuthResponse
    
    @POST("auth/passkey/register/options")
    suspend fun getPasskeyRegisterOptions(@Header("Authorization") token: String): PasskeyRegisterOptions
    
    @POST("auth/passkey/register/verify")
    suspend fun verifyPasskeyRegister(
        @Header("Authorization") token: String,
        @Body credential: PasskeyCredentialRequest
    ): PasskeyRegisterResponse
    
    @GET("auth/passkeys")
    suspend fun listPasskeys(@Header("Authorization") token: String): List<PasskeyInfo>
    
    @DELETE("auth/passkey/delete/{passkeyId}")
    suspend fun deletePasskey(
        @Header("Authorization") token: String,
        @Path("passkeyId") passkeyId: Int
    ): GenericResponse
    
    @POST("auth/change-password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body request: ChangePasswordRequest
    ): GenericResponse
}

data class ChangePasswordRequest(val current_password: String, val new_password: String)
// data class PasskeysListResponse removed
/**
 * One row from `GET /auth/passkeys`. [name] is the human-readable device label
 * the client supplied at registration time (e.g. "Pixel 10", "iPhone 15 Pro");
 * legacy rows registered before the column existed will have it as null and
 * AccountScreen falls back to "Passkey <id>" for those.
 */
data class PasskeyInfo(
    val id: Int,
    val credential_id: String,
    val name: String? = null,
    val created_at: String?,
)
data class GenericResponse(val success: Boolean, val message: String? = null, val error: String? = null)

data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String, val invite_code: String)
data class AuthResponse(
    val access_token: String,
    val refresh_token: String? = null,  // Present in responses issued by post-C6 backend.
    val expires_in: Long? = null,       // Seconds until access_token expires.
    val user: User
)
data class RefreshResponse(val access_token: String, val expires_in: Long? = null)
data class UserResponse(val id: Int, val username: String, val role: String)
data class User(val id: Int, val username: String, val role: String)

// Passkey data classes
data class PasskeyOptionsRequest(val username: String? = null)
data class PasskeyLoginOptions(
    val challenge: String,
    val rpId: String,
    val timeout: Long,
    val userVerification: String,
    val allowCredentials: List<CredentialDescriptor>?,
    val loginId: String
)
data class CredentialDescriptor(val id: String, val type: String, val transports: List<String>?)
data class PasskeyVerifyRequest(
    val loginId: String,
    val id: String,
    val rawId: String,
    val type: String,
    val response: PasskeyAssertionResponse
)
data class PasskeyAssertionResponse(
    val authenticatorData: String,
    val clientDataJSON: String,
    val signature: String,
    val userHandle: String?
)

data class PasskeyRegisterOptions(
    val challenge: String,
    val rp: RelyingParty,
    val user: PasskeyUser,
    val pubKeyCredParams: List<PubKeyCredParam>,
    val timeout: Long,
    val authenticatorSelection: AuthenticatorSelection?,
    val attestation: String?
)
data class RelyingParty(val id: String, val name: String)
data class PasskeyUser(val id: String, val name: String, val displayName: String)
data class PubKeyCredParam(val type: String, val alg: Int)
data class AuthenticatorSelection(val residentKey: String?, val userVerification: String?)
data class PasskeyCredentialRequest(
    val id: String,
    val rawId: String,
    val type: String,
    val response: PasskeyAttestationResponse,
    /**
     * Optional client-supplied device label. Backend stores this verbatim in
     * `passkey_credentials.name` and returns it on `GET /auth/passkeys` so the
     * Manage Passkeys list can show "Pixel 10" instead of "Passkey 7".
     */
    val name: String? = null,
)
data class PasskeyAttestationResponse(val attestationObject: String, val clientDataJSON: String)
data class PasskeyRegisterResponse(val success: Boolean, val message: String?)
