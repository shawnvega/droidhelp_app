package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WebSocketTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var ioScope: CoroutineScope
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()

    @BeforeEach
    fun setUp() {
        serverSockets.clear()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ReconnectPolicy.setSession(null)
    }

    @AfterEach
    fun tearDown() {
        serverSockets.forEach { it.close(NORMAL_CLOSE_CODE, "teardown") }
        serverSockets.clear()
        ioScope.cancel()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        ReconnectPolicy.setSession(null)
    }

    @Test
    fun testConnectInitialState() = runTest {
        val endpoint = Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT)
        val transport = WebSocketTransport(this, endpoint)
        try {
            assertFalse(transport.isTransportConnected())
            assertEquals(WebSocketTransport.ConnectionState.DISCONNECTED, transport.connectionState.value)
            assertEquals("ws://localhost:$DEFAULT_TEST_PORT/api/ws", transport.buildWebSocketUrl())

            val alreadyWsEndpoint = Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT, path = "/api/ws")
            val alreadyWsTransport = WebSocketTransport(this, alreadyWsEndpoint)
            try {
                assertEquals("ws://localhost:$DEFAULT_TEST_PORT/api/ws", alreadyWsTransport.buildWebSocketUrl())
            } finally {
                alreadyWsTransport.close()
            }

            val tlsEndpoint = Endpoint.build(scheme = "https", host = "secure.example.com")
            val tlsTransport = WebSocketTransport(this, tlsEndpoint)
            try {
                assertEquals("wss://secure.example.com:443/api/ws", tlsTransport.buildWebSocketUrl())
            } finally {
                tlsTransport.close()
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun testResumeSession() = runTest {
        val endpoint = Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT)
        val transport = WebSocketTransport(this, endpoint)
        try {
            val session = Session(id = "test-id", source = "bighelp", profile = "test-profile")
            transport.resumeSession(session)
            assertEquals(session, ReconnectPolicy.getSession())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testConnectSuccessSendsApiWsPathAndCustomHeaders() = runBlocking {
        val serverOpened = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    serverOpened.complete(webSocket)
                }
            })
        )

        val headers = mapOf(
            "Authorization" to "Bearer test-token",
            "X-DroidHelp-Client" to "android-test"
        )
        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(headers = headers, client = client)
        )
        val owner = testOwner(endpoint.canonical)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)

        val readyReceived = CompletableDeferred<Message>()
        transport.onMessage { msg ->
            if (msg is Message.Ready) {
                readyReceived.complete(msg)
            }
        }

        try {
            val connected = withTimeout(TEST_TIMEOUT_MS) { transport.connect() }
            assertTrue(connected)
            assertTrue(transport.isTransportConnected())
            assertNull(transport.getConnectionError())
            assertEquals(1L, transport.getOwners().getSocketOwner()?.socketEpoch)
            assertEquals(Message.Ready, withTimeout(TEST_TIMEOUT_MS) { readyReceived.await() })

            val recordedRequest = server.takeRequest(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(recordedRequest)
            assertEquals("/api/ws", recordedRequest?.path)
            assertEquals("Bearer test-token", recordedRequest?.getHeader("Authorization"))
            assertEquals("android-test", recordedRequest?.getHeader("X-DroidHelp-Client"))

            // Subsequent connect() while already connected returns true without opening a new socket
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertEquals(1, server.requestCount)
            withTimeout(TEST_TIMEOUT_MS) { serverOpened.await() }.close(NORMAL_CLOSE_CODE, "done")
        } finally {
            transport.close()
        }
    }

    @Test
    fun testConnectReturnsFalseOnServerRejection() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(HTTP_INTERNAL_ERROR).setBody("server unavailable"))

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(client = client)
        )

        try {
            val connected = withTimeout(TEST_TIMEOUT_MS) { transport.connect() }
            assertFalse(connected)
            assertFalse(transport.isTransportConnected())
            assertNotNull(transport.getConnectionError())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testSendWithoutSocketOwnerThrows() = runTest {
        val endpoint = Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT)
        val transport = WebSocketTransport(this, endpoint)
        try {
            assertThrows(IllegalStateException::class.java) {
                transport.send(Message.Ping)
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun testJsonFramesRoundTripThroughOutboundQueue() = runBlocking {
        val serverSocketDeferred = CompletableDeferred<WebSocket>()
        val serverReceived = CopyOnWriteArrayList<String>()
        val twoFramesReceived = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    serverSocketDeferred.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceived.add(text)
                    if (serverReceived.size >= 2) {
                        twoFramesReceived.complete(Unit)
                    }
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = SMALL_QUEUE_SIZE, client = client)
        )
        val owner = testOwner(endpoint.canonical)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)

        val clientReceived = CopyOnWriteArrayList<Message>()
        val pongReceived = CompletableDeferred<Message>()
        transport.onMessage { msg ->
            clientReceived.add(msg)
            if (msg is Message.Pong) {
                pongReceived.complete(msg)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }

            val prompt = Message.Prompt(text = "hello world", entryId = "entry-1")
            transport.send(prompt)
            transport.send(Message.Ping)

            withTimeout(TEST_TIMEOUT_MS) { twoFramesReceived.await() }
            assertEquals(2, transport.getMessagesSent())
            assertTrue(serverReceived[0].endsWith("\n"))
            assertTrue(serverReceived[1].endsWith("\n"))
            assertEquals(prompt, Json.decodeFromString(Message.serializer(), serverReceived[0]))
            assertEquals(Message.Ping, Json.decodeFromString(Message.serializer(), serverReceived[1]))

            // Server sends malformed JSON (dropped) followed by valid Pong frame
            serverWs.send("{malformed json")
            serverWs.send(Json.encodeToString(Message.serializer(), Message.Pong))

            assertEquals(Message.Pong, withTimeout(TEST_TIMEOUT_MS) { pongReceived.await() })
            assertEquals(1, transport.getMessagesReceived())
            assertTrue(clientReceived.contains(Message.Pong))
        } finally {
            transport.close()
        }
    }

    @Test
    fun testOutboundQueueDropsStaleFencedMessagesOnNewSocketEpoch() = runBlocking {
        val serverReceived = CopyOnWriteArrayList<String>()
        val freshMessageReceived = CompletableDeferred<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceived.add(text)
                    freshMessageReceived.complete(text)
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = SMALL_QUEUE_SIZE, client = client)
        )
        val owner = testOwner(endpoint.canonical)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)
        // Simulate an earlier socket epoch (epoch 1) before reconnect
        transport.getOwners().updateSocketOwner(SocketOwner(owner, 1L))

        try {
            // Queue a message while disconnected under epoch 1
            transport.send(Message.Prompt(text = "stale-prompt", entryId = "stale-1"))

            // Connect opens a new socket and bumps epoch to 2 before connectedState becomes true
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertEquals(2L, transport.getOwners().getSocketOwner()?.socketEpoch)

            // Now send a fresh message under epoch 2
            val freshMessage = Message.Prompt(text = "fresh-prompt", entryId = "fresh-2")
            transport.send(freshMessage)

            val receivedRaw = withTimeout(TEST_TIMEOUT_MS) { freshMessageReceived.await() }
            assertEquals(freshMessage, Json.decodeFromString(Message.serializer(), receivedRaw))
            assertEquals(1, serverReceived.size)
            assertEquals(1, transport.getMessagesSent())
            assertEquals(1, transport.getStaleMessagesDropped())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testBoundedDecodeRejectsOversizedTextAndBinaryFrames() = runBlocking {
        val serverSocketDeferred = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    serverSocketDeferred.complete(webSocket)
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(
                maxFrameSize = SMALL_MAX_FRAME_SIZE,
                client = client
            )
        )

        val validReceived = CompletableDeferred<Message>()
        val allMessages = CopyOnWriteArrayList<Message>()
        transport.onMessage { msg ->
            allMessages.add(msg)
            if (msg is Message.Ping) {
                validReceived.complete(msg)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }

            // Oversized JSON text frame (> SMALL_MAX_FRAME_SIZE bytes)
            val oversizedPrompt = Message.Prompt(text = "x".repeat(OVERSIZED_PAYLOAD_LEN), entryId = "big")
            serverWs.send(Json.encodeToString(Message.serializer(), oversizedPrompt))

            // Oversized binary frame (> SMALL_MAX_FRAME_SIZE bytes)
            serverWs.send("b".repeat(OVERSIZED_PAYLOAD_LEN).encodeUtf8())

            // Valid small binary frame (<= SMALL_MAX_FRAME_SIZE bytes)
            val smallBinary: ByteString = "ok".encodeUtf8()
            serverWs.send(smallBinary)

            // Valid small JSON frame (<= SMALL_MAX_FRAME_SIZE bytes)
            serverWs.send(Json.encodeToString(Message.serializer(), Message.Ping))

            assertEquals(Message.Ping, withTimeout(TEST_TIMEOUT_MS) { validReceived.await() })
            assertEquals(1, transport.getMessagesReceived())
            assertEquals(1, transport.getFramesDecoded())
            assertEquals(2, transport.getOversizedFramesDropped())
            assertFalse(allMessages.any { it is Message.Prompt })
        } finally {
            transport.close()
        }
    }

    @Test
    fun testRpcCorrelatorIntegratesWithIncomingReceiptsAndCancel() = runBlocking {
        val serverSocketDeferred = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    serverSocketDeferred.complete(webSocket)
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(client = client)
        )

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }

            val correlatedReceipt = CompletableDeferred<Message>()
            transport.register("req-1") { msg -> correlatedReceipt.complete(msg) }
            transport.register("req-2") { _ -> error("Cancelled request should not fire") }
            assertEquals(2, transport.pendingCount())

            transport.cancel("req-2")
            assertEquals(1, transport.pendingCount())

            val receipt = Message.Receipt(entryId = "req-1")
            serverWs.send(Json.encodeToString(Message.serializer(), receipt))

            assertEquals(receipt, withTimeout(TEST_TIMEOUT_MS) { correlatedReceipt.await() })
            assertEquals(0, transport.pendingCount())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testBoundedOutboundQueueDeliversQueuedMessages() = runBlocking {
        val serverReceived = CopyOnWriteArrayList<Message>()
        val allReceived = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceived.add(Json.decodeFromString(Message.serializer(), text))
                    if (serverReceived.size >= SMALL_QUEUE_SIZE) {
                        allReceived.complete(Unit)
                    }
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = 2, client = client)
        )
        val owner = testOwner(endpoint.canonical)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val sentIds = (1..SMALL_QUEUE_SIZE).map { idx -> "msg-$idx" }
            sentIds.forEach { id ->
                transport.send(Message.Prompt(text = "payload-$id", entryId = id))
            }

            withTimeout(TEST_TIMEOUT_MS) { allReceived.await() }
            assertEquals(SMALL_QUEUE_SIZE, transport.getMessagesSent())
            assertEquals(
                sentIds.toSet(),
                serverReceived.filterIsInstance<Message.Prompt>().map { it.entryId }.toSet()
            )
        } finally {
            transport.close()
        }
    }

    @Test
    fun testNewlineFramedAndSplitJsonPayloadsWithBoundedBuffer() = runBlocking {
        val serverSocketDeferred = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    serverSocketDeferred.complete(webSocket)
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(maxFrameSize = NDJSON_TEST_MAX_BUFFER, client = client)
        )
        val received = CopyOnWriteArrayList<Message>()
        val splitPromptReceived = CompletableDeferred<Message.Prompt>()
        transport.onMessage { msg ->
            received.add(msg)
            if (msg is Message.Prompt && msg.entryId == "split-entry") {
                splitPromptReceived.complete(msg)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }

            val pingJson = Json.encodeToString(Message.serializer(), Message.Ping)
            val pongJson = Json.encodeToString(Message.serializer(), Message.Pong)
            serverWs.send("$pingJson\n$pongJson\n")

            val splitPrompt = Message.Prompt(text = "chunked payload", entryId = "split-entry")
            val splitJson = Json.encodeToString(Message.serializer(), splitPrompt)
            val half = splitJson.length / 2
            serverWs.send(splitJson.substring(0, half))
            serverWs.send(splitJson.substring(half) + "\n")

            assertEquals(splitPrompt, withTimeout(TEST_TIMEOUT_MS) { splitPromptReceived.await() })
            assertEquals(EXPECTED_NDJSON_COUNT, transport.getMessagesReceived())
            assertTrue(received.contains(Message.Ping))
            assertTrue(received.contains(Message.Pong))
            assertEquals(0, transport.getOversizedFramesDropped())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testConnectionLifecycleAndCloseReleasesResources() = runBlocking {
        val firstServerWs = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    firstServerWs.complete(webSocket)
                }
            })
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(client = client)
        )
        val owner = testOwner(endpoint.canonical)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)

        assertEquals(WebSocketTransport.ConnectionState.DISCONNECTED, transport.connectionState.value)
        assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
        assertEquals(WebSocketTransport.ConnectionState.CONNECTED, transport.connectionState.value)

        // Server-initiated close transitions lifecycle back to DISCONNECTED
        withTimeout(TEST_TIMEOUT_MS) { firstServerWs.await() }.close(NORMAL_CLOSE_CODE, "server shutdown")
        withTimeout(TEST_TIMEOUT_MS) {
            transport.connectionState.first { it == WebSocketTransport.ConnectionState.DISCONNECTED }
        }

        // Disconnect + reconnect succeeds before permanent close
        transport.disconnect()
        assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
        assertEquals(WebSocketTransport.ConnectionState.CONNECTED, transport.connectionState.value)

        // Permanent close releases resources and blocks subsequent connect/send while leaving caller client usable
        transport.close()
        assertTrue(transport.isClosed())
        assertEquals(WebSocketTransport.ConnectionState.CLOSED, transport.connectionState.value)
        assertFalse(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
        assertThrows(IllegalStateException::class.java) {
            transport.send(Message.Ping)
        }
        assertFalse(client.dispatcher.executorService.isShutdown)
    }

    private fun testOwner(canonicalEndpoint: String): Owner {
        val signIn = SignIn(
            canonicalEndpoint = canonicalEndpoint,
            verifiedPrincipal = "user@example.com",
            authenticationGeneration = 1L
        )
        return Owner(signIn = signIn, workspaceConnectionGeneration = 1L)
    }

    companion object {
        private const val DEFAULT_TEST_PORT = 8080
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_TIMEOUT_MS = 5_000L
        private const val HTTP_INTERNAL_ERROR = 500
        private const val NORMAL_CLOSE_CODE = 1000
        private const val SMALL_QUEUE_SIZE = 4
        private const val SMALL_MAX_FRAME_SIZE = 64
        private const val OVERSIZED_PAYLOAD_LEN = 128
        private const val NDJSON_TEST_MAX_BUFFER = 256
        private const val EXPECTED_NDJSON_COUNT = 3
    }
}
