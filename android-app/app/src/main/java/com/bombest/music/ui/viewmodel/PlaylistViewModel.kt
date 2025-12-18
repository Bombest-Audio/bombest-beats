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
    val allTracks = mutableStateListOf<Track>()  // All library tracks for picker
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
        
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(com.bombest.music.data.NetworkModule.currentBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        
        playlistApi = retrofit.create(PlaylistApi::class.java)
        loadPlaylists()
        loadAllTracks()  // Load library tracks for picker
    }
    
    private fun loadAllTracks() {
        viewModelScope.launch {
            try {
                val response = com.bombest.music.data.NetworkModule.api.getLibrary()
                allTracks.clear()
                allTracks.addAll(response.items.map { item ->
                    Track(
                        id = item.id,
                        title = item.title ?: "Unknown",
                        artist = item.artist ?: "Unknown",
                        album = item.album,
                        duration = item.length,  // model.Track uses 'length' not 'duration'
                        path = item.path ?: ""   // path is nullable in model.Track
                    )
                })
                android.util.Log.d("PlaylistViewModel", "Loaded ${allTracks.size} library tracks")
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "Failed to load library: ${e.message}")
            }
        }
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
                android.util.Log.d("PlaylistViewModel", "Creating playlist: $name, isPublic: $isPublic")
                val playlist = playlistApi.createPlaylist(CreatePlaylistRequest(name, isPublic))
                android.util.Log.d("PlaylistViewModel", "Playlist created: ${playlist.id}, ${playlist.name}")
                playlists.add(0, playlist)
                // Also reload to ensure sync
                loadPlaylists()
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "Failed to create playlist: ${e.message}", e)
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
    
    fun addTracksToPlaylist(playlistId: Int, trackIds: List<Int>) {
        viewModelScope.launch {
            try {
                android.util.Log.d("PlaylistViewModel", "Adding ${trackIds.size} tracks to playlist $playlistId")
                playlistApi.addTracksToPlaylist(playlistId, AddTracksRequest(trackIds))
                // Reload playlist tracks to refresh the list
                loadPlaylistTracks(playlistId, currentPlaylistName.value)
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "Failed to add tracks: ${e.message}", e)
                error.value = e.message
            }
        }
    }
}
