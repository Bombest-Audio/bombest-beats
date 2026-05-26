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

    private val appContext = context.applicationContext

    /**
     * Queue file. ONE key (`queue_ids`) only. In production [saveQueue] writes
     * here via `apply()` and only when the timeline actually changes; tests
     * can opt into `commit()` via the `sync` parameter. Kept in its own file
     * so the synchronous final-flush in [savePosition] doesn't pay the cost
     * of rewriting a potentially-large joined-mediaIds blob.
     *
     * Reuses the original PREFS file name so existing devices upgrade
     * in-place — the queue payload lives where it always did. Position keys
     * from the original single-file layout are migrated to [statePrefs] on
     * first [load].
     */
    private val queuePrefs = appContext.getSharedPreferences(PREFS_QUEUE, Context.MODE_PRIVATE)

    /**
     * State file. Holds the 6 cheap keys (currentMediaId, currentIndex,
     * positionMs, shuffleEnabled, repeatMode, savedAt). [savePosition] writes
     * only here, so a sync `commit()` on teardown only rewrites this small
     * file — never the queue blob.
     */
    private val statePrefs = appContext.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE)

    /**
     * One-shot flag: have we migrated the legacy single-file payload to the
     * new two-file layout yet? Set after the first [load] runs the migration.
     */
    @Volatile
    private var migratedFromLegacy = false

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
     * mode. Does NOT rewrite the queue — callers must invoke [saveQueue]
     * separately when the timeline actually changes.
     *
     * Writes go to [statePrefs] only, so a `sync = true` final-flush rewrites
     * only the small state file — never the queue blob. The whole point of
     * splitting prefs files in this store is to keep this path's synchronous
     * commit truly small even when the queue grows large.
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
        val editor = statePrefs.edit()
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
     * Writes go to [queuePrefs] (its own file), so they never affect the
     * state-file's commit cost. Cheap when called with an unchanged queue:
     * the in-memory [lastSavedQueue] cache short-circuits the joinToString +
     * write entirely. Returns whether a disk write was actually scheduled.
     */
    fun saveQueue(queueIds: List<String>, sync: Boolean = false): Boolean {
        val clean = queueIds.filterNot { it.startsWith(SPECIAL_PREFIX) }
        if (clean.isEmpty()) return false
        if (clean == lastSavedQueue) return false
        lastSavedQueue = clean
        val editor = queuePrefs.edit().putString(KEY_QUEUE, clean.joinToString(SEP))
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
        // First call after upgrade: copy any legacy state keys from the old
        // single-file layout into the new state file, then drop the legacy
        // copies. Idempotent — guarded by migratedFromLegacy. The queue lives
        // in queuePrefs (the same file name as before) and needs no migration.
        if (!migratedFromLegacy) {
            migrateLegacyStateIfNeeded()
            migratedFromLegacy = true
        }
        val raw = queuePrefs.getString(KEY_QUEUE, null) ?: return null
        val ids = raw.split(SEP).filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        return SavedSession(
            queueIds = ids,
            currentMediaId = statePrefs.getString(KEY_CURRENT_MEDIA_ID, null),
            currentIndex = statePrefs.getInt(KEY_CURRENT_INDEX, 0),
            positionMs = statePrefs.getLong(KEY_POSITION_MS, 0L),
            shuffleEnabled = statePrefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = statePrefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF),
            savedAt = statePrefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun clear() {
        lastSavedQueue = null
        queuePrefs.edit().clear().apply()
        statePrefs.edit().clear().apply()
    }

    /**
     * Copy state keys (everything except queue_ids) that may have been written
     * by older versions of this class into the new state file, then remove
     * them from the queue file. Safe to call on a fresh install (no-op) and
     * on every subsequent launch (idempotent — the state file already wins on
     * second-and-later loads).
     *
     * Crash-safety: both writes use `commit()`, and the state-prefs write is
     * fully durable before the legacy keys are removed. A process kill at any
     * point either leaves the data fully in the old location (state-prefs
     * write didn't land yet) or fully in the new location (state-prefs write
     * landed, legacy removal may or may not have). The migration is
     * idempotent, so a repeat on next launch is harmless.
     */
    private fun migrateLegacyStateIfNeeded() {
        // Skip if the new state file already has data (already migrated, or
        // the user started fresh on the new layout).
        if (statePrefs.contains(KEY_CURRENT_MEDIA_ID)) return
        if (!queuePrefs.contains(KEY_CURRENT_MEDIA_ID)) return
        val editor = statePrefs.edit()
        queuePrefs.getString(KEY_CURRENT_MEDIA_ID, null)
            ?.let { editor.putString(KEY_CURRENT_MEDIA_ID, it) }
        editor.putInt(KEY_CURRENT_INDEX, queuePrefs.getInt(KEY_CURRENT_INDEX, 0))
        editor.putLong(KEY_POSITION_MS, queuePrefs.getLong(KEY_POSITION_MS, 0L))
        editor.putBoolean(KEY_SHUFFLE, queuePrefs.getBoolean(KEY_SHUFFLE, false))
        editor.putInt(KEY_REPEAT_MODE, queuePrefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF))
        editor.putLong(KEY_SAVED_AT, queuePrefs.getLong(KEY_SAVED_AT, 0L))
        // commit() so the new-file write is fully durable BEFORE we touch the
        // legacy keys. With apply() the two writes could ack out of order on
        // a crash, leaving the legacy keys removed but never persisted to
        // statePrefs — silently losing the user's saved session position.
        // This runs once per install, so the synchronous I/O cost is fine.
        editor.commit()
        queuePrefs.edit()
            .remove(KEY_CURRENT_MEDIA_ID)
            .remove(KEY_CURRENT_INDEX)
            .remove(KEY_POSITION_MS)
            .remove(KEY_SHUFFLE)
            .remove(KEY_REPEAT_MODE)
            .remove(KEY_SAVED_AT)
            .commit()
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
        // Queue file: keeps the original PREFS name so existing devices
        // upgrade in-place (the queue blob's been here all along).
        private const val PREFS_QUEUE = "bombest_playback_session"
        // State file: new name for the cheap per-tick keys, separated so the
        // synchronous final-flush only rewrites this small file.
        private const val PREFS_STATE = "bombest_playback_session_state"
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
