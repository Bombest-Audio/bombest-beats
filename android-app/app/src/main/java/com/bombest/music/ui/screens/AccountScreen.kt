package com.bombest.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.authDataStore
import com.bombest.music.data.api.ChangePasswordRequest
import com.bombest.music.data.api.PasskeyInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Format bytes as human-readable string (e.g., "1.2 GB")
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onRegisterPasskey: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showChangePassword by remember { mutableStateOf(false) }
    var showManagePasskeys by remember { mutableStateOf(false) }
    var passkeys by remember { mutableStateOf<List<PasskeyInfo>>(emptyList()) }
    var passkeysError by remember { mutableStateOf<String?>(null) }
    var isLoadingPasskeys by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF15192A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0D14)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Security",
                color = Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
            
            AccountMenuItem(
                icon = Icons.Default.Lock,
                title = "Change Password",
                subtitle = "Update your account password",
                onClick = { showChangePassword = true }
            )
            
            AccountMenuItem(
                icon = Icons.Default.Fingerprint,
                title = "Register Passkey",
                subtitle = "Add a new passkey for quick login",
                onClick = onRegisterPasskey
            )
            
            AccountMenuItem(
                icon = Icons.Default.Key,
                title = "Manage Passkeys",
                subtitle = "View and delete registered passkeys",
                onClick = {
                    showManagePasskeys = true
                    isLoadingPasskeys = true
                    scope.launch {
                        isLoadingPasskeys = true
                        passkeysError = null
                        try {
                            val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                            if (token != null) {
                                passkeys = NetworkModule.authApi.listPasskeys("Bearer $token")
                            }
                        } catch (e: Exception) {
                            passkeysError = e.message ?: "Failed to load passkeys"
                            android.util.Log.e("AccountScreen", "Error loading passkeys", e)
                        } finally {
                            isLoadingPasskeys = false
                        }
                    }
                }
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                text = "Downloads",
                color = Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
            
            // Download state
            var isAutoSyncEnabled by remember { 
                mutableStateOf(
                    context.getSharedPreferences("download_prefs", android.content.Context.MODE_PRIVATE)
                        .getBoolean("auto_sync_enabled", false)
                )
            }
            var downloadProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
            var downloadedCount by remember { mutableIntStateOf(0) }
            var storageUsed by remember {
                mutableStateOf(com.bombest.music.data.DownloadManager.getInstance(context).getDownloadsSizeBytes())
            }
            
            // Auto-Sync Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1F2E), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (isAutoSyncEnabled) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download All Tracks",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (downloadProgress != null) {
                            "Downloading ${downloadProgress!!.first}/${downloadProgress!!.second}..."
                        } else if (isAutoSyncEnabled) {
                            "$downloadedCount tracks synced"
                        } else {
                            "Sync library for offline playback"
                        },
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = isAutoSyncEnabled,
                    onCheckedChange = { enabled ->
                        isAutoSyncEnabled = enabled
                        context.getSharedPreferences("download_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("auto_sync_enabled", enabled).apply()
                        
                        if (enabled) {
                            // Start background download
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                                    if (token != null) {
                                        val library = NetworkModule.api.getLibrary()
                                        val tracks = library.items
                                        val baseUrl = NetworkModule.getStreamBaseUrl()
                                        
                                        com.bombest.music.data.DownloadManager.getInstance(context)
                                            .downloadAllTracks(
                                                tracks = tracks,
                                                getStreamUrl = { trackId -> "$baseUrl/stream/$trackId" },
                                                onProgress = { downloaded, total ->
                                                    downloadProgress = downloaded to total
                                                }
                                            )
                                        
                                        downloadProgress = null
                                        downloadedCount = tracks.size
                                        storageUsed = com.bombest.music.data.DownloadManager.getInstance(context).getDownloadsSizeBytes()
                                        
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(context, "All ${tracks.size} tracks downloaded!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    downloadProgress = null
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF3A3A3A)
                    )
                )
            }
            
            AccountMenuItem(
                icon = Icons.Default.FolderOpen,
                title = "Storage Used",
                subtitle = formatBytes(storageUsed),
                onClick = { }
            )
            
            AccountMenuItem(
                icon = Icons.Default.DeleteForever,
                title = "Clear Downloads", 
                subtitle = "Remove all downloaded tracks",
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.bombest.music.data.DownloadManager.getInstance(context).clearAllDownloads()
                        downloadedCount = 0
                        storageUsed = 0L
                        isAutoSyncEnabled = false
                        context.getSharedPreferences("download_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("auto_sync_enabled", false).apply()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Downloads cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
    
    // Change Password Dialog
    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onConfirm = { currentPassword, newPassword ->
                scope.launch {
                    try {
                        val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                        if (token != null) {
                            val response = NetworkModule.authApi.changePassword(
                                "Bearer $token",
                                ChangePasswordRequest(currentPassword, newPassword)
                            )
                            if (response.success) {
                                Toast.makeText(context, "Password changed successfully", Toast.LENGTH_SHORT).show()
                                showChangePassword = false
                            } else {
                                Toast.makeText(context, response.error ?: "Failed to change password", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Error changing password", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
    
    // Manage Passkeys Dialog
    if (showManagePasskeys) {
        ManagePasskeysDialog(
            passkeys = passkeys,
            isLoading = isLoadingPasskeys,
            error = passkeysError,
            onDismiss = { showManagePasskeys = false },
            onDelete = { passkeyId ->
                scope.launch {
                    try {
                        val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
                        if (token != null) {
                            val response = NetworkModule.authApi.deletePasskey("Bearer $token", passkeyId)
                            if (response.success) {
                                passkeys = passkeys.filter { it.id != passkeyId }
                                Toast.makeText(context, "Passkey deleted", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, response.error ?: "Failed to delete", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Error deleting passkey", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
fun AccountMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFE90060),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it; error = null },
                    label = { Text("Current Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    )
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; error = null },
                    label = { Text("New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    )
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null },
                    label = { Text("Confirm New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE90060),
                        cursorColor = Color(0xFFE90060)
                    )
                )
                error?.let {
                    Text(text = it, color = Color.Red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    currentPassword.isBlank() -> error = "Enter current password"
                    newPassword.length < 6 -> error = "New password must be at least 6 characters"
                    newPassword != confirmPassword -> error = "Passwords don't match"
                    else -> onConfirm(currentPassword, newPassword)
                }
            }) {
                Text("Change", color = Color(0xFFE90060))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1A1A2E)
    )
}

@Composable
fun ManagePasskeysDialog(
    passkeys: List<PasskeyInfo>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: (Int) -> Unit
) {
    var passkeyToDelete by remember { mutableStateOf<PasskeyInfo?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Passkeys", color = Color.White) },
        text = {
            Box(modifier = Modifier.heightIn(min = 100.dp, max = 300.dp)) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xFFE90060)
                        )
                    }
                    passkeys.isEmpty() -> {
                            Text(
                            text = "No passkeys registered",
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    error != null -> {
                        Text(
                            text = error,
                            color = Color.Red,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    else -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(passkeys) { passkey ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF252535))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Passkey ${passkey.id}",
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium
                                            )
                                            passkey.created_at?.let {
                                                Text(
                                                    text = it.take(10), // Just the date
                                                    color = Color.Gray,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                        IconButton(onClick = { passkeyToDelete = passkey }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = Color(0xFFE90060)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFFE90060))
            }
        },
        containerColor = Color(0xFF1A1A2E)
    )
    
    // Delete confirmation
    passkeyToDelete?.let { passkey ->
        AlertDialog(
            onDismissRequest = { passkeyToDelete = null },
            title = { Text("Delete Passkey?", color = Color.White) },
            text = { Text("This passkey will no longer work for login.", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(passkey.id)
                    passkeyToDelete = null
                }) {
                    Text("Delete", color = Color(0xFFE90060))
                }
            },
            dismissButton = {
                TextButton(onClick = { passkeyToDelete = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A2E)
        )
    }
}
