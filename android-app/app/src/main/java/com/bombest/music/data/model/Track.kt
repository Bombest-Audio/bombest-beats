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
        private val NUMBERED_TRACK_REGEX = Regex("^\\d+\\s+.*")
        private val STRIP_NUMBER_REGEX = Regex("^\\d+\\s*")
    }

    // Helper to get formatted Display Title
    // When the filename has a leading track-number prefix (e.g. "08 any other day.wav") and
    // the DB title is a single word (likely an artist/album identifier), prefer the
    // path-derived title. Requiring a numbered prefix avoids falsely overriding legitimate
    // single-word track titles such as "Hallelujah" or "Wonderwall".
    val displayTitle: String
        get() {
            val filename = path?.substringAfterLast("/") ?: ""
            val nameWithoutExt = if (filename.contains(".")) {
                filename.substringBeforeLast(".").trim()
            } else filename.trim()
            val hasTrackNumber = nameWithoutExt.matches(NUMBERED_TRACK_REGEX)
            val pathDerivedTitle = when {
                hasTrackNumber -> nameWithoutExt.replace(STRIP_NUMBER_REGEX, "").trim()
                nameWithoutExt.isNotBlank() -> nameWithoutExt
                else -> null
            }
            return when {
                title.isNullOrBlank() -> pathDerivedTitle ?: "Unknown Track"
                // Only prefer path-derived title when the filename had a track-number prefix
                // and the DB title is a single word (no spaces), indicating it may be a
                // mismatched artist/album identifier rather than the real track title
                hasTrackNumber && pathDerivedTitle != null
                        && pathDerivedTitle.contains(" ") && !title.contains(" ") ->
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
