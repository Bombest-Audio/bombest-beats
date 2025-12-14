package com.bombest.music.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bombest.music.data.api.*
import com.bombest.music.data.repository.AuthRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class AuthViewModel(context: Context) : ViewModel() {
    
    private val authApi: AuthApi
    val authRepository: AuthRepository
    
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    
    // Start as null (unknown), then set to true/false once checked
    val isLoggedIn = mutableStateOf<Boolean?>(null)
    val currentUser = mutableStateOf<User?>(null)
    
    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl("https://bom.best/beats/api/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        
        authApi = retrofit.create(AuthApi::class.java)
        authRepository = AuthRepository(context, authApi)
        
        // Check login state asynchronously
        viewModelScope.launch {
            try {
                val loggedIn = authRepository.isLoggedIn.first()
                isLoggedIn.value = loggedIn
                if (loggedIn) {
                    currentUser.value = authRepository.currentUser.first()
                }
            } catch (e: Exception) {
                isLoggedIn.value = false
            }
        }
    }
    
    fun login(username: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            
            val result = authRepository.login(username, password)
            result.fold(
                onSuccess = { user ->
                    currentUser.value = user
                    isLoggedIn.value = true
                    onSuccess()
                },
                onFailure = { e ->
                    error.value = e.message ?: "Login failed"
                }
            )
            isLoading.value = false
        }
    }
    
    fun register(username: String, password: String, inviteCode: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            
            val result = authRepository.register(username, password, inviteCode)
            result.fold(
                onSuccess = { user ->
                    currentUser.value = user
                    isLoggedIn.value = true
                    onSuccess()
                },
                onFailure = { e ->
                    error.value = e.message ?: "Registration failed"
                }
            )
            isLoading.value = false
        }
    }
    
    fun loginWithPasskey(onSuccess: () -> Unit, onGetOptions: (PasskeyLoginOptions) -> Unit) {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            
            val result = authRepository.getPasskeyLoginOptions()
            result.fold(
                onSuccess = { options ->
                    onGetOptions(options)
                },
                onFailure = { e ->
                    error.value = e.message ?: "Passkey not available"
                    isLoading.value = false
                }
            )
        }
    }
    
    fun completePasskeyLogin(request: PasskeyVerifyRequest, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = authRepository.verifyPasskeyLogin(request)
            result.fold(
                onSuccess = { user ->
                    currentUser.value = user
                    isLoggedIn.value = true
                    onSuccess()
                },
                onFailure = { e ->
                    error.value = e.message ?: "Passkey verification failed"
                }
            )
            isLoading.value = false
        }
    }
    
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            isLoggedIn.value = false
            currentUser.value = null
        }
    }
}
