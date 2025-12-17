package com.bombest.music.data

import android.content.Context
import android.util.Log
import com.bombest.music.data.api.BatchPlayRequest
import com.bombest.music.data.api.PlayEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manages batched reporting of playback metrics.
 * Queues events locally and uploads in chunks to minimize server load.
 */
object MetricsManager {
    private const val TAG = "MetricsManager"
    private const val BATCH_SIZE_THRESHOLD = 10
    private const val QUEUE_FILENAME = "metrics_queue.json"
    
    // In-memory queue
    private val eventQueue = mutableListOf<PlayEvent>()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, PlayEvent::class.java)
    private val adapter = moshi.adapter<List<PlayEvent>>(listType)
    
    private var appContext: Context? = null
    
    fun init(context: Context) {
        appContext = context.applicationContext
        loadQueue()
    }
    
    fun logPlay(trackId: Int) {
        scope.launch {
            val timestamp = getCurrentIsoTimestamp()
            val event = PlayEvent(trackId, timestamp)
            
            synchronized(eventQueue) {
                eventQueue.add(event)
                Log.d(TAG, "Logged play for track $trackId. Queue size: ${eventQueue.size}")
            }
            
            saveQueue()
            
            if (eventQueue.size >= BATCH_SIZE_THRESHOLD) {
                flush()
            }
        }
    }
    
    private fun getCurrentIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
    
    private suspend fun saveQueue() {
        val context = appContext ?: return
        val json = synchronized(eventQueue) {
            adapter.toJson(eventQueue)
        }
        try {
            val file = File(context.filesDir, QUEUE_FILENAME)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metrics queue", e)
        }
    }
    
    private fun loadQueue() {
        scope.launch {
            val context = appContext ?: return@launch
            try {
                val file = File(context.filesDir, QUEUE_FILENAME)
                if (file.exists()) {
                    val json = file.readText()
                    val loaded = adapter.fromJson(json) ?: emptyList()
                    synchronized(eventQueue) {
                        eventQueue.clear()
                        eventQueue.addAll(loaded)
                    }
                    Log.d(TAG, "Loaded ${eventQueue.size} events from disk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load metrics queue", e)
            }
        }
    }
    
    fun flush() {
        scope.launch {
            val context = appContext ?: return@launch
            
            // Get token from DataStore
            val token = context.authDataStore.data
                .map { it[AuthPreferences.TOKEN_KEY] }
                .first()
            
            if (token == null) {
                Log.d(TAG, "No auth token, skipping flush")
                return@launch
            }
            
            val api = NetworkModule.api
            
            val batch = synchronized(eventQueue) {
                if (eventQueue.isEmpty()) return@launch
                eventQueue.toList()
            }
            
            try {
                Log.d(TAG, "Flushing ${batch.size} events to server...")
                val response = api.batchRecordPlays(
                    BatchPlayRequest(batch),
                    "Bearer $token"
                )
                
                if (response.isSuccessful) {
                    Log.d(TAG, "Batch upload successful")
                    synchronized(eventQueue) {
                        eventQueue.removeAll(batch)
                    }
                    saveQueue()
                } else {
                    Log.e(TAG, "Batch upload failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch upload error", e)
            }
        }
    }
}
