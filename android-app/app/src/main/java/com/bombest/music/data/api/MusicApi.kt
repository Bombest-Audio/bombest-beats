package com.bombest.music.data.api

import com.bombest.music.data.model.LibraryResponse
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import com.squareup.moshi.Json

// Response model for waveform data
data class WaveformResponse(
    val peaks: List<Float>,
    val track_id: Int
)

// Response model for beat/bar detection
data class BeatsResponse(
    val bpm: Float,
    val beat_times: List<Float>,
    val bar_times: List<Float>,
    val beats_per_bar: Int,
    val duration: Float,
    val track_id: Int
)

// Dashboard models
data class TopTrack(
    val id: Int,
    val title: String?,
    val artist: String?,
    val plays: Int
)

data class DailyPlay(
    val date: String,
    val count: Int
)

data class DashboardUser(
    val id: Int,
    val username: String
)

data class DashboardResponse(
    val total_plays: Int,
    val top_tracks: List<TopTrack>,
    val daily_plays: List<DailyPlay>,
    val users: List<DashboardUser>? = null
)

interface MusicApi {
    @GET("library")
    suspend fun getLibrary(): LibraryResponse
    
    @DELETE("track/{trackId}")
    suspend fun deleteTrack(
        @Path("trackId") trackId: Int,
        @Header("Authorization") auth: String
    ): Response<Unit>
    
    @GET("waveform/{trackId}")
    suspend fun getWaveform(
        @Path("trackId") trackId: Int
    ): WaveformResponse

    @GET("track/{trackId}/beats")
    suspend fun getBeats(
        @Path("trackId") trackId: Int
    ): BeatsResponse
    
    @GET("metrics/dashboard")
    suspend fun getDashboardStats(
        @Header("Authorization") auth: String,
        @Query("user_id") userId: Int? = null
    ): DashboardResponse
    
    @retrofit2.http.POST("metrics/batch")
    suspend fun batchRecordPlays(
        @retrofit2.http.Body request: BatchPlayRequest,
        @Header("Authorization") auth: String
    ): Response<Unit>

    @DELETE("duplicates")
    suspend fun removeDuplicates(@Header("Authorization") auth: String): RemoveDuplicatesResponse
}

data class BatchPlayRequest(
    val events: List<PlayEvent>
)

data class PlayEvent(
    val track_id: Int,
    val timestamp: String // ISO 8601 or similar
)

data class RemoveDuplicatesResponse(
    val message: String,
    val deleted_ids: List<Int>
)
