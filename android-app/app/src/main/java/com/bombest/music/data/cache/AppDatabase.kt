package com.bombest.music.data.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-level Room database. Currently only hosts the playlist cache (offline Auto browse);
 * kept as a shared `AppDatabase` so future offline features (downloaded tracks, last-played
 * queue) can add their own entities without each needing a separate Room instance.
 */
@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistCacheDao(): PlaylistCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bombest-cache.db",
                )
                    // v1 — if the schema ever changes, add migrations here rather than
                    // dropping. For now, a failed migration wipes the offline cache which
                    // is recoverable on the next network-available fetch.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
