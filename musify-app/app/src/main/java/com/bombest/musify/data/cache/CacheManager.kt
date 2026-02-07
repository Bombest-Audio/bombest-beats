package com.bombest.musify.data.cache

import android.content.Context
import android.util.Log
import com.bombest.musify.data.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache manager for S3 tracks
 * Handles LRU cache eviction and cache size management
 */
@Singleton
class CacheManager @Inject constructor(
    private val context: Context,
    private val downloadManager: DownloadManager
) {
    companion object {
        private const val TAG = "CacheManager"
        private const val MAX_CACHE_SIZE_MB = 500L // 500 MB default cache limit
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "s3_audio_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Get cached file by filename (e.g. track id or sanitized track name).
     * Returns null if not cached.
     */
    suspend fun getCachedFile(filename: String): File? = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, sanitizeFilename(filename))
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "Cache hit for $filename")
            cacheFile
        } else {
            Log.d(TAG, "Cache miss for $filename")
            null
        }
    }

    /**
     * Check if track is cached by filename
     */
    suspend fun isCached(filename: String): Boolean {
        return getCachedFile(filename) != null
    }

    /**
     * Get cache size in bytes
     */
    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        if (!cacheDir.exists()) return@withContext 0L
        
        var totalSize = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
            }
        }
        totalSize
    }

    /**
     * Clear cache, optionally limiting to maxSizeMB
     * If maxSizeMB is 0, clears all cache
     */
    suspend fun clearCache(maxSizeMB: Long = MAX_CACHE_SIZE_MB): Boolean = withContext(Dispatchers.IO) {
        try {
            if (maxSizeMB == 0L) {
                // Clear all cache
                cacheDir.listFiles()?.forEach { it.delete() }
                Log.i(TAG, "Cleared all cache")
                return@withContext true
            }

            val maxSizeBytes = maxSizeMB * 1024 * 1024
            val currentSize = getCacheSize()

            if (currentSize <= maxSizeBytes) {
                Log.d(TAG, "Cache size ($currentSize bytes) is within limit ($maxSizeBytes bytes)")
                return@withContext true
            }

            // Sort files by last modified (LRU)
            val files = cacheDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            var sizeToRemove = currentSize - maxSizeBytes
            var removedCount = 0

            for (file in files) {
                if (sizeToRemove <= 0) break
                val fileSize = file.length()
                if (file.delete()) {
                    sizeToRemove -= fileSize
                    removedCount++
                }
            }

            Log.i(TAG, "Cleared $removedCount files, freed ${currentSize - getCacheSize()} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
            false
        }
    }

    private fun sanitizeFilename(filename: String): String {
        return filename.replace(Regex("[<>:\"|?*]"), "_")
    }
}
