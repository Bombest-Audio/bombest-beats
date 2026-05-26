package com.bombest.music.data

import android.content.Context
import androidx.media3.common.Player

/**
 * Persists the user's most recent listening session — current queue, position
 * within the song, and listening mode (shuffle/repeat) — so quitting the app
 * mid-track and relaunching resumes from the same point.
 *
 * Backed by [android.content.SharedPreferences] to match [AutoRecentTracksStore].
 * DataStore is overkill for ~7 keys and would force coroutine context-switching
 * on the per-5s save tick.
 *
 * # Save shape
 * Writes are split into two methods so the hot path stays cheap:
 *
 *  * [savePosition] — small payload (~5 keys, no list serialization). Called
 *    every 5s and on every transition / pause / seek / mode change.
 *  * [saveQueue]    — heavy payload (joins all queueIds into one string).
 *    Called only when the timeline actually changes, and a dedup cache
 *    (`lastSavedQueue`) short-circuits no-op writes.
 *
 * SharedPreferences' on-disk format is whole-file rewrite per commit, so
 * splitting the write doesn't reduce disk I/O — but it avoids the in-memory
 * cost of serializing a multi-hundred-element queue on every tick, which is
 * the dominant cost on the main thread.
 *
 * Writes during playback are best-effort (`apply()`); final-flush calls from
 * `onTaskRemoved` / `onDestroy` should call [savePosition] with `sync = true`.
 * They intentionally do not re-call [saveQueue] — the queue is already
 * persisted from the last `onTimelineChanged` and rewriting a 1000-element
 * string with `commit()` on Main is the exact ANR risk we're avoiding.
 */
class PlaybackSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * In-memory cache of the last successfully-persisted queue. [saveQueue]
     * compares the incoming list against this and skips the write (and the
     * joinToString that would precede it) if the queue is identical.
     *
     * `@Volatile` so reads from any thread see the latest writer's value;
     * writes happen from the service's `Dispatchers.Main` only, so no other
     * synchronisation is needed.
     */
    @Volatile
    private var lastSavedQueue: List<String>? = null

    /**
     * Persist the cheap, frequently-changing part of the session: position
     * within the current track, current track id, queue index, and listening
     * mode. Does NOT rewrite [KEY_QUEUE] — callers must invoke [saveQueue]
     * separately when the timeline actually changes.
     *
     * Skips writes that would clobber a valid prior save with a half-built
     * state (e.g. virtual `special:` ids reaching the player before they're
     * expanded).
     */
    fun savePosition(
        currentMediaId: String?,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
        sync: Boolean = false,
    ) {
        if (currentMediaId == null || currentMediaId.startsWith(SPECIAL_PREFIX)) return
        val editor = prefs.edit()
            .putString(KEY_CURRENT_MEDIA_ID, currentMediaId)
            .putInt(KEY_CURRENT_INDEX, currentIndex.coerceAtLeast(0))
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, shuffleEnabled)
            .putInt(KEY_REPEAT_MODE, repeatMode)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
        if (sync) editor.commit() else editor.apply()
    }

    /**
     * Persist the current queue. Virtual ids (anything starting with
     * [SPECIAL_PREFIX], e.g. `special:shuffle_all`) are stripped — the service
     * expands those into real track ids before they reach the player, but a
     * defensive filter here keeps a partially-expanded queue from poisoning
     * the save.
     *
     * Cheap when called with an unchanged queue: the in-memory
     * [lastSavedQueue] cache short-circuits the joinToString + write entirely.
     * Returns whether a disk write was actually scheduled.
     */
    fun saveQueue(queueIds: List<String>, sync: Boolean = false): Boolean {
        val clean = queueIds.filterNot { it.startsWith(SPECIAL_PREFIX) }
        if (clean.isEmpty()) return false
        if (clean == lastSavedQueue) return false
        lastSavedQueue = clean
        val editor = prefs.edit().putString(KEY_QUEUE, clean.joinToString(SEP))
        if (sync) editor.commit() else editor.apply()
        return true
    }

    /**
     * Tell the dedup cache that [queueIds] is already on disk without doing a
     * write. Used right after restoring a saved session so the immediate
     * `onTimelineChanged` callback (which fires for the restore's own
     * `setMediaItems` call) doesn't trigger a redundant re-write of the same
     * queue we just loaded.
     */
    fun markQueueAsSaved(queueIds: List<String>) {
        lastSavedQueue = queueIds.filterNot { it.startsWith(SPECIAL_PREFIX) }
    }

    fun load(): SavedSession? {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val ids = raw.split(SEP).filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        return SavedSession(
            queueIds = ids,
            currentMediaId = prefs.getString(KEY_CURRENT_MEDIA_ID, null),
            currentIndex = prefs.getInt(KEY_CURRENT_INDEX, 0),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF),
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun clear() {
        lastSavedQueue = null
        prefs.edit().clear().apply()
    }

    data class SavedSession(
        val queueIds: List<String>,
        val currentMediaId: String?,
        /**
         * Queue index at save time. Used as a fallback when [currentMediaId]
         * can't be resolved against the restored library snapshot (e.g. the
         * track has since been removed). Always clamped to the resolved queue
         * size on restore.
         */
        val currentIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
        val savedAt: Long,
    )

    companion object {
        private const val PREFS = "bombest_playback_session"
        private const val KEY_QUEUE = "queue_ids"
        private const val KEY_CURRENT_MEDIA_ID = "current_media_id"
        private const val KEY_CURRENT_INDEX = "current_index"
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
