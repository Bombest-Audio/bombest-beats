package com.bombest.musify.di

import android.content.Context
import com.bombest.musify.data.backend.BackendLibraryApi
import com.bombest.musify.data.backend.BackendLibraryApiImpl
import com.bombest.musify.data.cache.CacheManager
import com.bombest.musify.data.download.DownloadManager
import com.bombest.musify.data.s3.S3Repository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MusicServiceModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideBackendLibraryApi(httpClient: OkHttpClient): BackendLibraryApi =
        BackendLibraryApiImpl(httpClient)

    @Provides
    @Singleton
    fun provideS3Repository(
        httpClient: OkHttpClient
    ): S3Repository = S3Repository(httpClient = httpClient)

    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        s3Repository: S3Repository,
        httpClient: OkHttpClient
    ): DownloadManager = DownloadManager(context, s3Repository, httpClient)

    @Provides
    @Singleton
    fun provideCacheManager(
        @ApplicationContext context: Context,
        downloadManager: DownloadManager
    ): CacheManager = CacheManager(context, downloadManager)
}