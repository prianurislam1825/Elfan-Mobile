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
        val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_PORCUPINE_ACCESS_KEY = stringPreferencesKey("porcupine_access_key")

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

    /** Observe wake word enabled state. Emits false if not set. */
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD_ENABLED] ?: false
    }

    /** Observe Porcupine Access Key. Emits empty string if not set. */
    val porcupineAccessKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PORCUPINE_ACCESS_KEY] ?: ""
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

    /** Save the wake word enabled flag. */
    suspend fun saveWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_WORD_ENABLED] = enabled
        }
    }

    /** Save the Porcupine Access Key. */
    suspend fun savePorcupineAccessKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PORCUPINE_ACCESS_KEY] = key.trim()
        }
    }
}
