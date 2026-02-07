package com.bombest.musify.data.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bombest.musify.data.s3.S3Repository
import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED
}

data class DownloadTask(
    val track: SearchResult.TrackSearchResult,
    val downloadUrl: String,
    val filename: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val cancelToken: Boolean = false
) {
    val progress: Float
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
}

data class DownloadMetadata(
    val trackId: String,
    val filename: String,
    val s3Key: String,
    val downloadDate: Long,
    val fileSize: Long
)

@Singleton
class DownloadManager @Inject constructor(
    private val context: Context,
    private val s3Repository: S3Repository,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val PREFS_NAME = "download_metadata"
        private const val KEY_DOWNLOADS = "downloads"
    }

    private val _downloadQueue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadTask>> = _downloadQueue.asStateFlow()

    private val downloadDir: File by lazy {
        File(context.getExternalFilesDir(null), "downloads").apply {
            if (!exists()) mkdirs()
        }
    }

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // In-memory cache of persisted downloads
    private val persistedDownloads = mutableMapOf<String, DownloadMetadata>()

    init {
        // Load persisted downloads on startup
        loadPersistedDownloads()
        // Scan download directory to rebuild state
        scanDownloadDirectory()
    }
    
    /**
     * Add a track to the download queue. Uses track's stream URL (backend or S3).
     */
    fun addToQueue(track: SearchResult.TrackSearchResult) {
        val url = track.trackUrlString ?: return
        val existingTask = _downloadQueue.value.find { it.track.id == track.id }
        if (existingTask != null) {
            Log.d(TAG, "Track ${track.id} already in queue")
            return
        }
        val filename = buildDownloadFilename(track)
        val task = DownloadTask(
            track = track,
            downloadUrl = url,
            filename = filename,
            status = DownloadStatus.QUEUED,
            totalBytes = 0
        )
        _downloadQueue.value = _downloadQueue.value + task
        Log.d(TAG, "Added ${track.name} to download queue")
        if (_downloadQueue.value.none { it.status == DownloadStatus.DOWNLOADING }) {
            startNextDownload()
        }
    }

    private fun buildDownloadFilename(track: SearchResult.TrackSearchResult): String {
        val safeName = track.name.replace(Regex("[<>:\"|?*]"), "_").trim()
        return if (safeName.isNotEmpty()) "${safeName}.mp3" else "track_${track.id}.mp3"
    }

    /**
     * Add multiple tracks to the download queue
     */
    fun addAllToQueue(tracks: List<SearchResult.TrackSearchResult>) {
        val newTasks = tracks.mapNotNull { track ->
            val url = track.trackUrlString ?: return@mapNotNull null
            val existingTask = _downloadQueue.value.find { it.track.id == track.id }
            if (existingTask != null) {
                Log.d(TAG, "Track ${track.id} already in queue, skipping")
                null
            } else {
                DownloadTask(
                    track = track,
                    downloadUrl = url,
                    filename = buildDownloadFilename(track),
                    status = DownloadStatus.QUEUED,
                    totalBytes = 0
                )
            }
        }
        if (newTasks.isNotEmpty()) {
            _downloadQueue.value = _downloadQueue.value + newTasks
            Log.d(TAG, "Added ${newTasks.size} tracks to download queue")
            if (_downloadQueue.value.none { it.status == DownloadStatus.DOWNLOADING }) {
                startNextDownload()
            }
        }
    }

    /**
     * Cancel a download
     */
    fun cancel(track: SearchResult.TrackSearchResult) {
        _downloadQueue.value = _downloadQueue.value.map { task ->
            if (task.track.id == track.id) {
                task.copy(status = DownloadStatus.CANCELED, cancelToken = true)
            } else {
                task
            }
        }
        Log.d(TAG, "Canceled download for ${track.name}")
    }

    /**
     * Retry a failed download
     */
    fun retry(track: SearchResult.TrackSearchResult) {
        _downloadQueue.value = _downloadQueue.value.filter { it.track.id != track.id }
        addToQueue(track)
    }

    /**
     * Cancel all queued downloads
     */
    fun cancelQueuedOnly() {
        _downloadQueue.value = _downloadQueue.value.map { task ->
            if (task.status == DownloadStatus.QUEUED) {
                task.copy(status = DownloadStatus.CANCELED)
            } else {
                task
            }
        }
    }

    /**
     * Get download status for a track
     */
    fun getDownloadStatus(trackId: String): DownloadStatus? {
        return _downloadQueue.value.find { it.track.id == trackId }?.status
    }

    /**
     * Check if a track is downloaded
     */
    fun isDownloaded(trackId: String): Boolean {
        val queuedTask = _downloadQueue.value.find { it.track.id == trackId }
        if (queuedTask != null && queuedTask.status == DownloadStatus.COMPLETED) {
            val file = File(downloadDir, sanitizeFilename(queuedTask.filename))
            if (file.exists() && file.length() > 0) return true
        }
        val metadata = persistedDownloads[trackId]
        if (metadata != null) {
            val file = File(downloadDir, sanitizeFilename(metadata.filename))
            if (file.exists() && file.length() > 0) return true
        }
        return false
    }

    /**
     * Check if a track is downloaded by filename key (e.g. from S3 key)
     */
    fun isDownloadedByKey(s3Key: String): Boolean {
        val filename = s3Key.split('/').last()
        val file = File(downloadDir, sanitizeFilename(filename))
        return file.exists() && file.length() > 0
    }

    /**
     * Get downloaded file path
     */
    fun getDownloadedFilePath(trackId: String): String? {
        val queuedTask = _downloadQueue.value.find { it.track.id == trackId }
        if (queuedTask != null) {
            val file = File(downloadDir, sanitizeFilename(queuedTask.filename))
            if (file.exists()) return file.absolutePath
        }
        val metadata = persistedDownloads[trackId]
        if (metadata != null) {
            val file = File(downloadDir, sanitizeFilename(metadata.filename))
            if (file.exists()) return file.absolutePath
        }
        return null
    }
    
    /**
     * Get all downloaded track IDs
     */
    fun getAllDownloadedTrackIds(): Set<String> {
        return persistedDownloads.keys.toSet()
    }

    /**
     * Get local file URL for a track (for use in ExoPlayer)
     * Returns file:// URL if downloaded, null otherwise
     */
    fun getLocalFileUrl(trackId: String): String? {
        val filePath = getDownloadedFilePath(trackId) ?: return null
        return "file://$filePath"
    }

    private fun startNextDownload() {
        val queuedTask = _downloadQueue.value.find { it.status == DownloadStatus.QUEUED }
            ?: return

        // Update status to downloading
        _downloadQueue.value = _downloadQueue.value.map { task ->
            if (task.track.id == queuedTask.track.id) {
                task.copy(status = DownloadStatus.DOWNLOADING)
            } else {
                task
            }
        }

        // Start download in coroutine
        downloadScope.launch {
            downloadTrack(queuedTask)
        }
    }

    private suspend fun downloadTrack(task: DownloadTask) {
        val file = File(downloadDir, sanitizeFilename(task.filename))
        val tempFile = File(file.absolutePath + ".tmp")
        try {
            Log.i(TAG, "Starting download: ${task.track.name}")
            val request = Request.Builder().url(task.downloadUrl).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw Exception("Response body is null")
            val totalBytes = body.contentLength()
            _downloadQueue.value = _downloadQueue.value.map { t ->
                if (t.track.id == task.track.id) t.copy(totalBytes = totalBytes) else t
            }
            val buffer = ByteArray(8192)
            var downloadedBytes = 0L
            var bytesRead: Int
            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val currentTask = _downloadQueue.value.find { it.track.id == task.track.id }
                        if (currentTask?.cancelToken == true) {
                            tempFile.delete()
                            _downloadQueue.value = _downloadQueue.value.filter { it.track.id != task.track.id }
                            Log.d(TAG, "Download canceled: ${task.track.name}")
                            return
                        }
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        _downloadQueue.value = _downloadQueue.value.map { t ->
                            if (t.track.id == task.track.id) t.copy(downloadedBytes = downloadedBytes) else t
                        }
                    }
                }
            }
            tempFile.renameTo(file)
            val metadata = DownloadMetadata(
                trackId = task.track.id,
                filename = task.filename,
                s3Key = task.track.id,
                downloadDate = System.currentTimeMillis(),
                fileSize = downloadedBytes
            )
            persistDownloadMetadata(metadata)
            _downloadQueue.value = _downloadQueue.value.map { t ->
                if (t.track.id == task.track.id) t.copy(status = DownloadStatus.COMPLETED, downloadedBytes = downloadedBytes) else t
            }
            Log.i(TAG, "Download completed: ${task.track.name}")
            startNextDownload()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${task.track.name}", e)
            tempFile.delete()
            _downloadQueue.value = _downloadQueue.value.map { t ->
                if (t.track.id == task.track.id) t.copy(status = DownloadStatus.FAILED) else t
            }
            startNextDownload()
        }
    }

    private fun sanitizeFilename(filename: String): String {
        // Remove invalid characters for filenames
        return filename.replace(Regex("[<>:\"|?*]"), "_")
    }
    
    /**
     * Persist download metadata to SharedPreferences
     */
    private fun persistDownloadMetadata(metadata: DownloadMetadata) {
        persistedDownloads[metadata.trackId] = metadata
        
        val downloadsJson = JSONObject()
        persistedDownloads.forEach { (trackId, meta) ->
            val metaJson = JSONObject().apply {
                put("trackId", meta.trackId)
                put("filename", meta.filename)
                put("s3Key", meta.s3Key)
                put("downloadDate", meta.downloadDate)
                put("fileSize", meta.fileSize)
            }
            downloadsJson.put(trackId, metaJson)
        }
        
        prefs.edit().putString(KEY_DOWNLOADS, downloadsJson.toString()).apply()
    }
    
    /**
     * Load persisted downloads from SharedPreferences
     */
    private fun loadPersistedDownloads() {
        val downloadsJsonString = prefs.getString(KEY_DOWNLOADS, null) ?: return
        
        try {
            val downloadsJson = JSONObject(downloadsJsonString)
            val keys = downloadsJson.keys()
            
            while (keys.hasNext()) {
                val trackId = keys.next()
                val metaJson = downloadsJson.getJSONObject(trackId)
                val metadata = DownloadMetadata(
                    trackId = metaJson.getString("trackId"),
                    filename = metaJson.getString("filename"),
                    s3Key = metaJson.getString("s3Key"),
                    downloadDate = metaJson.getLong("downloadDate"),
                    fileSize = metaJson.getLong("fileSize")
                )
                persistedDownloads[trackId] = metadata
            }
            
            Log.d(TAG, "Loaded ${persistedDownloads.size} persisted downloads")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted downloads", e)
        }
    }
    
    /**
     * Scan download directory to rebuild download state
     * This helps recover downloads if metadata was lost
     */
    private fun scanDownloadDirectory() {
        if (!downloadDir.exists() || !downloadDir.isDirectory) return
        
        downloadScope.launch {
            try {
                val files = downloadDir.listFiles() ?: return@launch
                var scannedCount = 0
                
                files.forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        val filename = file.name
                        // Try to find matching metadata by filename
                        val matchingMetadata = persistedDownloads.values.find { 
                            sanitizeFilename(it.filename) == filename 
                        }
                        
                        // If no metadata found, we can't reliably map it to a trackId
                        // But we keep it in the directory for manual recovery if needed
                        if (matchingMetadata != null) {
                            scannedCount++
                        }
                    }
                }
                
                Log.d(TAG, "Scanned download directory: ${files.size} files, $scannedCount matched")
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning download directory", e)
            }
        }
    }
    
    /**
     * Remove download metadata (when file is deleted)
     */
    fun removeDownloadMetadata(trackId: String) {
        persistedDownloads.remove(trackId)
        
        val downloadsJson = JSONObject()
        persistedDownloads.forEach { (id, meta) ->
            val metaJson = JSONObject().apply {
                put("trackId", meta.trackId)
                put("filename", meta.filename)
                put("s3Key", meta.s3Key)
                put("downloadDate", meta.downloadDate)
                put("fileSize", meta.fileSize)
            }
            downloadsJson.put(id, metaJson)
        }
        
        prefs.edit().putString(KEY_DOWNLOADS, downloadsJson.toString()).apply()
    }
}
