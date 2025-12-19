package com.bombest.music.data

import com.bombest.music.data.api.MusicApi
import com.bombest.music.data.api.AuthApi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
            val originalRequest = chain.request()
            // Always start from primary URL for each new request
            var currentIndex = 0
            var lastException: Exception? = null
            
            // Try each URL in sequence
            while (currentIndex < BASE_URLS.size) {
                try {
                    // Build request with current base URL
                    val currentBaseUrl = BASE_URLS[currentIndex]
                    val baseUrlParsed = currentBaseUrl.toHttpUrlOrNull()
                    if (baseUrlParsed == null) {
                        android.util.Log.e("NetworkModule", "Invalid base URL: $currentBaseUrl")
                        currentIndex++
                        continue
                    }
                    
                    val originalUrl = originalRequest.url
                    // Replace host/scheme/port but keep the path and query
                    val newUrl = originalUrl.newBuilder()
                        .scheme(baseUrlParsed.scheme)
                        .host(baseUrlParsed.host)
                        .port(baseUrlParsed.port)
                        .build()
                    
                    val newRequest = originalRequest.newBuilder()
                        .url(newUrl)
                        .build()
                    
                    android.util.Log.d("NetworkModule", "Attempting request to: ${newUrl.host}${newUrl.encodedPath}")
                    val response = chain.proceed(newRequest)
                    
                    // If we get a successful response or HTTP error (4xx, 5xx), don't failover
                    // Only failover on network errors (timeout, connection refused, etc.)
                    if (response.isSuccessful || response.code in 400..599) {
                        return@addInterceptor response
                    }
                    
                    // Unexpected response code, try next URL
                    response.close()
                    throw java.net.SocketTimeoutException("Unexpected response code: ${response.code}")
                } catch (e: java.net.SocketTimeoutException) {
                    // Network timeout - try failover
                    lastException = e
                    android.util.Log.w("NetworkModule", "Timeout with URL index $currentIndex (${BASE_URLS[currentIndex]}): ${e.message}")
                    
                    // Try next URL if available
                    if (currentIndex < BASE_URLS.size - 1) {
                        currentIndex++
                        currentUrlIndex.set(currentIndex)
                        android.util.Log.i("NetworkModule", "Switching to failover URL: ${BASE_URLS[currentIndex]}")
                    } else {
                        // No more URLs to try
                        break
                    }
                } catch (e: java.net.ConnectException) {
                    // Connection refused - try failover
                    lastException = e
                    android.util.Log.w("NetworkModule", "Connection refused with URL index $currentIndex (${BASE_URLS[currentIndex]}): ${e.message}")
                    
                    // Try next URL if available
                    if (currentIndex < BASE_URLS.size - 1) {
                        currentIndex++
                        currentUrlIndex.set(currentIndex)
                        android.util.Log.i("NetworkModule", "Switching to failover URL: ${BASE_URLS[currentIndex]}")
                    } else {
                        // No more URLs to try
                        break
                    }
                } catch (e: java.io.IOException) {
                    // Other network errors - try failover
                    lastException = e
                    android.util.Log.w("NetworkModule", "Network error with URL index $currentIndex (${BASE_URLS[currentIndex]}): ${e.message}")
                    
                    // Try next URL if available
                    if (currentIndex < BASE_URLS.size - 1) {
                        currentIndex++
                        currentUrlIndex.set(currentIndex)
                        android.util.Log.i("NetworkModule", "Switching to failover URL: ${BASE_URLS[currentIndex]}")
                    } else {
                        // No more URLs to try
                        break
                    }
                } catch (e: Exception) {
                    // For other exceptions (including HTTP exceptions from Retrofit), don't failover
                    // These are likely application-level errors (401, 403, 500, etc.)
                    android.util.Log.w("NetworkModule", "Non-network error (not failing over): ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
            
            // All URLs failed, throw the last exception
            throw lastException ?: java.net.SocketTimeoutException("All servers failed")
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
     * Check if failover is available
     */
    fun canFailover(): Boolean {
        return currentUrlIndex.get() < BASE_URLS.size - 1
    }
    
    /**
     * Switch to failover URL
     */
    fun failover() {
        if (canFailover()) {
            currentUrlIndex.incrementAndGet()
            android.util.Log.i("NetworkModule", "Switched to failover: $currentBaseUrl")
        }
    }
    
    /**
     * Force switch to failover URL (useful for manual testing)
     */
    fun switchToFailover() {
        failover()
    }
    
    /**
     * Reset to primary URL
     */
    fun resetToPrimary() {
        currentUrlIndex.set(0)
        android.util.Log.i("NetworkModule", "Reset to primary: $currentBaseUrl")
    }
}
