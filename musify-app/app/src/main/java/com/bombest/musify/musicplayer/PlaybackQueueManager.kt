package com.bombest.musify.musicplayer

import com.bombest.musify.domain.SearchResult
import com.bombest.musify.domain.Streamable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PlaybackQueueManager @Inject constructor(
    private val playbackHistory: PlaybackHistory
) {
    private val _queue = MutableStateFlow<List<SearchResult.TrackSearchResult>>(emptyList())
    val queue: StateFlow<List<SearchResult.TrackSearchResult>> = _queue.asStateFlow()
    
    private val _currentIndex = MutableStateFlow<Int?>(null)
    val currentIndex: StateFlow<Int?> = _currentIndex.asStateFlow()
    
    private val _isShuffled = MutableStateFlow(false)
    val isShuffled: StateFlow<Boolean> = _isShuffled.asStateFlow()
    
    private val _repeatMode = MutableStateFlow(RepeatMode.NONE)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()
    
    private var originalQueue: List<SearchResult.TrackSearchResult> = emptyList()
    private var shuffledQueue: List<SearchResult.TrackSearchResult> = emptyList() // Maintain shuffled order
    private val playedInShuffle = mutableSetOf<String>() // Track played songs in shuffle mode (Spotify-style)
    
    enum class RepeatMode {
        NONE,    // Don't repeat
        ALL,     // Repeat all tracks
        ONE      // Repeat current track
    }
    
    fun setQueue(tracks: List<SearchResult.TrackSearchResult>) {
        originalQueue = tracks
        if (_isShuffled.value) {
            // When shuffle is enabled, create a new shuffled queue
            shuffledQueue = tracks.shuffled()
            playedInShuffle.clear() // Reset played tracks
            _queue.value = shuffledQueue
        } else {
            _queue.value = tracks
            playedInShuffle.clear() // Clear when shuffle is disabled
        }
    }
    
    fun setCurrentTrack(track: Streamable) {
        if (track !is SearchResult.TrackSearchResult) return
        
        val index = _queue.value.indexOfFirst { it.id == track.id }
        if (index >= 0) {
            _currentIndex.value = index
        } else {
            // Track not in queue, add it and set as current
            val newQueue = _queue.value + track
            _queue.value = newQueue
            _currentIndex.value = newQueue.size - 1
        }
    }
    
    fun getNextTrack(): SearchResult.TrackSearchResult? {
        val currentIdx = _currentIndex.value ?: return null
        val queueList = _queue.value
        if (queueList.isEmpty()) return null
        
        // Add current track to history before moving to next (Spotify-style)
        val currentTrack = queueList.getOrNull(currentIdx)
        currentTrack?.let { playbackHistory.addToHistory(it) }
        
        // If shuffle is enabled, mark current track as played (Spotify-style: no repeats until all played)
        if (_isShuffled.value && currentTrack != null) {
            playedInShuffle.add(currentTrack.id)
            
            // Check if we've played all songs in the shuffled queue
            if (playedInShuffle.size >= shuffledQueue.size && shuffledQueue.isNotEmpty()) {
                // All songs played - reshuffle and start over (Spotify-style)
                shuffledQueue = originalQueue.shuffled()
                playedInShuffle.clear()
                _queue.value = shuffledQueue
                // Reset to start of new shuffled queue
                _currentIndex.value = 0
                return shuffledQueue.firstOrNull()
            }
        }
        
        val nextTrack = when (_repeatMode.value) {
            RepeatMode.ONE -> {
                // Repeat current track
                queueList.getOrNull(currentIdx)
            }
            RepeatMode.ALL -> {
                if (_isShuffled.value) {
                    // In shuffle mode with repeat all, move to next in shuffled queue
                    // If we've reached the end, reshuffle and continue
                    val nextIdx = currentIdx + 1
                    if (nextIdx >= shuffledQueue.size) {
                        // Reached end of shuffled queue - reshuffle
                        shuffledQueue = originalQueue.shuffled()
                        playedInShuffle.clear()
                        _queue.value = shuffledQueue
                        shuffledQueue.firstOrNull()
                    } else {
                        shuffledQueue.getOrNull(nextIdx)
                    }
                } else {
                    // Move to next, wrap around if at end
                    val nextIdx = (currentIdx + 1) % queueList.size
                    queueList.getOrNull(nextIdx)
                }
            }
            RepeatMode.NONE -> {
                if (_isShuffled.value) {
                    // In shuffle mode, move to next unplayed track
                    val nextIdx = currentIdx + 1
                    if (nextIdx < shuffledQueue.size) {
                        shuffledQueue.getOrNull(nextIdx)
                    } else {
                        // Reached end - reshuffle and start over
                        shuffledQueue = originalQueue.shuffled()
                        playedInShuffle.clear()
                        _queue.value = shuffledQueue
                        shuffledQueue.firstOrNull()
                    }
                } else {
                    // Move to next, return null if at end
                    queueList.getOrNull(currentIdx + 1)
                }
            }
        }
        
        // Update index if we got a next track
        nextTrack?.let {
            val nextIdx = when {
                _repeatMode.value == RepeatMode.ONE -> currentIdx
                _isShuffled.value && _repeatMode.value == RepeatMode.NONE -> {
                    // If we reshuffled, index is 0, otherwise currentIdx + 1
                    if (playedInShuffle.isEmpty() && currentIdx + 1 >= shuffledQueue.size) 0 else currentIdx + 1
                }
                _isShuffled.value && _repeatMode.value == RepeatMode.ALL -> {
                    if (currentIdx + 1 >= shuffledQueue.size) 0 else currentIdx + 1
                }
                _repeatMode.value == RepeatMode.ALL -> (currentIdx + 1) % queueList.size
                else -> currentIdx + 1
            }
            if (nextIdx < queueList.size || _repeatMode.value == RepeatMode.ALL || _isShuffled.value) {
                _currentIndex.value = if (nextIdx >= queueList.size) 0 else nextIdx
            }
        }
        
        return nextTrack
    }
    
    fun getPreviousTrack(): SearchResult.TrackSearchResult? {
        val currentIdx = _currentIndex.value ?: return null
        val queueList = _queue.value
        if (queueList.isEmpty()) return null
        
        val prevTrack = when (_repeatMode.value) {
            RepeatMode.ONE -> {
                // Repeat current track
                queueList.getOrNull(currentIdx)
            }
            RepeatMode.ALL -> {
                // Move to previous, wrap around if at start
                val prevIdx = if (currentIdx == 0) queueList.size - 1 else currentIdx - 1
                queueList.getOrNull(prevIdx)
            }
            RepeatMode.NONE -> {
                // Move to previous, return null if at start
                if (currentIdx > 0) queueList.getOrNull(currentIdx - 1) else null
            }
        }
        
        // Update index if we got a previous track
        prevTrack?.let {
            val prevIdx = when (_repeatMode.value) {
                RepeatMode.ONE -> currentIdx
                RepeatMode.ALL -> if (currentIdx == 0) queueList.size - 1 else currentIdx - 1
                RepeatMode.NONE -> if (currentIdx > 0) currentIdx - 1 else null
            }
            if (prevIdx != null && prevIdx >= 0) {
                _currentIndex.value = prevIdx
            }
        }
        
        return prevTrack
    }
    
    fun toggleShuffle() {
        val wasShuffled = _isShuffled.value
        _isShuffled.value = !wasShuffled
        
        val currentTrack = _currentIndex.value?.let { _queue.value.getOrNull(it) }
        
        if (!wasShuffled) {
            // Enable shuffle - create a shuffled order and maintain it (Spotify-style)
            // Shuffle all tracks, but keep current track first if it exists in original queue
            val queueList = originalQueue.toMutableList()
            val currentIdx = originalQueue.indexOfFirst { it.id == currentTrack?.id }.takeIf { it >= 0 }
            
            if (currentIdx != null && currentIdx < queueList.size) {
                // Current track is in original queue - put it first, shuffle the rest
                val current = queueList.removeAt(currentIdx)
                val remaining = queueList.shuffled()
                shuffledQueue = listOf(current) + remaining
            } else {
                // Current track not in original queue, just shuffle everything
                shuffledQueue = queueList.shuffled()
            }
            
            // Reset played tracks when enabling shuffle
            playedInShuffle.clear()
            if (currentTrack != null) {
                // Mark current track as played since we're starting from it
                playedInShuffle.add(currentTrack.id)
            }
            
            _queue.value = shuffledQueue
            _currentIndex.value = 0
        } else {
            // Disable shuffle - restore original order
            playedInShuffle.clear()
            _queue.value = originalQueue
            shuffledQueue = emptyList()
            currentTrack?.let { setCurrentTrack(it) }
        }
    }
    
    fun toggleRepeat(): RepeatMode {
        val current = _repeatMode.value
        val next = when (current) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        _repeatMode.value = next
        return next
    }
    
    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }
    
    fun setShuffleMode(enabled: Boolean) {
        if (_isShuffled.value != enabled) {
            toggleShuffle()
        }
    }
    
    fun getCurrentTrack(): SearchResult.TrackSearchResult? {
        return _currentIndex.value?.let { _queue.value.getOrNull(it) }
    }
}
