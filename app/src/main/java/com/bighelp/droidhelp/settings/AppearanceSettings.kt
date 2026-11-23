package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bighelp.droidhelp.designsystem.ColorMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance_settings")

class AppearanceSettings(private val context: Context) {
    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }

    val themeModeFlow: Flow<ColorMode> = context.appearanceDataStore.data.map { preferences ->
        val modeString = preferences[THEME_MODE_KEY] ?: ColorMode.Dynamic.name
        ColorMode.entries.firstOrNull { it.name == modeString } ?: ColorMode.Dynamic
    }

    suspend fun setThemeMode(mode: ColorMode) {
        context.appearanceDataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode.name
        }
    }
}
