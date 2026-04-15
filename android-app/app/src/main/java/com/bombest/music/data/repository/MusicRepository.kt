package com.bombest.music.data.repository

import com.bombest.music.data.NetworkModule
import com.bombest.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(private val context: android.content.Context) {
    private val api = NetworkModule.api
    private val cacheFile = java.io.File(context.filesDir, "library_cache.json")
    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
    private val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, Track::class.java)
    private val adapter = moshi.adapter<List<Track>>(listType)

    suspend fun fetchLibrary(): List<Track> = withContext(Dispatchers.IO) {
        try {
            val response = api.getLibrary()
            // Cache the response
            try {
                val json = adapter.toJson(response.items)
                cacheFile.writeText(json)
            } catch (e: Exception) { e.printStackTrace() }
            
            response.items
        } catch (e: Exception) {
            e.printStackTrace()
            // Try loading from cache
            if (cacheFile.exists()) {
                try {
                    val json = cacheFile.readText()
                    adapter.fromJson(json) ?: emptyList()
                } catch (ce: Exception) {
                    ce.printStackTrace()
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }
    
    /**
     * Build a `/stream/<id>` URL.
     *
     * - [transcodeForAuto] = true (Android Auto path): request `?transcode=aac&bitrate=256`.
     *   Auto's certified playback stack is AAC/MP4 only — FLAC/WAV/OGG would be rejected by
     *   the ExoPlayer renderer the head-unit ships with. Transcoding server-side keeps the
     *   payload small (~5x smaller than FLAC) so the buffer fills faster on cellular.
     * - [transcodeForAuto] = false (phone/headphones path): no transcode flag — the server
     *   passes the source through with its canonical Content-Type (`audio/flac`, `audio/mpeg`,
     *   etc) so lossless sources stay lossless and MP3/AAC originals are served unchanged.
     */
    fun getStreamUrl(trackId: Int, transcodeForAuto: Boolean = false): String {
        val base = "${NetworkModule.getStreamBaseUrl()}/stream/$trackId"
        return if (transcodeForAuto) "$base?transcode=aac&bitrate=256" else base
    }
    
    fun getTrackArtUrl(trackId: Int): String {
        return "${NetworkModule.getStreamBaseUrl()}/track/$trackId/art"
    }
    
    fun getArtUrl(albumId: Int?): String? {
        return NetworkModule.getStreamBaseUrl().let { baseUrl ->
             if (albumId != null) "$baseUrl/album/$albumId/art" else null
        }
    }
}
