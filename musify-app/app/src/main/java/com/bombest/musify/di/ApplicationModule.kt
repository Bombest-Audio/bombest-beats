package com.bombest.musify.di

import com.bombest.musify.data.encoder.AndroidBase64Encoder
import com.bombest.musify.data.encoder.Base64Encoder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ApplicationModule {

    @Binds
    abstract fun bindBase64Encoder(
        androidBase64Encoder: AndroidBase64Encoder
    ): Base64Encoder
}