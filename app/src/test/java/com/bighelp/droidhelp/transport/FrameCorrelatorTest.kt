package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FrameCorrelatorTest {

    private val decoder = NewlineJsonFrameDecoder()

    private fun parseFrame(rawJson: String): ParsedJsonFrame {
        val result = decoder.decode("$rawJson\n")
        assertEquals(1, result.frames.size, "Expected single parsed frame for: $rawJson")
        return result.frames.single()
    }

    @Test
    fun testCorrelatesJsonRpcSuccessNullResultAndErrorFrames() = runBlocking {
        val correlator = FrameCorrelator()
        val listenerEvents = Collections.synchronizedList(mutableListOf<CorrelationEvent>())
        val perRequestEvents = Collections.synchronizedList(mutableListOf<CorrelationEvent>())
        correlator.addListener { listenerEvents.add(it) }

        val collectedFlow = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TEST_TIMEOUT_MS) {
                correlator.events.take(EXPECTED_THREE_EVENTS).toList()
            }
        }

        correlator.register("req-ok") { perRequestEvents.add(it) }
        correlator.register("req-null") { perRequestEvents.add(it) }
        correlator.register("req-err") { perRequestEvents.add(it) }
        assertEquals(EXPECTED_THREE_EVENTS, correlator.pendingCount())

        val okFrame = parseFrame("""{"jsonrpc":"2.0","id":"req-ok","result":{"ok":true}}""")
        val nullFrame = parseFrame("""{"jsonrpc":"2.0","id":"req-null","result":null}""")
        val errFrame = parseFrame(
            """{"jsonrpc":"2.0","id":"req-err","error":{"code":4090,"message":"Active session limit reached"}}"""
        )

        val okEvent = correlator.correlate(okFrame)
        val nullEvent = correlator.correlate(nullFrame)
        val errEvent = correlator.correlate(errFrame)

        assertNotNull(okEvent)
        assertNotNull(nullEvent)
        assertNotNull(errEvent)
        assertEquals("req-ok", okEvent?.requestId)
        assertFalse(okEvent?.isError ?: true)
        assertEquals("true", okEvent?.frame?.payloadObject?.get("ok")?.jsonPrimitive?.content)

        assertEquals("req-null", nullEvent?.requestId)
        assertFalse(nullEvent?.isError ?: true)

        assertEquals("req-err", errEvent?.requestId)
        assertTrue(errEvent?.isError ?: false)
        assertEquals("4090", errEvent?.frame?.payloadObject?.get("code")?.jsonPrimitive?.content)

        assertEquals(0, correlator.pendingCount())
        assertEquals(EXPECTED_THREE_EVENTS, correlator.correlatedCount)
        assertEquals(0, correlator.unmatchedCount)
        assertEquals(listOf("req-ok", "req-null", "req-err"), listenerEvents.map { it.requestId })
        assertEquals(listOf("req-ok", "req-null", "req-err"), perRequestEvents.map { it.requestId })
        assertEquals(listOf("req-ok", "req-null", "req-err"), collectedFlow.await().map { it.requestId })
    }

    @Test
    fun testIgnoresServerRequestsEventsAndMalformedJsonRpcReplies() {
        val correlator = FrameCorrelator()
        val emitted = mutableListOf<CorrelationEvent>()
        correlator.addListener { emitted.add(it) }
        correlator.register("client-req-1")

        // Server-to-client request sharing the exact same ID must NEVER hijack a client RPC
        val collidingServerReq = parseFrame(
            """{"jsonrpc":"2.0","id":"client-req-1","method":"approval","params":{"session_id":"rt-1"}}"""
        )
        assertNull(correlator.correlate(collidingServerReq))
        assertTrue(correlator.contains("client-req-1"))

        // Event notification
        val serverEvent = parseFrame(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
        )
        assertNull(correlator.correlate(serverEvent))

        // Invalid JSON-RPC replies: params present, both result & error, non-string id, null error
        val withParams = parseFrame("""{"jsonrpc":"2.0","id":"client-req-1","params":{},"result":{}}""")
        val withBoth = parseFrame(
            """{"jsonrpc":"2.0","id":"client-req-1","result":{},"error":{"code":-1,"message":"bad"}}"""
        )
        val numericId = parseFrame("""{"jsonrpc":"2.0","id":123,"result":{}}""")
        val nullError = parseFrame("""{"jsonrpc":"2.0","id":"client-req-1","error":null}""")

        assertNull(correlator.correlate(withParams))
        assertNull(correlator.correlate(withBoth))
        assertNull(correlator.correlate(numericId))
        assertNull(correlator.correlate(nullError))

        assertTrue(correlator.contains("client-req-1"))
        assertTrue(emitted.isEmpty())

        // Legitimate reply still correlates cleanly
        val validReply = parseFrame("""{"jsonrpc":"2.0","id":"client-req-1","result":{"accepted":true}}""")
        val matched = correlator.correlate(validReply)
        assertNotNull(matched)
        assertEquals("client-req-1", matched?.requestId)
        assertEquals(1, emitted.size)
        assertEquals(0, correlator.pendingCount())
    }

    @Test
    fun testExtractsRequestIdFromGenericFieldsHeadersReceiptAndOpaqueIds() {
        val correlator = FrameCorrelator()
        val opaqueId = "req: alpha/π 42 "
        val ids = listOf("id-top", "id-camel", "id-snake", "id-header", "id-receipt", opaqueId)
        ids.forEach { correlator.register(it) }

        val topIdFrame = parseFrame("""{"id":"id-top","payload":{"v":1}}""")
        val camelFrame = parseFrame("""{"requestId":"id-camel","payload":{"v":2}}""")
        val snakeFrame = parseFrame("""{"request_id":"id-snake","payload":{"v":3}}""")
        val headerFrame = parseFrame("""{"headers":{"X-Request-Id":"id-header"},"payload":{"v":4}}""")
        val receiptJson = Json.encodeToString(Message.serializer(), Message.Receipt("id-receipt"))
        val receiptFrame = parseFrame(receiptJson)
        val opaqueFrame = parseFrame("""{"jsonrpc":"2.0","id":"$opaqueId","result":{"ok":true}}""")

        // Prompt message with matching entryId must NOT correlate
        val promptJson = Json.encodeToString(Message.serializer(), Message.Prompt("hi", "id-receipt"))
        val promptFrame = parseFrame(promptJson)
        assertNull(correlator.correlate(promptFrame))
        assertTrue(correlator.contains("id-receipt"))

        // Oversized request ID (> 1024 UTF-8 bytes) must be ignored
        val hugeId = "x".repeat(OVERSIZED_REQUEST_ID_LENGTH)
        val hugeFrame = parseFrame("""{"jsonrpc":"2.0","id":"$hugeId","result":{}}""")
        assertNull(correlator.correlate(hugeFrame))

        assertEquals("id-top", correlator.correlate(topIdFrame)?.requestId)
        assertEquals("id-camel", correlator.correlate(camelFrame)?.requestId)
        assertEquals("id-snake", correlator.correlate(snakeFrame)?.requestId)
        assertEquals("id-header", correlator.correlate(headerFrame)?.requestId)
        assertEquals("id-receipt", correlator.correlate(receiptFrame)?.requestId)
        assertEquals(opaqueId, correlator.correlate(opaqueFrame)?.requestId)
        assertEquals(0, correlator.pendingCount())
    }

    @Test
    fun testCancelDuplicateAndLateRepliesDoNotEmitEvents() {
        val correlator = FrameCorrelator()
        val listenerHits = AtomicInteger(0)
        val listener: (CorrelationEvent) -> Unit = { listenerHits.incrementAndGet() }
        correlator.addListener(listener)

        correlator.register("req-1")
        correlator.register("req-2")
        assertTrue(correlator.cancel("req-2"))
        assertFalse(correlator.cancel("req-2"))

        val frame1 = parseFrame("""{"jsonrpc":"2.0","id":"req-1","result":{}}""")
        val frame2 = parseFrame("""{"jsonrpc":"2.0","id":"req-2","result":{}}""")

        assertNotNull(correlator.correlate(frame1))
        // Duplicate reply for already-correlated req-1 must not emit again
        assertNull(correlator.correlate(frame1))
        // Cancelled req-2 must not match
        assertNull(correlator.correlate(frame2))

        assertEquals(1, listenerHits.get())
        assertEquals(1, correlator.correlatedCount)
        assertEquals(2, correlator.unmatchedCount)

        correlator.removeListener(listener)
        correlator.register("req-3")
        assertNotNull(correlator.correlate(parseFrame("""{"jsonrpc":"2.0","id":"req-3","result":{}}""")))
        assertEquals(1, listenerHits.get())
    }

    @Test
    fun testCleanupTimeoutsRemovesOnlyExpiredEntries() {
        val correlator = FrameCorrelator()
        val baseNow = System.nanoTime()
        correlator.register("expired-1", timeoutNanos = 1L)
        correlator.register("active-1", timeoutNanos = LONG_TIMEOUT_NANOS)

        val removed = correlator.cleanupTimeouts(nowNanos = baseNow + EXPIRED_OFFSET_NANOS)
        assertEquals(1, removed)
        assertFalse(correlator.contains("expired-1"))
        assertTrue(correlator.contains("active-1"))
        assertEquals(1, correlator.pendingCount())
    }

    @Test
    fun testConcurrentRegistrationCorrelationAndCancellationIsThreadSafe() {
        val correlator = FrameCorrelator()
        val seenIds = ConcurrentHashMap.newKeySet<String>()
        val duplicateDetected = AtomicBoolean(false)
        val emittedCounter = AtomicInteger(0)

        correlator.addListener { event ->
            emittedCounter.incrementAndGet()
            if (!seenIds.add(event.requestId)) {
                duplicateDetected.set(true)
            }
        }

        // Race multiple correlator threads against a shared pool of registered request IDs
        val sharedIds = (1..SHARED_RACE_COUNT).map { "shared-$it" }
        sharedIds.forEach { correlator.register(it) }
        val sharedFrames = sharedIds.map { id ->
            parseFrame("""{"jsonrpc":"2.0","id":"$id","result":{"ok":true}}""")
        }

        val startGate = CountDownLatch(1)
        val racers = List(CONCURRENT_THREADS) { workerIdx ->
            Thread {
                startGate.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                sharedFrames.forEachIndexed { idx, frame ->
                    if (workerIdx == 0 && idx % CANCEL_STRIDE == 0) {
                        correlator.cancel(sharedIds[idx])
                    } else {
                        correlator.correlate(frame)
                    }
                }
                // Also register and correlate thread-private IDs concurrently
                for (i in 1..PRIVATE_REQUESTS_PER_THREAD) {
                    val privateId = "w$workerIdx-p$i"
                    correlator.register(privateId)
                    val frame = parseFrame("""{"jsonrpc":"2.0","id":"$privateId","result":{"v":$i}}""")
                    correlator.correlate(frame)
                }
            }
        }

        racers.forEach { it.start() }
        startGate.countDown()
        racers.forEach { it.join(TEST_TIMEOUT_MS) }

        assertFalse(duplicateDetected.get(), "A request ID must never emit duplicate correlation events")
        assertEquals(0, correlator.pendingCount())
        assertEquals(seenIds.size, emittedCounter.get())
        assertEquals(seenIds.size, correlator.correlatedCount)
        assertTrue(seenIds.size >= CONCURRENT_THREADS * PRIVATE_REQUESTS_PER_THREAD)
    }

    @Test
    fun testReentrantListenerCanRegisterAndCancelWithoutDeadlock() {
        val correlator = FrameCorrelator()
        val followUpMatched = AtomicBoolean(false)

        correlator.addListener { event ->
            if (event.requestId == "first") {
                correlator.register("follow-up") { followUpMatched.set(true) }
                correlator.cancel("unused")
            }
        }

        correlator.register("first")
        correlator.register("unused")

        assertNotNull(correlator.correlate(parseFrame("""{"jsonrpc":"2.0","id":"first","result":{}}""")))
        assertFalse(correlator.contains("unused"))
        assertTrue(correlator.contains("follow-up"))

        assertNotNull(correlator.correlate(parseFrame("""{"jsonrpc":"2.0","id":"follow-up","result":{}}""")))
        assertTrue(followUpMatched.get())
        assertEquals(0, correlator.pendingCount())
    }

    @Test
    fun testRpcCorrelatorOrderedLoopCorrelatesSubmittedFramesInWireOrder() {
        val correlator = RpcCorrelator()
        val receivedOrder = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(ORDERED_FRAME_COUNT)

        correlator.frameCorrelator.addListener { event ->
            receivedOrder.add(event.requestId)
            latch.countDown()
        }

        val expectedIds = (1..ORDERED_FRAME_COUNT).map { "frame-$it" }
        expectedIds.forEach { id -> correlator.frameCorrelator.register(id) }

        correlator.start()
        try {
            expectedIds.forEach { id ->
                correlator.submit(parseFrame("""{"jsonrpc":"2.0","id":"$id","result":{"seq":"$id"}}"""))
            }
            assertTrue(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), "All frames should correlate")
            assertEquals(expectedIds, receivedOrder.toList())
            assertEquals(0, correlator.pendingCount())
        } finally {
            correlator.shutdown()
        }
    }

    companion object {
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_TIMEOUT_MS = 5_000L
        private const val EXPECTED_THREE_EVENTS = 3
        private const val OVERSIZED_REQUEST_ID_LENGTH = 1_025
        private const val LONG_TIMEOUT_NANOS = 60_000_000_000L
        private const val EXPIRED_OFFSET_NANOS = 1_000_000_000L
        private const val SHARED_RACE_COUNT = 100
        private const val CONCURRENT_THREADS = 8
        private const val CANCEL_STRIDE = 5
        private const val PRIVATE_REQUESTS_PER_THREAD = 30
        private const val ORDERED_FRAME_COUNT = 25
    }
}
