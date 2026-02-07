package com.bombest.musify.data.backend

import android.util.Log
import com.bombest.musify.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API for the beets upload server: library catalog and track metadata updates.
 * Single source of truth for the app's track list when backend is configured.
 */
interface BackendLibraryApi {
    suspend fun getLibrary(): Result<List<LibraryItem>>
    suspend fun updateTrack(trackId: Int, title: String?, artist: String?, album: String?): Result<Unit>
}

data class LibraryItem(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Int?,
    /** Relative path (S3-key style) for matching S3 listing to backend track id. */
    val path: String?,
    val streamUrl: String
) {
    /** Default album art (graffiti bomb) when backend does not serve per-track art. */
    val defaultImageUrl: String
        get() = "https://bombest-beats-music.s3.us-west-2.amazonaws.com/music/graffitti-bomb.png"

    fun toTrackSearchResult(): com.bombest.musify.domain.SearchResult.TrackSearchResult {
        return com.bombest.musify.domain.SearchResult.TrackSearchResult(
            id = id.toString(),
            name = title,
            imageUrlString = defaultImageUrl,
            artistsString = artist,
            trackUrlString = streamUrl,
            albumName = album.ifEmpty { null },
            backendTrackId = id
        )
    }
}

class BackendLibraryApiImpl(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL
) : BackendLibraryApi {

    companion object {
        private const val TAG = "BackendLibraryApi"
    }

    private fun normalizeBaseUrl(): String {
        val url = baseUrl.trim().removeSuffix("/")
        return if (url.isEmpty()) "https://beats.bom.best" else url
    }

    override suspend fun getLibrary(): Result<List<LibraryItem>> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl()
        val url = "$base/library"
        try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                Log.e(TAG, "getLibrary failed: ${response.code} $body")
                return@withContext Result.failure(IOException("HTTP ${response.code}: $body"))
            }
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val itemsArray = json.optJSONArray("items") ?: return@withContext Result.success(emptyList())
            val list = mutableListOf<LibraryItem>()
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.getJSONObject(i)
                val streamUrlRaw = obj.optString("stream_url", "")
                val streamUrl = if (streamUrlRaw.startsWith("http")) streamUrlRaw else "$base$streamUrlRaw"
                list.add(
                    LibraryItem(
                        id = obj.getInt("id"),
                        title = obj.optString("title", "Unknown"),
                        artist = obj.optString("artist", "Unknown Artist"),
                        album = obj.optString("album", ""),
                        albumId = if (obj.has("album_id") && !obj.isNull("album_id")) obj.getInt("album_id") else null,
                        path = if (obj.has("path") && !obj.isNull("path")) obj.optString("path", null).takeIf { it?.isNotEmpty() == true } else null,
                        streamUrl = streamUrl
                    )
                )
            }
            Log.d(TAG, "getLibrary: loaded ${list.size} tracks")
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "getLibrary error", e)
            Result.failure(e)
        }
    }

    override suspend fun updateTrack(
        trackId: Int,
        title: String?,
        artist: String?,
        album: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl()
        val url = "$base/track/$trackId"
        try {
            val json = JSONObject()
            title?.let { json.put("title", it) }
            artist?.let { json.put("artist", it) }
            album?.let { json.put("album", it) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).put(body).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "updateTrack failed: ${response.code} $errBody")
                return@withContext Result.failure(IOException("HTTP ${response.code}: $errBody"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateTrack error", e)
            Result.failure(e)
        }
    }
}
