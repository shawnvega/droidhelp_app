package com.bighelp.droidhelp.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class BrowserAuthenticationTest {
    companion object {
        private const val TEST_PORT = 8080
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
}

