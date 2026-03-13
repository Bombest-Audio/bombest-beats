package com.bombest.music.data.model

import com.squareup.moshi.Json

data class Track(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "artist") val artist: String?,
    @Json(name = "album") val album: String?,
    @Json(name = "length") val length: Double = 0.0, // Seconds (default when API omits it)
    @Json(name = "path") val path: String?,
    @Json(name = "album_id") val albumId: Int?
) {
    companion object {
        private val TRACK_NUMBER_PREFIX = Regex("^\\d+\\s+.*")
        private val TRACK_NUMBER_STRIP = Regex("^\\d+\\s*")
    }

    // Helper to get formatted Display Title
    // Prefer title when present. Fall back to path-derived name (strip extension, track number).
    val displayTitle: String
        get() {
            val filename = path?.substringAfterLast("/") ?: ""
            val nameWithoutExt = if (filename.contains(".")) {
                filename.substringBeforeLast(".").trim()
            } else filename.trim()
            val pathDerivedTitle = when {
                nameWithoutExt.matches(TRACK_NUMBER_PREFIX) -> nameWithoutExt.replace(TRACK_NUMBER_STRIP, "").trim()
                nameWithoutExt.isNotBlank() -> nameWithoutExt
                else -> null
            }
            return when {
                title.isNullOrBlank() -> pathDerivedTitle ?: "Unknown Track"
                else -> title
            }
        }
        
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
