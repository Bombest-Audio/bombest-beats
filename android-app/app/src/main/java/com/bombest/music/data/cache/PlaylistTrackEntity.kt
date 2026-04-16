package com.bombest.music.data.cache

import androidx.room.Entity
import androidx.room.Index

/**
 * Cached track row for a specific playlist. Composite PK of (playlistId, trackId) so the same
 * track can appear in multiple cached playlists (catalog + user list, favorites + a mix, etc.).
 *
 * [position] preserves order as returned by the server — Room doesn't guarantee insertion order
 * on SELECT, so we sort by this column in the DAO.
 *
 * No foreign key to [PlaylistEntity]: callers may fetch tracks for a playlist without having
 * first fetched the playlist list (e.g. Auto deep-link to "playlist:42" drills in without
 * browsing PLAYLISTS_ID first). Orphaned track rows are harmless — the next `getPlaylists`
 * cycle refreshes them, and we clear-per-playlist on successful refetch anyway.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("playlistId")],
)
data class PlaylistTrackEntity(
    val playlistId: Int,
    val trackId: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Double?,
    val path: String,
    val position: Int,
)
