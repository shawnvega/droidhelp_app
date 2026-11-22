package com.bighelp.droidhelp.transport

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

class HealthClientTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private lateinit var server: MockWebServer
    private lateinit var client: HealthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HealthClient(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getHealth returns parsed response on success`() = runTest {
        val jsonResponse = """
            {
                "gateway": {
                    "ready": true,
                    "capabilities": {
                        "per_session_exclusive_submit": true
                    }
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.getHealth()

        assertEquals(true, response.gateway.ready)
        assertEquals(true, response.gateway.capabilities.per_session_exclusive_submit)
        assertEquals(null, response.gateway.capabilities.turnQueue)
        assertEquals(null, response.gateway.capabilities.turnInterrupt)
        
        val recordedRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("/api/health", recordedRequest?.path)
    }

    @Test
    fun `getHealth parses optional turn_queue and turn_interrupt capabilities`() = runTest {
        val jsonResponse = """
            {
                "gateway": {
                    "ready": true,
                    "capabilities": {
                        "per_session_exclusive_submit": true,
                        "turn_queue": true,
                        "turn_interrupt": false
                    }
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.getHealth()

        assertEquals(true, response.gateway.ready)
        assertEquals(true, response.gateway.capabilities.per_session_exclusive_submit)
        assertEquals(true, response.gateway.capabilities.turnQueue)
        assertEquals(false, response.gateway.capabilities.turnInterrupt)
    }

    @Test
    fun `getHealth throws exception when per_session_exclusive_submit is missing`() = runTest {
        val jsonResponse = """
            {
                "gateway": {
                    "ready": true,
                    "capabilities": {}
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception when per_session_exclusive_submit is false`() = runTest {
        val jsonResponse = """
            {
                "gateway": {
                    "ready": true,
                    "capabilities": {
                        "per_session_exclusive_submit": false
                    }
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception when gateway ready is false`() = runTest {
        val jsonResponse = """
            {
                "gateway": {
                    "ready": false,
                    "capabilities": {
                        "per_session_exclusive_submit": true
                    }
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception when gateway is missing`() = runTest {
        val jsonResponse = """
            {
                "capabilities": {
                    "per_session_exclusive_submit": true
                }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception when capabilities is missing`() = runTest {
        val jsonResponse = """
            {
                "gateway": {}
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception on malformed JSON`() = runTest {
        val malformedJson = """{"gateway": {"ready": true, "capabilities": { "key1": }}}"""
        server.enqueue(MockResponse().setBody(malformedJson).setResponseCode(200))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    @Test
    fun `getHealth throws exception on network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.getHealth() }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
