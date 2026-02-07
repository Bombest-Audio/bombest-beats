package com.bombest.musify.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a user-created playlist
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
