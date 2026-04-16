package com.bombest.music.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Cache-aside DAO for playlists and their tracks. Writes are transactional "replace all" —
 * we never merge partial server responses with cached state because stale entries would
 * linger across renames/deletes.
 */
@Dao
interface PlaylistCacheDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Int): PlaylistEntity?

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    /**
     * Atomic swap of the cached playlist list. Callers hand us the freshly-fetched server
     * response; we nuke the old rows and insert the new set in one transaction so a reader
     * never sees a half-updated cache.
     */
    @Transaction
    suspend fun replaceAllPlaylists(playlists: List<PlaylistEntity>) {
        clearPlaylists()
        insertPlaylists(playlists)
    }

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getTracksForPlaylist(playlistId: Int): List<PlaylistTrackEntity>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracksForPlaylist(playlistId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun replaceTracksForPlaylist(playlistId: Int, tracks: List<PlaylistTrackEntity>) {
        clearTracksForPlaylist(playlistId)
        insertTracks(tracks)
    }
}
