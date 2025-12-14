package com.bombest.music.data.repository

import com.bombest.music.data.NetworkModule
import com.bombest.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository {
    private val api = NetworkModule.api

    suspend fun fetchLibrary(): List<Track> = withContext(Dispatchers.IO) {
        try {
            val response = api.getLibrary()
            response.items
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    fun getStreamUrl(trackId: Int): String {
        return "${NetworkModule.getStreamBaseUrl()}/stream/$trackId"
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
