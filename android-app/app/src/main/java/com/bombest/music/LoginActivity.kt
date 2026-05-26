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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.authDataStore
import com.bombest.music.ui.theme.BombestBeatsTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.view.animation.AccelerateInterpolator
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
import com.bombest.music.data.PasskeyManager
import com.bombest.music.data.repository.AuthRepository
import com.bombest.music.data.NetworkModule
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint


// API
// API classes imported from com.bombest.music.data.api

class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen with exit animation - must be called before super.onCreate()
        val splashScreen = installSplashScreen()
        
        // Keep splash visible while checking auth
        var isCheckingAuth = true
        splashScreen.setKeepOnScreenCondition { isCheckingAuth }
        
        // Animate splash exit with fade + zoom using safer ViewPropertyAnimator
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            try {
                splashScreenView.view.animate()
                    .alpha(0f)
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(300)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        try {
                            splashScreenView.remove()
                        } catch (e: Exception) {
                            // Already removed, ignore
                        }
                    }
                    .start()
            } catch (e: Exception) {
                // If animation fails, just remove the splash
                try { splashScreenView.remove() } catch (e2: Exception) {}
            }
        }
        
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        
        // AuthRepository owns login/register/passkey persistence so all three
        // auth paths share the same DataStore-write semantics (see
        // AuthRepository.saveAuth).
        val authRepository = AuthRepository(this, NetworkModule.authApi)
        val passkeyManager = PasskeyManager(this, authRepository)
        
        setContent {
            // Track auth checking state in Compose
            var isCheckingAuthState by remember { mutableStateOf(true) }
            
            BombestBeatsTheme {
                // Check if already logged in
                LaunchedEffect(Unit) {
                    val token = authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                    if (token != null) {
                        goToMain()
                    } else {
                        // Allow splash to dismiss and show login
                        isCheckingAuth = false
                        isCheckingAuthState = false
                    }
                }
                
                // Only show auth screens after splash dismisses
                if (!isCheckingAuthState) {
                    AuthScreens(
                        onLogin = { username, password, onResult ->
                            // Route through AuthRepository.login so the refresh-token
                            // persistence (including the null → remove clear) lives in
                            // exactly one place — AuthRepository.saveAuth — alongside
                            // the passkey-login flow. Earlier rounds had the persistence
                            // duplicated inline here and could drift from saveAuth's
                            // semantics.
                            lifecycleScope.launch {
                                authRepository.login(username, password)
                                    .onSuccess {
                                        onResult(true, null)
                                        goToMain()
                                    }
                                    .onFailure { e ->
                                        onResult(false, e.message ?: "Login failed")
                                    }
                            }
                        },
                        onRegister = { username, password, inviteCode, onResult ->
                            // Same delegation as login — AuthRepository.register routes
                            // through saveAuth, which captures or clears refresh_token
                            // atomically with the other auth fields.
                            lifecycleScope.launch {
                                authRepository.register(username, password, inviteCode)
                                    .onSuccess {
                                        onResult(true, null)
                                        goToMain()
                                    }
                                    .onFailure { e ->
                                        onResult(false, e.message ?: "Registration failed")
                                    }
                            }
                        },
                        onPasskeyLogin = { onResult ->
                            lifecycleScope.launch {
                                val result = passkeyManager.loginWithPasskey()
                                if (result.isSuccess) {
                                    onResult(true, null)
                                    goToMain()
                                } else {
                                    val error = result.exceptionOrNull()?.message ?: "Passkey login failed"
                                    onResult(false, error)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
    
    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
fun SplashScreen() {
    var rotation by remember { mutableStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = rotation,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // Pulsing scale animation
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    LaunchedEffect(Unit) {
        while (true) {
            rotation += 360f
            kotlinx.coroutines.delay(1500)
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1A1F35), Color(0xFF0A0D14)),
                    center = Offset(0.5f, 0.5f),
                    radius = 1000f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated logo/icon area
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
                contentAlignment = Alignment.Center
            ) {
                // Outer glow ring
                Canvas(modifier = Modifier.size(120.dp)) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color(0xFFE90060),
                                Color(0xFFF470FF),
                                Color(0xFFFF6B35),
                                Color(0xFFE90060)
                            )
                        ),
                        radius = size.minDimension / 2,
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
                
                // Inner icon
                Text(
                    text = "🎵",
                    fontSize = 48.sp
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "bombest beats",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = alpha)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Loading...",
                fontSize = 14.sp,
                color = Color(0xFFF470FF).copy(alpha = alpha * 0.8f)
            )
        }
    }
}

@Composable
fun AuthScreens(
    onLogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
    onRegister: (String, String, String, (Boolean, String?) -> Unit) -> Unit,
    onPasskeyLogin: ((Boolean, String?) -> Unit) -> Unit = {}
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
                onGoToRegister = { showRegister = true },
                onPasskeyLogin = onPasskeyLogin
            )
        }
    }
}

@Composable
fun LoginForm(
    onLogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
    onGoToRegister: () -> Unit,
    onPasskeyLogin: ((Boolean, String?) -> Unit) -> Unit = {}
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isPasskeyLoading by remember { mutableStateOf(false) }
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
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Divider with "or"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Divider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
            Text(
                text = "  or  ",
                color = Color.Gray,
                fontSize = 14.sp
            )
            Divider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Passkey login button
        OutlinedButton(
            onClick = {
                isPasskeyLoading = true
                error = null
                onPasskeyLogin { success, msg ->
                    isPasskeyLoading = false
                    if (!success) error = msg
                }
            },
            enabled = !isLoading && !isPasskeyLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.linearGradient(listOf(Color(0xFFE90060), Color(0xFFE90060)))
            )
        ) {
            if (isPasskeyLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFFE90060),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = Color(0xFFE90060),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign in with Passkey", fontSize = 16.sp, color = Color(0xFFE90060))
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
