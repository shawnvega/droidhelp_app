package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Callback was not called in time")
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
        assertFalse(called, "Callback should not have been called after cancellation")
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

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all callbacks completed")
        assertEquals(0, maxConcurrentObserved.get(), "Callbacks were executed concurrently")
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

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all requests were correlated")
        assertEquals(totalRequests, completedCount.get())
        correlator.shutdown()
    }
}
