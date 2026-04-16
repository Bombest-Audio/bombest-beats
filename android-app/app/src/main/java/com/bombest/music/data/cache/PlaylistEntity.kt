package com.bombest.music.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached playlist row. Mirrors the subset of [com.bombest.music.data.api.Playlist] that Android
 * Auto browse needs to render a playlist tile without network. We don't mirror every field from
 * the API — just the ones we actually render (name, count, art URL) — because full fidelity
 * would turn every schema change into a Room migration.
 *
 * [cachedAt] is used by the cache-aside repository to decide whether a cached row is fresh
 * enough to return without hitting the network.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val count: Int,
    val artUrl: String?,
    val isPublic: Boolean,
    val isSystem: Boolean,
    val userId: Int?,
    val cachedAt: Long,
)
