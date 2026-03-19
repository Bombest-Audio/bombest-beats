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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "UploadScreen"
// Cloudflare Proxy Write Timeout is 30s. Batch uploads to stay under it (align with web frontend).
private const val UPLOAD_BATCH_SIZE = 3
private const val S3_UPLOAD_CONCURRENCY = 3
private const val MAX_RETRY_ATTEMPTS = 3
private const val MAX_FILE_SIZE_MB = 500
private const val MAX_FILENAME_LENGTH = 255

private fun collectFilesFromFolder(context: Context, treeUri: Uri): List<Pair<Uri, String>> {
    val list = mutableListOf<Pair<Uri, String>>()
    val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return list
    fun walk(file: DocumentFile, prefix: String) {
        val safeName = file.name ?: ""
        if (file.isFile) {
            list.add(file.uri to (if (prefix.isNotEmpty()) "$prefix/$safeName" else safeName.ifEmpty { "file" }))
        } else if (file.isDirectory) {
            file.listFiles().forEach { child ->
                walk(child, if (prefix.isNotEmpty()) "$prefix/$safeName" else safeName)
            }
        }
    }
    doc.listFiles().forEach { walk(it, "") }
    return list
}

/** Retry an OkHttp call with exponential backoff. */
private suspend fun <T> retryWithBackoff(
    maxRetries: Int = MAX_RETRY_ATTEMPTS,
    label: String = "request",
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    for (attempt in 0 until maxRetries) {
        try {
            return block()
        } catch (e: IOException) {
            lastException = e
            if (attempt < maxRetries - 1) {
                val delay = minOf(1000L * (1 shl attempt), 8000L)
                Log.w(TAG, "$label: attempt ${attempt + 1} failed (${e.message}), retrying in ${delay}ms")
                delay(delay)
            }
        }
    }
    throw lastException ?: IOException("$label failed after $maxRetries attempts")
}

