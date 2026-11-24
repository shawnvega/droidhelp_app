package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bighelp.droidhelp.designsystem.ColorMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance_settings")

class AppearanceSettings(private val context: Context) {
    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        const val MIN_FONT_SCALE = 0.85f
        const val DEFAULT_FONT_SCALE = 1.0f
        const val MAX_FONT_SCALE = 1.35f
        private val FONT_SCALE_KEY = floatPreferencesKey("font_scale")
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

    fun clampFontScale(scale: Float): Float = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    val fontScaleFlow: Flow<Float> = context.appearanceDataStore.data.map { preferences ->
        preferences[FONT_SCALE_KEY] ?: DEFAULT_FONT_SCALE
    }

    suspend fun setFontScale(scale: Float) {
        context.appearanceDataStore.edit { preferences ->
            preferences[FONT_SCALE_KEY] = clampFontScale(scale)
        }
    }
}
 
