package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bighelp.droidhelp.designsystem.ColorMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceSettingsTest {

    @Test
    fun testClampFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppearanceSettings(context)
        assertEquals(AppearanceSettings.MIN_FONT_SCALE, settings.clampFontScale(AppearanceSettings.MIN_FONT_SCALE))
        assertEquals(AppearanceSettings.MAX_FONT_SCALE, settings.clampFontScale(AppearanceSettings.MAX_FONT_SCALE))
        assertEquals(AppearanceSettings.MIN_FONT_SCALE, settings.clampFontScale(0.5f))
        assertEquals(AppearanceSettings.MAX_FONT_SCALE, settings.clampFontScale(2.0f))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testFontScaleDefault() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppearanceSettings(context)
        assertEquals(AppearanceSettings.DEFAULT_FONT_SCALE, settings.fontScaleFlow.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetAndGetFontScale() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppearanceSettings(context)

        settings.setFontScale(1.2f)
        assertEquals(1.2f, settings.fontScaleFlow.first())

        settings.setFontScale(0.5f) // Should be clamped to MIN
        assertEquals(AppearanceSettings.MIN_FONT_SCALE, settings.fontScaleFlow.first())

        settings.setFontScale(2.0f) // Should be clamped to MAX
        assertEquals(AppearanceSettings.MAX_FONT_SCALE, settings.fontScaleFlow.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetAndGetThemeMode() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppearanceSettings(context)

        settings.setThemeMode(ColorMode.Light)
        assertEquals(ColorMode.Light, settings.themeModeFlow.first())

        settings.setThemeMode(ColorMode.Dark)
        assertEquals(ColorMode.Dark, settings.themeModeFlow.first())

        settings.setThemeMode(ColorMode.Dynamic)
        assertEquals(ColorMode.Dynamic, settings.themeModeFlow.first())
    }
}
