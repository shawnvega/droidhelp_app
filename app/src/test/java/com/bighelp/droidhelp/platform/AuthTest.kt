package com.bighelp.droidhelp.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun testGenerateCodeChallengeMatchesRfc7636Vector() {
        val auth = Auth(FakeSecureStorage())
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, auth.generateCodeChallenge(verifier))
        assertThrows(IllegalArgumentException::class.java) {
            auth.generateCodeChallenge("   ")
        }
    }

    @Test
    fun testValidateAndClearStateFailureAndReplay() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        // No stored state yet
        assertFalse(auth.validateAndClearState("any_state"))

        val state = auth.generateAndSaveState()

        // Blank or wrong state fails without clearing stored state
        assertFalse(auth.validateAndClearState(""))
        assertFalse(auth.validateAndClearState("wrong_state"))
        assertEquals(state, storage.getString("oauth_state"))

        // Valid state succeeds once, then replay fails
        assertTrue(auth.validateAndClearState(state))
        assertNull(storage.getString("oauth_state"))
        assertFalse(auth.validateAndClearState(state))
    }

    @Test
    fun testRotateTokensConsumedRegistryAndReuseAfterClear() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        auth.saveTokens("access_1", "refresh_1")
        auth.rotateTokens("refresh_1", "access_2", "refresh_2")

        assertTrue(auth.isRefreshTokenConsumed("refresh_1"))
        assertFalse(auth.isRefreshTokenConsumed("refresh_2"))

        // Replaying consumed refresh_1 fails and preserves current tokens
        val replayException = runCatching {
            auth.rotateTokens("refresh_1", "access_bad", "refresh_bad")
        }.exceptionOrNull()
        assertTrue(replayException is Auth.TokenReuseException)
        assertEquals("access_2", auth.getAccessToken())
        assertEquals("refresh_2", auth.getRefreshToken())

        // Re-issuing an already consumed token as the new refresh token is rejected
        val reissueException = runCatching {
            auth.rotateTokens("refresh_2", "access_3", "refresh_1")
        }.exceptionOrNull()
        assertTrue(reissueException is Auth.TokenReuseException)
        assertEquals("refresh_2", auth.getRefreshToken())

        // Consumed digest survives clearTokens()
        auth.clearTokens()
        assertTrue(auth.isRefreshTokenConsumed("refresh_1"))
        val postClearException = runCatching {
            auth.rotateTokens("refresh_1", "access_4", "refresh_4")
        }.exceptionOrNull()
        assertTrue(postClearException is Auth.TokenReuseException)
    }

    @Test
    fun testRotateTokensNonRotatingServerAndBoundedRegistry() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        auth.saveTokens("access_1", "refresh_static")
        auth.rotateTokens("refresh_static", "access_2", "refresh_static")
        assertFalse(auth.isRefreshTokenConsumed("refresh_static"))
        assertEquals("access_2", auth.getAccessToken())

        // Subsequent rotation with the same token succeeds
        auth.rotateTokens("refresh_static", "access_3", "refresh_0")
        assertTrue(auth.isRefreshTokenConsumed("refresh_static"))

        // Rotate beyond the 64-entry cap and confirm oldest entries are evicted
        val totalRotations = 70
        for (i in 0 until totalRotations) {
            auth.rotateTokens("refresh_$i", "access_${i + 1}", "refresh_${i + 1}")
        }
        assertFalse(auth.isRefreshTokenConsumed("refresh_0"))
        assertTrue(auth.isRefreshTokenConsumed("refresh_${totalRotations - 1}"))
    }

    @Test
    fun testRotateTokensConcurrentRaceOnlyAllowsOne() = runTest {
        val baseStorage = FakeSecureStorage()
        val yieldingStorage = object : SecureStorage by baseStorage {
            override suspend fun getString(key: String): String? {
                yield()
                return baseStorage.getString(key)
            }

            override suspend fun saveString(key: String, value: String) {
                yield()
                baseStorage.saveString(key, value)
            }
        }
        val auth = Auth(yieldingStorage)
        auth.saveTokens("access_1", "refresh_1")

        val results = listOf(
            async { runCatching { auth.rotateTokens("refresh_1", "access_a", "refresh_a") } },
            async { runCatching { auth.rotateTokens("refresh_1", "access_b", "refresh_b") } }
        ).awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is Auth.TokenReuseException })
        assertTrue(auth.isRefreshTokenConsumed("refresh_1"))
    }

    @Test
    fun testGenerateCodeVerifierUniquenessAndRfc7636Format() {
        val auth = Auth(FakeSecureStorage())
        val generated = (1..32).map { auth.generateCodeVerifier() }.toSet()

        assertEquals(32, generated.size)
        for (verifier in generated) {
            assertEquals(Auth.MIN_VERIFIER_LENGTH, verifier.length)
            assertTrue(Auth.isValidCodeVerifier(verifier))
        }
        assertEquals("S256", Auth.CODE_CHALLENGE_METHOD_S256)
    }

    @Test
    fun testGenerateCodeChallengeEnforcesRfc7636VerifierConstraints() {
        val auth = Auth(FakeSecureStorage())

        // Boundary lengths and RFC 7636 unreserved characters (- . _ ~)
        val minLengthVerifier = "a".repeat(Auth.MIN_VERIFIER_LENGTH)
        val maxLengthVerifier = "Az09-._~".repeat(16) // 128 chars
        assertEquals(Auth.MAX_VERIFIER_LENGTH, maxLengthVerifier.length)
        assertTrue(auth.generateCodeChallenge(minLengthVerifier).isNotEmpty())
        assertTrue(auth.generateCodeChallenge(maxLengthVerifier).isNotEmpty())

        // Too short (< 43) and too long (> 128)
        assertThrows(IllegalArgumentException::class.java) {
            auth.generateCodeChallenge("a".repeat(Auth.MIN_VERIFIER_LENGTH - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            auth.generateCodeChallenge("a".repeat(Auth.MAX_VERIFIER_LENGTH + 1))
        }

        // Disallowed characters (+, /, =, whitespace, non-ASCII)
        val invalidChars = listOf('+', '/', '=', ' ', 'é')
        for (invalidChar in invalidChars) {
            val badVerifier = "a".repeat(Auth.MIN_VERIFIER_LENGTH - 1) + invalidChar
            assertFalse(Auth.isValidCodeVerifier(badVerifier))
            assertThrows(IllegalArgumentException::class.java) {
                auth.generateCodeChallenge(badVerifier)
            }
        }
    }

    @Test
    fun testGetOrCreateVerifierRegeneratesInvalidStoredValue() = runTest {
        val storage = FakeSecureStorage()
        storage.saveString("pkce_code_verifier", "too_short")
        val auth = Auth(storage)

        val verifier = auth.getOrCreateVerifier()

        assertTrue(Auth.isValidCodeVerifier(verifier))
        assertEquals(verifier, storage.getString("pkce_code_verifier"))
    }

    @Test
    fun testGenerateAndSaveStateEntropyUniquenessAndClearState() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        val states = (1..16).map { auth.generateAndSaveState() }.toSet()
        assertEquals(16, states.size)
        for (state in states) {
            assertTrue(state.length >= 22)
            assertFalse(state.contains("+"))
            assertFalse(state.contains("/"))
            assertFalse(state.contains("="))
        }

        val activeState = auth.generateAndSaveState()
        auth.clearState()
        assertNull(storage.getString("oauth_state"))
        assertFalse(auth.validateAndClearState(activeState))
    }

    @Test
    fun testValidateAndClearStateConcurrentRaceOnlyAllowsOne() = runTest {
        val baseStorage = FakeSecureStorage()
        val yieldingStorage = object : SecureStorage by baseStorage {
            override suspend fun getString(key: String): String? {
                yield()
                return baseStorage.getString(key)
            }

            override suspend fun delete(key: String) {
                yield()
                baseStorage.delete(key)
            }
        }
        val auth = Auth(yieldingStorage)
        val state = auth.generateAndSaveState()

        val results = listOf(
            async { auth.validateAndClearState(state) },
            async { auth.validateAndClearState(state) }
        ).awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertNull(baseStorage.getString("oauth_state"))
    }

    @Test
    fun testRotateTokensPersistsToSecureStorageAsDigestsAcrossRestarts() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        auth.saveTokens("access_1", "refresh_1")
        auth.rotateTokens("refresh_1", "access_2", "refresh_2")

        assertEquals("access_2", storage.getString("access_token"))
        assertEquals("refresh_2", storage.getString("refresh_token"))

        auth.rotateTokens("refresh_2", "access_3", "refresh_3")

        assertEquals("access_3", storage.getString("access_token"))
        assertEquals("refresh_3", storage.getString("refresh_token"))

        val consumedRaw = storage.getString("consumed_refresh_tokens")
        assertNotNull(consumedRaw)
        assertFalse(consumedRaw!!.contains("refresh_1"))
        assertFalse(consumedRaw.contains("refresh_2"))
        assertFalse(consumedRaw.contains("refresh_3"))

        val digestLines = consumedRaw.split('\n').filter { it.isNotBlank() }
        assertEquals(2, digestLines.size)
        val sha256HexRegex = Regex("^[0-9a-f]{64}$")
        for (digestLine in digestLines) {
            assertTrue(sha256HexRegex.matches(digestLine))
        }

        // Simulate process restart with a fresh Auth instance over the same SecureStorage
        val restartedAuth = Auth(storage)
        assertEquals("access_3", restartedAuth.getAccessToken())
        assertEquals("refresh_3", restartedAuth.getRefreshToken())
        assertTrue(restartedAuth.isRefreshTokenConsumed("refresh_1"))
        assertTrue(restartedAuth.isRefreshTokenConsumed("refresh_2"))
        assertFalse(restartedAuth.isRefreshTokenConsumed("refresh_3"))

        val replayAfterRestart = runCatching {
            restartedAuth.rotateTokens("refresh_1", "access_bad", "refresh_bad")
        }.exceptionOrNull()
        assertTrue(replayAfterRestart is Auth.TokenReuseException)
        assertEquals("access_3", restartedAuth.getAccessToken())
        assertEquals("refresh_3", restartedAuth.getRefreshToken())
    }

    @Test
    fun testSaveTokensAndRotateTokensRejectBlankInputs() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        assertTrue(runCatching { auth.saveTokens("", "refresh_1") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { auth.saveTokens("access_1", "   ") }.exceptionOrNull() is IllegalArgumentException)
        assertNull(storage.getString("access_token"))
        assertNull(storage.getString("refresh_token"))

        auth.saveTokens("access_1", "refresh_1")

        val blankOld = runCatching {
            auth.rotateTokens("", "access_2", "refresh_2")
        }.exceptionOrNull()
        val blankAccess = runCatching {
            auth.rotateTokens("refresh_1", "  ", "refresh_2")
        }.exceptionOrNull()
        val blankRefresh = runCatching {
            auth.rotateTokens("refresh_1", "access_2", "\t")
        }.exceptionOrNull()
        assertTrue(blankOld is IllegalArgumentException)
        assertTrue(blankAccess is IllegalArgumentException)
        assertTrue(blankRefresh is IllegalArgumentException)

        assertEquals("access_1", auth.getAccessToken())
        assertEquals("refresh_1", auth.getRefreshToken())
        assertFalse(auth.isRefreshTokenConsumed("refresh_1"))
        assertFalse(auth.isRefreshTokenConsumed(""))
        assertFalse(auth.isRefreshTokenConsumed("   "))
    }

    @Test
    fun testSaveTokensRejectsPreviouslyConsumedRefreshToken() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        auth.saveTokens("access_1", "refresh_1")
        auth.rotateTokens("refresh_1", "access_2", "refresh_2")

        // Attempting to reinstall consumed refresh_1 via saveTokens is rejected
        val activeReuseException = runCatching {
            auth.saveTokens("access_replayed", "refresh_1")
        }.exceptionOrNull()
        assertTrue(activeReuseException is Auth.TokenReuseException)
        assertEquals("access_2", auth.getAccessToken())
        assertEquals("refresh_2", auth.getRefreshToken())

        // Consumed rejection still applies after clearTokens()
        auth.clearTokens()
        val postClearReuseException = runCatching {
            auth.saveTokens("access_replayed", "refresh_1")
        }.exceptionOrNull()
        assertTrue(postClearReuseException is Auth.TokenReuseException)
        assertNull(auth.getAccessToken())
        assertNull(auth.getRefreshToken())
    }

    @Test
    fun testNonRotatingReplayOfConsumedTokenIsRejected() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        auth.saveTokens("access_1", "refresh_1")
        auth.rotateTokens("refresh_1", "access_2", "refresh_2")
        auth.clearTokens()

        // Even when oldRefreshToken == newRefreshToken, an already-consumed token must be rejected
        val replayException = runCatching {
            auth.rotateTokens("refresh_1", "access_3", "refresh_1")
        }.exceptionOrNull()
        assertTrue(replayException is Auth.TokenReuseException)
        assertNull(auth.getAccessToken())
        assertNull(auth.getRefreshToken())
    }

    @Test
    fun testRejectedReuseAttemptsDoNotPolluteOrEvictConsumedRegistry() = runTest {
        val storage = FakeSecureStorage()
        val auth = Auth(storage)

        auth.saveTokens("access_1", "refresh_1")
        auth.rotateTokens("refresh_1", "access_2", "refresh_2")
        assertTrue(auth.isRefreshTokenConsumed("refresh_1"))

        for (i in 0 until Auth.MAX_CONSUMED_TOKENS + 10) {
            val err = runCatching {
                auth.rotateTokens("bogus_token_$i", "access_bad_$i", "refresh_bad_$i")
            }.exceptionOrNull()
            assertTrue(err is Auth.TokenReuseException)
            assertFalse(auth.isRefreshTokenConsumed("bogus_token_$i"))
        }

        assertTrue(auth.isRefreshTokenConsumed("refresh_1"))
        assertEquals("access_2", auth.getAccessToken())
        assertEquals("refresh_2", auth.getRefreshToken())
    }

    @Test
    fun testRotateTokensRollsBackOnRefreshTokenWriteFailure() = runTest {
        val baseStorage = FakeSecureStorage()
        var failKey: String? = null
        val failingStorage = object : SecureStorage by baseStorage {
            override suspend fun saveString(key: String, value: String) {
                if (key == failKey) {
                    error("Simulated storage failure on $key")
                }
                baseStorage.saveString(key, value)
            }
        }
        val auth = Auth(failingStorage)
        auth.saveTokens("access_1", "refresh_1")

        // Failure while saving refresh_token rolls back access_token
        failKey = "refresh_token"
        val refreshFail = runCatching {
            auth.rotateTokens("refresh_1", "access_2", "refresh_2")
        }.exceptionOrNull()
        assertTrue(refreshFail is IllegalStateException)
        failKey = null
        assertEquals("access_1", auth.getAccessToken())
        assertEquals("refresh_1", auth.getRefreshToken())
        assertFalse(auth.isRefreshTokenConsumed("refresh_1"))

        // Failure while recording consumed digest rolls back both tokens
        failKey = "consumed_refresh_tokens"
        val consumedFail = runCatching {
            auth.rotateTokens("refresh_1", "access_2", "refresh_2")
        }.exceptionOrNull()
        assertTrue(consumedFail is IllegalStateException)
        failKey = null
        assertEquals("access_1", auth.getAccessToken())
        assertEquals("refresh_1", auth.getRefreshToken())
        assertFalse(auth.isRefreshTokenConsumed("refresh_1"))

        // Once storage recovers, rotation with refresh_1 succeeds cleanly
        auth.rotateTokens("refresh_1", "access_2", "refresh_2")
        assertEquals("access_2", auth.getAccessToken())
        assertEquals("refresh_2", auth.getRefreshToken())
        assertTrue(auth.isRefreshTokenConsumed("refresh_1"))
    }

    @Test
    fun testSaveTokensRollsBackAccessTokenOnRefreshWriteFailure() = runTest {
        val baseStorage = FakeSecureStorage()
        var failRefreshWrite = true
        val failingStorage = object : SecureStorage by baseStorage {
            override suspend fun saveString(key: String, value: String) {
                if (failRefreshWrite && key == "refresh_token") {
                    error("Simulated refresh write failure")
                }
                baseStorage.saveString(key, value)
            }
        }
        val auth = Auth(failingStorage)

        val err = runCatching {
            auth.saveTokens("access_1", "refresh_1")
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertNull(baseStorage.getString("access_token"))
        assertNull(baseStorage.getString("refresh_token"))

        failRefreshWrite = false
        auth.saveTokens("access_1", "refresh_1")
        failRefreshWrite = true

        val overwriteErr = runCatching {
            auth.saveTokens("access_2", "refresh_2")
        }.exceptionOrNull()
        assertTrue(overwriteErr is IllegalStateException)
        assertEquals("access_1", baseStorage.getString("access_token"))
        assertEquals("refresh_1", baseStorage.getString("refresh_token"))
    }
}

