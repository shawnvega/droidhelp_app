package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationSettingsTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testNotificationPreferencesDefaults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationSettings = NotificationSettings(context)

        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(true, preferences.pushEnabled)
        assertEquals(true, preferences.soundEnabled)
        assertEquals(true, preferences.vibrationEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetPushEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationSettings = NotificationSettings(context)

        notificationSettings.setPushEnabled(false)
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(false, preferences.pushEnabled)
        assertEquals(true, preferences.soundEnabled)
        assertEquals(true, preferences.vibrationEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetSoundEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationSettings = NotificationSettings(context)

        notificationSettings.setSoundEnabled(false)
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(true, preferences.pushEnabled)
        assertEquals(false, preferences.soundEnabled)
        assertEquals(true, preferences.vibrationEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetVibrationEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationSettings = NotificationSettings(context)

        notificationSettings.setVibrationEnabled(false)
        val preferences = notificationSettings.preferencesFlow.first()
        assertEquals(true, preferences.pushEnabled)
        assertEquals(true, preferences.soundEnabled)
        assertEquals(false, preferences.vibrationEnabled)
    }
}
