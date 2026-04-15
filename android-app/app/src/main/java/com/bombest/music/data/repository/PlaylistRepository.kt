package com.bombest.music.data.repository

import com.bombest.music.data.api.Playlist
import com.bombest.music.data.api.PlaylistApi
import com.bombest.music.data.api.Track
import com.bombest.music.data.cache.PlaylistCacheDao
import com.bombest.music.data.cache.PlaylistEntity
import com.bombest.music.data.cache.PlaylistTrackEntity

/**
 * Cache-aside repository for playlists. The flow for every read:
 *
 *   1. Fire the network request.
 *   2. On success, update Room and return fresh data.
 *   3. On failure, fall back to whatever Room has (may be empty on first run).
 *
 * We don't short-circuit reads with stale cache data (classic read-through) because the
 * browse UI prefers fresh-or-cached-on-error over fresh-eventually; the caller never sees
 * old data unless the backend is actually unreachable.
 *
 * [PlaylistApi] is nullable because the session's bearer token may not be attached yet;
 * when null, we serve purely from cache (rare — only happens pre-login, where Auto browse
 * should already be gated).
 */
class PlaylistRepository(
    private val api: PlaylistApi?,
    private val dao: PlaylistCacheDao,
) {

    suspend fun getPlaylists(): List<Playlist> {
        val fromNetwork = try {
            api?.getPlaylists()?.playlists
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getPlaylists network failed, falling back to cache: ${e.message}")
            null
        }
        if (fromNetwork != null) {
            val now = System.currentTimeMillis()
            dao.replaceAllPlaylists(fromNetwork.map { it.toEntity(now) })
            return fromNetwork
        }
        return dao.getAllPlaylists().map { it.toApi() }
    }

    suspend fun getPlaylistTracks(playlistId: Int): List<Track> {
        val fromNetwork = try {
            api?.getPlaylistTracks(playlistId)?.tracks
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getPlaylistTracks($playlistId) network failed, falling back to cache: ${e.message}")
            null
        }
        if (fromNetwork != null) {
            dao.replaceTracksForPlaylist(
                playlistId,
                fromNetwork.mapIndexed { idx, t -> t.toEntity(playlistId, idx) },
            )
            return fromNetwork
        }
        return dao.getTracksForPlaylist(playlistId).map { it.toApi() }
    }

    companion object {
        private const val TAG = "PlaylistRepository"
    }
}

private fun Playlist.toEntity(cachedAt: Long): PlaylistEntity = PlaylistEntity(
    id = id,
    name = name,
    count = count,
    artUrl = art_url,
    isPublic = is_public,
    isSystem = is_system,
    userId = user_id,
    cachedAt = cachedAt,
)

private fun PlaylistEntity.toApi(): Playlist = Playlist(
    id = id,
    name = name,
    count = count,
    created_at = null,
    user_id = userId,
    is_public = isPublic,
    is_system = isSystem,
    art_url = artUrl,
    share_token = null,
)

private fun Track.toEntity(playlistId: Int, position: Int): PlaylistTrackEntity = PlaylistTrackEntity(
    playlistId = playlistId,
    trackId = id,
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    path = path,
    position = position,
)

private fun PlaylistTrackEntity.toApi(): Track = Track(
    id = trackId,
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    path = path,
)
