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
class PrivacySettingsTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testAnalyticsEnabledDefault() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacySettings = PrivacySettings(context)
        
        assertEquals(false, privacySettings.analyticsEnabledFlow.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetAndGetAnalyticsEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacySettings = PrivacySettings(context)

        privacySettings.setAnalyticsEnabled(true)
        assertEquals(true, privacySettings.analyticsEnabledFlow.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetAndGetBiometricLockEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacySettings = PrivacySettings(context)

        privacySettings.setBiometricLockEnabled(true)
        assertEquals(true, privacySettings.biometricLockEnabledFlow.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSetAndGetDataSharingEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacySettings = PrivacySettings(context)

        privacySettings.setDataSharingEnabled(true)
        assertEquals(true, privacySettings.dataSharingEnabledFlow.first())
    }
}
