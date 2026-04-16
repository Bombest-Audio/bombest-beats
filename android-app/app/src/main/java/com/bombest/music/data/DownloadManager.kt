package com.bombest.music.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors

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

    @Volatile
    private var cachedToken: String? = null

    /** Set auth token for API requests. Call with null on logout. */
    fun setAuthToken(token: String?) {
        cachedToken = token
    }

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
    
    // OkHttp client with auth token, extended timeouts, and an explicit connection pool.
    // Pool size 10 supports concurrent streaming + preloading (default is 5).
    // 5-min keep-alive reuses the TLS connection between track transitions.
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                cachedToken?.let { token ->
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
            // No FLAG_IGNORE_CACHE_ON_ERROR: serve stale cached bytes during transient
            // network errors rather than failing immediately → fewer buffer underruns.
    }

    /**
     * MediaSession / Android Auto artwork loader. Uses the same OkHttp client as streaming
     * (User-Agent, optional Bearer) so CDN/WAF behavior matches audio requests; the default
     * [DataSourceBitmapLoader] constructor uses a different HTTP stack and often fails to load art.
     */
    fun createSessionBitmapLoader(): BitmapLoader {
        val executor = MoreExecutors.listeningDecorator(
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "bombest-artwork-loader").apply { isDaemon = true }
            }
        )
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(
                mapOf("User-Agent" to "BombestBeats-Android")
            )
        return CacheBitmapLoader(DataSourceBitmapLoader(executor, httpFactory))
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
     * True if a non-empty local file exists (sync). Prefer this for playback routing;
     * DataStore may be briefly out of sync with disk.
     */
    fun hasLocalTrackFile(trackId: String): Boolean {
        val f = getDownloadFile(trackId)
        return f.isFile && f.length() > 8_192L
    }

    /**
     * Offline-first: [file://] when a download exists, otherwise the HTTPS stream URL.
     */
    fun playbackUriForTrack(trackId: String, streamUrl: String): Uri {
        return if (hasLocalTrackFile(trackId)) {
            Uri.fromFile(getDownloadFile(trackId))
        } else {
            Uri.parse(streamUrl)
        }
    }

    /**
     * MIME type for ExoPlayer: local downloads are stored as .mp3; remote uses library path.
     */
    fun mimeTypeForPlayback(trackId: String, pathFromApi: String?): String {
        if (hasLocalTrackFile(trackId)) return MimeTypes.AUDIO_MPEG
        return mimeForPath(pathFromApi)
    }

    // Delegates to MimeInference to keep a single source of truth for
    // extension→MIME mapping. Critically, .m4a / .aac are MP4 containers and
    // must be advertised as AUDIO_MP4 (not AUDIO_AAC) so ExoPlayer picks the
    // right extractor — otherwise AAC-in-MP4 sources fail to play.
    private fun mimeForPath(path: String?): String =
        com.bombest.music.service.MimeInference.inferRemoteMimeType(path)
    
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
        return playbackUriForTrack(trackId, streamUrl).toString()
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
     * Clear all downloaded tracks.
     */
    suspend fun clearAllDownloads() {
        withContext(Dispatchers.IO) {
            downloadsDir.listFiles()?.forEach { it.delete() }
            context.downloadDataStore.edit { prefs ->
                prefs[DOWNLOADED_TRACKS_KEY] = emptySet()
            }
            Log.i(TAG, "Cleared all downloads")
        }
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
