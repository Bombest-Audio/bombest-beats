package com.bombest.musify.data.s3

import com.bombest.musify.domain.SearchResult

/**
 * Extension function to convert S3Track to TrackSearchResult
 * This maps S3 track data to the domain model used by Musify.
 * [backendTrackId] when non-null enables in-app metadata editing (PUT /track/<id>).
 */
fun S3Track.toTrackSearchResult(backendTrackId: Int? = null): SearchResult.TrackSearchResult {
    val trackName = extractTrackName()
    val artistName = extractArtist()
    val albumName = extractAlbumName()
    
    // Use graffiti bomb as default album art
    val defaultAlbumArtUrl = "https://bombest-beats-music.s3.us-west-2.amazonaws.com/music/graffitti-bomb.png"
    
    return SearchResult.TrackSearchResult(
        id = key, // Use S3 key as unique ID
        name = trackName,
        imageUrlString = defaultAlbumArtUrl,
        artistsString = artistName,
        trackUrlString = url, // S3 URL for streaming
        albumName = albumName,
        backendTrackId = backendTrackId
    )
}

/**
 * Convert a list of S3Tracks to TrackSearchResults with optional path→backend id mapping.
 * [pathToBackendId] maps normalized path (S3 key) to backend track id for metadata editing.
 */
fun List<S3Track>.toTrackSearchResults(pathToBackendId: Map<String, Int>? = null): List<SearchResult.TrackSearchResult> {
    return map { track ->
        val backendId = pathToBackendId?.get(normalizePathForMatch(track.key))
        track.toTrackSearchResult(backendTrackId = backendId)
    }
}

/** Normalize S3 key/path for matching backend path (no leading slash, forward slashes). */
fun normalizePathForMatch(keyOrPath: String): String {
    return keyOrPath.replace('\\', '/').trim().removePrefix("/")
}
