package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.notificationDataStore by preferencesDataStore(name = "notification_settings")

data class NotificationPreferences(
    val pushEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)

class NotificationSettings(private val context: Context) {
    companion object {
        private val PUSH_ENABLED_KEY = booleanPreferencesKey("push_enabled")
        private val SOUND_ENABLED_KEY = booleanPreferencesKey("sound_enabled")
        private val VIBRATION_ENABLED_KEY = booleanPreferencesKey("vibration_enabled")
    }

    val preferencesFlow: Flow<NotificationPreferences> = context.notificationDataStore.data.map { preferences ->
        NotificationPreferences(
            pushEnabled = preferences[PUSH_ENABLED_KEY] ?: true,
            soundEnabled = preferences[SOUND_ENABLED_KEY] ?: true,
            vibrationEnabled = preferences[VIBRATION_ENABLED_KEY] ?: true
        )
    }

    suspend fun setPushEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { preferences ->
            preferences[PUSH_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { preferences ->
            preferences[SOUND_ENABLED_KEY] = enabled
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { preferences ->
            preferences[VIBRATION_ENABLED_KEY] = enabled
        }
    }
}
