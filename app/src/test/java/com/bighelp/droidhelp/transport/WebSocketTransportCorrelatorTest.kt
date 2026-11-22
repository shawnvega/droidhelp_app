package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketTransportCorrelatorTest {

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
    }

    @After
    fun tearDown() {
        serverSockets.forEach { it.close(NORMAL_CLOSE_CODE, "teardown") }
        serverSockets.clear()
        ioScope.cancel()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun testCorrelatesIncomingJsonRpcSuccessAndErrorFramesOverWebSocket() : Unit = runBlocking {
        val serverWsDeferred = enqueueUpgrade()
        val transport = createTransport()

        val listenerEvents = Collections.synchronizedList(mutableListOf<CorrelationEvent>())
        val req1Deferred = CompletableDeferred<CorrelationEvent>()
        val req2Deferred = CompletableDeferred<CorrelationEvent>()
        transport.onCorrelationEvent { listenerEvents.add(it) }

        val flowCollector = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TEST_TIMEOUT_MS) {
                transport.correlationEvents.take(2).toList()
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverWsDeferred.await() }

            transport.registerRequest("rpc-1") { event -> req1Deferred.complete(event) }
            transport.registerRequest("rpc-2") { event -> req2Deferred.complete(event) }
            assertEquals(2, transport.pendingCount())

            // Server sends a colliding server-to-client request (must be ignored by correlator),
            // an unrelated event, an unknown reply ID, then the two matching JSON-RPC replies.
            val batch = listOf(
                """{"jsonrpc":"2.0","id":"rpc-1","method":"approval","params":{"session_id":"s1"}}""",
                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""",
                """{"jsonrpc":"2.0","id":"rpc-unknown","result":{"ignored":true}}""",
                """{"jsonrpc":"2.0","id":"rpc-1","result":{"ok":true}}""",
                """{"jsonrpc":"2.0","id":"rpc-2","error":{"code":4090,"message":"limit"}}"""
            ).joinToString("\n") + "\n"
            serverWs.send(batch)

            val event1 = withTimeout(TEST_TIMEOUT_MS) { req1Deferred.await() }
            val event2 = withTimeout(TEST_TIMEOUT_MS) { req2Deferred.await() }
            val flowEvents = flowCollector.await()

            assertEquals("rpc-1", event1.requestId)
            assertFalse(event1.isError)
            assertEquals("true", event1.frame.payloadObject?.get("ok")?.jsonPrimitive?.content)

            assertEquals("rpc-2", event2.requestId)
            assertTrue(event2.isError)
            assertEquals("4090", event2.frame.payloadObject?.get("code")?.jsonPrimitive?.content)

            assertEquals(listOf("rpc-1", "rpc-2"), flowEvents.map { it.requestId })
            assertEquals(listOf("rpc-1", "rpc-2"), listenerEvents.map { it.requestId })
            assertEquals(0, transport.pendingCount())
            assertEquals(2, transport.getCorrelatedFramesCount())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testCorrelatesBinaryUtf8AndHeaderKeyedJsonFramesOverWebSocket() : Unit = runBlocking {
        val serverWsDeferred = enqueueUpgrade()
        val transport = createTransport()

        val binaryEventDeferred = CompletableDeferred<CorrelationEvent>()
        val headerEventDeferred = CompletableDeferred<CorrelationEvent>()

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverWsDeferred.await() }

            transport.registerRequest("bin-req-1") { binaryEventDeferred.complete(it) }
            transport.registerRequest("hdr-req-2") { headerEventDeferred.complete(it) }

            val binaryReply = """{"jsonrpc":"2.0","id":"bin-req-1","result":{"kind":"binary"}}""" + "\n"
            val headerReply = """{"headers":{"X-Request-Id":"hdr-req-2"},"payload":{"kind":"header"}}""" + "\n"
            serverWs.send(binaryReply.encodeUtf8())
            serverWs.send(headerReply)

            val binEvent = withTimeout(TEST_TIMEOUT_MS) { binaryEventDeferred.await() }
            val hdrEvent = withTimeout(TEST_TIMEOUT_MS) { headerEventDeferred.await() }

            assertEquals("binary", binEvent.frame.payloadObject?.get("kind")?.jsonPrimitive?.content)
            assertEquals("header", hdrEvent.frame.payloadObject?.get("kind")?.jsonPrimitive?.content)
            assertEquals("hdr-req-2", hdrEvent.frame.headers["X-Request-Id"])
            assertEquals(0, transport.pendingCount())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testLegacyReceiptRegistrationAlsoEmitsCorrelationEvent() : Unit = runBlocking {
        val serverWsDeferred = enqueueUpgrade()
        val transport = createTransport()

        val receiptCallbackDeferred = CompletableDeferred<Message>()
        val correlationEventDeferred = CompletableDeferred<CorrelationEvent>()
        transport.onCorrelationEvent { event ->
            if (event.requestId == "legacy-req-1") {
                correlationEventDeferred.complete(event)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverWsDeferred.await() }

            transport.register("legacy-req-1") { msg -> receiptCallbackDeferred.complete(msg) }
            val receipt = Message.Receipt(entryId = "legacy-req-1")
            serverWs.send(Json.encodeToString(Message.serializer(), receipt) + "\n")

            val receivedMsg = withTimeout(TEST_TIMEOUT_MS) { receiptCallbackDeferred.await() }
            val receivedEvent = withTimeout(TEST_TIMEOUT_MS) { correlationEventDeferred.await() }

            assertEquals(receipt, receivedMsg)
            assertEquals("legacy-req-1", receivedEvent.requestId)
            assertEquals(receipt, receivedEvent.frame.message)
            assertFalse(receivedEvent.isError)
            assertEquals(0, transport.pendingCount())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testConcurrentWebSocketFrameCorrelationAndCancelUnderLoad() : Unit = runBlocking {
        val serverWsDeferred = enqueueUpgrade()
        val transport = createTransport()

        val matchedIds = ConcurrentHashMap.newKeySet<String>()
        val cancelledHit = AtomicBoolean(false)
        val expectedMatchedCount = LOAD_TOTAL_REQUESTS - LOAD_CANCELLED_COUNT
        val allMatchedLatch = CountDownLatch(expectedMatchedCount)

        transport.onCorrelationEvent { event ->
            if (matchedIds.add(event.requestId)) {
                allMatchedLatch.countDown()
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverWsDeferred.await() }

            val registrationThreads = List(REGISTRATION_THREADS) { worker ->
                Thread {
                    val start = worker * REQUESTS_PER_THREAD
                    val end = start + REQUESTS_PER_THREAD
                    for (idx in start until end) {
                        val id = "load-req-$idx"
                        if (idx < LOAD_CANCELLED_COUNT) {
                            transport.registerRequest(id) { cancelledHit.set(true) }
                            transport.cancel(id)
                        } else {
                            transport.registerRequest(id)
                        }
                    }
                }
            }
            registrationThreads.forEach { it.start() }
            registrationThreads.forEach { it.join(TEST_TIMEOUT_MS) }

            assertEquals(expectedMatchedCount, transport.pendingCount())

            val batch = (0 until LOAD_TOTAL_REQUESTS).joinToString("\n") { idx ->
                """{"jsonrpc":"2.0","id":"load-req-$idx","result":{"index":$idx}}"""
            } + "\n"
            serverWs.send(batch)

            assertTrue(
                "Expected all non-cancelled requests to correlate",
                allMatchedLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )
            assertFalse("Cancelled requests must never emit events", cancelledHit.get())
            assertEquals(expectedMatchedCount, matchedIds.size)
            assertEquals(0, transport.pendingCount())
            assertEquals(expectedMatchedCount, transport.getCorrelatedFramesCount())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testCloseClearsPendingRequestsAndRejectsNewRegistration() : Unit = runBlocking {
        val transport = createTransport()
        transport.registerRequest("pending-before-close")
        assertEquals(1, transport.pendingCount())

        transport.close()
        assertEquals(0, transport.pendingCount())
        assertThrows(IllegalStateException::class.java) {
            transport.registerRequest("after-close")
        }
    }

    private fun enqueueUpgrade(): CompletableDeferred<WebSocket> {
        val opened = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                    opened.complete(webSocket)
                }
            })
        )
        return opened
    }

    private fun createTransport(): WebSocketTransport {
        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        return WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(client = client)
        )
    }

    companion object {
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_TIMEOUT_MS = 5_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val REGISTRATION_THREADS = 4
        private const val REQUESTS_PER_THREAD = 15
        private const val LOAD_TOTAL_REQUESTS = REGISTRATION_THREADS * REQUESTS_PER_THREAD
        private const val LOAD_CANCELLED_COUNT = 10
    }
}
