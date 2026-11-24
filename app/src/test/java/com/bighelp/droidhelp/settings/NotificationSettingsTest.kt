package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationSettingsTest {

    private lateinit var notificationSettings: NotificationSettings
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        notificationSettings = NotificationSettings(context)
        // Reset to defaults to ensure test independence
        runTest {
            notificationSettings.setPushEnabled(true)
            notificationSettings.setSoundEnabled(true)
            notificationSettings.setVibrationEnabled(true)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testNotificationPreferencesDefaults() = runTest {
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(true, preferences.pushEnabled)
        assertEquals(true, preferences.soundEnabled)
        assertEquals(true, preferences.vibrationEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetPushEnabled() = runTest {
        notificationSettings.setPushEnabled(false)
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(false, preferences.pushEnabled)
        assertEquals(true, preferences.soundEnabled)
        assertEquals(true, preferences.vibrationEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetSoundEnabled() = runTest {
        notificationSettings.setSoundEnabled(false)
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(true, preferences.pushEnabled)
        assertEquals(false, preferences.soundEnabled)
        assertEquals(true, preferences.vibrationEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetVibrationEnabled() = runTest {
        notificationSettings.setVibrationEnabled(false)
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(true, preferences.pushEnabled)
        assertEquals(true, preferences.soundEnabled)
        assertEquals(false, preferences.vibrationEnabled)
    }
}
