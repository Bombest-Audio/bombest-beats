package com.bombest.musify.data.repositories.playlistrepository

import com.bombest.musify.domain.SearchResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing local playlists
 */
interface PlaylistRepository {
    /**
     * Get all user-created playlists
     */
    fun getAllPlaylists(): Flow<List<SearchResult.PlaylistSearchResult>>

    /**
     * Get a playlist by ID
     */
    suspend fun getPlaylistById(playlistId: String): SearchResult.PlaylistSearchResult?

    /**
     * Get tracks for a playlist
     */
    fun getTracksForPlaylist(playlistId: String): Flow<List<SearchResult.TrackSearchResult>>

    /**
     * Create a new playlist
     */
    suspend fun createPlaylist(name: String, description: String? = null): String

    /**
     * Update playlist metadata
     */
    suspend fun updatePlaylist(playlistId: String, name: String?, description: String?)

    /**
     * Delete a playlist
     */
    suspend fun deletePlaylist(playlistId: String)

    /**
     * Add a track to a playlist
     */
    suspend fun addTrackToPlaylist(playlistId: String, track: SearchResult.TrackSearchResult)

    /**
     * Remove a track from a playlist
     */
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    /**
     * Reorder tracks in a playlist
     */
    suspend fun reorderTracks(playlistId: String, fromPosition: Int, toPosition: Int)
}
