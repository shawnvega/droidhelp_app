package com.bighelp.droidhelp.settings

import com.bighelp.droidhelp.platform.SecureStorage
import com.bighelp.droidhelp.transport.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AccountSettingsTest {

    private lateinit var storage: FakeSecureStorage
    private lateinit var accountSettings: AccountSettings

    @Before
    fun setUp() {
        storage = FakeSecureStorage()
        accountSettings = AccountSettings(storage)
    }

    @Test
    fun testSaveAndGetEndpointUrl() {
        runBlocking {
            val url = "https://api.example.com/"
            accountSettings.saveEndpointUrl(url)
            val retrieved = accountSettings.getEndpointUrl()
            assertEquals(Endpoint.fromString(url).canonical, retrieved)
        }
    }

    @Test
    fun testClear() {
        runBlocking {
            val url = "https://api.example.com/"
            accountSettings.saveEndpointUrl(url)
            accountSettings.clear()
            val retrieved = accountSettings.getEndpointUrl()
            assertNull(retrieved)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSaveInvalidUrl() {
        runBlocking {
            // Invalid URL (missing scheme)
            accountSettings.saveEndpointUrl("api.example.com")
        }
    }

    private class FakeSecureStorage : SecureStorage {
        private val data = mutableMapOf<String, Any>()

        override suspend fun saveString(key: String, value: String) {
            data[key] = value
        }

        override suspend fun getString(key: String): String? {
            return data[key] as? String
        }

        override suspend fun delete(key: String) {
            data.remove(key)
        }

        override suspend fun clearAll() {
            data.clear()
        }

        override suspend fun saveByteArray(key: String, value: ByteArray) {
            data[key] = value
        }

        override suspend fun getByteArray(key: String): ByteArray? {
            return data[key] as? ByteArray
        }
    }
}
