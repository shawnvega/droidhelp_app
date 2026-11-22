package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WebSocketTransportFrameQueueTest {

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
    fun testIncomingNewlineJsonFramesExtractFieldsIntoQueueAndRecoverFromMalformed() : Unit = runBlocking {
        val serverWsDeferred = enqueueUpgrade()
        val transport = createTransport(maxFrameSize = TEST_MAX_FRAME_SIZE)
        val listenerFrames = CopyOnWriteArrayList<ParsedJsonFrame>()
        val lastFrameArrived = CompletableDeferred<Unit>()
        transport.onParsedFrame { frame ->
            listenerFrames.add(frame)
            if (frame.headers["x-id"] == "binary-3") {
                lastFrameArrived.complete(Unit)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverWsDeferred.await() }

            val frame1 = """{"headers":{"x-id":"frame-1","channel":"chat"},"payload":{"text":"hello"}}"""
            val bigPayload = "z".repeat(TEST_MAX_FRAME_SIZE)
            val oversized = """{"headers":{"x-id":"too-big"},"payload":{"blob":"$bigPayload"}}"""
            val frame2 =
                """{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","payload":{"running":true}}}"""
            val binaryFrame = """{"headers":{"x-id":"binary-3"},"payload":{"ok":true}}"""

            serverWs.send("$frame1\n{not valid json\n[1,2,3]\n$oversized\n$frame2\n")
            serverWs.send("$binaryFrame\n".encodeUtf8())

            withTimeout(TEST_TIMEOUT_MS) { lastFrameArrived.await() }

            assertEquals(EXPECTED_THREE_FRAMES, transport.getParsedFramesReceived())
            assertEquals(EXPECTED_THREE_FRAMES, transport.getParsedFrameQueueSize())
            assertEquals(2, transport.getMalformedFramesDropped())
            assertEquals(1, transport.getOversizedFramesDropped())

            val queuedFirst = withTimeout(TEST_TIMEOUT_MS) { transport.receiveParsedFrame() }
            assertEquals(mapOf("x-id" to "frame-1", "channel" to "chat"), queuedFirst?.headers)
            assertEquals("hello", queuedFirst?.payloadObject?.get("text")?.jsonPrimitive?.content)

            val remaining = transport.drainParsedFrames()
            assertEquals(2, remaining.size)
            assertEquals("true", remaining[0].payloadObject?.get("running")?.jsonPrimitive?.content)
            assertEquals(mapOf("x-id" to "binary-3"), remaining[1].headers)
            assertEquals("true", remaining[1].payloadObject?.get("ok")?.jsonPrimitive?.content)
            assertEquals(0, transport.getParsedFrameQueueSize())
            assertEquals(EXPECTED_THREE_FRAMES, listenerFrames.size)
        } finally {
            transport.close()
        }
    }

    @Test
    fun testParsedFrameQueueEnforcesMaxQueueSizeAndUnblocksReceiveOnClose() : Unit = runBlocking {
        val serverWsDeferred = enqueueUpgrade()
        val transport = createTransport(maxQueueSize = 2)
        val fourthSeen = CompletableDeferred<Unit>()
        transport.onParsedFrame { frame ->
            if (frame.headers["seq"] == "4") {
                fourthSeen.complete(Unit)
            }
        }

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val serverWs = withTimeout(TEST_TIMEOUT_MS) { serverWsDeferred.await() }

            val batch = (1..BATCH_FOUR_FRAMES).joinToString("\n") { idx ->
                """{"headers":{"seq":"$idx"},"payload":{"value":$idx}}"""
            } + "\n"
            serverWs.send(batch)

            withTimeout(TEST_TIMEOUT_MS) { fourthSeen.await() }
            assertEquals(2, transport.getParsedFrameQueueSize())
            assertEquals(2, transport.getDroppedQueuedFrames())

            val retained = transport.drainParsedFrames()
            assertEquals(listOf("3", "4"), retained.map { it.headers["seq"] })
            assertNull(transport.pollParsedFrame())
        } finally {
            transport.close()
        }
        assertNull(withTimeout(TEST_TIMEOUT_MS) { transport.receiveParsedFrame() })
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

    private fun createTransport(
        maxFrameSize: Int = WebSocketTransport.DEFAULT_MAX_FRAME_SIZE,
        maxQueueSize: Int = WebSocketTransport.DEFAULT_MAX_QUEUE_SIZE
    ): WebSocketTransport {
        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        return WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(
                maxFrameSize = maxFrameSize,
                maxQueueSize = maxQueueSize,
                client = client
            )
        )
    }

    companion object {
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_TIMEOUT_MS = 5_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val TEST_MAX_FRAME_SIZE = 256
        private const val EXPECTED_THREE_FRAMES = 3
        private const val BATCH_FOUR_FRAMES = 4
    }
}
