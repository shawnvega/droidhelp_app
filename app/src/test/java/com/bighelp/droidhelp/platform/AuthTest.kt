package com.bighelp.droidhelp.platform

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FakeSecureStorage : SecureStorage {
    private val storage = mutableMapOf<String, Any>()

    override suspend fun saveString(key: String, value: String) {
        storage[key] = value
    }

    override suspend fun getString(key: String): String? {
        return storage[key] as? String
    }

    override suspend fun delete(key: String) {
        storage.remove(key)
    }

    override suspend fun clearAll() {
        storage.clear()
    }

    override suspend fun saveByteArray(key: String, value: ByteArray) {
        storage[key] = value
    }

    override suspend fun getByteArray(key: String): ByteArray? {
        return storage[key] as? ByteArray
    }
}

class AuthTest {
    @Test
    fun testGenerateCodeVerifier() = runTest {
        val auth = Auth(FakeSecureStorage())
        val verifier = auth.generateCodeVerifier()
        
        assertTrue(verifier.length in 43..128)
        assertFalse(verifier.contains("+"))
        assertFalse(verifier.contains("/"))
        assertFalse(verifier.contains("="))
    }

    @Test
    fun testGenerateCodeChallenge() {
        val auth = Auth(FakeSecureStorage())
        val verifier = "this_is_a_very_long_and_random_code_verifier_string_for_testing_purposes"
        val challenge = auth.generateCodeChallenge(verifier)
        
        assertFalse(challenge.isEmpty())
        assertFalse(challenge.contains("+"))
        assertFalse(challenge.contains("/"))
        assertFalse(challenge.contains("="))
    }

    @Test
    fun testGetOrCreateVerifier() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        val v1 = auth.getOrCreateVerifier()
        val v2 = auth.getOrCreateVerifier()
        
        assertEquals(v1, v2)
        assertEquals(v1, storage.getString("pkce_code_verifier"))
    }

    @Test
    fun testClearVerifier() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        auth.getOrCreateVerifier()
        auth.clearVerifier()
        
        assertNull(storage.getString("pkce_code_verifier"))
    }

    @Test
    fun testGenerateAndSaveState() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        val state = auth.generateAndSaveState()
        
        assertTrue(state.isNotEmpty())
        // Check if it's URL safe (no + or /)
        assertFalse(state.contains("+"))
        assertFalse(state.contains("/"))
        assertEquals(state, storage.getString("oauth_state"))
    }

    @Test
    fun testValidateAndClearStateSuccess() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        val state = auth.generateAndSaveState()
        
        val isValid = auth.validateAndClearState(state)
        
        assertTrue(isValid)
        assertNull(storage.getString("oauth_state"))
    }

    @Test
    fun testSaveAndGetTokens() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        auth.saveTokens("access_123", "refresh_456")
        
        assertEquals("access_123", auth.getAccessToken())
        assertEquals("refresh_456", auth.getRefreshToken())
    }

    @Test
    fun testClearTokens() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        auth.saveTokens("access_123", "refresh_456")
        auth.clearTokens()
        
        assertNull(auth.getAccessToken())
        assertNull(auth.getRefreshToken())
    }

    @Test
    fun testRotateTokensSuccess() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        auth.saveTokens("access_old", "refresh_old")
        
        auth.rotateTokens("refresh_old", "access_new", "refresh_new")
        
        assertEquals("access_new", auth.getAccessToken())
        assertEquals("refresh_new", auth.getRefreshToken())
    }

    @Test
    fun testRotateTokensReuseDetected() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)
        
        auth.saveTokens("access_old", "refresh_old")
        
        val exception = try {
            auth.rotateTokens("refresh_wrong", "access_new", "refresh_new")
            null
        } catch (e: Auth.TokenReuseException) {
            e
        }
        
        assertNotNull(exception)
    }
}
