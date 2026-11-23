package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Endpoint
import com.bighelp.droidhelp.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

class CancellationIntegrationTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private var t: WebSocketTransport? = null

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(Dispatchers.Default + Job())
    }

    @After
    fun teardown() {
        t?.close()
        server.shutdown()
        scope.cancel()
    }

    @Test
    fun `test cancelSession`() = runBlocking {
        // 1. Setup MockWebServer with WebSocket upgrade
        val baseUrl = server.url("/")
        val endpoint = Endpoint.build(
            scheme = "http",
            host = baseUrl.host,
            port = baseUrl.port,
            path = ""
        )
        println("DEBUG: Endpoint: $endpoint")
        println("DEBUG: WS URL: ${endpoint.canonical}")

        // Prepare responses for the WebSocket handshake and the subsequent DELETE request
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : okhttp3.WebSocketListener() {
                    override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
            )
        )
        server.enqueue(MockResponse().setResponseCode(200))

        // 2. Connect WebSocketTransport and create Session
        val newTransport = WebSocketTransport.create(endpoint, scope = scope)
        t = newTransport
        
        // Start the connection process
        val connectionJob = launch { newTransport.connect() }
        
        // Wait for connection to be established
        try {
            withTimeout(5000) {
                while (newTransport.connectionState.value != WebSocketTransport.ConnectionState.CONNECTED) {
                    delay(50)
                    println("DEBUG: Current state: ${newTransport.connectionState.value}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            println("DEBUG: Connection timed out. Current state: ${newTransport.connectionState.value}")
            println("DEBUG: Connection error: ${newTransport.getConnectionError()}")
            throw e
        }
        connectionJob.join()

        val sessionId = "test-session-id"
        val runtimeSessionId = "runtime-$sessionId"
        val profile = "test-profile"
        val session = Session.create(sessionId, "test-source", profile, runtimeSessionId)

        // Create a SessionCancellation instance
        val httpClient = okhttp3.OkHttpClient()
        val cancellation = SessionCancellation(httpClient, endpoint)

        // Get the handle from the transport via the cancellation object
        val socketHandle = cancellation.createSocketHandle(session, transport = newTransport)

        // 3. Call cancelSession with SocketHandle
        val result = cancellation.cancelSession(session, socketHandle)

        // 4. Verify HTTP DELETE request and transport state
        assertEquals(200, result.httpStatusCode)
        assertTrue(result.sessionClosed)
        assertEquals(runtimeSessionId, result.sessionId)
        assertEquals(socketHandle.handleId, result.socketHandleId)

        // Consume the WebSocket upgrade request, then verify the HTTP DELETE request
        val wsRequest = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(wsRequest)
        assertEquals("GET", wsRequest?.method)
        val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("DELETE", request?.method)
        assertEquals("/api/sessions/$runtimeSessionId?profile=$profile", request?.path)
        
        // Verify transport state
        assertEquals(WebSocketTransport.ConnectionState.DISCONNECTED, newTransport.connectionState.value)
        assertFalse(newTransport.isClosed())

        // 5. Cleanup
        newTransport.close()
        assertTrue(newTransport.isClosed())
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
