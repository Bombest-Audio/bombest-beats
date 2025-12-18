package com.bombest.music.data

import com.bombest.music.data.api.MusicApi
import com.bombest.music.data.api.AuthApi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network configuration with automatic failover support.
 * Primary: beats.bom.best (Home server via Tunnel)
 * Failover: beats-aws.bom.best (AWS EC2)
 */
object NetworkModule {
    // Server URLs with failover support
    private val BASE_URLS = listOf(
        "https://beats.bom.best/",      // Primary (Home via Tunnel)
        "https://beats-aws.bom.best/"   // Failover (AWS EC2)
    )
    
    private val currentUrlIndex = AtomicInteger(0)
    
    val currentBaseUrl: String
        get() = BASE_URLS[currentUrlIndex.get()]

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            try {
                chain.proceed(request)
            } catch (e: Exception) {
                // On failure, try failover URL
                if (currentUrlIndex.get() < BASE_URLS.size - 1) {
                    android.util.Log.w("NetworkModule", "Primary failed, switching to failover: ${e.message}")
                    currentUrlIndex.incrementAndGet()
                }
                throw e
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()

    // Lazy rebuild retrofit when URL changes
    private fun buildRetrofit(baseUrl: String) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private var _retrofit = buildRetrofit(BASE_URLS[0])
    
    private val retrofit: Retrofit
        get() {
            val expectedUrl = currentBaseUrl
            if (!_retrofit.baseUrl().toString().equals(expectedUrl, ignoreCase = true)) {
                _retrofit = buildRetrofit(expectedUrl)
            }
            return _retrofit
        }

    val api: MusicApi get() = retrofit.create(MusicApi::class.java)
    val authApi: AuthApi get() = retrofit.create(AuthApi::class.java)
    
    fun getStreamBaseUrl(): String = currentBaseUrl.dropLast(1) // remove trailing slash
    
    /**
     * Force switch to failover URL (useful for manual testing)
     */
    fun switchToFailover() {
        if (currentUrlIndex.get() < BASE_URLS.size - 1) {
            currentUrlIndex.incrementAndGet()
            android.util.Log.i("NetworkModule", "Switched to failover: $currentBaseUrl")
        }
    }
    
    /**
     * Reset to primary URL
     */
    fun resetToPrimary() {
        currentUrlIndex.set(0)
        android.util.Log.i("NetworkModule", "Reset to primary: $currentBaseUrl")
    }
}
