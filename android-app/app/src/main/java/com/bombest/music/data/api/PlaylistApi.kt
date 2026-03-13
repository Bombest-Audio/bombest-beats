package com.bombest.music.data.api

import okhttp3.MultipartBody
import retrofit2.http.*

data class Playlist(
    val id: Int,
    val name: String,
    val count: Int = 0,
    val created_at: String? = null,
    val is_public: Boolean = false, // Admin can publish to all users
    val is_system: Boolean = false,
    val art_url: String? = null,
    val share_token: String? = null
)

data class ShareResponse(
    val share_token: String,
    val share_url: String
)

data class PlaylistsResponse(val playlists: List<Playlist>)
data class CreatePlaylistRequest(
    val name: String,
    val is_public: Boolean = false  // false = local only, true = published to network
)
data class AddTracksRequest(val track_ids: List<Int>)
data class FavoriteToggleRequest(val track_id: Int)
data class FavoriteToggleResponse(val success: Boolean, val favorited: Boolean)

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

    @Multipart
    @PUT("playlists/{id}/art")
    suspend fun setPlaylistArt(@Path("id") id: Int, @Part image: MultipartBody.Part): Map<String, Any>
    
    @POST("playlists/{id}/tracks")
    suspend fun addTracksToPlaylist(@Path("id") id: Int, @Body request: AddTracksRequest): Map<String, Any>
    
    @HTTP(method = "DELETE", path = "playlists/{id}/tracks", hasBody = true)
    suspend fun removeTracksFromPlaylist(@Path("id") id: Int, @Body request: AddTracksRequest): Map<String, Any>
    
    @POST("playlists/{id}/favorites/toggle")
    suspend fun toggleFavorite(@Path("id") id: Int, @Body request: FavoriteToggleRequest): FavoriteToggleResponse

    @POST("playlists/system/init")
    suspend fun initializeSystemPlaylists(): Map<String, Any>

    @PUT("playlists/{id}/reorder")
    suspend fun reorderPlaylistTracks(@Path("id") id: Int, @Body request: AddTracksRequest): Map<String, Any>

    @GET("playlists/{id}/search")
    suspend fun searchPlaylist(@Path("id") id: Int, @Query("q") query: String): TracksResponse

    @POST("playlists/{id}/share")
    suspend fun sharePlaylist(@Path("id") id: Int): ShareResponse

    @DELETE("playlists/{id}/share")
    suspend fun unsharePlaylist(@Path("id") id: Int): retrofit2.Response<Unit>
}

data class TracksResponse(val tracks: List<Track>)

data class Track(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Double?,
    val path: String
)
