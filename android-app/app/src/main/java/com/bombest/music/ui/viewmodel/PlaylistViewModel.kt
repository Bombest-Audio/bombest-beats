package com.bombest.music.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import okhttp3.MultipartBody
import com.bombest.music.data.api.*
import com.bombest.music.data.FavoritesManager
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.authDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class PlaylistViewModel : ViewModel() {

    private lateinit var playlistApi: PlaylistApi
    private val authBound = AtomicBoolean(false)
    private var lastAuthTokenForPlaylist: String? = null
    private var playlistAuthDataReady = false

    val playlists = mutableStateListOf<Playlist>()
    val currentPlaylistTracks = mutableStateListOf<Track>()
    val allTracks = mutableStateListOf<Track>()
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val currentPlaylistName = mutableStateOf("")
    /** Playlist id for the open detail screen, if any. */
    val viewingPlaylistId = mutableStateOf<Int?>(null)
    val currentUserId = mutableStateOf<Int?>(null)
    val userRole = mutableStateOf<String?>(null)

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessages = _userMessages.asSharedFlow()

    private fun notifyUser(msg: String) {
        viewModelScope.launch {
            _userMessages.emit(msg)
        }
    }

    fun initialize(context: Context) {
        if (!authBound.compareAndSet(false, true)) return
        viewModelScope.launch {
            context.authDataStore.data.collect { prefs ->
                NetworkModule.setPlaylistBearerToken(prefs[AuthPreferences.TOKEN_KEY])
                currentUserId.value = prefs[AuthPreferences.USER_ID_KEY]?.toIntOrNull()
                userRole.value = prefs[AuthPreferences.ROLE_KEY]
                playlistApi = NetworkModule.getPlaylistApi()
                val tok = prefs[AuthPreferences.TOKEN_KEY]
                if (!playlistAuthDataReady || tok != lastAuthTokenForPlaylist) {
                    playlistAuthDataReady = true
                    lastAuthTokenForPlaylist = tok
                    loadPlaylists()
                    refreshAllTracks()
                }
            }
        }
    }

    /** Whether the current user may edit this playlist (owner or admin). */
    fun canEditPlaylist(playlist: Playlist?): Boolean {
        if (playlist == null) return false
        if (userRole.value == "admin") return true
        val uid = currentUserId.value ?: return false
        return playlist.user_id != null && playlist.user_id == uid
    }

    fun refreshAllTracks() {
        viewModelScope.launch {
            try {
                val response = NetworkModule.api.getLibrary()
                allTracks.clear()
                allTracks.addAll(response.items.map { item ->
                    Track(
                        id = item.id,
                        title = item.title ?: "Unknown",
                        artist = item.artist ?: "Unknown",
                        album = item.album,
                        duration = item.length,
                        path = item.path ?: ""
                    )
                })
                android.util.Log.d("PlaylistViewModel", "Loaded ${allTracks.size} library tracks")
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "Failed to load library: ${e.message}")
                notifyUser("Could not load library: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            if (!::playlistApi.isInitialized) return@launch
            isLoading.value = true
            try {
                val response = playlistApi.getPlaylists()
                playlists.clear()
                playlists.addAll(response.playlists)
                val favoritesPlaylist = response.playlists.find { it.name == "Favorites" }
                if (favoritesPlaylist != null) {
                    FavoritesManager.initialize(playlistApi, favoritesPlaylist.id)
                    FavoritesManager.loadFavorites(favoritesPlaylist.id)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load playlists"
                error.value = msg
                notifyUser(msg)
            }
            isLoading.value = false
        }
    }

    fun createPlaylist(name: String, isPublic: Boolean = false) {
        viewModelScope.launch {
            try {
                val pub = if (userRole.value == "admin") isPublic else false
                val playlist = playlistApi.createPlaylist(CreatePlaylistRequest(name, pub))
                playlists.add(0, playlist)
                loadPlaylists()
                notifyUser("Playlist created")
            } catch (e: Exception) {
                val msg = e.message ?: "Create failed"
                android.util.Log.e("PlaylistViewModel", msg, e)
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    fun deletePlaylist(id: Int) {
        viewModelScope.launch {
            try {
                playlistApi.deletePlaylist(id)
                playlists.removeAll { it.id == id }
                notifyUser("Playlist deleted")
            } catch (e: Exception) {
                val msg = e.message ?: "Delete failed"
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    suspend fun getPlaylistTracksOnce(id: Int): List<Track> = withContext(Dispatchers.IO) {
        try {
            if (!::playlistApi.isInitialized) return@withContext emptyList()
            playlistApi.getPlaylistTracks(id).tracks
        } catch (e: Exception) {
            android.util.Log.e("PlaylistViewModel", "getPlaylistTracksOnce: ${e.message}", e)
            emptyList()
        }
    }

    fun loadPlaylistTracks(id: Int, name: String) {
        viewingPlaylistId.value = id
        currentPlaylistName.value = name
        viewModelScope.launch {
            if (!::playlistApi.isInitialized) return@launch
            isLoading.value = true
            try {
                val response = playlistApi.getPlaylistTracks(id)
                currentPlaylistTracks.clear()
                currentPlaylistTracks.addAll(response.tracks)
                updatePlaylistCountInList(id, response.tracks.size)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load tracks"
                android.util.Log.e("PlaylistViewModel", msg, e)
                error.value = msg
                notifyUser(msg)
            }
            isLoading.value = false
        }
    }

    private fun updatePlaylistCountInList(playlistId: Int, count: Int) {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index >= 0) {
            val p = playlists[index]
            playlists[index] = p.copy(count = count)
        }
    }

    fun renamePlaylist(playlistId: Int, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                playlistApi.updatePlaylist(playlistId, CreatePlaylistRequest(trimmed, false))
                val idx = playlists.indexOfFirst { it.id == playlistId }
                if (idx >= 0) {
                    playlists[idx] = playlists[idx].copy(name = trimmed)
                }
                if (viewingPlaylistId.value == playlistId) {
                    currentPlaylistName.value = trimmed
                }
                notifyUser("Playlist renamed")
            } catch (e: Exception) {
                val msg = e.message ?: "Rename failed"
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    /** Move a track up (-1) or down (+1) and persist order via API. */
    fun reorderTrack(playlistId: Int, fromIndex: Int, direction: Int) {
        if (fromIndex < 0 || fromIndex >= currentPlaylistTracks.size) return
        val toIndex = (fromIndex + direction).coerceIn(0, currentPlaylistTracks.lastIndex)
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            try {
                val order = currentPlaylistTracks.toMutableList()
                val item = order.removeAt(fromIndex)
                order.add(toIndex, item)
                val ids = order.map { it.id }
                playlistApi.reorderPlaylistTracks(playlistId, AddTracksRequest(ids))
                currentPlaylistTracks.clear()
                currentPlaylistTracks.addAll(order)
            } catch (e: Exception) {
                val msg = e.message ?: "Reorder failed"
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: Int, trackId: Int) {
        viewModelScope.launch {
            try {
                playlistApi.removeTracksFromPlaylist(playlistId, AddTracksRequest(listOf(trackId)))
                currentPlaylistTracks.removeAll { it.id == trackId }
                updatePlaylistCountInList(playlistId, currentPlaylistTracks.size)
            } catch (e: Exception) {
                val msg = e.message ?: "Remove failed"
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    fun addTracksToPlaylist(playlistId: Int, trackIds: List<Int>) {
        viewModelScope.launch {
            try {
                playlistApi.addTracksToPlaylist(playlistId, AddTracksRequest(trackIds))
                isLoading.value = true
                try {
                    val response = playlistApi.getPlaylistTracks(playlistId)
                    currentPlaylistTracks.clear()
                    currentPlaylistTracks.addAll(response.tracks)
                    updatePlaylistCountInList(playlistId, response.tracks.size)
                } finally {
                    isLoading.value = false
                }
                loadPlaylists()
                notifyUser("Tracks added")
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "addTracks: ${e.message}", e)
                val msg = e.message ?: "Add tracks failed"
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    fun uploadPlaylistArt(playlistId: Int, imagePart: MultipartBody.Part) {
        viewModelScope.launch {
            try {
                playlistApi.setPlaylistArt(playlistId, imagePart)
                loadPlaylists()
                if (currentPlaylistName.value.isNotEmpty()) {
                    loadPlaylistTracks(playlistId, currentPlaylistName.value)
                }
                notifyUser("Cover updated")
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "upload art: ${e.message}", e)
                val msg = e.message ?: "Upload failed"
                error.value = msg
                notifyUser(msg)
            }
        }
    }

    suspend fun sharePlaylist(playlistId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val response = playlistApi.sharePlaylist(playlistId)
                withContext(Dispatchers.Main) {
                    val index = playlists.indexOfFirst { it.id == playlistId }
                    if (index >= 0) {
                        playlists[index] = playlists[index].copy(share_token = response.share_token)
                    }
                }
                response.share_url
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "share: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    error.value = e.message
                    notifyUser(e.message ?: "Share failed")
                }
                null
            }
        }
    }

    suspend fun unsharePlaylist(playlistId: Int) {
        withContext(Dispatchers.IO) {
            try {
                val response = playlistApi.unsharePlaylist(playlistId)
                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        val index = playlists.indexOfFirst { it.id == playlistId }
                        if (index >= 0) {
                            playlists[index] = playlists[index].copy(share_token = null)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        notifyUser("Failed to unshare")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error.value = e.message
                    notifyUser(e.message ?: "Unshare failed")
                }
            }
        }
    }
}
