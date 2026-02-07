package com.bombest.musify.musicplayer

import com.bombest.musify.domain.SearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks playback history for back navigation (Spotify-style).
 * Maintains a stack of previously played tracks that can be navigated backwards through.
 */
@Singleton
class PlaybackHistory @Inject constructor() {
    private val history = ArrayDeque<SearchResult.TrackSearchResult>(100) // Limit to 100 tracks
    
    /**
     * Add a track to the history when it starts playing.
     * Called before moving to the next track.
     */
    fun addToHistory(track: SearchResult.TrackSearchResult) {
        // Don't add if it's the same as the last track in history
        if (history.lastOrNull()?.id != track.id) {
            history.addLast(track)
            // Limit history size to prevent memory issues
            if (history.size > 100) {
                history.removeFirst()
            }
        }
    }
    
    /**
     * Get the previous track from history and remove it from history.
     * Returns null if history is empty.
     */
    fun getPreviousFromHistory(): SearchResult.TrackSearchResult? {
        return if (history.isNotEmpty()) {
            history.removeLast()
        } else {
            null
        }
    }
    
    /**
     * Peek at the previous track without removing it.
     */
    fun peekPrevious(): SearchResult.TrackSearchResult? {
        return history.lastOrNull()
    }
    
    /**
     * Clear the history.
     */
    fun clear() {
        history.clear()
    }
    
    /**
     * Get current history size (for debugging).
     */
    fun size(): Int = history.size
    
    /**
     * Get all history tracks (for debugging).
     */
    fun getAll(): List<SearchResult.TrackSearchResult> = history.toList()
}
