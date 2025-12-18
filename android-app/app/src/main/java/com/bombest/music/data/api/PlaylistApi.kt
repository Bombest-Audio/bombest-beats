package com.bombest.music.data.api

import retrofit2.http.*

data class Playlist(
    val id: Int,
    val name: String,
    val count: Int = 0,
    val created_at: String? = null,
    val is_public: Boolean = false  // Admin can publish to all users
)

data class PlaylistsResponse(val playlists: List<Playlist>)
data class CreatePlaylistRequest(
    val name: String,
    val is_public: Boolean = false  // false = local only, true = published to network
)
data class AddTracksRequest(val track_ids: List<Int>)

interface PlaylistApi {
    @GET("playlists")
    suspend fun getPlaylists(): PlaylistsResponse
    
    @POST("playlists")
    suspend fun createPlaylist(@Body request: CreatePlaylistRequest): Playlist
    
    @PUT("playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: Int, @Body request: CreatePlaylistRequest): Map<String, Any>
    
    @DELETE("playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Int): Map<String, Any>
    
    @GET("playlists/{id}/tracks")
    suspend fun getPlaylistTracks(@Path("id") id: Int): TracksResponse
    
    @POST("playlists/{id}/tracks")
    suspend fun addTracksToPlaylist(@Path("id") id: Int, @Body request: AddTracksRequest): Map<String, Any>
    
    @HTTP(method = "DELETE", path = "playlists/{id}/tracks", hasBody = true)
    suspend fun removeTracksFromPlaylist(@Path("id") id: Int, @Body request: AddTracksRequest): Map<String, Any>
    
    // Phase 2: New playlist endpoints
    @POST("playlists/system/init")
    suspend fun initializeSystemPlaylists(): SystemPlaylistsResponse
    
    @PUT("playlists/{id}/reorder")
    suspend fun reorderPlaylist(@Path("id") id: Int, @Body request: ReorderRequest): Map<String, Any>
    
    @GET("playlists/{id}/search")
    suspend fun searchPlaylist(@Path("id") id: Int, @Query("q") query: String): TracksResponse
    
    @POST("playlists/{id}/favorites/toggle")
    suspend fun toggleFavorite(@Path("id") id: Int, @Body request: FavoriteToggleRequest): FavoriteToggleResponse
    
    @PUT("playlists/{id}/sort")
    suspend fun sortPlaylist(@Path("id") id: Int, @Body request: SortRequest): Map<String, Any>
    
    @POST("playlists/{id}/publish")
    suspend fun publishPlaylist(@Path("id") id: Int): Map<String, Any>
    
    @GET("playlists/published")
    suspend fun getPublishedPlaylists(): PlaylistsResponse
    
    @POST("playlists/{id}/save")
    suspend fun savePublishedPlaylist(@Path("id") id: Int, @Body request: SavePlaylistRequest): SavePlaylistResponse
}

data class TracksResponse(val items: List<Track>)

data class Track(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String?,
    @com.squareup.moshi.Json(name = "length") val duration: Double?,
    val path: String?,  // Nullable because some tracks may not have paths yet
    @com.squareup.moshi.Json(name = "album_id") val album_id: Int? = null
)

// Phase 2: New request/response data classes
data class SystemPlaylistsResponse(
    val success: Boolean,
    val all_songs_id: Int,
    val favorites_id: Int
)

data class ReorderRequest(val track_ids: List<Int>)

data class FavoriteToggleRequest(
    val track_id: Int,
    val user_id: Int = 1
)

data class FavoriteToggleResponse(
    val success: Boolean,
    val favorited: Boolean
)

data class SortRequest(val sort_mode: String) // "custom" | "title" | "artist" | "date"

data class SavePlaylistRequest(val user_id: Int = 1)

data class SavePlaylistResponse(
    val success: Boolean,
    val new_playlist_id: Int,
    val track_count: Int
)
