package com.bombest.music.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.documentfile.provider.DocumentFile
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.NetworkModule
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
// Cloudflare Proxy Write Timeout is 30s. Batch uploads to stay under it (align with web frontend).
private const val UPLOAD_BATCH_SIZE = 3

private fun collectFilesFromFolder(context: Context, treeUri: Uri): List<Pair<Uri, String>> {
    val list = mutableListOf<Pair<Uri, String>>()
    val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return list
    fun walk(file: DocumentFile, prefix: String) {
        if (file.isFile) {
            list.add(file.uri to (if (prefix.isNotEmpty()) "$prefix/${file.name}" else (file.name ?: "file")))
        } else if (file.isDirectory) {
            file.listFiles().forEach { child ->
                walk(child, if (prefix.isNotEmpty()) "$prefix/${file.name}" else (file.name ?: ""))
            }
        }
    }
    doc.listFiles().forEach { walk(it, "") }
    return list
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }  // for zip display
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var currentFileIndex by remember { mutableStateOf(0) }
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        Log.d(TAG, "File picker returned ${uris.size} files")
        selectedFiles = uris
        selectedFileName = null
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val pairs = collectFilesFromFolder(context, uri)
            selectedFiles = pairs.map { it.first }
            selectedFileName = if (pairs.isNotEmpty()) "Folder (${pairs.size} files)" else null
            Log.d(TAG, "Folder picker returned ${pairs.size} files")
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFiles = listOf(uri)
            selectedFileName = getFileName(context, uri) ?: "archive.zip"
            Log.d(TAG, "Zip picker: $selectedFileName")
        }
    }

    fun uploadFiles() {
        if (selectedFiles.isEmpty()) {
            error = "No files selected"
            return
        }

        val isZip = selectedFiles.size == 1 && (getFileName(context, selectedFiles[0])?.lowercase()?.endsWith(".zip") == true)
        val useFolderUpload = isZip || selectedFiles.size > 1

        scope.launch {
            isUploading = true
            error = null
            uploadMessage = null
            currentFileIndex = 0
            uploadProgress = 0f

            val token = context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
            if (token == null) {
                error = "Not authenticated. Please login again."
                isUploading = false
                return@launch
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val baseUrl = NetworkModule.getStreamBaseUrl()

            if (useFolderUpload) {
                uploadMessage = "Uploading folder/zip..."
                try {
                    val useBatching = !isZip && selectedFiles.size > UPLOAD_BATCH_SIZE
                    var totalImported = 0
                    var totalFailed = 0
                    var totalSkipped = 0

                    if (useBatching) {
                        // Many files: upload in batches to avoid Cloudflare 30s timeout
                        for (batchStart in selectedFiles.indices step UPLOAD_BATCH_SIZE) {
                            val batch = selectedFiles.subList(batchStart, minOf(batchStart + UPLOAD_BATCH_SIZE, selectedFiles.size))
                            uploadMessage = "Uploading batch ${(batchStart / UPLOAD_BATCH_SIZE) + 1}/${(selectedFiles.size + UPLOAD_BATCH_SIZE - 1) / UPLOAD_BATCH_SIZE}..."
                            uploadProgress = batchStart.toFloat() / selectedFiles.size

                            val batchTempFiles = mutableListOf<File>()
                            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                            try {
                                batch.forEachIndexed { idx, uri ->
                                    val globalIdx = batchStart + idx
                                    val fileName = getFileName(context, uri) ?: "file_$globalIdx"
                                    val contentType = context.contentResolver.getType(uri)?.toMediaType() ?: "application/octet-stream".toMediaType()
                                    val stream = context.contentResolver.openInputStream(uri)
                                    if (stream != null) {
                                        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}_$globalIdx")
                                        stream.use { it.copyTo(FileOutputStream(tempFile)) }
                                        batchTempFiles.add(tempFile)
                                        builder.addFormDataPart("files[]", fileName, tempFile.asRequestBody(contentType))
                                    }
                                }

                                val request = Request.Builder()
                                    .url("$baseUrl/upload/folder")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post(builder.build())
                                    .build()
                                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                                val body = response.body?.string() ?: ""
                                Log.d(TAG, "Folder batch upload: ${response.code} - $body")

                                if (response.isSuccessful) {
                                    val json = org.json.JSONObject(body)
                                    totalImported += json.optJSONArray("imported")?.length() ?: 0
                                    totalFailed += json.optJSONArray("failed")?.length() ?: 0
                                    totalSkipped += json.optJSONArray("skipped")?.length() ?: 0
                                } else {
                                    error = org.json.JSONObject(body).optString("error", "Upload failed")
                                    break
                                }
                            } finally {
                                batchTempFiles.forEach { it.delete() }
                            }
                        }
                        uploadMessage = when {
                            error != null -> null
                            totalImported > 0 -> "Imported $totalImported tracks" +
                                (if (totalFailed > 0) ", $totalFailed failed" else "") +
                                (if (totalSkipped > 0) ", $totalSkipped skipped" else "")
                            else -> "No tracks imported"
                        }
                    } else {
                        // Single request: zip or small folder (<= 3 files)
                        val singleTempFiles = mutableListOf<File>()
                        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                        try {
                            if (isZip) {
                                val uri = selectedFiles[0]
                                val fileName = getFileName(context, uri) ?: "upload.zip"
                                val stream = context.contentResolver.openInputStream(uri)
                                if (stream != null) {
                                    val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.zip")
                                    stream.use { it.copyTo(FileOutputStream(tempFile)) }
                                    singleTempFiles.add(tempFile)
                                    builder.addFormDataPart("file", fileName, tempFile.asRequestBody("application/zip".toMediaType()))
                                }
                            } else {
                                selectedFiles.forEachIndexed { idx, uri ->
                                    val fileName = getFileName(context, uri) ?: "file_$idx"
                                    val contentType = context.contentResolver.getType(uri)?.toMediaType() ?: "application/octet-stream".toMediaType()
                                    val stream = context.contentResolver.openInputStream(uri)
                                    if (stream != null) {
                                        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}_$idx")
                                        stream.use { it.copyTo(FileOutputStream(tempFile)) }
                                        singleTempFiles.add(tempFile)
                                        builder.addFormDataPart("files[]", fileName, tempFile.asRequestBody(contentType))
                                    }
                                }
                            }
                            val request = Request.Builder()
                                .url("$baseUrl/upload/folder")
                                .addHeader("Authorization", "Bearer $token")
                                .post(builder.build())
                                .build()
                            withContext(Dispatchers.IO) {
                                val response = client.newCall(request).execute()
                                val body = response.body?.string() ?: ""
                                Log.d(TAG, "Folder upload: ${response.code} - $body")
                                if (response.isSuccessful) {
                                    val imported = org.json.JSONObject(body).optJSONArray("imported")?.length() ?: 0
                                    uploadMessage = "Imported $imported tracks"
                                    selectedFiles = emptyList()
                                    selectedFileName = null
                                } else {
                                    error = org.json.JSONObject(body).optString("error", "Upload failed")
                                }
                            }
                        } finally {
                            singleTempFiles.forEach { it.delete() }
                        }
                    }
                    if (useBatching && error == null) {
                        selectedFiles = emptyList()
                        selectedFileName = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Folder upload error", e)
                    error = e.message ?: "Upload failed"
                }
            } else {
                var successCount = 0
                var failCount = 0
                var lastError = ""
                selectedFiles.forEachIndexed { index, uri ->
                    currentFileIndex = index + 1
                    uploadProgress = (index + 1).toFloat() / selectedFiles.size
                    val fileName = getFileName(context, uri) ?: "audio_${System.currentTimeMillis()}.mp3"
                    val tempFile = File(context.cacheDir, fileName)
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream == null) {
                            failCount++
                            lastError = "Could not read file"
                            return@forEachIndexed
                        }
                        inputStream.use { FileOutputStream(tempFile).use { out -> it.copyTo(out) } }
                        val mediaType = context.contentResolver.getType(uri)?.toMediaType() ?: "audio/mpeg".toMediaType()
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", fileName, tempFile.asRequestBody(mediaType))
                            .build()
                        val request = Request.Builder()
                            .url("$baseUrl/upload")
                            .addHeader("Authorization", "Bearer $token")
                            .post(requestBody)
                            .build()
                        withContext(Dispatchers.IO) {
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) successCount++
                            else {
                                failCount++
                                lastError = when (response.code) {
                                    401 -> "Unauthorized"
                                    403 -> "Admin required"
                                    409 -> "Duplicate"
                                    else -> "Error ${response.code}"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        failCount++
                        lastError = e.message ?: "Unknown error"
                    } finally {
                        tempFile.delete()
                    }
                }
                uploadMessage = when {
                    failCount == 0 && successCount > 0 -> "Uploaded $successCount file(s)!"
                    successCount > 0 -> "Uploaded $successCount, failed $failCount"
                    else -> "Failed: $lastError"
                }
                if (successCount > 0) {
                    selectedFiles = emptyList()
                    selectedFileName = null
                }
            }
            uploadProgress = 1f
            isUploading = false
            if (uploadMessage == null) uploadMessage = "Done"
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
            Spacer(Modifier.height(32.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFFE90060), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = when {
                            selectedFiles.isEmpty() -> "Select files or folder"
                            selectedFileName != null -> selectedFileName!!
                            else -> "${selectedFiles.size} file(s) selected"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("MP3, WAV, FLAC, M4A, ZIP, or folder", fontSize = 14.sp, color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("audio/*", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Files") }
                        Button(
                            onClick = { folderPicker.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Folder") }
                        Button(
                            onClick = { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Zip") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (isUploading) {
                LinearProgressIndicator(
                    progress = { uploadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFE90060),
                    trackColor = Color(0xFF3A3A3A)
                )
                Spacer(Modifier.height(8.dp))
                Text("Uploading...", color = Color.White)
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = Color.Red, textAlign = TextAlign.Center)
            }
            uploadMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    color = if (it.contains("Uploaded") || it.contains("Imported")) Color(0xFF4CAF50) else Color.Yellow,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { uploadFiles() },
                enabled = selectedFiles.isNotEmpty() && !isUploading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE90060),
                    disabledContainerColor = Color(0xFF4A4A4A)
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (selectedFiles.isEmpty()) "Select to Upload" else "Upload ${selectedFiles.size}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            name = cursor.getString(nameIndex)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast("/")
}