/** Get file size from content resolver. */
private fun getFileSize(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (_: Exception) { 0L }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }  // for zip display
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var currentFileIndex by remember { mutableStateOf(0) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        Log.d(TAG, "File picker returned ${uris.size} files")
        selectedFiles = uris
        selectedFileNames = uris.map { getFileName(context, it) ?: "file" }
        selectedFileName = null
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val pairs = collectFilesFromFolder(context, uri)
            selectedFiles = pairs.map { it.first }
            selectedFileNames = pairs.map { it.second }
            selectedFileName = if (pairs.isNotEmpty()) "Folder (${pairs.size} files)" else null
            Log.d(TAG, "Folder picker returned ${pairs.size} files")
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedFiles = listOf(uri)
            val name = getFileName(context, uri) ?: "archive.zip"
            selectedFileNames = listOf(name)
            selectedFileName = name
            Log.d(TAG, "Zip picker: $selectedFileName")
        }
    }

    fun uploadFiles() {
        if (selectedFiles.isEmpty()) {
            error = "No files selected"
            return
        }

        // Client-side validation
        var totalSize = 0L
        for ((i, uri) in selectedFiles.withIndex()) {
            val size = getFileSize(context, uri)
            totalSize += size
            val name = selectedFileNames.getOrElse(i) { "file" }
            if (name.length > MAX_FILENAME_LENGTH) {
                error = "Filename too long: ${name.take(50)}..."
                return
            }
        }
        if (totalSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
            error = "Total size (${totalSize / 1024 / 1024} MB) exceeds ${MAX_FILE_SIZE_MB} MB limit"
            return
        }

        val isZip = selectedFiles.size == 1 && (selectedFileNames.firstOrNull()?.lowercase()?.endsWith(".zip") == true)
        val useFolderUpload = isZip || selectedFiles.size > 1

        scope.launch {
            isUploading = true
            error = null
            uploadMessage = null
            uploadStatus = null
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
                try {
                    // Try presigned S3 upload first (bypasses Cloudflare limits)
                    val result = tryPresignedUpload(
                        context, client, baseUrl, token,
                        selectedFiles, selectedFileNames, isZip
                    ) { done, total, status ->
                        uploadProgress = done.toFloat() / total
                        uploadStatus = status
                    }

                    if (result != null) {
                        // Presigned upload succeeded
                        val imported = result.optJSONArray("imported")?.length() ?: 0
                        val failed = result.optJSONArray("failed")?.length() ?: 0
                        val skipped = result.optJSONArray("skipped")?.length() ?: 0
                        val s3Warnings = result.optJSONArray("s3_warnings")

                        uploadMessage = buildString {
                            append("Imported $imported tracks")
                            if (failed > 0) append(", $failed failed")
                            if (skipped > 0) append(", $skipped skipped")
                            if (s3Warnings != null && s3Warnings.length() > 0) {
                                append(" (${s3Warnings.length()} S3 sync warnings)")
                            }
                        }
                        selectedFiles = emptyList()
                        selectedFileNames = emptyList()
                        selectedFileName = null
                    } else {
                        // Presigned not available, fall back to direct upload
                        Log.d(TAG, "Presigned upload not available, using direct upload")
                        doDirectFolderUpload(
                            context, client, baseUrl, token,
                            selectedFiles, selectedFileNames, isZip
                        ) { done, total, status ->
                            uploadProgress = done.toFloat() / total
                            uploadStatus = status
                        }.let { (msg, err) ->
                            uploadMessage = msg
                            if (err != null) error = err
                            if (err == null) {
                                selectedFiles = emptyList()
                                selectedFileNames = emptyList()
                                selectedFileName = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Upload error", e)
                    error = e.message ?: "Upload failed"
                }
            } else {
                // Single file upload with retry
                var successCount = 0
                var failCount = 0
                var lastError = ""
                selectedFiles.forEachIndexed { index, uri ->
                    currentFileIndex = index + 1
                    uploadProgress = (index + 1).toFloat() / selectedFiles.size
                    uploadStatus = "Uploading ${index + 1}/${selectedFiles.size}..."
                    val fileName = selectedFileNames.getOrElse(index) { "audio_${System.currentTimeMillis()}.mp3" }
                    val tempFile = File(context.cacheDir, "upload_single_${System.currentTimeMillis()}_$index")
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

                        retryWithBackoff(label = "upload $fileName") {
                            withContext(Dispatchers.IO) {
                                val request = Request.Builder()
                                    .url("$baseUrl/upload")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post(requestBody)
                                    .build()
                                client.newCall(request).execute().use { resp ->
                                    if (resp.isSuccessful) {
                                        successCount++
                                        // Check for S3 warnings
                                        val body = resp.body?.string() ?: ""
                                        try {
                                            val json = JSONObject(body)
                                            val s3Warn = json.optString("s3_warning", "")
                                            if (s3Warn.isNotEmpty()) {
                                                Log.w(TAG, "S3 warning for $fileName: $s3Warn")
                                            }
                                        } catch (_: Exception) {}
                                    } else {
                                        val code = resp.code
                                        if (code >= 500) throw IOException("Server error $code")
                                        failCount++
                                        lastError = when (code) {
                                            401 -> "Unauthorized"
                                            403 -> "Admin required"
                                            409 -> "Duplicate"
                                            else -> "Error $code"
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        failCount++
                        lastError = e.message ?: "Unknown error"
                        Log.e(TAG, "Upload failed for $fileName", e)
                    } finally {
                        if (tempFile.exists() && !tempFile.delete()) {
                            Log.w(TAG, "Failed to delete temp file: ${tempFile.absolutePath}")
                        }
                    }
                }
                uploadMessage = when {
                    failCount == 0 && successCount > 0 -> "Uploaded $successCount file(s)!"
                    successCount > 0 -> "Uploaded $successCount, failed $failCount"
                    else -> "Failed: $lastError"
                }
                if (successCount > 0) {
                    selectedFiles = emptyList()
                    selectedFileNames = emptyList()
                    selectedFileName = null
                }
            }
            uploadProgress = 1f
            uploadStatus = null
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
                Text(uploadStatus ?: "Uploading...", color = Color.White, fontSize = 14.sp)
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

// --- Presigned S3 Upload (new) ---

/**
 * Try presigned S3 upload. Returns JSONObject result on success, null if S3 not configured (501).
 * Throws on actual errors.
 */
private suspend fun tryPresignedUpload(
    context: Context,
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    files: List<Uri>,
    fileNames: List<String>,
    isZip: Boolean,
    onProgress: (done: Int, total: Int, status: String) -> Unit
): JSONObject? {
    val total = files.size
    onProgress(0, total, "Preparing upload...")

    // Step 1: Get presigned URLs
    val presignBody = JSONObject().apply {
        put("filenames", JSONArray(fileNames))
    }

    val presignResp = retryWithBackoff(label = "presign") {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/upload/presign")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(presignBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute()
        }
    }

    val presignRespBody = presignResp.use { it.body?.string() ?: "" }

    if (presignResp.code == 501) {
        return null // S3 not configured, caller should fall back to direct upload
    }
    if (presignResp.code !in 200..299) {
        val errMsg = try { JSONObject(presignRespBody).optString("error", "Presign failed") } catch (_: Exception) { "Presign failed (${presignResp.code})" }
        throw IOException(errMsg)
    }

    val presignJson = JSONObject(presignRespBody)
    val sessionId = presignJson.getString("session_id")
    val urls = presignJson.getJSONArray("urls")

    // Step 2: Upload files to S3 with concurrency and retry
    val uploadedKeys = mutableListOf<String>()
    var completed = 0

    // Use coroutine-based concurrency
    val semaphore = kotlinx.coroutines.sync.Semaphore(S3_UPLOAD_CONCURRENCY)

    coroutineScope {
        val jobs = (0 until urls.length()).map { i ->
            val urlInfo = urls.getJSONObject(i)
            val presignedUrl = urlInfo.getString("url")
            val key = urlInfo.getString("key")
            val filename = urlInfo.getString("filename")
            val fileUri = files[i]

            async(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    // Copy file to temp
                    val tempFile = File(context.cacheDir, "s3_upload_${System.currentTimeMillis()}_$i")
                    try {
                        context.contentResolver.openInputStream(fileUri)?.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        } ?: throw IOException("Cannot read file: $filename")

                        val contentType = context.contentResolver.getType(fileUri) ?: "application/octet-stream"

                        retryWithBackoff(label = "S3 upload: $filename") {
                            val putRequest = Request.Builder()
                                .url(presignedUrl)
                                .put(tempFile.asRequestBody(contentType.toMediaType()))
                                .build()
                            client.newCall(putRequest).execute().use { resp ->
                                if (!resp.isSuccessful) {
                                    throw IOException("S3 PUT failed for $filename: ${resp.code}")
                                }
                            }
                        }

                        synchronized(uploadedKeys) {
                            uploadedKeys.add(key)
                            completed++
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(completed, total, "Uploading $completed/$total...")
                        }
                    } finally {
                        if (tempFile.exists() && !tempFile.delete()) {
                            Log.w(TAG, "Failed to delete S3 temp file: ${tempFile.absolutePath}")
                        }
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
        jobs.awaitAll()
    }

    if (uploadedKeys.isEmpty()) {
        throw IOException("All file uploads to S3 failed")
    }

    // Step 3: Tell backend to process
    onProgress(total, total, "Processing...")
    val processBody = JSONObject().apply {
        put("session_id", sessionId)
        put("keys", JSONArray(uploadedKeys))
    }

    val processResp = retryWithBackoff(label = "process") {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/upload/process")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(processBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute()
        }
    }

    val processRespBody = processResp.use { it.body?.string() ?: "" }
    if (processResp.code !in 200..299) {
        val errMsg = try { JSONObject(processRespBody).optString("error", "Processing failed") } catch (_: Exception) { "Processing failed (${processResp.code})" }
        throw IOException(errMsg)
    }

    return JSONObject(processRespBody)
}

// --- Direct upload (fallback) ---

/**
 * Perform direct multipart upload. Returns (message, error).
 */
private suspend fun doDirectFolderUpload(
    context: Context,
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    files: List<Uri>,
    fileNames: List<String>,
    isZip: Boolean,
    onProgress: (done: Int, total: Int, status: String) -> Unit
): Pair<String?, String?> {
    var totalImported = 0
    var totalFailed = 0
    var totalSkipped = 0
    val total = files.size
    var lastError: String? = null

    val useBatching = !isZip && files.size > UPLOAD_BATCH_SIZE

    if (useBatching) {
        for (batchStart in files.indices step UPLOAD_BATCH_SIZE) {
            val batchEnd = minOf(batchStart + UPLOAD_BATCH_SIZE, files.size)
            val batchNum = (batchStart / UPLOAD_BATCH_SIZE) + 1
            val totalBatches = (files.size + UPLOAD_BATCH_SIZE - 1) / UPLOAD_BATCH_SIZE
            onProgress(batchStart, total, "Batch $batchNum/$totalBatches...")

            val batchTempFiles = mutableListOf<File>()
            try {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                for (idx in batchStart until batchEnd) {
                    val uri = files[idx]
                    val fileName = fileNames.getOrElse(idx) { "file_$idx" }
                    val contentType = context.contentResolver.getType(uri)?.toMediaType() ?: "application/octet-stream".toMediaType()
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val tempFile = File(context.cacheDir, "batch_upload_${System.currentTimeMillis()}_$idx")
                        stream.use { it.copyTo(FileOutputStream(tempFile)) }
                        batchTempFiles.add(tempFile)
                        builder.addFormDataPart("files[]", fileName, tempFile.asRequestBody(contentType))
                    }
                }

                val (code, body) = retryWithBackoff(label = "batch $batchNum") {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url("$baseUrl/upload/folder")
                            .addHeader("Authorization", "Bearer $token")
                            .post(builder.build())
                            .build()
                        client.newCall(request).execute().use { resp ->
                            resp.code to (resp.body?.string() ?: "")
                        }
                    }
                }

                Log.d(TAG, "Folder batch upload: $code - $body")

                if (code in 200..299) {
                    val json = JSONObject(body)
                    totalImported += json.optJSONArray("imported")?.length() ?: 0
                    totalFailed += json.optJSONArray("failed")?.length() ?: 0
                    totalSkipped += json.optJSONArray("skipped")?.length() ?: 0
                } else {
                    lastError = try { JSONObject(body).optString("error", "Upload failed") } catch (_: Exception) { "Upload failed ($code)" }
                    break
                }
            } finally {
                batchTempFiles.forEach { file ->
                    if (file.exists() && !file.delete()) {
                        Log.w(TAG, "Failed to delete batch temp file: ${file.absolutePath}")
                    }
                }
            }
        }
    } else {
        // Single request: zip or small folder (<= 3 files)
        val singleTempFiles = mutableListOf<File>()
        try {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            if (isZip) {
                val uri = files[0]
                val fileName = fileNames.firstOrNull() ?: "upload.zip"
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    val tempFile = File(context.cacheDir, "upload_zip_${System.currentTimeMillis()}.zip")
                    stream.use { it.copyTo(FileOutputStream(tempFile)) }
                    singleTempFiles.add(tempFile)
                    builder.addFormDataPart("file", fileName, tempFile.asRequestBody("application/zip".toMediaType()))
                }
            } else {
                files.forEachIndexed { idx, uri ->
                    val fileName = fileNames.getOrElse(idx) { "file_$idx" }
                    val contentType = context.contentResolver.getType(uri)?.toMediaType() ?: "application/octet-stream".toMediaType()
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val tempFile = File(context.cacheDir, "upload_single_${System.currentTimeMillis()}_$idx")
                        stream.use { it.copyTo(FileOutputStream(tempFile)) }
                        singleTempFiles.add(tempFile)
                        builder.addFormDataPart("files[]", fileName, tempFile.asRequestBody(contentType))
                    }
                }
            }

            onProgress(0, total, "Uploading...")

            val (code, body) = retryWithBackoff(label = "folder upload") {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$baseUrl/upload/folder")
                        .addHeader("Authorization", "Bearer $token")
                        .post(builder.build())
                        .build()
                    client.newCall(request).execute().use { resp ->
                        resp.code to (resp.body?.string() ?: "")
                    }
                }
            }

            Log.d(TAG, "Folder upload: $code - $body")
            if (code in 200..299) {
                val json = JSONObject(body)
                totalImported = json.optJSONArray("imported")?.length() ?: 0
                totalFailed = json.optJSONArray("failed")?.length() ?: 0
                totalSkipped = json.optJSONArray("skipped")?.length() ?: 0
            } else {
                lastError = try { JSONObject(body).optString("error", "Upload failed") } catch (_: Exception) { "Upload failed ($code)" }
            }
        } finally {
            singleTempFiles.forEach { file ->
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete temp file: ${file.absolutePath}")
                }
            }
        }
    }

    val message = when {
        lastError != null -> null
        totalImported > 0 -> buildString {
            append("Imported $totalImported tracks")
            if (totalFailed > 0) append(", $totalFailed failed")
            if (totalSkipped > 0) append(", $totalSkipped skipped")
        }
        else -> "No tracks imported"
    }

    return message to lastError
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
