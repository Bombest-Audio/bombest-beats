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
    
    fun getStreamUrl(trackId: Int): String {
        // Always request AAC 256 kbps for remote streams:
        //   - WAV/FLAC/OGG (~800–1400 kbps) are transcoded server-side to 256 kbps AAC
        //   - 5x smaller payload → buffer fills faster → fewer underruns on cellular/Android Auto
        //   - AAC triggers hardware audio offload on Android (WAV/FLAC do not)
        //   - MP3/AAC originals are served unchanged (server skips transcode)
        return "${NetworkModule.getStreamBaseUrl()}/stream/$trackId?format=aac&bitrate=256"
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
