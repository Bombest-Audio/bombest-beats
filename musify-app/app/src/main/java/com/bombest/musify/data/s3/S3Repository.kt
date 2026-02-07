package com.bombest.musify.data.s3

import android.util.Log
import com.bombest.musify.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Repository for fetching tracks from S3 bucket
 * Uses S3 ListObjects API to retrieve track metadata
 */
class S3Repository(
    private val bucket: String = BuildConfig.S3_BUCKET_NAME,
    private val region: String = BuildConfig.S3_REGION,
    private val prefix: String = BuildConfig.S3_PREFIX,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "S3Repository"
        private val AUDIO_EXTENSIONS = setOf(".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus")
        private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Fetch all tracks from S3 bucket
     * Parses XML response from S3 ListObjects API
     */
    suspend fun fetchTracks(): Result<List<S3Track>> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting fetchTracks() from bucket=$bucket prefix=$prefix")
            
            val url = "https://$bucket.s3.$region.amazonaws.com/?list-type=2&prefix=$prefix"
            Log.d(TAG, "Fetching from URL: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = when (response.code) {
                    403 -> "Access denied: S3 bucket permissions issue. Please check bucket configuration."
                    404 -> "Bucket not found: S3 bucket does not exist or is not accessible."
                    in 500..599 -> "S3 server error: Please try again later."
                    else -> "Failed to fetch tracks from S3: HTTP ${response.code}"
                }
                Log.e(TAG, errorMessage)
                return@withContext Result.failure(IOException(errorMessage))
            }

            val xmlBody = response.body?.string() ?: ""
            Log.d(TAG, "Received ${xmlBody.length} bytes, parsing XML")

            val tracks = parseTracksFromXml(xmlBody)
            Log.i(TAG, "Successfully parsed ${tracks.size} valid tracks")
            
            tracks.take(3).forEach { track ->
                Log.d(TAG, "  - ${track.extractTrackName()} (${track.size} bytes)")
            }
            if (tracks.size > 3) {
                Log.d(TAG, "  ... and ${tracks.size - 3} more")
            }

            Result.success(tracks)
        } catch (e: IOException) {
            val errorMessage = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                "Network timeout: Unable to fetch tracks from S3. Please check your internet connection."
            } else if (e.message?.contains("socket", ignoreCase = true) == true || 
                      e.message?.contains("host", ignoreCase = true) == true) {
                "Network error: Unable to connect to S3. Please check your internet connection."
            } else {
                "Failed to fetch tracks: ${e.message}"
            }
            Log.e(TAG, errorMessage, e)
            Result.failure(IOException(errorMessage, e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching tracks", e)
            Result.failure(e)
        }
    }

    /**
     * Parse tracks from S3 ListObjects XML response
     */
    private fun parseTracksFromXml(xml: String): List<S3Track> {
        val tracks = mutableListOf<S3Track>()
        var skippedCount = 0

        // Pattern to match <Contents>...</Contents> blocks
        val contentsPattern = Pattern.compile("<Contents>(.*?)</Contents>", Pattern.DOTALL)
        val contentsMatcher = contentsPattern.matcher(xml)

        whileLoop@ while (contentsMatcher.find()) {
            try {
                val contentsBlock = contentsMatcher.group(1) ?: continue

                // Extract Key
                val keyPattern = Pattern.compile("<Key>(.*?)</Key>")
                val keyMatcher = keyPattern.matcher(contentsBlock)
                if (!keyMatcher.find()) {
                    skippedCount++
                    continue
                }
                val key = keyMatcher.group(1)
                if (key == null) {
                    skippedCount++
                    continue@whileLoop
                }

                // Skip "Non-Album" entries (beets placeholder; not a real track)
                if (key.contains("Non-Album", ignoreCase = true)) {
                    skippedCount++
                    continue
                }

                // Skip folders (keys ending with '/')
                if (key.endsWith("/")) {
                    skippedCount++
                    continue
                }

                // Skip entries without extension
                if (!key.contains(".")) {
                    skippedCount++
                    continue
                }

                // Check if it's an audio file
                val lowerKey = key.lowercase()
                val isAudioFile = AUDIO_EXTENSIONS.any { lowerKey.endsWith(it) }
                if (!isAudioFile) {
                    skippedCount++
                    continue
                }

                // Extract Size
                val sizePattern = Pattern.compile("<Size>(.*?)</Size>")
                val sizeMatcher = sizePattern.matcher(contentsBlock)
                val size = if (sizeMatcher.find()) {
                    sizeMatcher.group(1)?.toLongOrNull() ?: 0L
                } else {
                    0L
                }

                // Skip zero-length files
                if (size <= 0) {
                    skippedCount++
                    continue
                }

                // Skip files with numeric-only names (e.g., "00.wav")
                val filename = key.split("/").last()
                if (filename.contains(".")) {
                    val nameWithoutExt = filename.substring(0, filename.lastIndexOf("."))
                    if (nameWithoutExt.matches(Regex("^\\d+$"))) {
                        skippedCount++
                        continue
                    }
                }

                // Extract ETag
                val eTagPattern = Pattern.compile("<ETag>(.*?)</ETag>")
                val eTagMatcher = eTagPattern.matcher(contentsBlock)
                val eTag = if (eTagMatcher.find()) {
                    eTagMatcher.group(1)?.replace("\"", "") ?: ""
                } else {
                    ""
                }

                // Extract LastModified
                val lastModifiedPattern = Pattern.compile("<LastModified>(.*?)</LastModified>")
                val lastModifiedMatcher = lastModifiedPattern.matcher(contentsBlock)
                if (!lastModifiedMatcher.find()) {
                    skippedCount++
                    continue
                }
                val lastModifiedString = lastModifiedMatcher.group(1)
                if (lastModifiedString == null) {
                    skippedCount++
                    continue@whileLoop
                }

                // Parse ISO 8601 date to timestamp
                val lastModified = try {
                    val parsed = ISO_DATE_FORMAT.parse(lastModifiedString)?.time
                    if (parsed == null) {
                        Log.w(TAG, "Invalid date format for $key, skipping")
                        skippedCount++
                        continue@whileLoop
                    }
                    parsed
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid date format for $key, skipping", e)
                    skippedCount++
                    continue@whileLoop
                }

                tracks.add(
                    S3Track(
                        key = key,
                        bucket = bucket,
                        region = region,
                        size = size,
                        lastModified = lastModified,
                        eTag = eTag
                    )
                )
            } catch (e: Exception) {
                // Skip individual track parsing errors, log and continue
                Log.w(TAG, "Error parsing track element, skipping", e)
                skippedCount++
            }
        }

        if (skippedCount > 0) {
            Log.d(TAG, "Skipped $skippedCount invalid entries")
        }

        return tracks
    }

    /**
     * Get track URL for a given S3Track
     */
    fun getTrackUrl(track: S3Track): String = track.url
}
