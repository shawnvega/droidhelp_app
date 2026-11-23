package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.privacyDataStore by preferencesDataStore(name = "privacy_settings")

class PrivacySettings(private val context: Context) {
    companion object {
        private val ANALYTICS_ENABLED_KEY = booleanPreferencesKey("analytics_enabled")
        private val BIOMETRIC_LOCK_ENABLED_KEY = booleanPreferencesKey("biometric_lock_enabled")
        private val DATA_SHARING_ENABLED_KEY = booleanPreferencesKey("data_sharing_enabled")
    }

    val analyticsEnabledFlow: Flow<Boolean> = context.privacyDataStore.data.map { preferences ->
        preferences[ANALYTICS_ENABLED_KEY] ?: false
    }

    suspend fun setAnalyticsEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[ANALYTICS_ENABLED_KEY] = enabled
        }
    }

    val biometricLockEnabledFlow: Flow<Boolean> = context.privacyDataStore.data.map { preferences ->
        preferences[BIOMETRIC_LOCK_ENABLED_KEY] ?: false
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[BIOMETRIC_LOCK_ENABLED_KEY] = enabled
        }
    }

    val dataSharingEnabledFlow: Flow<Boolean> = context.privacyDataStore.data.map { preferences ->
        preferences[DATA_SHARING_ENABLED_KEY] ?: false
    }

    suspend fun setDataSharingEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[DATA_SHARING_ENABLED_KEY] = enabled
        }
    }
}
