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

    @retrofit2.http.POST("metrics/performance")
    suspend fun batchRecordPerfEvents(
        @retrofit2.http.Body request: PerfBatchRequest,
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

/**
 * Flat union type for all performance event flavors. The [event_type] discriminator tells
 * the server which fields are populated:
 *   "AudioUnderrun"  — buffer_ms, elapsed_since_feed_ms
 *   "LoadError"      — error_type, error_message
 *   "BandwidthSample"— bitrate_kbps, bytes_loaded, load_time_ms
 *   "OffloadState"   — sleeping
 */
data class PerfEventPayload(
    val event_type: String,
    val timestamp: String,
    val track_id: String? = null,
    val format: String? = null,
    val connection: String? = null,
    val buffer_ms: Long? = null,
    val elapsed_since_feed_ms: Long? = null,
    val error_type: String? = null,
    val error_message: String? = null,
    val bitrate_kbps: Long? = null,
    val bytes_loaded: Long? = null,
    val load_time_ms: Int? = null,
    val sleeping: Boolean? = null
)

data class PerfBatchRequest(val events: List<PerfEventPayload>)

data class RemoveDuplicatesResponse(
    val message: String,
    val deleted_ids: List<Int>
)
