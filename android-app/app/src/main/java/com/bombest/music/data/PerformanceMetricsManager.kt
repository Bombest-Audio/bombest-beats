package com.bombest.music.data

import android.content.Context
import android.util.Log
import com.bombest.music.data.api.PerfBatchRequest
import com.bombest.music.data.api.PerfEventPayload
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects playback performance events (underruns, load errors, bandwidth samples, offload state)
 * and uploads them in batches to /metrics/performance.
 *
 * Mirrors MetricsManager's pattern: in-memory queue, disk-persisted, JWT-authenticated flush.
 * Flushed every 60s during playback + on track transition + on service destroy — so you get
 * data even when driving with the phone screen off and adb disconnected.
 */
object PerformanceMetricsManager {
    private const val TAG = "PerformanceMetrics"
    private const val BATCH_SIZE_THRESHOLD = 20
    private const val QUEUE_FILENAME = "perf_metrics_queue.json"

    private val eventQueue = mutableListOf<PerfEventPayload>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, PerfEventPayload::class.java)
    private val adapter = moshi.adapter<List<PerfEventPayload>>(listType)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadQueue()
    }

    fun record(event: PerfEventPayload) {
        scope.launch {
            synchronized(eventQueue) {
                eventQueue.add(event)
            }
            saveQueue()
            if (eventQueue.size >= BATCH_SIZE_THRESHOLD) {
                flush()
            }
        }
    }

    fun flush() {
        scope.launch {
            val context = appContext ?: return@launch
            val token = try {
                context.authDataStore.data.map { it[AuthPreferences.TOKEN_KEY] }.first()
            } catch (_: Exception) { null }

            if (token == null) {
                Log.d(TAG, "No auth token, skipping flush")
                return@launch
            }

            val batch = synchronized(eventQueue) {
                if (eventQueue.isEmpty()) return@launch
                eventQueue.toList()
            }

            try {
                Log.d(TAG, "Flushing ${batch.size} perf events...")
                val response = NetworkModule.api.batchRecordPerfEvents(
                    PerfBatchRequest(batch),
                    "Bearer $token"
                )
                if (response.isSuccessful) {
                    Log.d(TAG, "Perf batch upload successful")
                    synchronized(eventQueue) { eventQueue.removeAll(batch.toSet()) }
                    saveQueue()
                } else {
                    Log.e(TAG, "Perf batch upload failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Perf batch upload error", e)
            }
        }
    }

    private suspend fun saveQueue() {
        val context = appContext ?: return
        val json = synchronized(eventQueue) { adapter.toJson(eventQueue) }
        try {
            File(context.filesDir, QUEUE_FILENAME).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save perf queue", e)
        }
    }

    private fun loadQueue() {
        scope.launch {
            val context = appContext ?: return@launch
            try {
                val file = File(context.filesDir, QUEUE_FILENAME)
                if (file.exists()) {
                    val loaded = adapter.fromJson(file.readText()) ?: emptyList()
                    synchronized(eventQueue) {
                        eventQueue.clear()
                        eventQueue.addAll(loaded)
                    }
                    Log.d(TAG, "Loaded ${loaded.size} perf events from disk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load perf queue", e)
            }
        }
    }

    fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
