package com.bombest.music.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.net.URL

private val Context.downloadDataStore by preferencesDataStore(name = "downloads")

/**
 * Manages song caching and downloads for the Bombest Beats app.
 * 
 * Two-tier storage strategy:
 * 1. Streaming cache (100MB LRU) - Auto-evicted, for smooth playback
 * 2. Downloads folder - Persistent, user-initiated downloads
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "DownloadManager"
        private const val CACHE_SIZE_BYTES = 1024L * 1024 * 1024 // 1 GB
        private const val CACHE_DIR_NAME = "media_cache"
        private const val DOWNLOADS_DIR_NAME = "downloads"
        
        private val DOWNLOADED_TRACKS_KEY = stringSetPreferencesKey("downloaded_tracks")
        
        @Volatile
        private var instance: DownloadManager? = null
        
        fun getInstance(context: Context): DownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Streaming cache with LRU eviction
    private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val cacheEvictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
    
    val cache: SimpleCache by lazy {
        SimpleCache(cacheDir, cacheEvictor, databaseProvider)
    }
    
    // OkHttp client with auth token and extended timeouts for mobile networks
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                // Add auth token if available
                val token = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    .getString("auth_token", null)
                if (token != null) {
                    request.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(request.build())
            }
            .build()
    }
    
    // Data source factory for cached streaming with auth
    val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to "BombestBeats-Android"
            ))
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
    
    // Downloads folder (persistent, not auto-evicted)
    private val downloadsDir: File by lazy {
        File(context.filesDir, DOWNLOADS_DIR_NAME).also { 
            if (!it.exists()) it.mkdirs() 
        }
    }
    
    /**
     * Get the set of downloaded track IDs.
     */
    suspend fun getDownloadedTracks(): Set<String> {
        return context.downloadDataStore.data
            .map { prefs -> prefs[DOWNLOADED_TRACKS_KEY] ?: emptySet() }
            .first()
    }
    
    /**
     * Check if a track is downloaded.
     */
    suspend fun isDownloaded(trackId: String): Boolean {
        return getDownloadedTracks().contains(trackId) && 
               getDownloadFile(trackId).exists()
    }
    
    /**
     * Get the local file path for a downloaded track.
     */
    fun getDownloadFile(trackId: String): File {
        return File(downloadsDir, "$trackId.mp3")
    }
    
    /**
     * Download a track to local storage.
     * @return true if successful, false on error
     */
    suspend fun downloadTrack(trackId: String, streamUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download for track $trackId from $streamUrl")
            
            val file = getDownloadFile(trackId)
            val url = URL(streamUrl)
            val connection = url.openConnection()
            connection.connect()
            
            val inputStream = connection.getInputStream()
            val outputStream = FileOutputStream(file)
            
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            
            // Save to DataStore
            context.downloadDataStore.edit { prefs ->
                val current = prefs[DOWNLOADED_TRACKS_KEY] ?: emptySet()
                prefs[DOWNLOADED_TRACKS_KEY] = current + trackId
            }
            
            Log.d(TAG, "Download complete for track $trackId (${file.length()} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for track $trackId", e)
            Result.failure(e)
        }
    }
    
    /**
     * Remove a downloaded track.
     */
    suspend fun removeDownload(trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = getDownloadFile(trackId)
            if (file.exists()) {
                file.delete()
            }
            
            // Remove from DataStore
            context.downloadDataStore.edit { prefs ->
                val current = prefs[DOWNLOADED_TRACKS_KEY] ?: emptySet()
                prefs[DOWNLOADED_TRACKS_KEY] = current - trackId
            }
            
            Log.d(TAG, "Removed download for track $trackId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove download for track $trackId", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get the URI for playback - uses local file if downloaded, otherwise stream URL.
     */
    suspend fun getPlaybackUri(trackId: String, streamUrl: String): String {
        return if (isDownloaded(trackId)) {
            getDownloadFile(trackId).toURI().toString()
        } else {
            streamUrl
        }
    }
    
    /**
     * Get total size of downloads folder.
     */
    fun getDownloadsSizeBytes(): Long {
        return downloadsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
    
    /**
     * Release resources when app is destroyed.
     */
    fun release() {
        cache.release()
    }
    
    /**
     * Download all tracks from library for offline playback.
     * Skips already downloaded tracks.
     */
    suspend fun downloadAllTracks(
        tracks: List<com.bombest.music.data.model.Track>,
        getStreamUrl: (Int) -> String,
        onProgress: (downloaded: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting batch download of ${tracks.size} tracks")
            var downloaded = 0
            var failed = 0
            
            for (track in tracks) {
                val trackId = track.id.toString()
                
                // Skip if already downloaded
                if (isDownloaded(trackId)) {
                    downloaded++
                    onProgress(downloaded, tracks.size)
                    continue
                }
                
                // Download track
                val streamUrl = getStreamUrl(track.id)
                val result = downloadTrack(trackId, streamUrl)
                
                if (result.isSuccess) {
                    downloaded++
                } else {
                    failed++
                    Log.w(TAG, "Failed to download track ${track.id}: ${track.title}")
                }
                
                onProgress(downloaded + failed, tracks.size)
            }
            
            Log.d(TAG, "Batch download complete: $downloaded downloaded, $failed failed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Batch download failed", e)
            Result.failure(e)
        }
    }
}
