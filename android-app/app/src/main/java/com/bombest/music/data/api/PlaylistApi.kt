package com.bombest.music.data.api

import okhttp3.MultipartBody
import retrofit2.http.*

data class Playlist(
    val id: Int,
    val name: String,
    val count: Int = 0,
    val created_at: String? = null,
    /** Server: null = catalog/system row; else owning user id for private playlists */
    val user_id: Int? = null,
    val is_public: Boolean = false,
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
    val is_public: Boolean = false  // Admin only: publish catalog playlist
)
data class AddTracksRequest(val track_ids: List<Int>)
data class FavoriteToggleRequest(val track_id: Int)
data class FavoriteToggleResponse(val success: Boolean, val favorited: Boolean)

/** Moshi-friendly JSON for mutation endpoints (avoid Map<String, Any>). */
data class PlaylistMutationResponse(
    val success: Boolean? = null,
    val count: Int? = null,
    val art_url: String? = null,
)

data class PlaylistSystemInitResponse(
    val success: Boolean? = null,
    val all_songs_id: Int? = null,
    val favorites_id: Int? = null,
    val error: String? = null,
)

interface PlaylistApi {
    @GET("playlists")
    suspend fun getPlaylists(): PlaylistsResponse

    @POST("playlists")
    suspend fun createPlaylist(@Body request: CreatePlaylistRequest): Playlist

    @PUT("playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: Int, @Body request: CreatePlaylistRequest): PlaylistMutationResponse

    @DELETE("playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Int): PlaylistMutationResponse

    @GET("playlists/{id}/tracks")
    suspend fun getPlaylistTracks(@Path("id") id: Int): TracksResponse

    @Multipart
    @PUT("playlists/{id}/art")
    suspend fun setPlaylistArt(@Path("id") id: Int, @Part image: MultipartBody.Part): PlaylistMutationResponse

    @POST("playlists/{id}/tracks")
    suspend fun addTracksToPlaylist(@Path("id") id: Int, @Body request: AddTracksRequest): PlaylistMutationResponse

    @HTTP(method = "DELETE", path = "playlists/{id}/tracks", hasBody = true)
    suspend fun removeTracksFromPlaylist(@Path("id") id: Int, @Body request: AddTracksRequest): PlaylistMutationResponse

    @POST("playlists/{id}/favorites/toggle")
    suspend fun toggleFavorite(@Path("id") id: Int, @Body request: FavoriteToggleRequest): FavoriteToggleResponse

    @POST("playlists/system/init")
    suspend fun initializeSystemPlaylists(): PlaylistSystemInitResponse

    @PUT("playlists/{id}/reorder")
    suspend fun reorderPlaylistTracks(@Path("id") id: Int, @Body request: AddTracksRequest): PlaylistMutationResponse

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
