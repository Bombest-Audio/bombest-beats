package com.bombest.music.data

import com.bombest.music.data.api.MusicApi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object NetworkModule {
    // Production URL
    private const val BASE_URL = "https://bom.best/beats/api/" 

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    val api: MusicApi = retrofit.create(MusicApi::class.java)
    
    fun getStreamBaseUrl(): String = BASE_URL.dropLast(1) // remove trailing slash
}
