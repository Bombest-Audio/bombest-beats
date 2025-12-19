package com.bombest.music.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bombest.music.data.api.*
import com.bombest.music.data.authDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AuthRepository(private val context: Context, private val authApi: AuthApi) {
    
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("access_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val ROLE_KEY = stringPreferencesKey("role")
    }
    
    val isLoggedIn: Flow<Boolean> = context.authDataStore.data.map { prefs ->
        prefs[TOKEN_KEY] != null
    }
    
    val token: Flow<String?> = context.authDataStore.data.map { prefs ->
        prefs[TOKEN_KEY]
    }
    
    val currentUser: Flow<User?> = context.authDataStore.data.map { prefs ->
        val id = prefs[USER_ID_KEY]?.toIntOrNull()
        val username = prefs[USERNAME_KEY]
        val role = prefs[ROLE_KEY]
        if (id != null && username != null && role != null) {
            User(id, username, role)
        } else null
    }
    
    suspend fun login(username: String, password: String): Result<User> {
        return try {
            android.util.Log.d("AuthRepository", "Attempting login for username: $username")
            val response = authApi.login(LoginRequest(username, password))
            android.util.Log.d("AuthRepository", "Login successful for user: ${response.user.username}")
            saveAuth(response.access_token, response.user)
            Result.success(response.user)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            android.util.Log.e("AuthRepository", "Login failed with HTTP ${e.code()}: $errorBody")
            Result.failure(Exception("Login failed: ${errorBody ?: e.message}"))
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Login failed with exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun register(username: String, password: String, inviteCode: String): Result<User> {
        return try {
            val response = authApi.register(RegisterRequest(username, password, inviteCode))
            saveAuth(response.access_token, response.user)
            Result.success(response.user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getPasskeyLoginOptions(): Result<PasskeyLoginOptions> {
        return try {
            val options = authApi.getPasskeyLoginOptions()
            Result.success(options)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun verifyPasskeyLogin(request: PasskeyVerifyRequest): Result<User> {
        return try {
            val response = authApi.verifyPasskeyLogin(request)
            saveAuth(response.access_token, response.user)
            Result.success(response.user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getPasskeyRegisterOptions(): Result<PasskeyRegisterOptions> {
        return try {
            val token = getTokenSync() ?: return Result.failure(Exception("Not logged in"))
            val options = authApi.getPasskeyRegisterOptions("Bearer $token")
            Result.success(options)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun verifyPasskeyRegister(credential: PasskeyCredentialRequest): Result<Boolean> {
        return try {
            val token = getTokenSync() ?: return Result.failure(Exception("Not logged in"))
            val response = authApi.verifyPasskeyRegister("Bearer $token", credential)
            Result.success(response.success)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun logout() {
        context.authDataStore.edit { prefs ->
            prefs.clear()
        }
    }
    
    private suspend fun saveAuth(token: String, user: User) {
        context.authDataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
            prefs[USER_ID_KEY] = user.id.toString()
            prefs[USERNAME_KEY] = user.username
            prefs[ROLE_KEY] = user.role
        }
    }
    
    suspend fun getTokenSync(): String? {
        return context.authDataStore.data.first()[TOKEN_KEY]
    }
}
