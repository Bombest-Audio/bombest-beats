package com.bombest.music.data

import com.bombest.music.data.api.MusicApi
import com.bombest.music.data.api.AuthApi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object NetworkModule {
    // Production URL
    private const val BASE_URL = "https://bom.best/beats/api/" 

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    val api: MusicApi = retrofit.create(MusicApi::class.java)
    val authApi: AuthApi = retrofit.create(AuthApi::class.java)
    
    fun getStreamBaseUrl(): String = BASE_URL.dropLast(1) // remove trailing slash
}

