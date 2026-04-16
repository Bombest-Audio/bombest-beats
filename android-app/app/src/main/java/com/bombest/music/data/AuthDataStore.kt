package com.bombest.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// Single DataStore instance for auth - MUST be declared only once at top level
val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

object AuthPreferences {
    val TOKEN_KEY = stringPreferencesKey("access_token")
    // Refresh token is stored alongside access to let JwtAuthenticator mint a new
    // access token on 401 without re-prompting the user for credentials (issue #28).
    val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    val USER_KEY = stringPreferencesKey("username")
    val USER_ID_KEY = stringPreferencesKey("user_id")
    val ROLE_KEY = stringPreferencesKey("role")
}
