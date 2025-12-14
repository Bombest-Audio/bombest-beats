package com.bombest.music.ui.screens

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.authDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "UploadScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var currentFileIndex by remember { mutableStateOf(0) }
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Toggle for direct upload via Tailscale (bypasses Cloudflare timeout)
    var useDirectUpload by remember { mutableStateOf(true) }
    
    // Accept all files from picker - don't filter by mime type (can be unreliable)
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        Log.d(TAG, "File picker returned ${uris.size} files")
        selectedFiles = uris
        uris.forEachIndexed { i, uri ->
            Log.d(TAG, "File $i: $uri, mimeType=${context.contentResolver.getType(uri)}")
        }
    }
    
    fun uploadFiles() {
        if (selectedFiles.isEmpty()) {
            error = "No files selected"
            return
        }
        
        scope.launch {
            isUploading = true
            error = null
            uploadMessage = null
            currentFileIndex = 0
            uploadProgress = 0f
            
            Log.d(TAG, "Starting upload of ${selectedFiles.size} files")
            
            val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
            if (token == null) {
                Log.e(TAG, "No auth token found")
                error = "Not authenticated. Please login again."
                isUploading = false
                return@launch
            }
            Log.d(TAG, "Got auth token: ${token.take(10)}...")
            
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            
            var successCount = 0
            var failCount = 0
            var lastError = ""
            
            selectedFiles.forEachIndexed { index, uri ->
                currentFileIndex = index + 1
                uploadProgress = (index + 1).toFloat() / selectedFiles.size
                Log.d(TAG, "Uploading file ${index + 1}/${selectedFiles.size}: $uri")
                
                try {
                    // Copy URI content to temp file
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        Log.e(TAG, "Could not open input stream for $uri")
                        failCount++
                        lastError = "Could not read file"
                        return@forEachIndexed
                    }
                    
                    // Get filename from URI
                    val fileName = getFileName(context, uri) ?: "audio_${System.currentTimeMillis()}.mp3"
                    Log.d(TAG, "Filename: $fileName")
                    
                    val tempFile = File(context.cacheDir, fileName)
                    
                    inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copied to temp file: ${tempFile.length()} bytes")
                    
                    // Build multipart request
                    val mediaType = context.contentResolver.getType(uri)?.toMediaType() 
                        ?: "audio/mpeg".toMediaType()
                    
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", fileName, tempFile.asRequestBody(mediaType))
                        .build()
                    
                    // Use direct Tailscale URL for large files (bypasses Cloudflare 100s timeout)
                    val uploadUrl = if (useDirectUpload) {
                        "http://100.69.137.108:8338/upload"
                    } else {
                        "https://bom.best/beats/api/upload"
                    }
                    Log.d(TAG, "Using upload URL: $uploadUrl (direct=$useDirectUpload)")
                    
                    val request = Request.Builder()
                        .url(uploadUrl)
                        .addHeader("Authorization", "Bearer $token")
                        .post(requestBody)
                        .build()
                    
                    Log.d(TAG, "Sending upload request...")
                    
                    withContext(Dispatchers.IO) {
                        val response = client.newCall(request).execute()
                        val responseBody = response.body?.string() ?: ""
                        Log.d(TAG, "Response: ${response.code} - $responseBody")
                        
                        if (response.isSuccessful) {
                            successCount++
                        } else {
                            failCount++
                            lastError = when (response.code) {
                                401 -> "Unauthorized - please login again"
                                403 -> "Admin access required"
                                409 -> "Duplicate: file already exists"
                                524 -> "Upload timeout - file too large"
                                else -> "Error ${response.code}"
                            }
                        }
                        response.close()
                    }
                    
                    // Clean up temp file
                    tempFile.delete()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Upload failed for $uri", e)
                    failCount++
                    lastError = e.message ?: "Unknown error"
                }
            }
            
            uploadProgress = 1f
            isUploading = false
            
            Log.d(TAG, "Upload complete: $successCount success, $failCount failed")
            
            uploadMessage = when {
                failCount == 0 && successCount > 0 -> "Successfully uploaded $successCount file(s)!"
                successCount == 0 && failCount > 0 -> "Failed: $lastError"
                successCount > 0 -> "Uploaded $successCount, failed $failCount ($lastError)"
                else -> "No files uploaded"
            }
            
            if (successCount > 0) {
                selectedFiles = emptyList()
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload Music", fontWeight = FontWeight.Bold) },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // File picker card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Color(0xFFE90060),
                        modifier = Modifier.size(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = if (selectedFiles.isEmpty()) "Select Audio Files" 
                               else "${selectedFiles.size} file(s) selected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Supports MP3, WAV, FLAC, M4A",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { 
                            filePicker.launch(arrayOf("audio/*", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Browse Files")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Direct upload toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Direct Upload (Tailscale)",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (useDirectUpload) "For large files" else "Via Cloudflare",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = useDirectUpload,
                    onCheckedChange = { useDirectUpload = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFE90060)
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Upload progress
            if (isUploading) {
                LinearProgressIndicator(
                    progress = uploadProgress,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFE90060),
                    trackColor = Color(0xFF3A3A3A)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Uploading file $currentFileIndex of ${selectedFiles.size}...",
                    color = Color.White
                )
            }
            
            // Messages
            error?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = it, color = Color.Red, textAlign = TextAlign.Center)
            }
            
            uploadMessage?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = it, 
                    color = if (it.contains("Successfully")) Color(0xFF4CAF50) else Color.Yellow,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Upload button
            Button(
                onClick = { uploadFiles() },
                enabled = selectedFiles.isNotEmpty() && !isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE90060),
                    disabledContainerColor = Color(0xFF4A4A4A)
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (selectedFiles.isEmpty()) "Select Files to Upload"
                               else "Upload ${selectedFiles.size} File(s)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            name = cursor.getString(nameIndex)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast("/")
}
