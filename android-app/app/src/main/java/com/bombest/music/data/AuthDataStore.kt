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
    val USER_KEY = stringPreferencesKey("username")
    val USER_ID_KEY = stringPreferencesKey("user_id")
    val ROLE_KEY = stringPreferencesKey("role")
}
