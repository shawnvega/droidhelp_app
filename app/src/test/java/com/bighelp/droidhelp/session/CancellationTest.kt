package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Endpoint
import com.bighelp.droidhelp.transport.Owner
import com.bighelp.droidhelp.transport.Owners
import com.bighelp.droidhelp.transport.SignIn
import com.bighelp.droidhelp.transport.StaleOwnerException
import com.bighelp.droidhelp.transport.WebSocketTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CancellationTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var endpoint: Endpoint
    private val json = Json { ignoreUnknownKeys = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
        endpoint = Endpoint.build(
            scheme = "http",
            host = "localhost",
            port = server.port,
            path = "/",
        )
        SecureInput.clear()
    }

    @After
    fun tearDown() {
        SecureInput.clear()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `cancelSession sends DELETE with generated socket handle and request id and closes gracefully`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        var closedCode: Int? = null
        var closedReason: String? = null
        val closeInvocations = AtomicInteger(0)

        val cancellation = SessionCancellation(
            httpClient = httpClient,
            endpoint = endpoint,
            requestIdGenerator = { "req-generated-1" },
            handleIdGenerator = { "sock-handle-1" },
        )
        val handle = SocketHandle.generate(
            sessionId = "runtime-sess-1",
            socketGeneration = 3L,
            idGenerator = { "sock-handle-1" },
            closer = { code, reason ->
                closeInvocations.incrementAndGet()
                closedCode = code
                closedReason = reason
            },
        )

        val result = cancellation.cancelSession(
            sessionId = "runtime-sess-1",
            socketHandle = handle,
            reason = "User requested cancel",
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/sessions/runtime-sess-1", recorded.path)
        assertEquals("req-generated-1", recorded.getHeader(SessionCancellation.HEADER_REQUEST_ID))
        assertEquals("sock-handle-1", recorded.getHeader(SessionCancellation.HEADER_SOCKET_HANDLE))
        assertEquals("3", recorded.getHeader(SessionCancellation.HEADER_SOCKET_GENERATION))

        val payload = json.decodeFromString<CancellationPayload>(recorded.body.readUtf8())
        assertEquals("runtime-sess-1", payload.sessionId)
        assertEquals("sock-handle-1", payload.socketHandle)
        assertEquals(3L, payload.socketGeneration)
        assertEquals("req-generated-1", payload.requestId)
        assertEquals("User requested cancel", payload.reason)

        assertTrue(result.sessionClosed)
        assertTrue(handle.isClosed)
        assertEquals(200, result.httpStatusCode)
        assertEquals(CancellationHttpMethod.DELETE, result.httpMethod)
        assertEquals("req-generated-1", result.requestId)
        assertEquals("sock-handle-1", result.socketHandleId)
        assertEquals(3L, result.socketGeneration)
        assertEquals(0, result.retiredRequestCount)
        assertEquals(1, closeInvocations.get())
        assertEquals(WebSocketTransport.NORMAL_CLOSURE_CODE, closedCode)
        assertEquals("User requested cancel", closedReason)

        // Closing again is an idempotent no-op
        assertFalse(handle.closeGracefully())
        assertEquals(1, closeInvocations.get())
    }

    @Test
    fun `cancelSession with Session uses runtimeSessionId when attached and sends profile and custom headers`() = 
        runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val session = Session.create(
            durableSessionId = "durable-abc",
            source = SessionCoordinator.SOURCE,
            profile = "research",
            runtimeSessionId = "runtime-xyz",
        )
        val cancellation = SessionCancellation(
            httpClient = httpClient,
            endpoint = endpoint,
            headers = mapOf("Authorization" to "Bearer tok-123"),
            requestIdGenerator = { "req-sess-1" },
            handleIdGenerator = { "handle-sess-1" },
        )
        val handle = cancellation.createSocketHandle(session)

        val result = cancellation.cancelSession(
            session = session,
            socketHandle = handle,
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/sessions/runtime-xyz?profile=research", recorded.path)
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
        assertEquals("handle-sess-1", recorded.getHeader(SessionCancellation.HEADER_SOCKET_HANDLE))
        assertEquals("req-sess-1", recorded.getHeader(SessionCancellation.HEADER_REQUEST_ID))
        assertEquals(204, result.httpStatusCode)
        assertEquals("runtime-xyz", result.sessionId)
        assertTrue(handle.isClosed)
    }

    @Test
    fun `cancelSession supports POST method, ws scheme conversion, deploymentPrefix, and path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        val prefixedEndpoint = Endpoint.build(
            scheme = "ws",
            host = "localhost",
            port = server.port,
            path = "/v2/hermes",
            deploymentPrefix = "tenant-a",
        )
        val cancellation = SessionCancellation(
            httpClient = httpClient,
            endpoint = prefixedEndpoint,
        )
        val handle = SocketHandle.generate(
            sessionId = "sess-post-1",
            socketGeneration = 2L,
            idGenerator = { "handle-post-1" },
        )

        val result = cancellation.cancelSession(
            sessionId = "sess-post-1",
            socketHandle = handle,
            requestId = "req-post-1",
            httpMethod = CancellationHttpMethod.POST,
            profile = "default",
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/tenant-a/v2/hermes/api/sessions/sess-post-1?profile=default", recorded.path)
        assertEquals(CancellationHttpMethod.POST, result.httpMethod)
        assertEquals(202, result.httpStatusCode)
        assertTrue(result.sessionClosed)
    }

    @Test
    fun `cancelSession enforces TLS policy for public non-private hosts`() = runTest {
        val publicPlaintext = Endpoint.build(
            scheme = "http",
            host = "api.example.com",
            port = 80,
            path = "/",
        )
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = publicPlaintext)
        val handle = SocketHandle.generate(sessionId = "sess-tls")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                cancellation.cancelSession(
                    sessionId = "sess-tls",
                    socketHandle = handle,
                    requestId = "req-tls",
                )
            }
        }
        assertFalse(handle.isClosed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelSession classifies 4xx definitive rejections and 5xx uncertain outcomes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle404 = SocketHandle.generate(sessionId = "sess-404")

        val ex404 = assertThrows(SessionCancellationException::class.java) {
            runBlocking {
                cancellation.cancelSession(
                    sessionId = "sess-404",
                    socketHandle = handle404,
                    requestId = "req-404",
                )
            }
        }
        assertEquals(404, ex404.statusCode)
        assertFalse(ex404.outcomeUnknown)
        assertFalse(handle404.isClosed)

        server.enqueue(MockResponse().setResponseCode(503))
        val handle503 = SocketHandle.generate(sessionId = "sess-503")
        val ex503 = assertThrows(SessionCancellationException::class.java) {
            runBlocking {
                cancellation.cancelSession(
                    sessionId = "sess-503",
                    socketHandle = handle503,
                    requestId = "req-503",
                )
            }
        }
        assertEquals(503, ex503.statusCode)
        assertTrue(ex503.outcomeUnknown)
        assertFalse(handle503.isClosed)
    }

    @Test
    fun `cancelSession throws StaleOwnerException on pre-flight and post-flight owner mismatch`() : Unit = runBlocking {
        val owners = Owners()
        val signIn = SignIn(endpoint.canonical, "alice", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        val epoch1 = owners.incrementSocketEpoch(owner)

        // Pre-flight stale owner check: rotate epoch before cancelSession
        val epoch2 = owners.incrementSocketEpoch(owner)
        assertNotEquals(epoch1, epoch2)

        val cancellation = SessionCancellation(
            httpClient = httpClient,
            endpoint = endpoint,
            owners = owners,
        )
        val staleHandle = SocketHandle.generate(
            sessionId = "sess-fence",
            socketGeneration = epoch1.socketEpoch,
            socketOwner = epoch1,
        )

        assertThrows(StaleOwnerException::class.java) {
            runBlocking {
                cancellation.cancelSession(
                    sessionId = "sess-fence",
                    socketHandle = staleHandle,
                    requestId = "req-stale-1",
                )
            }
        }
        assertEquals(0, server.requestCount)
        assertFalse(staleHandle.isClosed)

        // Post-flight stale owner check: rotate epoch while HTTP request is in flight
        val requestArrived = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestArrived.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200)
            }
        }

        val liveHandle = SocketHandle.generate(
            sessionId = "sess-fence-2",
            socketGeneration = epoch2.socketEpoch,
            socketOwner = epoch2,
        )

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deferred = testScope.async {
                cancellation.cancelSession(
                    sessionId = "sess-fence-2",
                    socketHandle = liveHandle,
                    requestId = "req-stale-2",
                )
            }

            assertTrue(requestArrived.await(5, TimeUnit.SECONDS))
            // Rotate socket epoch while HTTP call is in flight
            owners.incrementSocketEpoch(owner)
            allowResponse.countDown()

            assertThrows(StaleOwnerException::class.java) {
                runBlocking { deferred.await() }
            }
            assertFalse(liveHandle.isClosed)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `opaque Unicode request IDs remain byte-distinct through cancellation and HTTP encoding`() = runTest {
        val composed = "request:\u00e9"
        val decomposed = "request:e\u0301"
        assertNotEquals(composed, decomposed)

        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle = SocketHandle.generate(sessionId = "sess-unicode", socketGeneration = 1L)

        assertEquals(
            RequestRegistrationResult.ACCEPTED,
            cancellation.registerPendingRequest(handle, composed, "approval"),
        )
        assertEquals(
            RequestRegistrationResult.ACCEPTED,
            cancellation.registerPendingRequest(handle, decomposed, "approval"),
        )
        assertEquals(2, cancellation.pendingRequestCount)
        assertTrue(cancellation.stageResponse(handle, composed, "approval"))

        // Cancel only the decomposed ID
        assertTrue(
            cancellation.cancelPendingRequest(
                handle,
                ServerRequestCancellation(id = decomposed, method = "approval", reason = "expired"),
            ),
        )
        assertEquals(1, cancellation.pendingRequestCount)
        assertTrue(cancellation.beginSendingResponse(handle, composed, "approval"))
        assertEquals(0, cancellation.pendingRequestCount)

        // HTTP cancellation with non-ASCII opaque requestId succeeds without OkHttp header error
        server.enqueue(MockResponse().setResponseCode(200))
        cancellation.cancelSession(
            sessionId = "sess-unicode",
            socketHandle = handle,
            requestId = decomposed,
        )
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        val payload = json.decodeFromString<CancellationPayload>(recorded.body.readUtf8())
        assertEquals(decomposed, payload.requestId)
        assertTrue(recorded.getHeader(SessionCancellation.HEADER_REQUEST_ID)!!.contains("%CC%81"))
    }

    @Test
    fun `queued response remains cancellable and wrong method or generation cannot retire it`() {
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handleGen1 = SocketHandle.generate(sessionId = "sess-1", socketGeneration = 1L)
        val handleGen2 = SocketHandle.generate(sessionId = "sess-1", socketGeneration = 2L)

        assertEquals(
            RequestRegistrationResult.ACCEPTED,
            cancellation.registerPendingRequest(handleGen1, "orig-id", "approval"),
        )
        assertEquals(
            RequestRegistrationResult.DUPLICATE,
            cancellation.registerPendingRequest(handleGen1, "orig-id", "approval"),
        )
        assertTrue(cancellation.stageResponse(handleGen1, "orig-id", "approval"))
        assertFalse(cancellation.stageResponse(handleGen1, "orig-id", "approval"))

        // Wrong method cannot retire it
        assertFalse(
            cancellation.cancelPendingRequest(
                handleGen1,
                ServerRequestCancellation(id = "orig-id", method = "clarify", reason = "expired"),
            ),
        )
        // Wrong socket generation cannot retire it
        assertFalse(
            cancellation.cancelPendingRequest(
                handleGen2,
                ServerRequestCancellation(id = "orig-id", method = "approval", reason = "expired"),
            ),
        )
        assertEquals(1, cancellation.pendingRequestCount)

        // Exact generation + ID + method retires the staged response before write begins
        assertTrue(
            cancellation.cancelPendingRequest(
                handleGen1,
                ServerRequestCancellation(id = "orig-id", method = "approval", reason = "expired"),
            ),
        )
        assertFalse(cancellation.beginSendingResponse(handleGen1, "orig-id", "approval"))
        assertEquals(0, cancellation.pendingRequestCount)
    }

    @Test
    fun `pending request registration enforces capacity limit and retires secure inputs on cancel`() = runTest {
        val retiredSecureIds = mutableListOf<String>()
        val cancellation = SessionCancellation(
            httpClient = httpClient,
            endpoint = endpoint,
            onRetireSecureInput = { id ->
                retiredSecureIds.add(id)
                SecureInput.clear()
            },
        )
        val handle = SocketHandle.generate(sessionId = "sess-cap", socketGeneration = 5L)

        for (index in 0 until SessionCancellation.MAX_OPEN_REQUEST_COUNT) {
            val secureId = if (index == 0) "sec-prompt-0" else null
            assertEquals(
                RequestRegistrationResult.ACCEPTED,
                cancellation.registerPendingRequest(handle, "req-$index", "secret", secureInputId = secureId),
            )
        }
        assertEquals(
            RequestRegistrationResult.AT_CAPACITY,
            cancellation.registerPendingRequest(handle, "overflow", "secret"),
        )

        SecureInput.put("sec-prompt-0", "super-secret-token")
        assertTrue(
            cancellation.cancelPendingRequest(
                handle,
                ServerRequestCancellation(id = "req-0", method = "secret", reason = "cancelled"),
            ),
        )
        assertEquals(listOf("sec-prompt-0"), retiredSecureIds)
        assertNull(SecureInput.get("sec-prompt-0"))

        // Graceful cancelSession retires remaining 63 requests bound to this handle
        server.enqueue(MockResponse().setResponseCode(200))
        val result = cancellation.cancelSession(
            sessionId = "sess-cap",
            socketHandle = handle,
            requestId = "cancel-all-req",
        )
        assertEquals(SessionCancellation.MAX_OPEN_REQUEST_COUNT - 1, result.retiredRequestCount)
        assertEquals(0, cancellation.pendingRequestCount)
        assertTrue(result.sessionClosed)
    }

    @Test
    fun `SocketHandle forWebSocket closes gracefully and truncates long multi-byte close reason`() {
        var closedCode: Int? = null
        var closedReason: String? = null
        var abruptCancelled = false
        val fakeWebSocket = object : WebSocket {
            override fun request(): Request = Request.Builder().url("http://localhost/api/ws").build()
            override fun queueSize(): Long = 0L
            override fun send(text: String): Boolean = true
            override fun send(bytes: ByteString): Boolean = true
            override fun close(code: Int, reason: String?): Boolean {
                closedCode = code
                closedReason = reason
                return true
            }
            override fun cancel() {
                abruptCancelled = true
            }
        }

        val handle = SocketHandle.forWebSocket(
            sessionId = "sess-ws",
            webSocket = fakeWebSocket,
            socketGeneration = 4L,
        )
        // 40 * 4-byte emoji = 160 UTF-8 bytes (> 123 RFC 6455 limit)
        val longEmojiReason = "\uD83D\uDE80".repeat(40)
        assertTrue(handle.closeGracefully(reason = longEmojiReason))

        assertFalse(abruptCancelled)
        assertEquals(WebSocketTransport.NORMAL_CLOSURE_CODE, closedCode)
        val actualReason = closedReason!!
        assertTrue(actualReason.toByteArray(Charsets.UTF_8).size <= 123)
        // 40 * 4-byte emoji = 160 UTF-8 bytes (> 123 RFC 6455 limit)
        // 123 / 4 = 30 complete rocket emojis (120 UTF-8 bytes) with no split surrogate
        assertEquals("\uD83D\uDE80".repeat(30), actualReason)
    }

    @Test
    fun `SocketHandle forTransport respects socket ownership when disconnecting WebSocketTransport`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = WebSocketTransport(
            scope = scope,
            endpoint = endpoint,
            config = WebSocketTransport.WebSocketConfig(client = httpClient),
        )
        try {
            val signIn = SignIn(endpoint.canonical, "bob", 1L)
            val owner = Owner(signIn, 1L)
            transport.setSignIn(signIn)
            transport.setOwner(owner)
            val epoch1 = transport.getOwners().incrementSocketEpoch(owner)

            val handle = SocketHandle.forTransport(sessionId = "sess-tr", transport = transport)
            assertEquals(epoch1, handle.socketOwner)
            assertEquals(epoch1.socketEpoch, handle.socketGeneration)

            assertTrue(handle.closeGracefully())
            assertTrue(handle.isClosed)
            assertEquals(WebSocketTransport.ConnectionState.DISCONNECTED, transport.connectionState.value)
        } finally {
            transport.close()
            scope.cancel()
        }
    }

    @Test
    fun `validation rejects mismatched sessionId, path traversal, and invalid cancellation payloads`() = runTest {
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle = SocketHandle.generate(sessionId = "sess-a")

        assertThrows(IllegalArgumentException::class.java) {
            SocketHandle.generate(sessionId = "..")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SocketHandle.generate(sessionId = "sess\u0000bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerRequestCancellation(id = "x".repeat(1_025), method = "clarify", reason = "expired")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerRequestCancellation(id = "id-1", method = "bad method", reason = "expired")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                cancellation.cancelSession(
                    sessionId = "sess-b",
                    socketHandle = handle,
                    requestId = "req-mismatch",
                )
            }
        }
    }

    @Test
    fun `coroutine cancellation propagates CancellationException without swallowing`() : Unit = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200)
            }
        }

        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle = SocketHandle.generate(sessionId = "sess-cancel-coro")

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = testScope.async {
                cancellation.cancelSession(
                    sessionId = "sess-cancel-coro",
                    socketHandle = handle,
                    requestId = "req-coro",
                )
            }

            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            delay(20)
            job.cancel()
            releaseServer.countDown()

            assertThrows(CancellationException::class.java) {
                runBlocking { job.await() }
            }
            assertFalse(handle.isClosed)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `queued response remains cancellable and wrong requestId cannot retire it`() {
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle = SocketHandle.generate(sessionId = "sess-1", socketGeneration = 1L)
        assertEquals(
            RequestRegistrationResult.ACCEPTED,
            cancellation.registerPendingRequest(handle, "orig-id", "approval"),
        )
        assertTrue(cancellation.stageResponse(handle, "orig-id", "approval"))

        // Wrong requestId cannot retire it
        assertFalse(
            cancellation.cancelPendingRequest(
                handle,
                ServerRequestCancellation(id = "wrong-id", method = "approval", reason = "expired"),
            ),
        )
        assertEquals(1, cancellation.pendingRequestCount)
    }

    @Test
    fun `activePendingRequests returns correct keys`() {
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle = SocketHandle.generate(sessionId = "sess-1", socketGeneration = 1L)
        cancellation.registerPendingRequest(handle, "req-1", "GET")
        cancellation.registerPendingRequest(handle, "req-2", "POST")

        val active = cancellation.activePendingRequests
        assertEquals(2, active.size)
        assertTrue(active.contains(PendingRequestKey(1L, "req-1", "GET")))
        assertTrue(active.contains(PendingRequestKey(1L, "req-2", "POST")))
    }

    @Test
    fun `registration rejects non-printable ASCII methods`() {
        val cancellation = SessionCancellation(httpClient = httpClient, endpoint = endpoint)
        val handle = SocketHandle.generate(sessionId = "sess-1", socketGeneration = 1L)
        // Space is not allowed in printable ASCII according to the requirement in Cancellation.kt
        // (ASCII_PRINTABLE_MIN = 33, which is '!')
        assertThrows(IllegalArgumentException::class.java) {
            cancellation.registerPendingRequest(handle, "req-1", "GET ")
        }
    }
}
