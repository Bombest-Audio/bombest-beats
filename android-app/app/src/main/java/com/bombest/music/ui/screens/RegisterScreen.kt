package com.bombest.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RegisterScreen(
    isLoading: Boolean,
    error: String?,
    onRegister: (username: String, password: String, inviteCode: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF15192A)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E2235)
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Bombest Beats",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF7A00)
                )
                
                Text(
                    text = "Create Account",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
                
                // Error message
                if (error != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE90060).copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = error,
                            color = Color(0xFFFF6B8A),
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Username field
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Invite code field
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it },
                    label = { Text("Invite Code") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { 
                            focusManager.clearFocus()
                            if (username.isNotBlank() && password.isNotBlank() && inviteCode.isNotBlank()) {
                                onRegister(username, password, inviteCode)
                            }
                        }
                    )
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Register button
                Button(
                    onClick = { onRegister(username, password, inviteCode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = !isLoading && username.isNotBlank() && password.isNotBlank() && inviteCode.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFFF7A00), Color(0xFFE90060))
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White
                            )
                        } else {
                            Text("Register", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Login link
                TextButton(onClick = onNavigateToLogin) {
                    Text(
                        text = "Already have an account? Login",
                        color = Color(0xFFE90060)
                    )
                }
            }
        }
    }
}
