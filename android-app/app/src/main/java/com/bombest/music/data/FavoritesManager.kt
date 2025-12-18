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
        
        // #region agent log
        try {
            val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
            val logEntry = org.json.JSONObject().apply {
                put("location", "FavoritesManager.kt:41")
                put("message", "toggleFavorite entry")
                put("data", org.json.JSONObject().apply {
                    put("trackId", trackId)
                    put("wasFavorited", wasFavorited)
                    put("nowFavorited", nowFavorited)
                    put("favoritesPlaylistId", favoritesPlaylistId)
                })
                put("timestamp", System.currentTimeMillis())
                put("sessionId", "debug-session")
                put("runId", "run1")
                put("hypothesisId", "B")
            }
            logFile.appendText(logEntry.toString() + "\n")
        } catch (e: Exception) {}
        // #endregion
        
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
                // #region agent log
                try {
                    val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                    val logEntry = org.json.JSONObject().apply {
                        put("location", "FavoritesManager.kt:59")
                        put("message", "No favorites playlist ID - cannot sync")
                        put("data", org.json.JSONObject().apply {
                            put("trackId", trackId)
                        })
                        put("timestamp", System.currentTimeMillis())
                        put("sessionId", "debug-session")
                        put("runId", "run1")
                        put("hypothesisId", "B")
                    }
                    logFile.appendText(logEntry.toString() + "\n")
                } catch (e: Exception) {}
                // #endregion
                return nowFavorited
            }
            
            Log.d("FavoritesManager", "Calling backend API: toggleFavorite(playlistId=$pid, trackId=$trackId)")
            
            // #region agent log
            try {
                val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                val logEntry = org.json.JSONObject().apply {
                    put("location", "FavoritesManager.kt:70")
                    put("message", "Before API call")
                    put("data", org.json.JSONObject().apply {
                        put("playlistId", pid)
                        put("trackId", trackId)
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("sessionId", "debug-session")
                    put("runId", "run1")
                    put("hypothesisId", "B")
                }
                logFile.appendText(logEntry.toString() + "\n")
            } catch (e: Exception) {
                Log.e("FavoritesManager", "Failed to write debug log", e)
            }
            // #endregion
            
            val response = try {
                playlistApi.toggleFavorite(
                    pid,
                    FavoriteToggleRequest(track_id = trackId)
                )
            } catch (e: Exception) {
                Log.e("FavoritesManager", "API call exception: ${e.message}", e)
                // #region agent log
                try {
                    val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                    val logEntry = org.json.JSONObject().apply {
                        put("location", "FavoritesManager.kt:125")
                        put("message", "API call exception")
                        put("data", org.json.JSONObject().apply {
                            put("error", e.message)
                            put("errorType", e.javaClass.simpleName)
                            put("playlistId", pid)
                            put("trackId", trackId)
                        })
                        put("timestamp", System.currentTimeMillis())
                        put("sessionId", "debug-session")
                        put("runId", "run1")
                        put("hypothesisId", "B")
                    }
                    logFile.appendText(logEntry.toString() + "\n")
                } catch (e2: Exception) {}
                // #endregion
                throw e
            }
            
            // #region agent log
            try {
                val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                val logEntry = org.json.JSONObject().apply {
                    put("location", "FavoritesManager.kt:85")
                    put("message", "After API call")
                    put("data", org.json.JSONObject().apply {
                        put("success", response.success)
                        put("favorited", response.favorited)
                        put("trackId", trackId)
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("sessionId", "debug-session")
                    put("runId", "run1")
                    put("hypothesisId", "B")
                }
                logFile.appendText(logEntry.toString() + "\n")
            } catch (e: Exception) {}
            // #endregion
            
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
            // #region agent log
            try {
                val logFile = java.io.File("/Users/thomasphillips/bombest-audio/.cursor/debug.log")
                val logEntry = org.json.JSONObject().apply {
                    put("location", "FavoritesManager.kt:110")
                    put("message", "Exception in toggleFavorite")
                    put("data", org.json.JSONObject().apply {
                        put("error", e.message)
                        put("trackId", trackId)
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("sessionId", "debug-session")
                    put("runId", "run1")
                    put("hypothesisId", "B")
                }
                logFile.appendText(logEntry.toString() + "\n")
            } catch (e2: Exception) {}
            // #endregion
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
            val favoriteIds = response.tracks.map { it.id }.toSet()
            _favoritedTrackIds.value = favoriteIds
            Log.d("FavoritesManager", "Loaded ${favoriteIds.size} favorites: $favoriteIds")
        } catch (e: Exception) {
            Log.e("FavoritesManager", "Failed to load favorites", e)
        }
    }
}
