package com.bombest.music.data

import android.content.Context
import com.bombest.music.data.api.MusicApi
import com.bombest.music.data.api.AuthApi
import com.bombest.music.data.api.PlaylistApi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Network configuration with automatic failover support.
 * Primary: beats.bom.best (EC2 via Cloudflare)
 * Failover: beats-aws.bom.best (EC2 direct)
 */
object NetworkModule {
    // Server URLs with failover support
    private val BASE_URLS = listOf(
        "https://beats.bom.best/",      // Primary (EC2 via Cloudflare)
        "https://beats-aws.bom.best/"   // Failover (EC2 direct)
    )
    
    private val currentUrlIndex = AtomicInteger(0)

    // Once we've failed over, stay on the failover URL for this cooldown before
    // auto-retrying the primary. Prevents ping-pong when Cloudflare has a brief
    // blip but also recovers quickly when primary comes back up. 60s strikes a
    // balance: long enough to let Cloudflare route changes settle, short enough
    // that a recovered primary isn't stuck behind the failover for 5 minutes.
    //
    // For a probe-based recovery (hitting /health on primary before each
    // request once cooldown elapses), see the follow-up ticket.
    private const val FAILOVER_COOLDOWN_MS = 60L * 1000L
    private val failoverTimestamp = AtomicLong(0L)

    /** If cooldown has elapsed since last failover, reset to primary. */
    private fun maybeResetAfterCooldown() {
        val ts = failoverTimestamp.get()
        if (ts == 0L) return
        if (System.currentTimeMillis() - ts >= FAILOVER_COOLDOWN_MS) {
            if (currentUrlIndex.compareAndSet(1, 0)) {
                failoverTimestamp.set(0L)
                android.util.Log.i(
                    "NetworkModule",
                    "Failover cooldown elapsed; reset to primary: ${BASE_URLS[0]}",
                )
            }
        }
    }

    val currentBaseUrl: String
        get() {
            maybeResetAfterCooldown()
            return BASE_URLS[currentUrlIndex.get()]
        }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    
    // Set by attachJwtAuthenticator() once the Application context is available.
    // Kept as an OkHttp Authenticator (not an interceptor) so it runs *after* the
    // server responds 401, giving us both the expired token and the original request
    // in one shot — which is exactly what refresh-and-retry needs.
    private var jwtAuthenticator: okhttp3.Authenticator? = null
    private var builtClient: OkHttpClient? = null

    /**
     * Install the JWT refresh authenticator. Call once from [BombestApplication.onCreate].
     * Safe to call multiple times (rebuilds the shared client so existing retrofit
     * instances pick up the new authenticator on the next request).
     */
    fun attachJwtAuthenticator(context: Context) {
        jwtAuthenticator = JwtAuthenticator(context.applicationContext)
        builtClient = null  // Force lazy rebuild on next access.
        builtPlaylistClient = null  // Same for the playlist client.
        _retrofit = buildRetrofit(BASE_URLS[currentUrlIndex.get()])
        playlistRetrofit = null
    }

    private fun buildOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            // Start from whichever URL is currently active (primary after cooldown,
            // failover while sticky). The cooldown reset happens via currentBaseUrl's
            // maybeResetAfterCooldown(), which we call to nudge state first.
            maybeResetAfterCooldown()
            var currentIndex = currentUrlIndex.get()
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
                    
                    // Log only host + first path segment to avoid leaking share
                    // tokens (/playlists/share/<token>) via Logcat/bugreports.
                    android.util.Log.d("NetworkModule", "Attempting request to: ${newUrl.host}/${newUrl.pathSegments.firstOrNull().orEmpty()}")
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
                        failoverTimestamp.set(System.currentTimeMillis())
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
                        failoverTimestamp.set(System.currentTimeMillis())
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
                        failoverTimestamp.set(System.currentTimeMillis())
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
        .apply {
            jwtAuthenticator?.let { authenticator(it) }
        }
        .build()

    /** Lazy accessor — rebuilds when attachJwtAuthenticator() nulls builtClient. */
    private val okHttpClient: OkHttpClient
        get() = builtClient ?: buildOkHttpClient().also { builtClient = it }

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

    /** Bearer token for playlist routes; updated when auth/session changes. */
    private val playlistBearerToken = AtomicReference<String?>(null)

    fun setPlaylistBearerToken(token: String?) {
        playlistBearerToken.set(token)
    }

    // Lazy + rebuildable. When attachJwtAuthenticator() nulls builtClient, it
    // also nulls this so the new JwtAuthenticator is picked up on next access.
    // Without this, the playlist client snapshots the pre-authenticator
    // okHttpClient once and silently never auto-refreshes on 401.
    private var builtPlaylistClient: OkHttpClient? = null

    private val playlistAuthClient: OkHttpClient
        get() = builtPlaylistClient ?: okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val t = playlistBearerToken.get()
                val req = if (!t.isNullOrBlank()) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $t")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(req)
            }
            .build()
            .also { builtPlaylistClient = it }

    private var playlistRetrofit: Retrofit? = null
    private var playlistRetrofitBase: String? = null

    /**
     * Playlist API: same failover/logging stack as [api], plus dynamic Bearer from [setPlaylistBearerToken].
     * GET /playlists works without a token (public lists); mutations require login.
     */
    fun getPlaylistApi(): PlaylistApi {
        val base = currentBaseUrl
        if (playlistRetrofit == null || playlistRetrofitBase != base) {
            playlistRetrofit = Retrofit.Builder()
                .baseUrl(base)
                .client(playlistAuthClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            playlistRetrofitBase = base
        }
        return playlistRetrofit!!.create(PlaylistApi::class.java)
    }

    /**
     * @deprecated Use [getPlaylistApi] with [setPlaylistBearerToken].
     */
    fun createPlaylistApi(authToken: String?): PlaylistApi {
        setPlaylistBearerToken(authToken)
        return getPlaylistApi()
    }
    
    fun getStreamBaseUrl(): String = currentBaseUrl.dropLast(1) // remove trailing slash

    /** Shared OkHttpClient for one-off requests (e.g. Canvas probe). Reuse to avoid connection leaks. */
    val simpleOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

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
            failoverTimestamp.set(System.currentTimeMillis())
            android.util.Log.i("NetworkModule", "Switched to failover: ${BASE_URLS[currentUrlIndex.get()]}")
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
        failoverTimestamp.set(0L)
        android.util.Log.i("NetworkModule", "Reset to primary: ${BASE_URLS[0]}")
    }
}
