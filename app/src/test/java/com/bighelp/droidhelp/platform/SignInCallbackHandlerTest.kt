package com.bighelp.droidhelp.platform

import com.bighelp.droidhelp.transport.Endpoint
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Tests for the SignInCallbackHandler class.
 * Covers the complete sign-in callback flow:
 * - Parse authorization response
 * - Validate state
 * - Validate loopback redirect
 * - Exchange authorization code using PKCE
 * - Store tokens including refresh token
 * - Handle errors gracefully
 */
class SignInCallbackHandlerTest {

    // Fake implementations for testing
    class FakeHttpClient : HttpClient {
        override suspend fun post(url: String, body: Map<String, String>): Response {
            val responseBody = ResponseBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"access_token": "access_123", "refresh_token": "refresh_456"}"""
            )
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody)
                .build()
        }
    }

    @Test
    fun testProcessCallbackSuccess() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Set up the code_verifier in storage
        val verifier = auth.generateCodeVerifier()
        auth.saveTokens("old_access", "old_refresh") // Initialize tokens

        // Simulate the callback URL with code and state
        val callbackUrl = "http://localhost:8080/auth/callback?state=test_state&code=test_code"

        // Act
        val (accessToken, refreshToken) = handler.processCallback(callbackUrl, 8080, "/auth/callback")

        // Assert
        assertNotNull(accessToken)
        assertNotNull(refreshToken)
        assertEquals("access_123", accessToken)
        assertEquals("refresh_456", refreshToken)
    }

    @Test
    fun testProcessCallbackInvalidState() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Set up a different state in storage (won't match)
        auth.generateAndSaveState()

        // Simulate the callback URL with a different state
        val callbackUrl = "http://localhost:8080/auth/callback?state=wrong_state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: SignInCallbackHandler.SignInCallbackException) {
            exception = e
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_STATE, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackInvalidRedirectHost() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://example.com:8080/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Simulate the callback URL with a non-loopback host
        val callbackUrl = "http://example.com:8080/auth/callback?state=test_state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: SignInCallbackHandler.SignInCallbackException) {
            exception = e
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_REDIRECT, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackInvalidRedirectPort() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:9090/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Simulate the callback URL with a different port
        val callbackUrl = "http://localhost:9090/auth/callback?state=test_state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: SignInCallbackHandler.SignInCallbackException) {
            exception = e
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_REDIRECT, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackMissingCode() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Simulate the callback URL without code parameter
        val callbackUrl = "http://localhost:8080/auth/callback?state=test_state"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: SignInCallbackHandler.SignInCallbackException) {
            exception = e
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.MISSING_CODE, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackMissingState() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Simulate the callback URL without state parameter
        val callbackUrl = "http://localhost:8080/auth/callback?code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: SignInCallbackHandler.SignInCallbackException) {
            exception = e
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_STATE, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackLoopbackRedirectValidation() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val endpoint = Endpoint(
            deploymentPrefix = null,
            host = "127.0.0.1",
            port = 8080,
            path = "/auth/callback",
            scheme = "http"
        )

        // Set up the code_verifier in storage
        val verifier = auth.generateCodeVerifier()

        // Simulate the callback URL with valid loopback host
        val callbackUrl = "http://127.0.0.1:8080/auth/callback?state=test_state&code=test_code"

        // Act
        val (accessToken, refreshToken) = handler.processCallback(callbackUrl, 8080, "/auth/callback")

        // Assert
        assertNotNull(accessToken)
        assertNotNull(refreshToken)
    }
}
