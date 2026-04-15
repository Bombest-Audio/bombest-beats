package com.bombest.music.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bombest.music.data.AuthPreferences
import com.bombest.music.data.AutoRecentTracksStore
import com.bombest.music.data.DownloadManager
import com.bombest.music.data.NetworkModule
import com.bombest.music.data.authDataStore
import com.bombest.music.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * On unmetered network, batch-downloads Favorites plus Android Auto "recent" tracks (capped)
 * so driving playback can use local files.
 */
class CarLibrarySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val token = applicationContext.authDataStore.data
                .map { it[AuthPreferences.TOKEN_KEY] }
                .first()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "No auth token; skipping car library sync")
                return Result.failure()
            }

            val dm = DownloadManager.getInstance(applicationContext)
            dm.setAuthToken(token)

            val playlistApi = NetworkModule.createPlaylistApi(token)
            val playlists = playlistApi.getPlaylists().playlists
            val favoritesId = playlists.find { it.name == "Favorites" }?.id

            val favoriteTracks = if (favoritesId != null) {
                playlistApi.getPlaylistTracks(favoritesId).tracks
            } else {
                emptyList()
            }

            val recentIds = AutoRecentTracksStore(applicationContext).getRecentIds()
            val library = MusicRepository(applicationContext).fetchLibrary()
            val byId = library.associateBy { it.id }

            val orderedIds = LinkedHashSet<Int>()
            for (t in favoriteTracks) orderedIds.add(t.id)
            for (rid in recentIds) orderedIds.add(rid)

            val tracks = orderedIds.take(MAX_TRACKS).mapNotNull { byId[it] }
            if (tracks.isEmpty()) {
                Log.i(TAG, "No tracks to download (empty library or no overlap)")
                return Result.success()
            }

            val repo = MusicRepository(applicationContext)
            val batch = dm.downloadAllTracks(
                tracks = tracks,
                getStreamUrl = { id -> repo.getStreamUrl(id) }
            )
            if (batch.isFailure) {
                Log.w(TAG, "Batch completed with error: ${batch.exceptionOrNull()?.message}")
            }
            Log.i(TAG, "Car library sync finished (${tracks.size} tracks considered)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Car library sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CarLibrarySyncWorker"
        private const val MAX_TRACKS = 200
        private const val UNIQUE_PERIODIC = "bombest_car_library_sync_periodic"
        private const val UNIQUE_ONCE = "bombest_car_library_sync_once"

        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val req = OneTimeWorkRequestBuilder<CarLibrarySyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE,
                ExistingWorkPolicy.KEEP,
                req
            )
        }

        fun schedulePeriodicWifi(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val periodic = PeriodicWorkRequestBuilder<CarLibrarySyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}
