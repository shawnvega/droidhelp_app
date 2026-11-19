package com.bighelp.droidhelp.transport

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import java.io.IOException

class HealthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HealthClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HealthClient(OkHttpClient(), server.url("/").toString())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getHealth returns parsed response on success`() = runTest {
        val jsonResponse = """
            {
                "gateway": {
                    "capabilities": {
                        "key1": "value1",
                        "key2": "value2"
                    }
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.getHealth()

        assertEquals("value1", response.gateway.capabilities["key1"])
        assertEquals("value2", response.gateway.capabilities["key2"])
        
        val recordedRequest = server.takeRequest()
        assertEquals("/api/health", recordedRequest.path)
    }

    @Test
    fun `getHealth throws exception on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(IOException::class.java) {
            // Since we are in runTest, we can use runTest again or just call it? 
            // Actually, runTest is a coroutine scope. We can use it to launch a new coroutine.
            // But assertThrows expects a synchronous call.
            // The easiest way is to use runBlocking inside the lambda.
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception on malformed JSON`() = runTest {
        val malformedJson = """{"gateway": {"capabilities": { "key1": }}"""
        server.enqueue(MockResponse().setBody(malformedJson).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }
}
