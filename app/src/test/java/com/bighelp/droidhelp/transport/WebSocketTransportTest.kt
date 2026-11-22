package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WebSocketTransportTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var ioScope: CoroutineScope
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()

    @Before
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

    @After
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

            val alreadyWs = WebSocketTransport(
                this,
                Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT, path = "/api/ws")
            )
            try {
                assertEquals("ws://localhost:$DEFAULT_TEST_PORT/api/ws", alreadyWs.buildWebSocketUrl())
            } finally {
                alreadyWs.close()
            }

            val tlsTransport = WebSocketTransport(this, Endpoint.build(scheme = "https", host = "secure.example.com"))
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
        val transport = WebSocketTransport(this, Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT))
        try {
            val session = Session(id = "test-id", source = "bighelp", profile = "test-profile")
            transport.resumeSession(session)
            assertEquals(session, ReconnectPolicy.getSession())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testConnectSuccessSendsApiWsPathAndCustomHeaders() : Unit = runBlocking {
        val serverOpened = enqueueUpgrade()
        val headers = mapOf("Authorization" to "Bearer test-token", "X-DroidHelp-Client" to "android-test")
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(headers = headers, client = client),
            bindOwner = true
        )
        val readyReceived = CompletableDeferred<Message>()
        transport.onMessage { msg -> if (msg is Message.Ready) readyReceived.complete(msg) }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
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
    fun testConnectReturnsFalseOnServerRejection() : Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(HTTP_INTERNAL_ERROR).setBody("server unavailable"))
        val transport = createServerTransport()
        try {
            assertFalse(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertFalse(transport.isTransportConnected())
            assertNotNull(transport.getConnectionError())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testSendWithoutSocketOwnerThrows() = runTest {
        val transport = WebSocketTransport(this, Endpoint.build(host = "localhost", port = DEFAULT_TEST_PORT))
        try {
            assertThrows(IllegalStateException::class.java) { transport.send(Message.Ping) }
        } finally {
            transport.close()
        }
    }

    @Test
    fun testJsonFramesRoundTripThroughOutboundQueue() : Unit = runBlocking {
        val serverReceived = CopyOnWriteArrayList<String>()
        val twoFramesReceived = CompletableDeferred<Unit>()
        val serverSocketDeferred = enqueueUpgrade { _, text ->
            serverReceived.add(text)
            if (serverReceived.size >= 2) twoFramesReceived.complete(Unit)
        }
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = SMALL_QUEUE_SIZE, client = client),
            bindOwner = true
        )
        val clientReceived = CopyOnWriteArrayList<Message>()
        val pongReceived = CompletableDeferred<Message>()
        transport.onMessage { msg ->
            clientReceived.add(msg)
            if (msg is Message.Pong) pongReceived.complete(msg)
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
    fun testOutboundQueueDropsStaleFencedMessagesOnNewSocketEpoch() : Unit = runBlocking {
        val serverReceived = CopyOnWriteArrayList<String>()
        val freshMessageReceived = CompletableDeferred<String>()
        enqueueUpgrade { _, text ->
            serverReceived.add(text)
            freshMessageReceived.complete(text)
        }
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = SMALL_QUEUE_SIZE, client = client),
            bindOwner = true
        )
        // Simulate an earlier socket epoch (epoch 1) before reconnect
        transport.getOwners().updateSocketOwner(SocketOwner(transport.getOwners().getOwner()!!, 1L))

        try {
            // Queue a message while disconnected under epoch 1
            transport.send(Message.Prompt(text = "stale-prompt", entryId = "stale-1"))

            // Connect opens a new socket and bumps epoch to 2 before connectedState becomes true
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertEquals(2L, transport.getOwners().getSocketOwner()?.socketEpoch)

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
    fun testBoundedDecodeRejectsOversizedTextAndBinaryFrames() : Unit = runBlocking {
        val serverSocketDeferred = enqueueUpgrade()
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxFrameSize = SMALL_MAX_FRAME_SIZE, client = client)
        )
        val validReceived = CompletableDeferred<Message>()
        val allMessages = CopyOnWriteArrayList<Message>()
        transport.onMessage { msg ->
            allMessages.add(msg)
            if (msg is Message.Ping) validReceived.complete(msg)
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }

            val oversizedPrompt = Message.Prompt(text = "x".repeat(OVERSIZED_PAYLOAD_LEN), entryId = "big")
            serverWs.send(Json.encodeToString(Message.serializer(), oversizedPrompt))
            serverWs.send("b".repeat(OVERSIZED_PAYLOAD_LEN).encodeUtf8())
            val smallBinary: ByteString = "ok".encodeUtf8()
            serverWs.send(smallBinary)
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
    fun testRpcCorrelatorIntegratesWithIncomingReceiptsAndCancel() : Unit = runBlocking {
        val serverSocketDeferred = enqueueUpgrade()
        val transport = createServerTransport()
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
    fun testBoundedOutboundQueueDeliversQueuedMessages() : Unit = runBlocking {
        val serverReceived = CopyOnWriteArrayList<Message>()
        val allReceived = CompletableDeferred<Unit>()
        enqueueUpgrade { _, text ->
            serverReceived.add(Json.decodeFromString(Message.serializer(), text))
            if (serverReceived.size >= SMALL_QUEUE_SIZE) allReceived.complete(Unit)
        }
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = 2, client = client),
            bindOwner = true
        )
        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val sentIds = (1..SMALL_QUEUE_SIZE).map { idx -> "msg-$idx" }
            sentIds.forEach { id -> transport.send(Message.Prompt(text = "payload-$id", entryId = id)) }

            withTimeout(TEST_TIMEOUT_MS) { allReceived.await() }
            assertEquals(SMALL_QUEUE_SIZE, transport.getMessagesSent())
            assertEquals(sentIds, serverReceived.filterIsInstance<Message.Prompt>().map { it.entryId })
        } finally {
            transport.close()
        }
    }

    @Test
    fun testNewlineFramedAndSplitJsonPayloadsWithBoundedBuffer() : Unit = runBlocking {
        val serverSocketDeferred = enqueueUpgrade()
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxFrameSize = NDJSON_TEST_MAX_BUFFER, client = client)
        )
        val received = CopyOnWriteArrayList<Message>()
        val splitPromptReceived = CompletableDeferred<Message.Prompt>()
        transport.onMessage { msg ->
            received.add(msg)
            if (msg is Message.Prompt && msg.entryId == "split-entry") splitPromptReceived.complete(msg)
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
    fun testConnectionLifecycleAndCloseReleasesResources() : Unit = runBlocking {
        val firstServerWs = enqueueUpgrade()
        enqueueUpgrade()
        val transport = createServerTransport(bindOwner = true)

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
        assertThrows(IllegalStateException::class.java) { transport.send(Message.Ping) }
        assertFalse(client.dispatcher.executorService.isShutdown)
    }

    @Test
    fun testBuildWebSocketUrlPreservesWssSchemeDeploymentPrefixAndSnapshotsHeaders() = runTest {
        val wssTransport = WebSocketTransport(this, Endpoint.build(scheme = "wss", host = "secure.example.com"))
        val prefixed = WebSocketTransport(
            this,
            Endpoint.build(scheme = "https", host = "hermes.example", deploymentPrefix = "hermes")
        )
        val prefixedWs = WebSocketTransport(
            this,
            Endpoint.build(scheme = "wss", host = "hermes.example", path = "/api/ws", deploymentPrefix = "hermes")
        )
        val mutableHeaders = mutableMapOf("X-Custom" to "initial-value")
        val ipv6Prefixed = WebSocketTransport(
            scope = this,
            endpoint = Endpoint.build(scheme = "http", host = "::1", port = DEFAULT_TEST_PORT, deploymentPrefix = "v1"),
            config = WebSocketTransport.WebSocketConfig(headers = mutableHeaders)
        )
        try {
            assertEquals("wss://secure.example.com:443/api/ws", wssTransport.buildWebSocketUrl())
            assertEquals("wss://hermes.example:443/hermes/api/ws", prefixed.buildWebSocketUrl())
            assertEquals("wss://hermes.example:443/hermes/api/ws", prefixedWs.buildWebSocketUrl())

            mutableHeaders["X-Custom"] = "mutated-value"
            mutableHeaders["X-Injected"] = "should-not-appear"
            val request = ipv6Prefixed.buildWebSocketRequest()
            assertEquals("ws://[::1]:$DEFAULT_TEST_PORT/v1/api/ws", ipv6Prefixed.buildWebSocketUrl())
            assertEquals("initial-value", request.header("X-Custom"))
            assertNull(request.header("X-Injected"))
        } finally {
            wssTransport.close()
            prefixed.close()
            prefixedWs.close()
            ipv6Prefixed.close()
        }
    }

    @Test
    fun testInvalidCustomHeaderFailsConnectCleanly() : Unit = runBlocking {
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(
                headers = mapOf("X-Bad-Header" to "line1\r\nInjected: true"),
                client = client
            )
        )
        try {
            assertFalse(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertEquals(WebSocketTransport.ConnectionState.DISCONNECTED, transport.connectionState.value)
            assertTrue(transport.getConnectionError() is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        } finally {
            transport.close()
        }
    }

    @Test
    fun testConcurrentConnectCallsDeduplicateIntoSingleHandshake() : Unit = runBlocking {
        enqueueUpgrade()
        val transport = createServerTransport()
        try {
            val results = List(CONCURRENT_CONNECT_CALLS) {
                async { withTimeout(TEST_TIMEOUT_MS) { transport.connect() } }
            }.awaitAll()
            assertTrue(results.all { it })
            assertEquals(1, server.requestCount)
            assertTrue(transport.isTransportConnected())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testBoundedDecodeRejectsOversizedMessageChunksDeepNestingAndIncompleteBinaryJson() : Unit = runBlocking {
        val serverSocketDeferred = enqueueUpgrade()
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(
                maxFrameSize = SMALL_MAX_FRAME_SIZE,
                maxMessageBytes = SMALL_MAX_MESSAGE_BYTES,
                maxNestingDepth = SMALL_MAX_NESTING_DEPTH,
                client = client
            )
        )
        val finalFrameReceived = CompletableDeferred<ParsedJsonFrame>()
        transport.onParsedFrame { frame ->
            if (frame.headers["seq"] == "final") finalFrameReceived.complete(frame)
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }

            // Start a partial JSON frame, then send a chunk exceeding maxMessageBytes so the partial buffer resets
            serverWs.send("""{"headers":{"seq":"partial"""")
            serverWs.send("a".repeat(SMALL_MAX_MESSAGE_BYTES + 1))
            // Deeply nested JSON object exceeding SMALL_MAX_NESTING_DEPTH (dropped as malformed)
            serverWs.send("""{"a":{"b":{"c":{"d":1}}}}""" + "\n")
            // Truncated binary UTF-8 JSON frame (dropped as malformed, does not count as decoded binary frame)
            serverWs.send("""{"headers":{"broken":true""".encodeUtf8())
            // Valid frame after recovery
            serverWs.send("""{"headers":{"seq":"final"},"payload":{"ok":true}}""" + "\n")

            val finalFrame = withTimeout(TEST_TIMEOUT_MS) { finalFrameReceived.await() }
            assertEquals("true", finalFrame.payloadObject?.get("ok")?.jsonPrimitive?.content)
            assertEquals(1, transport.getOversizedFramesDropped())
            assertEquals(2, transport.getMalformedFramesDropped())
            assertEquals(1, transport.getParsedFramesReceived())
            assertEquals(0, transport.getFramesDecoded())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testBurstIncomingFramesBeyondChannelCapacityAreNotSilentlyDropped() : Unit = runBlocking {
        val serverSocketDeferred = enqueueUpgrade()
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = 2, client = client)
        )
        val receivedIds = CopyOnWriteArrayList<String>()
        val allArrived = CompletableDeferred<Unit>()
        transport.onMessage { msg ->
            if (msg is Message.Prompt) {
                receivedIds.add(msg.entryId)
                if (receivedIds.size >= BURST_FRAME_COUNT) allArrived.complete(Unit)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverSocketDeferred.await() }
            val expectedIds = (1..BURST_FRAME_COUNT).map { "burst-$it" }
            expectedIds.forEach { id ->
                serverWs.send(Json.encodeToString(Message.serializer(), Message.Prompt("p-$id", id)) + "\n")
            }

            withTimeout(TEST_TIMEOUT_MS) { allArrived.await() }
            assertEquals(expectedIds, receivedIds.toList())
            assertEquals(BURST_FRAME_COUNT, transport.getMessagesReceived())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testBoundedOutboundQueuePreservesStrictFifoOrderUnderSuspendOverflow() : Unit = runBlocking {
        val serverReceivedIds = CopyOnWriteArrayList<String>()
        val allReceived = CompletableDeferred<Unit>()
        enqueueUpgrade { _, text ->
            val decoded = Json.decodeFromString(Message.serializer(), text)
            if (decoded is Message.Prompt) {
                serverReceivedIds.add(decoded.entryId)
                if (serverReceivedIds.size >= FIFO_OVERFLOW_COUNT) allReceived.complete(Unit)
            }
        }
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(
                maxQueueSize = 2,
                outboundOverflowPolicy = OutboundMessageQueue.OverflowPolicy.SUSPEND,
                client = client
            ),
            bindOwner = true
        )

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val expectedIds = (1..FIFO_OVERFLOW_COUNT).map { "fifo-$it" }
            expectedIds.forEach { id -> transport.send(Message.Prompt(text = "payload-$id", entryId = id)) }

            withTimeout(TEST_TIMEOUT_MS) { allReceived.await() }
            assertEquals(expectedIds, serverReceivedIds.toList())
            assertEquals(FIFO_OVERFLOW_COUNT, transport.getMessagesSent())
            assertEquals(0, transport.getDroppedOutboundMessages())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testSendWithCorrelationRoutesThroughOutboundQueueAndCorrelatesReply() : Unit = runBlocking {
        val serverReceived = CompletableDeferred<String>()
        enqueueUpgrade { webSocket, text ->
            serverReceived.complete(text)
            val decoded = Json.decodeFromString(Message.serializer(), text)
            if (decoded is Message.Prompt) {
                webSocket.send("""{"jsonrpc":"2.0","id":"${decoded.entryId}","result":{"accepted":true}}""" + "\n")
            }
        }
        val transport = createServerTransport(bindOwner = true)

        try {
            assertThrows(IllegalStateException::class.java) {
                transport.sendWithCorrelation("unconnected-1", Message.Prompt("hi", "unconnected-1"))
            }
            assertEquals(0, transport.pendingCount())

            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val matchedEvent = CompletableDeferred<CorrelationEvent>()
            val prompt = Message.Prompt(text = "correlate me", entryId = "corr-42")
            transport.sendWithCorrelation(requestId = "corr-42", message = prompt) { matchedEvent.complete(it) }

            val rawOutbound = withTimeout(TEST_TIMEOUT_MS) { serverReceived.await() }
            assertTrue(rawOutbound.endsWith("\n"))
            assertEquals(prompt, Json.decodeFromString(Message.serializer(), rawOutbound))

            val event = withTimeout(TEST_TIMEOUT_MS) { matchedEvent.await() }
            assertEquals("corr-42", event.requestId)
            assertFalse(event.isError)
            assertEquals("true", event.frame.payloadObject?.get("accepted")?.jsonPrimitive?.content)
            assertEquals(0, transport.pendingCount())
            assertEquals(1, transport.getCorrelatedFramesCount())
        } finally {
            transport.close()
        }

        assertThrows(IllegalStateException::class.java) { transport.register("after-close") { } }
        assertThrows(IllegalStateException::class.java) { transport.sendWithCorrelation("after-close", Message.Ping) }
    }

    @Test
    fun testDisconnectAndReconnectIncrementsSocketEpochAndSupportsSuspendingEnqueue() : Unit = runBlocking {
        val secondServerReceived = CompletableDeferred<Message>()
        enqueueUpgrade()
        enqueueUpgrade { _, text -> secondServerReceived.complete(Json.decodeFromString(Message.serializer(), text)) }
        val transport = createServerTransport(
            config = WebSocketTransport.WebSocketConfig(maxQueueSize = 2, client = client),
            bindOwner = true
        )

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertEquals(1L, transport.getOwners().getSocketOwner()?.socketEpoch)

            transport.disconnect()
            assertEquals(WebSocketTransport.ConnectionState.DISCONNECTED, transport.connectionState.value)
            assertFalse(transport.isTransportConnected())

            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            assertEquals(2L, transport.getOwners().getSocketOwner()?.socketEpoch)

            val prompt = Message.Prompt(text = "after-reconnect", entryId = "reconn-1")
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.enqueueOutgoingSuspending(prompt) })
            assertEquals(prompt, withTimeout(TEST_TIMEOUT_MS) { secondServerReceived.await() })
        } finally {
            transport.close()
        }
    }

    private fun enqueueUpgrade(
        onText: ((WebSocket, String) -> Unit)? = null
    ): CompletableDeferred<WebSocket> {
        val opened = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    opened.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onText?.invoke(webSocket, text)
                }
            })
        )
        return opened
    }

    private fun createServerTransport(
        config: WebSocketTransport.WebSocketConfig = WebSocketTransport.WebSocketConfig(client = client),
        bindOwner: Boolean = false
    ): WebSocketTransport {
        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        return WebSocketTransport(scope = ioScope, endpoint = endpoint, config = config).also { transport ->
            if (bindOwner) {
                val owner = testOwner(endpoint.canonical)
                transport.setSignIn(owner.signIn)
                transport.setOwner(owner)
            }
        }
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
        private const val SMALL_MAX_MESSAGE_BYTES = 128
        private const val SMALL_MAX_NESTING_DEPTH = 3
        private const val OVERSIZED_PAYLOAD_LEN = 128
        private const val NDJSON_TEST_MAX_BUFFER = 256
        private const val EXPECTED_NDJSON_COUNT = 3
        private const val CONCURRENT_CONNECT_CALLS = 4
        private const val BURST_FRAME_COUNT = 10
        private const val FIFO_OVERFLOW_COUNT = 8
    }
}
