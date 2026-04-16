package com.bombest.music.data

import android.content.Context

/**
 * Persists recently played track IDs for Android Auto / car "Recently played" browse.
 * Order is most-recent first; capped for storage and UI performance.
 */
class AutoRecentTracksStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordPlayed(trackId: Int?) {
        if (trackId == null || trackId <= 0) return
        val ids = getRecentIds().toMutableList()
        ids.remove(trackId)
        ids.add(0, trackId)
        while (ids.size > MAX) ids.removeAt(ids.lastIndex)
        prefs.edit().putString(KEY_IDS, ids.joinToString(",")).apply()
    }

    fun getRecentIds(): List<Int> {
        val s = prefs.getString(KEY_IDS, null) ?: return emptyList()
        return s.split(',').mapNotNull { it.toIntOrNull() }
    }

    companion object {
        private const val PREFS = "bombest_auto_recent_tracks"
        private const val KEY_IDS = "track_ids"
        private const val MAX = 40
    }
}
