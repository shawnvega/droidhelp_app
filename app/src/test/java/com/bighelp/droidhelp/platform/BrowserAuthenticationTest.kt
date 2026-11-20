package com.bighelp.droidhelp.platform

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class BrowserAuthenticationTest {
    companion object {
        private const val TEST_PORT = 8080
        private const val DEFAULT_HTTP_PORT = 80
        private const val OTHER_PORT = 9090
        private const val OUT_OF_RANGE_PORT = 65536
        private const val TEST_PATH = "/auth/callback"
        private const val TEST_STATE = "test-state"
    }

    @Test
    fun testValidCallback() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        val code = callback.validate(incomingUrl, queryParams)
        assertEquals("test-code", code)
    }

    @Test
    fun testValidCallbackIp() {
        val url = URI("http://127.0.0.1:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://127.0.0.1:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        val code = callback.validate(incomingUrl, queryParams)
        assertEquals("test-code", code)
    }

    @Test
    fun testValidCallbackIpv6Loopback() {
        val url = URI("http://[::1]:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://[::1]:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "ipv6-code")

        val code = callback.validate(incomingUrl, queryParams)
        assertEquals("ipv6-code", code)
    }

    @Test
    fun testInvalidHost() {
        val url = URI("http://example.com:$TEST_PORT$TEST_PATH")
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)
        }
    }

    @Test
    fun testInvalidHostMismatch() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://127.0.0.1:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testPathMismatch() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://localhost:$TEST_PORT/wrong-path")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testStateMismatch() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to "wrong-state", "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testMissingCode() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE)

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testInvalidScheme() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("https://localhost:$TEST_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testInvalidPort() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val incomingUrl = URI("http://localhost:$OTHER_PORT$TEST_PATH")
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testConstructorRejectsInvalidConfiguration() {
        val validUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")

        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(0, TEST_PATH, TEST_STATE, validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(-1, TEST_PATH, TEST_STATE, validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(OUT_OF_RANGE_PORT, TEST_PATH, TEST_STATE, validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(TEST_PORT, "no-leading-slash", TEST_STATE, validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(TEST_PORT, "/auth//callback", TEST_STATE, validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(TEST_PORT, "/auth/../callback", TEST_STATE, validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, "   ", validUrl)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(
                TEST_PORT,
                TEST_PATH,
                TEST_STATE,
                URI("https://localhost:$TEST_PORT$TEST_PATH")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(
                TEST_PORT,
                TEST_PATH,
                TEST_STATE,
                URI("http://user@localhost:$TEST_PORT$TEST_PATH")
            )
        }
    }

    @Test
    fun testValidateUriQueryParsingAndDuplicateRejection() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val validIncoming = URI(
            "http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=abc%26def%3D123"
        )
        assertEquals("abc&def=123", callback.validate(validIncoming))

        val duplicateParam = URI(
            "http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&state=other&code=test-code"
        )
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(duplicateParam)
        }

        val missingQuery = URI("http://localhost:$TEST_PORT$TEST_PATH")
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(missingQuery)
        }
    }

    @Test
    fun testRejectsEncodedPathAndUserInfoInIncomingUrl() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http://localhost:$TEST_PORT/auth%2Fcallback"), queryParams)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http://localhost:$TEST_PORT/x/../auth/callback"), queryParams)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http://user@localhost:$TEST_PORT$TEST_PATH"), queryParams)
        }
    }

    @Test
    fun testRejectsBlankCodeAndAuthorizationError() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)
        val incomingUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, mapOf("state" to TEST_STATE, "code" to "   "))
        }

        val errorException = assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                incomingUrl,
                mapOf(
                    "state" to TEST_STATE,
                    "error" to "access_denied",
                    "error_description" to "User denied access"
                )
            )
        }
        assertTrue(errorException.message?.contains("access_denied") == true)
        assertTrue(errorException.message?.contains("User denied access") == true)

        val spoofedError = assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                incomingUrl,
                mapOf("state" to "wrong-state", "error" to "access_denied")
            )
        }
        assertTrue(spoofedError.message?.contains("State mismatch") == true)
    }

    @Test
    fun testRejectsMissingOrPartialState() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)
        val incomingUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, mapOf("code" to "test-code"))
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, mapOf("state" to "test-stat", "code" to "test-code"))
        }
    }

    @Test
    fun testRejectsFragmentsInConstructorAndIncomingUrl() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(
                TEST_PORT,
                TEST_PATH,
                TEST_STATE,
                URI("http://localhost:$TEST_PORT$TEST_PATH#fragment")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(
                TEST_PORT,
                TEST_PATH,
                TEST_STATE,
                URI("http://localhost:$TEST_PORT$TEST_PATH#")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=test-code#fragment")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("http://localhost:$TEST_PORT$TEST_PATH#"),
                mapOf("state" to TEST_STATE, "code" to "test-code")
            )
        }
    }

    @Test
    fun testRejectsOpaqueUrisEmptyPortAndOmittedPortMismatch() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)
        val queryParams = mapOf("state" to TEST_STATE, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(
                TEST_PORT,
                TEST_PATH,
                TEST_STATE,
                URI("http:localhost:$TEST_PORT$TEST_PATH")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http:localhost:$TEST_PORT$TEST_PATH"), queryParams)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(
                TEST_PORT,
                TEST_PATH,
                TEST_STATE,
                URI("http://localhost:$TEST_PATH")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http://localhost:$TEST_PATH"), queryParams)
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http://localhost$TEST_PATH"), queryParams)
        }
    }

    @Test
    fun testDefaultHttpPort80Handling() {
        val url = URI("http://localhost:$DEFAULT_HTTP_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(DEFAULT_HTTP_PORT, TEST_PATH, TEST_STATE, url)

        val omittedPortIncoming = URI(
            "http://localhost$TEST_PATH?state=$TEST_STATE&code=port80-code"
        )
        assertEquals("port80-code", callback.validate(omittedPortIncoming))

        val explicitPortIncoming = URI(
            "http://localhost:$DEFAULT_HTTP_PORT$TEST_PATH?state=$TEST_STATE&code=port80-explicit"
        )
        assertEquals("port80-explicit", callback.validate(explicitPortIncoming))

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=wrong-port")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("http://localhost:$TEST_PATH?state=$TEST_STATE&code=empty-port")
            )
        }
    }

    @Test
    fun testCaseInsensitiveSchemeAndHostNormalization() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        val upperIncoming = URI(
            "HTTP://LOCALHOST:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=upper-code"
        )
        assertEquals("upper-code", callback.validate(upperIncoming))

        val upperConfig = BrowserAuthenticationCallback(
            TEST_PORT,
            TEST_PATH,
            TEST_STATE,
            URI("HTTP://LOCALHOST:$TEST_PORT$TEST_PATH")
        )
        assertEquals(
            "lower-code",
            upperConfig.validate(
                URI("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=lower-code")
            )
        )

        // Still rejects non-http schemes and non-loopback lookalike hosts
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("HTTPS://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=test-code")
            )
        }
        for (nonLoopback in listOf("localhost.evil.com", "127.0.0.2", "localhost.", "0.0.0.0")) {
            assertThrows(BrowserAuthenticationException::class.java) {
                BrowserAuthenticationCallback(
                    TEST_PORT,
                    TEST_PATH,
                    TEST_STATE,
                    URI("http://$nonLoopback:$TEST_PORT$TEST_PATH")
                )
            }
        }
    }

    @Test
    fun testConstructorRejectsAdditionalInvalidPaths() {
        val validUrl = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val invalidPaths = listOf(
            "/auth/./callback",
            "/.",
            "/auth\\callback",
            "/auth/callback?x=1",
            "/auth/callback#frag",
            "/auth/%2e%2e/callback",
            "/auth/callback ",
            "/auth\n/callback"
        )
        for (badPath in invalidPaths) {
            assertThrows(BrowserAuthenticationException::class.java) {
                BrowserAuthenticationCallback(TEST_PORT, badPath, TEST_STATE, validUrl)
            }
        }
    }

    @Test
    fun testMalformedQueryEncodingEncodedDuplicateKeysAndBareError() {
        val url = URI("http://localhost:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, TEST_STATE, url)

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=%ZZ")
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=%2")
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&%73tate=other&code=test-code")
            )
        }
        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(URI("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code"))
        }

        val bareError = assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(
                URI("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&error=server_error&code=ignored")
            )
        }
        assertTrue(bareError.message?.contains("server_error") == true)

        assertEquals(
            "a b+c/d",
            callback.validate("http://localhost:$TEST_PORT$TEST_PATH?state=$TEST_STATE&code=a%20b%2Bc%2Fd")
        )
    }

    @Test
    fun testEndToEndPkceStateAndLoopbackValidation() = runTest {
        val auth = Auth(FakeSecureStorage())
        val verifier = auth.getOrCreateVerifier()
        val challenge = auth.generateCodeChallenge(verifier)
        val state = auth.generateAndSaveState()

        assertTrue(Auth.isValidCodeVerifier(verifier))
        assertTrue(challenge.isNotBlank())

        val redirectUri = URI("http://127.0.0.1:$TEST_PORT$TEST_PATH")
        val callback = BrowserAuthenticationCallback(TEST_PORT, TEST_PATH, state, redirectUri)

        val incomingCallback = URI(
            "http://127.0.0.1:$TEST_PORT$TEST_PATH?state=$state&code=auth_code_xyz"
        )
        val authorizationCode = callback.validate(incomingCallback)

        assertEquals("auth_code_xyz", authorizationCode)
        assertTrue(auth.validateAndClearState(state))
        assertFalse(auth.validateAndClearState(state))

        auth.clearVerifier()
        assertFalse(auth.getOrCreateVerifier() == verifier)
    }
}

