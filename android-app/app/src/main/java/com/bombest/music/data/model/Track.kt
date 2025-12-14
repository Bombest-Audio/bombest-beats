package com.bombest.music.data.model

import com.squareup.moshi.Json

data class Track(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "artist") val artist: String?,
    @Json(name = "album") val album: String?,
    @Json(name = "length") val length: Double, // Seconds
    @Json(name = "path") val path: String?,
    @Json(name = "album_id") val albumId: Int?
) {
    // Helper to get formatted Display Title
    val displayTitle: String
        get() = title ?: path?.substringAfterLast("/") ?: "Unknown Track"
        
    val displayArtist: String
        get() = artist ?: "Unknown Artist"
        
    fun getStreamUrl(baseUrl: String): String = "$baseUrl/stream/$id"
    fun getArtUrl(baseUrl: String): String? {
        return if (albumId != null) "$baseUrl/album/$albumId/art" else null
    }
}

data class LibraryResponse(
    @Json(name = "items") val items: List<Track>
)
