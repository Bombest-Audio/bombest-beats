package com.bombest.music.data.api

import com.bombest.music.data.model.LibraryResponse
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

// Response model for waveform data
data class WaveformResponse(
    val peaks: List<Float>,
    val track_id: Int
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
}
