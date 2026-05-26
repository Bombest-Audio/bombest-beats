package com.bombest.music.data

import android.content.Context
import androidx.media3.common.Player

/**
 * Persists the user's most recent listening session — current queue, position
 * within the song, and listening mode (shuffle/repeat) — so quitting the app
 * mid-track and relaunching resumes from the same point.
 *
 * Backed by [android.content.SharedPreferences] to match [AutoRecentTracksStore].
 * DataStore is overkill for ~6 keys and would require turning writes into
 * coroutines on Main, which the periodic save loop in
 * [com.bombest.music.service.BombestMediaService] does not need.
 *
 * Writes during playback are best-effort (`apply()`); final-flush calls from
 * `onTaskRemoved` / `onDestroy` use `commit()` so the value reaches disk
 * before the process exits.
 */
class PlaybackSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Persist the currently loaded queue + playback position.
     *
     * Virtual ids (anything starting with [SPECIAL_PREFIX], e.g.
     * `special:shuffle_all`) are stripped — the service expands those into
     * real track ids before they reach the player, but a defensive filter
     * here keeps a partially-expanded queue from poisoning the save.
     */
    fun save(
        queueIds: List<String>,
        currentMediaId: String?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
        sync: Boolean = false,
    ) {
        val clean = queueIds.filterNot { it.startsWith(SPECIAL_PREFIX) }
        if (clean.isEmpty() || currentMediaId == null || currentMediaId.startsWith(SPECIAL_PREFIX)) {
            // Nothing meaningful to resume to — leave any prior save intact
            // rather than clobbering it with a half-built queue.
            return
        }
        val editor = prefs.edit()
            .putString(KEY_QUEUE, clean.joinToString(SEP))
            .putString(KEY_CURRENT_MEDIA_ID, currentMediaId)
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, shuffleEnabled)
            .putInt(KEY_REPEAT_MODE, repeatMode)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
        if (sync) editor.commit() else editor.apply()
    }

    fun load(): SavedSession? {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val ids = raw.split(SEP).filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        return SavedSession(
            queueIds = ids,
            currentMediaId = prefs.getString(KEY_CURRENT_MEDIA_ID, null),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF),
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    data class SavedSession(
        val queueIds: List<String>,
        val currentMediaId: String?,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
        val savedAt: Long,
    )

    companion object {
        private const val PREFS = "bombest_playback_session"
        private const val KEY_QUEUE = "queue_ids"
        private const val KEY_CURRENT_MEDIA_ID = "current_media_id"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_SHUFFLE = "shuffle_enabled"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_SAVED_AT = "saved_at"
        // Newline is safe — mediaIds are numeric track ids or `playlist:N` /
        // `special:...` strings. None of them ever contain a newline.
        private const val SEP = "\n"
        private const val SPECIAL_PREFIX = "special:"
    }
}
