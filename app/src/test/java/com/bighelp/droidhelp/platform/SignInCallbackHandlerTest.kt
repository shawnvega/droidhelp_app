package com.bighelp.droidhelp.platform

import java.net.URI
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SignInCallbackHandler class.
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
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody)
                .build()
        }

        override suspend fun upload(
            payload: UploadPayload,
            onProgress: (Float) -> Unit
        ): Response {
            onProgress(1f)
            val responseBody = ResponseBody.create(
                "application/octet-stream".toMediaTypeOrNull(),
                ""
            )
            return Response.Builder()
                .request(Request.Builder().url(payload.url).build())
                .protocol(Protocol.HTTP_1_1)
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

        // Set up the code_verifier in storage
        val verifier = auth.generateCodeVerifier()
        auth.saveTokens("old_access", "old_refresh") // Initialize tokens

        // Simulate the callback URL with code and state
        val state = auth.generateAndSaveState()
        val callbackUrl = "http://localhost:8080/auth/callback?state=$state&code=test_code"

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

        // Set up a different state in storage (won't match)
        auth.generateAndSaveState()

        // Simulate the callback URL with a different state
        val callbackUrl = "http://localhost:8080/auth/callback?state=wrong_state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
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

        // Simulate the callback URL with a non-loopback host
        val callbackUrl = "http://example.com:8080/auth/callback?state=test_state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_REDIRECT, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackInvalidRedirectPort() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        // Set up the code_verifier in storage
        val verifier = auth.generateCodeVerifier()
        val state = auth.generateAndSaveState()

        // Simulate the callback URL with a different port
        val callbackUrl = "http://localhost:9090/auth/callback?state=$state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
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

        // Simulate the callback URL without code parameter
        val callbackUrl = "http://localhost:8080/auth/callback?state=test_state"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
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

        // Simulate the callback URL without state parameter
        val callbackUrl = "http://localhost:8080/auth/callback?code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
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

        // Set up the code_verifier in storage
        val verifier = auth.generateCodeVerifier()
        val state = auth.generateAndSaveState()

        // Simulate the callback URL with valid loopback host
        val callbackUrl = "http://127.0.0.1:8080/auth/callback?state=$state&code=test_code"

        // Act
        val (accessToken, refreshToken) = handler.processCallback(callbackUrl, 8080, "/auth/callback")

        // Assert
        assertNotNull(accessToken)
        assertNotNull(refreshToken)
    }

    @Test
    fun testProcessCallbackInvalidPath() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        // Simulate the callback URL with a different path
        val state = auth.generateAndSaveState()
        val callbackUrl = "http://localhost:8080/wrong/path?state=$state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_REDIRECT, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackTokenExchangeFailure() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = object : HttpClient {
            override suspend fun post(url: String, body: Map<String, String>): Response {
                return Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(ResponseBody.create("application/json".toMediaTypeOrNull(), ""))
                    .build()
            }

            override suspend fun upload(
                payload: UploadPayload,
                onProgress: (Float) -> Unit
            ): Response {
                onProgress(1f)
                return Response.Builder()
                    .request(Request.Builder().url(payload.url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(ResponseBody.create("application/json".toMediaTypeOrNull(), ""))
                    .build()
            }
        }
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val state = auth.generateAndSaveState()
        val callbackUrl = "http://localhost:8080/auth/callback?state=$state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackEmptyResponse() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = object : HttpClient {
            override suspend fun post(url: String, body: Map<String, String>): Response {
                return Response.Builder()
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("application/json".toMediaTypeOrNull(), ""))
                    .build()
            }

            override suspend fun upload(
                payload: UploadPayload,
                onProgress: (Float) -> Unit
            ): Response {
                onProgress(1f)
                return Response.Builder()
                    .request(Request.Builder().url(payload.url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("application/json".toMediaTypeOrNull(), ""))
                    .build()
            }
        }
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        val state = auth.generateAndSaveState()
        val callbackUrl = "http://localhost:8080/auth/callback?state=$state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED, exception!!.errorType)
    }

    @Test
    fun testProcessCallbackInvalidScheme() = runTest {
        // Arrange
        val auth = Auth(FakeSecureStorage())
        val httpClient = FakeHttpClient()
        val handler = SignInCallbackHandler(auth, httpClient, "http://localhost:8080/auth/callback")

        // Simulate the callback URL with a different scheme
        val state = auth.generateAndSaveState()
        val callbackUrl = "https://localhost:8080/auth/callback?state=$state&code=test_code"

        // Assert
        var exception: SignInCallbackHandler.SignInCallbackException? = null
        try {
            handler.processCallback(callbackUrl, 8080, "/auth/callback")
        } catch (e: Exception) {
            exception = e as? SignInCallbackHandler.SignInCallbackException
        }
        assertNotNull(exception)
        assertEquals(SignInCallbackHandler.SignInCallbackException.ErrorType.INVALID_REDIRECT, exception!!.errorType)
    }
}
