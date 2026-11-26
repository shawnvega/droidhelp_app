package com.bighelp.droidhelp.platform

import com.bighelp.droidhelp.model.LoopdyLinkError
import com.bighelp.droidhelp.model.PairingApprovedUser
import com.bighelp.droidhelp.model.PairingPendingRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.TimeUnit

class PairingClientTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private lateinit var server: MockWebServer
    private lateinit var client: PairingClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PairingClient(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `beginPairing returns parsed response on success`() = runTest {
        val jsonResponse = """
            {
                "platform": "android",
                "request_id": "a1b2c3d4e5f6a7b8",
                "user_id": "user_123",
                "user_name": "Test User",
                "age_minutes": 5
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.beginPairing()

        assertEquals("android", response.platform)
        assertEquals("a1b2c3d4e5f6a7b8", response.requestID)
        assertEquals("user_123", response.userID)
        assertEquals("Test User", response.userName)
        assertEquals(5, response.ageMinutes)

        val recordedRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("/api/pairing/begin", recordedRequest?.path)
    }

    @Test
    fun `beginPairing throws LoopdyLinkError on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(LoopdyLinkError::class.java) {
            kotlinx.coroutines.runBlocking { client.beginPairing() }
        }
    }

    @Test
    fun `beginPairing throws LoopdyLinkError on malformed JSON`() = runTest {
        val malformedJson = """{"platform": "android", "request_id": }"""
        server.enqueue(MockResponse().setBody(malformedJson).setResponseCode(200))

        assertThrows(LoopdyLinkError::class.java) {
            kotlinx.coroutines.runBlocking { client.beginPairing() }
        }
    }

    @Test
    fun `beginPairing throws LoopdyLinkError on network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(LoopdyLinkError::class.java) {
            kotlinx.coroutines.runBlocking { client.beginPairing() }
        }
    }

    @Test
    fun `approvePairing returns parsed response on success`() = runTest {
        val jsonResponse = """
            {
                "platform": "android",
                "user_id": "user_123",
                "user_name": "Test User",
                "approved_at": 1700000000000
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val request = PairingPendingRequest(
            platform = "android",
            requestID = "a1b2c3d4e5f6a7b8",
            userID = "user_123",
            userName = "Test User",
            ageMinutes = 5
        )
        val response = client.approvePairing(request, "profile_1")

        assertEquals("android", response.platform)
        assertEquals("user_123", response.userID)
        assertEquals("Test User", response.userName)
        assertEquals(1700000000000L, response.approvedAt)

        val recordedRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("/api/pairing/approve", recordedRequest?.path)
    }

    @Test
    fun `approvePairing throws LoopdyLinkError on HTTP error`() = runTest {
        val request = PairingPendingRequest(
            platform = "android",
            requestID = "a1b2c3d4e5f6a7b8",
            userID = "user_123",
            userName = "Test User",
            ageMinutes = 5
        )
        server.enqueue(MockResponse().setResponseCode(400))

        assertThrows(LoopdyLinkError::class.java) {
            kotlinx.coroutines.runBlocking { client.approvePairing(request, "profile_1") }
        }
    }

    @Test
    fun `claimPairing returns parsed response on success`() = runTest {
        val jsonResponse = """
            {
                "platform": "android",
                "user_id": "user_123",
                "user_name": "Test User",
                "approved_at": 1700000000000
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.claimPairing()

        assertEquals("android", response.platform)
        assertEquals("user_123", response.userID)
        assertEquals("Test User", response.userName)
        assertEquals(1700000000000L, response.approvedAt)

        val recordedRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("/api/pairing/claim", recordedRequest?.path)
    }

    @Test
    fun `claimPairing throws LoopdyLinkError on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(LoopdyLinkError::class.java) {
            kotlinx.coroutines.runBlocking { client.claimPairing() }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
