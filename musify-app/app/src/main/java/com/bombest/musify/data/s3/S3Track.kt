package com.bombest.musify.data.s3

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Represents a track stored in S3 bucket
 */
data class S3Track(
    val key: String,
    val bucket: String,
    val region: String,
    val size: Long,
    val lastModified: Long, // Unix timestamp in milliseconds
    val eTag: String
) {
    /**
     * Get filename from S3 key (last segment after '/')
     */
    val filename: String
        get() = key.split('/').last()

    /**
     * Get title from filename (filename without extension)
     */
    val title: String
        get() {
            val name = filename
            val dotIndex = name.lastIndexOf('.')
            return if (dotIndex == -1) name else name.substring(0, dotIndex)
        }

    /**
     * Get S3 URL for this track
     * URL-safe path construction (keys may contain spaces/brackets).
     * Uses %20 for spaces (S3 path style), not + (form-encoding).
     */
    val url: String
        get() {
            val encodedSegments = key.split('/').map { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20") // S3 paths use %20, not + for spaces
            }
            return "https://$bucket.s3.$region.amazonaws.com/${encodedSegments.joinToString("/")}"
        }

    /**
     * Extract album name from S3 key path.
     * Pattern: "music/thomas phillips/time off 3/track.mp3" -> "time off 3"
     * Returns null for "Non-Album" so UI shows artist as subtitle instead.
     */
    fun extractAlbumName(): String? {
        val parts = key.split('/')
        if (parts.size >= 3) {
            val albumSegment = parts[parts.size - 2]
            if (albumSegment.equals("Non-Album", ignoreCase = true)) return null
            return if (albumSegment != "thomas phillips" && albumSegment != "music") {
                albumSegment
            } else null
        }
        return null
    }

    /**
     * Extract artist name from filename
     * Supports patterns like "Artist - Title.wav" or just "Title.wav"
     */
    fun extractArtist(): String {
        val title = this.title.replace("_", " ")
        return if (title.contains(" - ")) {
            val parts = title.split(" - ")
            if (parts.isNotEmpty()) parts[0].trim() else "Unknown Artist"
        } else {
            "thomas phillips" // Default artist
        }
    }

    /**
     * Extract track name from filename
     * Supports patterns like "Artist - Title.wav" or just "Title.wav"
     * Removes numeric prefixes like "00 ", "01 " from filenames.
     * Treats literal "Non-Album" as display sentinel and returns "Unknown track".
     */
    fun extractTrackName(): String {
        val title = this.title.replace("_", " ")
        val cleaned = title.replace(Regex("^\\d+\\s+"), "") // Remove leading digits + space
        val resolved = if (cleaned.contains(" - ")) {
            val parts = cleaned.split(" - ")
            if (parts.size >= 2) {
                parts.subList(1, parts.size).joinToString(" - ").trim()
            } else {
                cleaned
            }
        } else {
            cleaned
        }
        return if (resolved.equals("Non-Album", ignoreCase = true)) "Unknown track" else resolved
    }
}
