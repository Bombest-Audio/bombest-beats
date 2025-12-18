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
}

data class TracksResponse(val items: List<Track>)

data class Track(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String?,
    @com.squareup.moshi.Json(name = "length") val duration: Double?,
    val path: String,
    @com.squareup.moshi.Json(name = "album_id") val album_id: Int? = null
)
