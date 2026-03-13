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
    // Helper to get formatted Display Title
    // When DB title is suspicious (e.g. single word like "jefferson" that may be album name)
    // but path filename has a proper title (e.g. "08 any other day.wav"), prefer path-derived title
    val displayTitle: String
        get() {
            val filename = path?.substringAfterLast("/") ?: ""
            val nameWithoutExt = if (filename.contains(".")) {
                filename.substringBeforeLast(".").trim()
            } else filename.trim()
            val pathDerivedTitle = when {
                nameWithoutExt.matches(Regex("^\\d+\\s+.*")) -> nameWithoutExt.replace(Regex("^\\d+\\s*"), "").trim()
                nameWithoutExt.isNotBlank() -> nameWithoutExt
                else -> null
            }
            return when {
                title.isNullOrBlank() -> pathDerivedTitle ?: "Unknown Track"
                pathDerivedTitle != null && pathDerivedTitle.contains(" ") && !title.contains(" ") ->
                    pathDerivedTitle
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
