package com.bombest.music.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.*
import androidx.credentials.exceptions.*
import com.bombest.music.data.api.*
import com.bombest.music.data.repository.AuthRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager class for handling passkey (WebAuthn) operations using Android's Credential Manager.
 * Bridges the gap between Android's Credential Manager API and the backend WebAuthn endpoints.
 */
class PasskeyManager(
    private val context: Context,
    private val authRepository: AuthRepository
) {
    private val credentialManager = CredentialManager.create(context)
    
    companion object {
        private const val TAG = "PasskeyManager"
        
        // Helper to convert base64url to base64 standard
        fun base64UrlToBase64(base64Url: String): String {
            return base64Url
                .replace('-', '+')
                .replace('_', '/')
                .let { 
                    when (it.length % 4) {
                        2 -> "$it=="
                        3 -> "$it="
                        else -> it
                    }
                }
        }
        
        // Helper to convert base64 standard to base64url
        fun base64ToBase64Url(base64: String): String {
            return base64
                .replace('+', '-')
                .replace('/', '_')
                .replace("=", "")
        }
    }
    
    /**
     * Register a new passkey for the currently logged-in user.
     * @return Result with success boolean or error
     */
    suspend fun registerPasskey(): Result<Boolean> {
        return try {
            // 1. Get registration options from the backend
            Log.d(TAG, "Getting passkey registration options from backend...")
            val optionsResult = authRepository.getPasskeyRegisterOptions()
            if (optionsResult.isFailure) {
                return Result.failure(optionsResult.exceptionOrNull() ?: Exception("Failed to get options"))
            }
            val options = optionsResult.getOrThrow()
            Log.d(TAG, "Got registration options: rp=${options.rp.id}, user=${options.user.name}")
            
            // 2. Convert backend options to Android Credential Manager JSON format
            val requestJson = buildRegistrationRequestJson(options)
            Log.d(TAG, "Built registration request JSON")
            
            // 3. Create the passkey using Credential Manager
            val createPublicKeyCredentialRequest = CreatePublicKeyCredentialRequest(requestJson)
            
            Log.d(TAG, "Calling Credential Manager to create passkey...")
            val response = credentialManager.createCredential(
                context = context,
                request = createPublicKeyCredentialRequest
            )
            
            // 4. Extract the credential response
            if (response !is CreatePublicKeyCredentialResponse) {
                return Result.failure(Exception("Unexpected response type"))
            }
            
            val credentialJson = response.registrationResponseJson
            Log.d(TAG, "Got credential response from Credential Manager")
            
            // 5. Parse the response and send to backend for verification
            val credentialRequest = parseRegistrationResponse(credentialJson)
            
            Log.d(TAG, "Sending credential to backend for verification...")
            val verifyResult = authRepository.verifyPasskeyRegister(credentialRequest)
            
            if (verifyResult.isSuccess && verifyResult.getOrNull() == true) {
                Log.d(TAG, "Passkey registration successful!")
                Result.success(true)
            } else {
                Log.e(TAG, "Passkey verification failed")
                Result.failure(verifyResult.exceptionOrNull() ?: Exception("Verification failed"))
            }
            
        } catch (e: CreateCredentialCancellationException) {
            Log.d(TAG, "User cancelled passkey registration")
            Result.failure(Exception("Cancelled"))
        } catch (e: CreateCredentialException) {
            Log.e(TAG, "Credential creation failed: ${e.message}", e)
            Result.failure(Exception("Passkey creation failed: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Passkey registration failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Log in using a passkey.
     * @return Result with User on success or error
     */
    suspend fun loginWithPasskey(): Result<User> {
        return try {
            // 1. Get login options from the backend
            Log.d(TAG, "Getting passkey login options from backend...")
            val optionsResult = authRepository.getPasskeyLoginOptions()
            if (optionsResult.isFailure) {
                return Result.failure(optionsResult.exceptionOrNull() ?: Exception("Failed to get options"))
            }
            val options = optionsResult.getOrThrow()
            Log.d(TAG, "Got login options: rpId=${options.rpId}, loginId=${options.loginId}")
            
            // 2. Convert backend options to Android Credential Manager JSON format
            val requestJson = buildLoginRequestJson(options)
            Log.d(TAG, "Built login request JSON")
            
            // 3. Get the passkey credential using Credential Manager
            val getCredentialRequest = GetCredentialRequest(
                listOf(GetPublicKeyCredentialOption(requestJson))
            )
            
            Log.d(TAG, "Calling Credential Manager to get passkey...")
            val response = credentialManager.getCredential(
                context = context,
                request = getCredentialRequest
            )
            
            // 4. Extract the credential response
            val credential = response.credential
            if (credential !is PublicKeyCredential) {
                return Result.failure(Exception("Unexpected credential type"))
            }
            
            val assertionJson = credential.authenticationResponseJson
            Log.d(TAG, "Got assertion response from Credential Manager")
            
            // 5. Parse the response and send to backend for verification
            val verifyRequest = parseLoginResponse(assertionJson, options.loginId)
            
            Log.d(TAG, "Sending assertion to backend for verification...")
            val verifyResult = authRepository.verifyPasskeyLogin(verifyRequest)
            
            if (verifyResult.isSuccess) {
                Log.d(TAG, "Passkey login successful!")
                verifyResult
            } else {
                Log.e(TAG, "Passkey login verification failed")
                Result.failure(verifyResult.exceptionOrNull() ?: Exception("Login failed"))
            }
            
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "User cancelled passkey login")
            Result.failure(Exception("Cancelled"))
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No passkeys found")
            Result.failure(Exception("No passkeys registered. Please log in with password first."))
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential retrieval failed: ${e.message}", e)
            Result.failure(Exception("Passkey login failed: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Passkey login failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Build the JSON request for passkey registration that Android Credential Manager expects.
     */
    private fun buildRegistrationRequestJson(options: PasskeyRegisterOptions): String {
        val json = JSONObject().apply {
            put("challenge", options.challenge)
            put("rp", JSONObject().apply {
                put("id", options.rp.id)
                put("name", options.rp.name)
            })
            put("user", JSONObject().apply {
                put("id", options.user.id)
                put("name", options.user.name)
                put("displayName", options.user.displayName)
            })
            put("pubKeyCredParams", JSONArray().apply {
                options.pubKeyCredParams.forEach { param ->
                    put(JSONObject().apply {
                        put("type", param.type)
                        put("alg", param.alg)
                    })
                }
            })
            put("timeout", options.timeout)
            options.authenticatorSelection?.let { sel ->
                put("authenticatorSelection", JSONObject().apply {
                    sel.residentKey?.let { put("residentKey", it) }
                    sel.userVerification?.let { put("userVerification", it) }
                })
            }
            options.attestation?.let { put("attestation", it) }
        }
        return json.toString()
    }
    
    /**
     * Build the JSON request for passkey login that Android Credential Manager expects.
     */
    private fun buildLoginRequestJson(options: PasskeyLoginOptions): String {
        val json = JSONObject().apply {
            put("challenge", options.challenge)
            put("rpId", options.rpId)
            put("timeout", options.timeout)
            put("userVerification", options.userVerification)
            options.allowCredentials?.let { creds ->
                if (creds.isNotEmpty()) {
                    put("allowCredentials", JSONArray().apply {
                        creds.forEach { cred ->
                            put(JSONObject().apply {
                                put("type", cred.type)
                                put("id", cred.id)
                                cred.transports?.let { transports ->
                                    put("transports", JSONArray(transports))
                                }
                            })
                        }
                    })
                }
            }
        }
        return json.toString()
    }
    
    /**
     * Parse the registration response from Credential Manager into backend format.
     */
    private fun parseRegistrationResponse(responseJson: String): PasskeyCredentialRequest {
        val json = JSONObject(responseJson)
        val responseObj = json.getJSONObject("response")
        
        return PasskeyCredentialRequest(
            id = json.getString("id"),
            rawId = json.getString("rawId"),
            type = json.getString("type"),
            response = PasskeyAttestationResponse(
                attestationObject = responseObj.getString("attestationObject"),
                clientDataJSON = responseObj.getString("clientDataJSON")
            )
        )
    }
    
    /**
     * Parse the login response from Credential Manager into backend format.
     */
    private fun parseLoginResponse(responseJson: String, loginId: String): PasskeyVerifyRequest {
        val json = JSONObject(responseJson)
        val responseObj = json.getJSONObject("response")
        
        return PasskeyVerifyRequest(
            loginId = loginId,
            id = json.getString("id"),
            rawId = json.getString("rawId"),
            type = json.getString("type"),
            response = PasskeyAssertionResponse(
                authenticatorData = responseObj.getString("authenticatorData"),
                clientDataJSON = responseObj.getString("clientDataJSON"),
                signature = responseObj.getString("signature"),
                userHandle = responseObj.optString("userHandle", null)
            )
        )
    }
}
