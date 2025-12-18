package com.bombest.music.data

import android.util.Log
import com.bombest.music.data.api.FavoriteToggleRequest
import com.bombest.music.data.api.PlaylistApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        
        Log.d("FavoritesManager", "Toggling favorite for track $trackId (was: $wasFavorited, now: $nowFavorited)")
        
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
                Log.e("FavoritesManager", "No favorites playlist ID set - cannot sync to backend")
                return nowFavorited
            }
            
            Log.d("FavoritesManager", "Calling backend API: toggleFavorite(playlistId=$pid, trackId=$trackId)")
            
            val response = playlistApi.toggleFavorite(
                pid,
                FavoriteToggleRequest(track_id = trackId)
            )
            
            if (response.success) {
                Log.d("FavoritesManager", "Backend sync successful! Track $trackId favorited=${response.favorited}")
                return response.favorited
            } else {
                Log.e("FavoritesManager", "Backend returned success=false for track $trackId")
                // Revert optimistic update on failure
                _favoritedTrackIds.value = _favoritedTrackIds.value.toMutableSet().apply {
                    if (wasFavorited) add(trackId) else remove(trackId)
                }
                return wasFavorited
            }
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to sync favorite toggle for track $trackId", e)
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
            Log.d("FavoritesManager", "Loading favorites from backend (playlist $favoritesId)")
            val response = playlistApi.getPlaylistTracks(favoritesId)
            val favoriteIds = response.items.map { it.id }.toSet()
            _favoritedTrackIds.value = favoriteIds
            Log.d("FavoritesManager", "Loaded ${favoriteIds.size} favorites: $favoriteIds")
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to load favorites", e)
        }
    }
}
