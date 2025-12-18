package com.bombest.music.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bombest.music.data.api.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class PlaylistViewModel : ViewModel() {
    
    private lateinit var playlistApi: PlaylistApi
    
    val playlists = mutableStateListOf<Playlist>()
    val currentPlaylistTracks = mutableStateListOf<Track>()
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val currentPlaylistName = mutableStateOf("")
    
    fun initialize(context: Context) {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(com.bombest.music.data.NetworkModule.currentBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        
        playlistApi = retrofit.create(PlaylistApi::class.java)
        loadPlaylists()
    }
    
    fun loadPlaylists() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                val response = playlistApi.getPlaylists()
                playlists.clear()
                playlists.addAll(response.playlists)
            } catch (e: Exception) {
                error.value = e.message
            }
            isLoading.value = false
        }
    }
    
    fun createPlaylist(name: String, isPublic: Boolean = false) {
        viewModelScope.launch {
            try {
                val playlist = playlistApi.createPlaylist(CreatePlaylistRequest(name, isPublic))
                playlists.add(0, playlist)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }
    
    fun deletePlaylist(id: Int) {
        viewModelScope.launch {
            try {
                playlistApi.deletePlaylist(id)
                playlists.removeAll { it.id == id }
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }
    
    fun loadPlaylistTracks(id: Int, name: String) {
        currentPlaylistName.value = name
        viewModelScope.launch {
            isLoading.value = true
            try {
                val response = playlistApi.getPlaylistTracks(id)
                currentPlaylistTracks.clear()
                currentPlaylistTracks.addAll(response.tracks)
            } catch (e: Exception) {
                error.value = e.message
            }
            isLoading.value = false
        }
    }
    
    fun removeTrackFromPlaylist(playlistId: Int, trackId: Int) {
        viewModelScope.launch {
            try {
                playlistApi.removeTracksFromPlaylist(playlistId, AddTracksRequest(listOf(trackId)))
                currentPlaylistTracks.removeAll { it.id == trackId }
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }
}
