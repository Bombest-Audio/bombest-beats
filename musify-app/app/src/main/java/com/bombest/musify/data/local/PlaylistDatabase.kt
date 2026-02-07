package com.bombest.musify.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room database for local playlists
 * Since S3 doesn't provide playlist data, we store user-created playlists locally
 */
@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
}

@Module
@InstallIn(SingletonComponent::class)
object PlaylistDatabaseModule {
    @Provides
    @Singleton
    fun providePlaylistDatabase(@ApplicationContext context: Context): PlaylistDatabase {
        return Room.databaseBuilder(
            context,
            PlaylistDatabase::class.java,
            "playlist_database"
        ).build()
    }

    @Provides
    fun providePlaylistDao(database: PlaylistDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun providePlaylistTrackDao(database: PlaylistDatabase): PlaylistTrackDao = database.playlistTrackDao()
}
