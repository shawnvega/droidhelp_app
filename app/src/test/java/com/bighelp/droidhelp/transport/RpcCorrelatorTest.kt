package com.bighelp.droidhelp.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RpcCorrelatorTest {

    @Test
    fun testRegisterAndCorrelate() {
        val correlator = RpcCorrelator()
        val requestId = "req-1"
        val message = Message.Ping
        val latch = CountDownLatch(1)
        var receivedMessage: Message? = null

        correlator.register(requestId) { msg ->
            receivedMessage = msg
            latch.countDown()
        }

        correlator.correlate(requestId, message)

        assertTrue("Callback was not called in time", latch.await(2, TimeUnit.SECONDS))
        assertEquals(message, receivedMessage)
        correlator.shutdown()
    }

    @Test
    fun testCancel() {
        val correlator = RpcCorrelator()
        val requestId = "req-cancel"
        val message = Message.Pong
        val latch = CountDownLatch(1)
        var called = false

        correlator.register(requestId) {
            called = true
            latch.countDown()
        }

        correlator.cancel(requestId)
        correlator.correlate(requestId, message)

        // Wait a bit to see if it gets called
        latch.await(500, TimeUnit.MILLISECONDS)
        assertFalse("Callback should not have been called after cancellation", called)
        correlator.shutdown()
    }

    @Test
    fun testCallbackSerialization() {
        val correlator = RpcCorrelator()
        val numCallbacks = 5
        val latch = CountDownLatch(numCallbacks)
        val activeCallbacks = AtomicInteger(0)
        val maxConcurrentObserved = AtomicInteger(0)

        for (i in 1..numCallbacks) {
            val requestId = "req-$i"
            correlator.register(requestId) {
                val current = activeCallbacks.incrementAndGet()
                
                // Check if we are violating serialization
                if (current > 1) {
                    maxConcurrentObserved.set(current)
                }

                Thread.sleep(100) // Simulate work

                activeCallbacks.decrementAndGet()
                latch.countDown()
            }
        }

        // Correlate them all
        for (i in 1..numCallbacks) {
            correlator.correlate("req-$i", Message.Ping)
        }

        assertTrue("Not all callbacks completed", latch.await(5, TimeUnit.SECONDS))
        assertEquals("Callbacks were executed concurrently", 0, maxConcurrentObserved.get())
        correlator.shutdown()
    }

    @Test
    fun testConcurrentAccess() {
        val correlator = RpcCorrelator()
        val numThreads = 10
        val requestsPerThread = 50
        val totalRequests = numThreads * requestsPerThread
        val latch = CountDownLatch(totalRequests)
        val completedCount = AtomicInteger(0)

        val threads = List(numThreads) { threadIdx ->
            Thread {
                for (i in 1..requestsPerThread) {
                    val requestId = "t${threadIdx}-r$i"
                    correlator.register(requestId) {
                        completedCount.incrementAndGet()
                        latch.countDown()
                    }
                    correlator.correlate(requestId, Message.Ping)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("Not all requests were correlated", latch.await(5, TimeUnit.SECONDS))
        assertEquals(totalRequests, completedCount.get())
        correlator.shutdown()
    }

    @Test
    fun testOrderedReaderWriterLoopPreservesOrder() {
        val correlator = RpcCorrelator()
        val count = 20
        val receivedOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(count)

        // Register callbacks that record the order they fire in
        for (i in 1..count) {
            val id = "ordered-$i"
            correlator.register(id) { msg ->
                if (msg is Message.Receipt) {
                    receivedOrder.add(msg.entryId)
                }
                latch.countDown()
            }
        }

        correlator.start()

        // Submit receipts in strict order through the reader/writer loop
        for (i in 1..count) {
            correlator.submit(Message.Receipt("ordered-$i"))
        }

        assertTrue("Not all ordered messages processed", latch.await(5, TimeUnit.SECONDS))
        val expected = (1..count).map { "ordered-$it" }
        assertEquals("Messages were not processed in submission order", expected, receivedOrder.toList())
        correlator.shutdown()
    }

    @Test
    fun testReaderWriterLoopUsesCorrelatorMap() {
        val correlator = RpcCorrelator()
        val latch = CountDownLatch(1)
        var received: Message? = null

        correlator.register("loop-req") { msg ->
            received = msg
            latch.countDown()
        }
        assertEquals(1, correlator.pendingCount())

        correlator.start()
        correlator.submit(Message.Receipt("loop-req"))

        assertTrue("Callback was not invoked by the loop", latch.await(2, TimeUnit.SECONDS))
        assertTrue(received is Message.Receipt)
        assertEquals("loop-req", (received as Message.Receipt).entryId)
        // The pending entry must be removed after correlation
        assertEquals(0, correlator.pendingCount())
        correlator.shutdown()
    }

    @Test
    fun testReaderWriterLoopSerializesCallbacks() {
        val correlator = RpcCorrelator()
        val count = 10
        val latch = CountDownLatch(count)
        val activeCallbacks = AtomicInteger(0)
        val maxConcurrentObserved = AtomicInteger(0)

        for (i in 1..count) {
            val id = "serial-$i"
            correlator.register(id) {
                val current = activeCallbacks.incrementAndGet()
                if (current > 1) {
                    maxConcurrentObserved.set(current)
                }
                Thread.sleep(50) // Simulate work
                activeCallbacks.decrementAndGet()
                latch.countDown()
            }
        }

        correlator.start()
        for (i in 1..count) {
            correlator.submit(Message.Receipt("serial-$i"))
        }

        assertTrue("Not all callbacks completed", latch.await(5, TimeUnit.SECONDS))
        assertEquals("Callbacks executed concurrently in reader/writer loop", 0, maxConcurrentObserved.get())
        correlator.shutdown()
    }

    @Test
    fun testReaderWriterLoopIgnoresUnknownResponses() {
        val correlator = RpcCorrelator()
        correlator.start()
        // Submitting a receipt with no registered request must not throw or block
        correlator.submit(Message.Receipt("unknown-id"))
        correlator.submit(Message.Ping)
        Thread.sleep(200)
        assertEquals(0, correlator.pendingCount())
        correlator.shutdown()
    }
}
