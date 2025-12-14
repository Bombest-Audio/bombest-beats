package com.bombest.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.authDataStore
import com.bombest.music.ui.theme.BombestBeatsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit


// API
data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String, val invite_code: String)
data class AuthResponse(val access_token: String, val user_id: Int, val username: String, val role: String)

interface AuthApiSimple {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse
    
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse
}

class LoginActivity : ComponentActivity() {
    
    private lateinit var authApi: AuthApiSimple
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        
        // Setup API
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
        
        authApi = retrofit.create(AuthApiSimple::class.java)
        
        // Check if already logged in
        lifecycleScope.launch {
            val token = authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
            if (token != null) {
                goToMain()
                return@launch
            }
        }
        
        setContent {
            BombestBeatsTheme {
                AuthScreens(
                    onLogin = { username, password, onResult ->
                        lifecycleScope.launch {
                            try {
                                val response = authApi.login(LoginRequest(username, password))
                                authDataStore.edit { prefs ->
                                    prefs[AuthPreferences.TOKEN_KEY] = response.access_token
                                    prefs[AuthPreferences.USER_KEY] = response.username
                                }
                                onResult(true, null)
                                goToMain()
                            } catch (e: Exception) {
                                onResult(false, e.message ?: "Login failed")
                            }
                        }
                    },
                    onRegister = { username, password, inviteCode, onResult ->
                        lifecycleScope.launch {
                            try {
                                val response = authApi.register(RegisterRequest(username, password, inviteCode))
                                authDataStore.edit { prefs ->
                                    prefs[AuthPreferences.TOKEN_KEY] = response.access_token
                                    prefs[AuthPreferences.USER_KEY] = response.username
                                }
                                onResult(true, null)
                                goToMain()
                            } catch (e: Exception) {
                                onResult(false, e.message ?: "Registration failed")
                            }
                        }
                    }
                )
            }
        }
    }
    
    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
fun AuthScreens(
    onLogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
    onRegister: (String, String, String, (Boolean, String?) -> Unit) -> Unit
) {
    var showRegister by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF15192A), Color(0xFF0A0D14))
                )
            )
    ) {
        if (showRegister) {
            RegisterForm(
                onRegister = onRegister,
                onBackToLogin = { showRegister = false }
            )
        } else {
            LoginForm(
                onLogin = onLogin,
                onGoToRegister = { showRegister = true }
            )
        }
    }
}

@Composable
fun LoginForm(
    onLogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
    onGoToRegister: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "bombest beats",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Sign in to continue",
            fontSize = 16.sp,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFE90060),
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFE90060),
                focusedLabelColor = Color(0xFFE90060),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFE90060),
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFE90060),
                focusedLabelColor = Color(0xFFE90060),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
        
        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it, color = Color(0xFFE90060), fontSize = 14.sp)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                if (username.isNotBlank() && password.isNotBlank()) {
                    isLoading = true
                    error = null
                    onLogin(username, password) { success, msg ->
                        isLoading = false
                        if (!success) error = msg
                    }
                }
            },
            enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE90060),
                disabledContainerColor = Color(0xFF4A4A4A)
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TextButton(onClick = onGoToRegister) {
            Text(
                text = "Don't have an account? Register",
                color = Color(0xFFE90060)
            )
        }
    }
}

@Composable
fun RegisterForm(
    onRegister: (String, String, String, (Boolean, String?) -> Unit) -> Unit,
    onBackToLogin: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Create Account",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Join bombest beats",
            fontSize = 16.sp,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFE90060),
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFE90060),
                focusedLabelColor = Color(0xFFE90060),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFE90060),
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFE90060),
                focusedLabelColor = Color(0xFFE90060),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = { Text("Invite Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFE90060),
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFE90060),
                focusedLabelColor = Color(0xFFE90060),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
        
        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it, color = Color(0xFFE90060), fontSize = 14.sp)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                if (username.isNotBlank() && password.isNotBlank() && inviteCode.isNotBlank()) {
                    isLoading = true
                    error = null
                    onRegister(username, password, inviteCode) { success, msg ->
                        isLoading = false
                        if (!success) error = msg
                    }
                }
            },
            enabled = !isLoading && username.isNotBlank() && password.isNotBlank() && inviteCode.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE90060),
                disabledContainerColor = Color(0xFF4A4A4A)
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Create Account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TextButton(onClick = onBackToLogin) {
            Text(
                text = "Already have an account? Sign In",
                color = Color(0xFFE90060)
            )
        }
    }
}
