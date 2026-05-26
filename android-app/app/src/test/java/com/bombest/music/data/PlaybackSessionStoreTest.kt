package com.bombest.music.data

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [PlaybackSessionStore]. The store has several edge
 * cases that directly affect cold-start playback restore, so each one gets a
 * focused test:
 *
 *  - `special:` virtual ids are filtered from both [saveQueue] and
 *    [savePosition] inputs
 *  - [savePosition] is a no-op when currentMediaId is null
 *  - [saveQueue] is a no-op when the clean queue is empty
 *  - The queue/state round-trip preserves all fields including the fallback
 *    [PlaybackSessionStore.SavedSession.currentIndex]
 *  - The in-memory dedup cache short-circuits identical-queue writes
 *  - [PlaybackSessionStore.markQueueAsSaved] primes the cache so the next
 *    [saveQueue] with the same ids is a no-op
 *  - [load] returns null when no queue is on disk
 *  - The empty-string handling on queue split round-trips correctly
 *  - Legacy single-file payload migrates to the new two-file layout on first
 *    load (without losing the queue)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackSessionStoreTest {

    private lateinit var context: Context
    private lateinit var store: PlaybackSessionStore

    // Match the private constants in PlaybackSessionStore. Kept duplicated
    // (rather than reflective access) so a rename of either side fails the
    // test loudly instead of silently mismatching prefs files.
    private val prefsQueueName = "bombest_playback_session"
    private val prefsStateName = "bombest_playback_session_state"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Fresh slate per test — Robolectric persists SharedPreferences across
        // tests in the same VM, so clear both files explicitly.
        context.getSharedPreferences(prefsQueueName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(prefsStateName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = PlaybackSessionStore(context)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `load returns null when nothing saved`() {
        assertNull(store.load())
    }

    @Test
    fun `saveQueue then savePosition then load returns the full session`() {
        store.saveQueue(listOf("42", "107", "8"), sync = true)
        store.savePosition(
            currentMediaId = "107",
            currentIndex = 1,
            positionMs = 73_500L,
            shuffleEnabled = true,
            repeatMode = Player.REPEAT_MODE_ONE,
            sync = true,
        )
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(listOf("42", "107", "8"), loaded!!.queueIds)
        assertEquals("107", loaded.currentMediaId)
        assertEquals(1, loaded.currentIndex)
        assertEquals(73_500L, loaded.positionMs)
        assertTrue(loaded.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, loaded.repeatMode)
    }

    @Test
    fun `saveQueue filters out special prefix ids`() {
        store.saveQueue(listOf("special:shuffle_all", "42", "special:foo", "107"), sync = true)
        // Need a savePosition so load() returns non-null fields beyond
        // queueIds — load() pulls currentMediaId from statePrefs.
        store.savePosition("42", 0, 0L, false, Player.REPEAT_MODE_OFF, sync = true)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(listOf("42", "107"), loaded!!.queueIds)
    }

    @Test
    fun `saveQueue with only special ids is a no-op`() {
        val wrote = store.saveQueue(listOf("special:shuffle_all", "special:foo"), sync = true)
        assertFalse(wrote)
        assertNull(store.load())
    }

    @Test
    fun `saveQueue dedup cache skips identical second write`() {
        val first = store.saveQueue(listOf("1", "2", "3"), sync = true)
        val second = store.saveQueue(listOf("1", "2", "3"), sync = true)
        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `saveQueue dedup cache writes when queue actually changes`() {
        store.saveQueue(listOf("1", "2", "3"), sync = true)
        val wrote = store.saveQueue(listOf("1", "2", "3", "4"), sync = true)
        assertTrue(wrote)
    }

    @Test
    fun `markQueueAsSaved primes the dedup cache so subsequent identical write is a no-op`() {
        store.markQueueAsSaved(listOf("1", "2", "3"))
        val wrote = store.saveQueue(listOf("1", "2", "3"), sync = true)
        assertFalse(wrote)
    }

    @Test
    fun `savePosition is a no-op when currentMediaId is null`() {
        store.saveQueue(listOf("1", "2"), sync = true)
        store.savePosition(
            currentMediaId = null,
            currentIndex = 0,
            positionMs = 1000L,
            shuffleEnabled = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            sync = true,
        )
        val loaded = store.load()
        assertNotNull(loaded)
        // No state was persisted — currentMediaId stays null, defaults apply.
        assertNull(loaded!!.currentMediaId)
        assertEquals(0L, loaded.positionMs)
    }

    @Test
    fun `savePosition is a no-op when currentMediaId is a special id`() {
        store.saveQueue(listOf("1"), sync = true)
        store.savePosition(
            currentMediaId = "special:shuffle_all",
            currentIndex = 0,
            positionMs = 1000L,
            shuffleEnabled = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            sync = true,
        )
        assertNull(store.load()?.currentMediaId)
    }

    @Test
    fun `savePosition clamps negative position to zero`() {
        store.saveQueue(listOf("1"), sync = true)
        store.savePosition("1", 0, -500L, false, Player.REPEAT_MODE_OFF, sync = true)
        assertEquals(0L, store.load()?.positionMs)
    }

    @Test
    fun `savePosition clamps negative index to zero`() {
        store.saveQueue(listOf("1", "2"), sync = true)
        store.savePosition("1", -3, 0L, false, Player.REPEAT_MODE_OFF, sync = true)
        assertEquals(0, store.load()?.currentIndex)
    }

    @Test
    fun `clear empties both files and resets dedup cache`() {
        store.saveQueue(listOf("1", "2"), sync = true)
        store.savePosition("1", 0, 5000L, true, Player.REPEAT_MODE_ALL, sync = true)
        store.clear()
        assertNull(store.load())
        // Dedup cache is reset — same queue should write again.
        assertTrue(store.saveQueue(listOf("1", "2"), sync = true))
    }

    @Test
    fun `legacy single-file payload migrates to the new two-file layout on first load`() {
        // Simulate what the v1 PlaybackSessionStore wrote: everything in the
        // queue prefs file (the old PREFS name).
        context.getSharedPreferences(prefsQueueName, Context.MODE_PRIVATE).edit()
            .putString("queue_ids", "42\n107\n8")
            .putString("current_media_id", "107")
            .putInt("current_index", 1)
            .putLong("position_ms", 73_500L)
            .putBoolean("shuffle_enabled", true)
            .putInt("repeat_mode", Player.REPEAT_MODE_ONE)
            .putLong("saved_at", 1_700_000_000_000L)
            .commit()
        // State file empty — fresh upgrade scenario.
        assertFalse(
            context.getSharedPreferences(prefsStateName, Context.MODE_PRIVATE)
                .contains("current_media_id")
        )

        // Construct a NEW store instance (otherwise the @Volatile
        // migratedFromLegacy flag from setUp's store would already be true).
        val freshStore = PlaybackSessionStore(context)
        val loaded = freshStore.load()
        assertNotNull(loaded)
        assertEquals(listOf("42", "107", "8"), loaded!!.queueIds)
        assertEquals("107", loaded.currentMediaId)
        assertEquals(1, loaded.currentIndex)
        assertEquals(73_500L, loaded.positionMs)
        assertTrue(loaded.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, loaded.repeatMode)

        // Migration side-effects: queue stays in queuePrefs (intact), state
        // keys moved to statePrefs, and the state keys were removed from the
        // legacy file.
        val queueFile = context.getSharedPreferences(prefsQueueName, Context.MODE_PRIVATE)
        val stateFile = context.getSharedPreferences(prefsStateName, Context.MODE_PRIVATE)
        assertEquals("42\n107\n8", queueFile.getString("queue_ids", null))
        assertFalse(queueFile.contains("current_media_id"))
        assertFalse(queueFile.contains("position_ms"))
        assertEquals("107", stateFile.getString("current_media_id", null))
        assertEquals(73_500L, stateFile.getLong("position_ms", -1L))
    }

    @Test
    fun `queue join split round-trips correctly with single id`() {
        store.saveQueue(listOf("only"), sync = true)
        store.savePosition("only", 0, 0L, false, Player.REPEAT_MODE_OFF, sync = true)
        assertEquals(listOf("only"), store.load()?.queueIds)
    }

    @Test
    fun `queue split filters empty strings from accidental trailing separators`() {
        // Force a malformed payload directly (simulating an old build with a
        // bug that wrote a trailing newline) and verify load handles it.
        context.getSharedPreferences(prefsQueueName, Context.MODE_PRIVATE).edit()
            .putString("queue_ids", "1\n2\n\n3\n")
            .commit()
        context.getSharedPreferences(prefsStateName, Context.MODE_PRIVATE).edit()
            .putString("current_media_id", "1")
            .commit()
        val freshStore = PlaybackSessionStore(context)
        assertEquals(listOf("1", "2", "3"), freshStore.load()?.queueIds)
    }
}
