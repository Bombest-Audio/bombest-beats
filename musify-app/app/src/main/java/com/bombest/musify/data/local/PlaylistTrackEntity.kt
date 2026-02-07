package com.bombest.musify.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a track in a playlist
 */
@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistTrackEntity(
    @PrimaryKey
    val id: String, // playlistId_trackId
    val playlistId: String,
    val trackId: String, // S3 key
    val position: Int // Order in playlist
)
