package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WebSocketTransportOutboundQueueTest {

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
    fun testEnqueueAndDequeueOutgoingWhileDisconnectedRespectsBackpressureAndDropPolicies() = runTest {
        val endpoint = Endpoint.build(host = "localhost", port = DEFAULT_PORT)
        val transport = WebSocketTransport(
            scope = this,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(
                maxQueueSize = 2,
                outboundOverflowPolicy = OutboundMessageQueue.OverflowPolicy.SUSPEND
            )
        )
        bindTestOwner(transport, endpoint.canonical)

        try {
            val first = Message.Prompt("first", "1")
            val second = Message.Prompt("second", "2")
            val third = Message.Prompt("third", "3")
            val fourth = Message.Prompt("fourth", "4")

            assertTrue(transport.enqueueOutgoing(first))
            assertTrue(transport.enqueueOutgoing(second))
            assertEquals(2, transport.getOutboundQueueSize())

            // Default SUSPEND policy rejects non-blocking enqueue without dropping
            assertFalse(transport.enqueueOutgoing(third))
            assertEquals(0, transport.getDroppedOutboundMessages())

            // Explicit DROP_LATEST drops incoming message
            assertFalse(transport.enqueueOutgoing(third, OutboundMessageQueue.OverflowPolicy.DROP_LATEST))
            assertEquals(1, transport.getDroppedOutboundMessages())

            // Explicit DROP_OLDEST evicts oldest message ("first") and enqueues "fourth"
            assertTrue(transport.enqueueOutgoing(fourth, OutboundMessageQueue.OverflowPolicy.DROP_OLDEST))
            assertEquals(2, transport.getOutboundQueueSize())
            assertEquals(2, transport.getDroppedOutboundMessages())

            assertEquals(second, transport.dequeueOutgoing())
            assertEquals(fourth, transport.dequeueOutgoing())
            assertNull(transport.dequeueOutgoing())
            assertEquals(0, transport.getOutboundQueueSize())
        } finally {
            transport.close()
        }
    }

    @Test
    fun testSendWithDropOldestAndDropLatestPoliciesDropsWhenFull() = runTest {
        val endpoint = Endpoint.build(host = "localhost", port = DEFAULT_PORT)
        val dropOldestTransport = WebSocketTransport(
            scope = this,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(
                maxQueueSize = 2,
                outboundOverflowPolicy = OutboundMessageQueue.OverflowPolicy.DROP_OLDEST
            )
        )
        bindTestOwner(dropOldestTransport, endpoint.canonical)

        val dropLatestTransport = WebSocketTransport(
            scope = this,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(
                maxQueueSize = 2,
                outboundOverflowPolicy = OutboundMessageQueue.OverflowPolicy.DROP_LATEST
            )
        )
        bindTestOwner(dropLatestTransport, endpoint.canonical)

        try {
            val m1 = Message.Prompt("m1", "1")
            val m2 = Message.Prompt("m2", "2")
            val m3 = Message.Prompt("m3", "3")

            dropOldestTransport.send(m1)
            dropOldestTransport.send(m2)
            dropOldestTransport.send(m3)
            assertEquals(2, dropOldestTransport.getOutboundQueueSize())
            assertEquals(1, dropOldestTransport.getDroppedOutboundMessages())
            assertEquals(m2, dropOldestTransport.dequeueOutgoing())
            assertEquals(m3, dropOldestTransport.dequeueOutgoing())

            dropLatestTransport.send(m1)
            dropLatestTransport.send(m2)
            dropLatestTransport.send(m3)
            assertEquals(2, dropLatestTransport.getOutboundQueueSize())
            assertEquals(1, dropLatestTransport.getDroppedOutboundMessages())
            assertEquals(m1, dropLatestTransport.dequeueOutgoing())
            assertEquals(m2, dropLatestTransport.dequeueOutgoing())
        } finally {
            dropOldestTransport.close()
            dropLatestTransport.close()
        }
    }

    @Test
    fun testEnqueueOutgoingRequiresOpenTransportAndSocketOwner() = runTest {
        val endpoint = Endpoint.build(host = "localhost", port = DEFAULT_PORT)
        val transport = WebSocketTransport(this, endpoint)
        try {
            assertThrows(IllegalStateException::class.java) {
                transport.enqueueOutgoing(Message.Ping)
            }
        } finally {
            transport.close()
        }
        assertThrows(IllegalStateException::class.java) {
            transport.enqueueOutgoing(Message.Ping)
        }
    }

    @Test
    fun testEnqueueOutgoingDeliversMessagesInFifoOrderWhenConnected() : Unit = runBlocking {
        val serverReceived = CopyOnWriteArrayList<Message>()
        val allReceived = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSockets.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceived.add(Json.decodeFromString(Message.serializer(), text))
                    if (serverReceived.size >= EXPECTED_THREE_MESSAGES) {
                        allReceived.complete(Unit)
                    }
                }
            })
        )

        val endpoint = Endpoint.build(host = server.hostName, port = server.port)
        val transport = WebSocketTransport(
            scope = ioScope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(
                maxQueueSize = QUEUE_CAPACITY,
                client = client
            )
        )
        val owner = testOwner(endpoint.canonical)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)

        try {
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { transport.connect() })
            val messages = listOf(
                Message.Prompt("alpha", "e1"),
                Message.Prompt("beta", "e2"),
                Message.Ping
            )
            messages.forEach { msg ->
                assertTrue(transport.enqueueOutgoing(msg))
            }

            withTimeout(TEST_TIMEOUT_MS) { allReceived.await() }
            assertEquals(messages, serverReceived.toList())
            assertEquals(EXPECTED_THREE_MESSAGES, transport.getMessagesSent())
            assertEquals(0, transport.getOutboundQueueSize())
            assertEquals(0, transport.getDroppedOutboundMessages())
        } finally {
            transport.close()
        }
    }

    private fun bindTestOwner(transport: WebSocketTransport, canonicalEndpoint: String) {
        val owner = testOwner(canonicalEndpoint)
        transport.setSignIn(owner.signIn)
        transport.setOwner(owner)
        transport.getOwners().updateSocketOwner(SocketOwner(owner, 1L))
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
        private const val DEFAULT_PORT = 8080
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_TIMEOUT_MS = 5_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val QUEUE_CAPACITY = 8
        private const val EXPECTED_THREE_MESSAGES = 3
    }
}
