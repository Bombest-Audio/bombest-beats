package com.bombest.music.haptics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed preferences for the Haptic Groove Engine.
 * 
 * Settings:
 * - isEnabled: Master toggle for "Feel the Beat"
 * - intensity: LOW, MEDIUM, or HIGH
 */
object HapticPreferences {
    
    private val Context.hapticDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "haptic_preferences"
    )
    
    private val ENABLED_KEY = booleanPreferencesKey("haptics_enabled")
    private val INTENSITY_KEY = stringPreferencesKey("haptics_intensity")
    
    private var dataStore: DataStore<Preferences>? = null
    
    /**
     * Initialize with application context.
     */
    fun initialize(context: Context) {
        dataStore = context.hapticDataStore
    }
    
    /**
     * Flow of the enabled state.
     * Defaults to false (opt-in feature).
     */
    fun isEnabled(context: Context): Flow<Boolean> {
        return context.hapticDataStore.data.map { prefs ->
            prefs[ENABLED_KEY] ?: false
        }
    }
    
    /**
     * Flow of the intensity level.
     * Defaults to MEDIUM.
     */
    fun intensity(context: Context): Flow<HapticPatternLibrary.Intensity> {
        return context.hapticDataStore.data.map { prefs ->
            val value = prefs[INTENSITY_KEY] ?: "MEDIUM"
            try {
                HapticPatternLibrary.Intensity.valueOf(value)
            } catch (e: Exception) {
                HapticPatternLibrary.Intensity.MEDIUM
            }
        }
    }
    
    /**
     * Set the enabled state.
     */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.hapticDataStore.edit { prefs ->
            prefs[ENABLED_KEY] = enabled
        }
    }
    
    /**
     * Set the intensity level.
     */
    suspend fun setIntensity(context: Context, intensity: HapticPatternLibrary.Intensity) {
        context.hapticDataStore.edit { prefs ->
            prefs[INTENSITY_KEY] = intensity.name
        }
    }
}
