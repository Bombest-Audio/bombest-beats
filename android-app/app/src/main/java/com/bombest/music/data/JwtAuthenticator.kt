package com.bombest.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp Authenticator that handles expired JWT access tokens transparently.
 *
 * When the server returns 401 on an authenticated request, this runs:
 *   1. Read the stored refresh token from DataStore.
 *   2. Hit `POST /auth/refresh` on a dedicated OkHttpClient (so we don't recurse
 *      through this same authenticator) with the refresh token as Bearer.
 *   3. Persist the new access token and replay the original request with it.
 *
 * Guards against recursion:
 *   - `response.priorResponse != null` → this is already a retry of a retry; give up.
 *   - URL path is `/auth/refresh` → never retry the refresh endpoint itself.
 *   - No stored refresh token → nothing to try; surface the 401 to the caller.
 *
 * Returning `null` from [authenticate] tells OkHttp to propagate the original 401
 * to the caller (who will then trigger the user-facing logout flow).
 */
class JwtAuthenticator(private val appContext: Context) : Authenticator {

    /** Separate client for the refresh call — never reuse the authenticated client here. */
    private val refreshClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Already retried once — don't loop forever.
        if (response.priorResponse != null) return null
        // Never try to refresh the refresh endpoint itself.
        if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null
        // Same guard for the login endpoints — 401 there means bad creds, not expired token.
        if (response.request.url.encodedPath.endsWith("/auth/login")) return null

        val refreshToken = readRefreshTokenBlocking() ?: return null
        val newAccess = tryRefresh(refreshToken) ?: return null
        saveAccessTokenBlocking(newAccess)

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private fun readRefreshTokenBlocking(): String? {
        // Authenticator callback runs on a network-dispatcher thread — safe to block.
        return runBlocking {
            appContext.authDataStore.data.first()[AuthPreferences.REFRESH_TOKEN_KEY]
        }
    }

    private fun saveAccessTokenBlocking(token: String) {
        runBlocking {
            appContext.authDataStore.edit { prefs ->
                prefs[AuthPreferences.TOKEN_KEY] = token
            }
        }
    }

    private fun tryRefresh(refreshToken: String): String? {
        val baseUrl = NetworkModule.getStreamBaseUrl()
        val req = Request.Builder()
            .url("$baseUrl/auth/refresh")
            .header("Authorization", "Bearer $refreshToken")
            // /auth/refresh is a POST with an empty body; keep the content-length explicit.
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            refreshClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string() ?: return null
                // Tiny ad-hoc JSON parse — avoids taking a Moshi dep here and keeps the
                // Authenticator self-contained. Shape is {"access_token":"..","expires_in":N}.
                val match = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(body)
                match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            android.util.Log.w("JwtAuthenticator", "Refresh failed: ${e.message}")
            null
        }
    }
}
