package com.bighelp.droidhelp.transport

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class CancellationClientTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private lateinit var server: MockWebServer
    private lateinit var client: CancellationClient
    private lateinit var endpoint: Endpoint

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Use a dummy endpoint for testing
        endpoint = Endpoint.build(
            scheme = "http",
            host = "localhost",
            port = server.port,
            path = "/"
        )
        client = CancellationClient(OkHttpClient(), endpoint)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `cancel sends correct request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val requestId = "test-request-id"
        client.cancel(requestId)

        val recordedRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("/api/cancel", recordedRequest?.path)
        assertEquals("POST", recordedRequest?.method)
        val body = recordedRequest?.body?.readUtf8() ?: ""
        println("DEBUG: body = $body")
        // Check that both fields are present and have non-empty values
        assertTrue("Missing requestId", body.contains("\"requestId\":\"$requestId\""))
        assertTrue("Missing cancellationId key or opening quote", body.contains("\"cancellationId\":\""))
        // Check that cancellationId is not empty by looking for the closing quote after the opening one
        // A simple way is to check that it contains a non-empty string between quotes
        // But since we don't want to parse, let's just check that it doesn't end with :"\""
        assertTrue("cancellationId should not be empty", !body.contains("\"cancellationId\":\"\""))
    }

    @Test
    fun `cancel throws exception on non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val requestId = "test-request-id"
        assertThrows(IOException::class.java) {
            runBlocking { client.cancel(requestId) }
        }
    }

    @Test
    fun `cancel handles different endpoint paths correctly`() = runTest {
        val customEndpoint = Endpoint.build(
            scheme = "http",
            host = "localhost",
            port = server.port,
            path = "/v1"
        )
        val customClient = CancellationClient(OkHttpClient(), customEndpoint)
        server.enqueue(MockResponse().setResponseCode(200))

        customClient.cancel("req-1")

        val recordedRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        // Expected path: /v1/api/cancel
        assertEquals("/v1/api/cancel", recordedRequest?.path)
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
