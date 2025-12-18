package com.bombest.music.data

import android.util.Log
import com.bombest.music.data.api.FavoriteToggleRequest
import com.bombest.music.data.api.PlaylistApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton manager for favorites state and synchronization.
 * Provides reactive favorites updates across the app.
 */
object FavoritesManager {
    private val _favoritedTrackIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoritedTrackIds: StateFlow<Set<Int>> = _favoritedTrackIds.asStateFlow()
    
    private var favoritesPlaylistId: Int? = null
    private lateinit var playlistApi: PlaylistApi
    
    /**
     * Initialize with API client and favorites playlist ID
     */
    fun initialize(api: PlaylistApi, favoritesId: Int) {
        playlistApi = api
        favoritesPlaylistId = favoritesId
        Log.d("FavoritesManager", "Initialized with favorites playlist ID: $favoritesId")
    }
    
    /**
     * Check if a track is favorited
     */
    fun isFavorited(trackId: Int): Boolean {
        return _favoritedTrackIds.value.contains(trackId)
    }
    
    /**
     * Toggle favorite status for a track
     * Updates local state immediately (optimistic) and syncs to backend
     */
    suspend fun toggleFavorite(trackId: Int, playlistId: Int? = null): Boolean {
        val wasFavorited = isFavorited(trackId)
        val nowFavorited = !wasFavorited
        
        // Optimistic update
        val currentFavorites = _favoritedTrackIds.value.toMutableSet()
        if (nowFavorited) {
            currentFavorites.add(trackId)
        } else {
            currentFavorites.remove(trackId)
        }
        _favoritedTrackIds.value = currentFavorites
        
        // Sync to backend
        try {
            val pid = playlistId ?: favoritesPlaylistId ?: run {
                Log.e("FavoritesManager", "No favorites playlist ID set")
                return nowFavorited
            }
            
            val response = playlistApi.toggleFavorite(
                pid,
                FavoriteToggleRequest(trackId = trackId)
            )
            
            if (response.success) {
                Log.d("FavoritesManager", "Toggled favorite for track $trackId: ${response.favorited}")
                // Backend confirms our optimistic update
                return response.favorited
            } else {
                // Revert optimistic update on failure
                _favoritedTrackIds.value = _favoritedTrackIds.value.toMutableSet().apply {
                    if (wasFavorited) add(trackId) else remove(trackId)
                }
                return wasFavorited
            }
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to sync favorite toggle", e)
            // Revert on error
            _favoritedTrackIds.value = _favoritedTrackIds.value.toMutableSet().apply {
                if (wasFavorited) add(trackId) else remove(trackId)
            }
            return wasFavorited
        }
    }
    
    /**
     * Load favorites from backend (call on app start)
     */
    suspend fun loadFavorites(favoritesId: Int) {
        try {
            val response = playlistApi.getPlaylistTracks(favoritesId)
            val favoriteIds = response.items.map { it.id }.toSet()
            _favoritedTrackIds.value = favoriteIds
            Log.d("FavoritesManager", "Loaded ${favoriteIds.size} favorites")
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to load favorites", e)
        }
    }
}
