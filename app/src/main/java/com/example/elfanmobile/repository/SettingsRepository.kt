package com.example.elfanmobile.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "elfan_settings")

/**
 * SettingsRepository
 *
 * Persists user preferences using Jetpack DataStore.
 * Stored settings:
 *   - Raspberry Pi base URL
 *   - Debug mode enabled flag
 */
class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_RASPBERRY_PI_URL = stringPreferencesKey("raspberry_pi_url")
        val KEY_DEBUG_MODE = booleanPreferencesKey("debug_mode")

        const val DEFAULT_RASPBERRY_PI_URL = "http://192.168.20.126:5001"
    }

    /** Observe the Raspberry Pi base URL. Emits [DEFAULT_RASPBERRY_PI_URL] if not set. */
    val raspberryPiUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_RASPBERRY_PI_URL] ?: DEFAULT_RASPBERRY_PI_URL
    }

    /** Observe debug mode state. Emits false if not set. */
    val debugMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_MODE] ?: false
    }

    /** Save the Raspberry Pi base URL. */
    suspend fun saveRaspberryPiUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RASPBERRY_PI_URL] = url.trim()
        }
    }

    /** Save the debug mode flag. */
    suspend fun saveDebugMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEBUG_MODE] = enabled
        }
    }
}
