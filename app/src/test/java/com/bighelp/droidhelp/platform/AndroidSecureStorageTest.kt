package com.bighelp.droidhelp.platform

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AndroidSecureStorageTest {

    private lateinit var storage: SecureStorage

    @Before
    fun setup() {
        storage = InMemorySecureStorage()
    }

    @Test
    fun `test round-trip save and read string`() = runTest {
        val key = "test_string_key"
        val value = "hello_world"

        storage.saveString(key, value)
        val retrieved = storage.getString(key)

        assertEquals(value, retrieved)
    }

    @Test
    fun `test round-trip save and read byte array`() = runTest {
        val key = "test_byte_key"
        val value = byteArrayOf(1, 2, 3, 4, 5)

        storage.saveByteArray(key, value)
        val retrieved = storage.getByteArray(key)

        assertNotNull(retrieved)
        assertArrayEquals(value, retrieved)
    }

    @Test
    fun `test delete removes entry`() = runTest {
        val key = "test_delete_key"
        val value = "to_be_deleted"

        storage.saveString(key, value)
        storage.delete(key)

        val retrieved = storage.getString(key)
        assertNull(retrieved)
    }

    @Test
    fun `test clearAll clears everything`() = runTest {
        storage.saveString("key1", "val1")
        storage.saveString("key2", "val2")

        storage.clearAll()

        assertNull(storage.getString("key1"))
        assertNull(storage.getString("key2"))
    }

    private class InMemorySecureStorage : SecureStorage {
        private val strings = mutableMapOf<String, String>()
        private val bytes = mutableMapOf<String, ByteArray>()

        override suspend fun saveString(key: String, value: String) {
            strings[key] = value
        }

        override suspend fun getString(key: String): String? = strings[key]

        override suspend fun saveByteArray(key: String, value: ByteArray) {
            bytes[key] = value
        }

        override suspend fun getByteArray(key: String): ByteArray? = bytes[key]

        override suspend fun delete(key: String) {
            strings.remove(key)
            bytes.remove(key)
        }

        override suspend fun clearAll() {
            strings.clear()
            bytes.clear()
        }
    }
}