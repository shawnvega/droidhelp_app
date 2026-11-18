package com.bighelp.droidhelp.platform

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P]) // API 28
class AndroidSecureStorageTest {

    private lateinit var context: Context
    private lateinit var secureStorage: AndroidSecureStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Use a regular JKS keystore for testing since AndroidKeyStore is not available in Robolectric
        val testKeyStore = KeyStore.getInstance("JKS")
        testKeyStore.load(null, null)
        
        secureStorage = AndroidSecureStorage(context, testKeyStore)
    }

    @Test
    fun `test round-trip save and read string`() = kotlinx.coroutines.runBlocking {
        val key = "test_string_key"
        val value = "hello_world"

        secureStorage.saveString(key, value)
        val retrieved = secureStorage.getString(key)

        assertEquals(value, retrieved)
    }

    @Test
    fun `test round-trip save and read byte array`() = kotlinx.coroutines.runBlocking {
        val key = "test_byte_key"
        val value = byteArrayOf(1, 2, 3, 4, 5)

        secureStorage.saveByteArray(key, value)
        val retrieved = secureStorage.getByteArray(key)

        assertNotNull(retrieved)
        assertArrayEquals(value, retrieved)
    }

    @Test
    fun `test delete removes entry`() = kotlinx.coroutines.runBlocking {
        val key = "test_delete_key"
        val value = "to_be_deleted"

        secureStorage.saveString(key, value)
        secureStorage.delete(key)

        val retrieved = secureStorage.getString(key)
        assertNull(retrieved)
    }

    @Test
    fun `test clearAll clears everything`() = kotlinx.coroutines.runBlocking {
        secureStorage.saveString("key1", "val1")
        secureStorage.saveString("key2", "val2")

        secureStorage.clearAll()

        assertNull(secureStorage.getString("key1"))
        assertNull(secureStorage.getString("key2"))
    }

    @Test
    fun `test tampered ciphertext returns null`() = kotlinx.coroutines.runBlocking {
        val key = "tamper_key"
        val value = "secret_data"

        secureStorage.saveString(key, value)

        // Manually tamper with SharedPreferences to simulate corrupted data
        val prefs = context.getSharedPreferences("secure_storage_prefs", Context.MODE_PRIVATE)
        val originalEncoded = prefs.getString(key, null)
        assertNotNull(originalEncoded)

        // Tamper by changing one character in the Base64 string
        val tamperedEncoded = if (originalEncoded!!.endsWith("A")) {
            originalEncoded.dropLast(1) + "B"
        } else {
            originalEncoded + "A"
        }
        prefs.edit().putString(key, tamperedEncoded).apply()

        val retrieved = secureStorage.getString(key)
        assertNull("Tampered data should return null", retrieved)
    }

    @Test
    fun `test stored value is not plaintext`() = kotlinx.coroutines.runBlocking {
        val key = "plaintext_check_key"
        val value = "super_secret_password"

        secureStorage.saveString(key, value)

        val prefs = context.getSharedPreferences("secure_storage_prefs", Context.MODE_PRIVATE)
        val encodedValue = prefs.getString(key, null)

        assertNotNull(encodedValue)
        assertNotEquals("Stored value must be encrypted/encoded, not plaintext", value, encodedValue)
    }
}
