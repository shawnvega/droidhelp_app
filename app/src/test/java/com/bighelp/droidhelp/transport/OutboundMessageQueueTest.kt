package com.bighelp.droidhelp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OutboundMessageQueueTest {

    @Test
    fun testRejectsNonPositiveMaxSize() {
        assertThrows(IllegalArgumentException::class.java) {
            OutboundMessageQueue<Message>(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OutboundMessageQueue<Message>(-1)
        }
    }

    @Test
    fun testFifoEnqueuePeekDequeueDrainAndClear() {
        val queue = OutboundMessageQueue<Message>(maxSize = CAPACITY_FOUR)
        val first = Message.Prompt("first", "1")
        val second = Message.Prompt("second", "2")
        val third = Message.Prompt("third", "3")

        assertTrue(queue.isEmpty())
        assertFalse(queue.isFull())
        assertTrue(queue.enqueueOutgoing(first))
        assertTrue(queue.enqueueOutgoing(second))
        assertTrue(queue.enqueueOutgoing(third))

        assertEquals(COUNT_THREE, queue.size())
        assertFalse(queue.isEmpty())
        assertEquals(first, queue.peek())

        assertEquals(first, queue.dequeueOutgoing())
        assertEquals(2, queue.size())
        assertEquals(listOf(second, third), queue.drain())
        assertTrue(queue.isEmpty())
        assertNull(queue.dequeueOutgoing())

        assertTrue(queue.enqueueOutgoing(Message.Ping))
        queue.clear()
        assertTrue(queue.isEmpty())
        assertNull(queue.peek())
    }

    @Test
    fun testSuspendPolicyRejectsNonBlockingWhenFullWithoutDropping() {
        val queue = OutboundMessageQueue<Message>(
            maxSize = 2,
            overflowPolicy = OutboundMessageQueue.OverflowPolicy.SUSPEND
        )
        val first = Message.Prompt("one", "1")
        val second = Message.Prompt("two", "2")
        val third = Message.Prompt("three", "3")

        assertTrue(queue.enqueueOutgoing(first))
        assertTrue(queue.enqueueOutgoing(second))
        assertTrue(queue.isFull())

        assertFalse(queue.enqueueOutgoing(third))
        assertEquals(2, queue.size())
        assertEquals(0, queue.droppedCount())
        assertEquals(listOf(first, second), queue.drain())
    }

    @Test
    fun testDropOldestPolicyEvictsHeadWhenFull() {
        val queue = OutboundMessageQueue<Message>(
            maxSize = 2,
            overflowPolicy = OutboundMessageQueue.OverflowPolicy.DROP_OLDEST
        )
        val first = Message.Prompt("1", "1")
        val second = Message.Prompt("2", "2")
        val third = Message.Prompt("3", "3")
        val fourth = Message.Prompt("4", "4")

        assertTrue(queue.enqueueOutgoing(first))
        assertTrue(queue.enqueueOutgoing(second))
        assertTrue(queue.enqueueOutgoing(third))
        assertTrue(queue.enqueueOutgoing(fourth))

        assertEquals(2, queue.size())
        assertEquals(2, queue.droppedCount())
        assertEquals(listOf(third, fourth), queue.drain())
    }

    @Test
    fun testDropLatestPolicyDropsIncomingWhenFull() {
        val queue = OutboundMessageQueue<Message>(
            maxSize = 2,
            overflowPolicy = OutboundMessageQueue.OverflowPolicy.DROP_LATEST
        )
        val first = Message.Prompt("1", "1")
        val second = Message.Prompt("2", "2")
        val third = Message.Prompt("3", "3")

        assertTrue(queue.enqueueOutgoing(first))
        assertTrue(queue.enqueueOutgoing(second))
        assertFalse(queue.enqueueOutgoing(third))

        assertEquals(2, queue.size())
        assertEquals(1, queue.droppedCount())
        assertEquals(listOf(first, second), queue.drain())
    }

    @Test
    fun testPerCallOverflowPolicyOverride() {
        val queue = OutboundMessageQueue<Message>(
            maxSize = 2,
            overflowPolicy = OutboundMessageQueue.OverflowPolicy.SUSPEND
        )
        val first = Message.Prompt("1", "1")
        val second = Message.Prompt("2", "2")
        val third = Message.Prompt("3", "3")
        val fourth = Message.Prompt("4", "4")

        assertTrue(queue.enqueueOutgoing(first))
        assertTrue(queue.enqueueOutgoing(second))
        assertFalse(queue.enqueueOutgoing(third, OutboundMessageQueue.OverflowPolicy.DROP_LATEST))
        assertEquals(1, queue.droppedCount())

        assertTrue(queue.enqueueOutgoing(fourth, OutboundMessageQueue.OverflowPolicy.DROP_OLDEST))
        assertEquals(2, queue.droppedCount())
        assertEquals(listOf(second, fourth), queue.drain())
    }

    @Test
    fun testSuspendingEnqueueAppliesBackpressureAndChainWakesMultipleWaiters() = runTest {
        val queue = OutboundMessageQueue<Message>(
            maxSize = 2,
            overflowPolicy = OutboundMessageQueue.OverflowPolicy.SUSPEND
        )
        val m1 = Message.Prompt("1", "1")
        val m2 = Message.Prompt("2", "2")
        val m3 = Message.Prompt("3", "3")
        val m4 = Message.Prompt("4", "4")

        assertTrue(queue.enqueueOutgoing(m1))
        assertTrue(queue.enqueueOutgoing(m2))

        val waiter1 = async { queue.enqueueOutgoingSuspending(m3) }
        val waiter2 = async { queue.enqueueOutgoingSuspending(m4) }
        testScheduler.runCurrent()
        assertFalse(waiter1.isCompleted)
        assertFalse(waiter2.isCompleted)

        // Draining both items wakes waiter1, which chain-wakes waiter2
        assertEquals(listOf(m1, m2), queue.drain())
        testScheduler.runCurrent()

        assertTrue(waiter1.await())
        assertTrue(waiter2.await())
        assertEquals(setOf(m3, m4), queue.drain().toSet())
    }

    @Test
    fun testSuspendingDequeueAndCloseUnblocksProducersAndConsumers() = runTest {
        val queue = OutboundMessageQueue<Message>(maxSize = 1)
        val expected = Message.Prompt("async", "a1")

        val consumer = async { queue.dequeueOutgoingSuspending() }
        testScheduler.runCurrent()
        assertFalse(consumer.isCompleted)

        assertTrue(queue.enqueueOutgoing(expected))
        assertEquals(expected, consumer.await())

        // Fill queue and suspend a producer, then close
        assertTrue(queue.enqueueOutgoing(Message.Ping))
        val blockedProducer = async { queue.enqueueOutgoingSuspending(Message.Pong) }
        testScheduler.runCurrent()
        assertFalse(blockedProducer.isCompleted)

        queue.close()
        assertTrue(queue.isClosed())
        assertFalse(blockedProducer.await())
        assertFalse(queue.enqueueOutgoing(Message.Ready))

        // Already-queued item remains drainable after close; subsequent suspending dequeue returns null
        assertEquals(Message.Ping, queue.dequeueOutgoingSuspending())
        assertNull(queue.dequeueOutgoingSuspending())
    }

    @Test
    fun testConcurrentProducersAndConsumersRespectCapacityAndConservation() : Unit = runBlocking(Dispatchers.Default) {
        val queue = OutboundMessageQueue<Int>(
            maxSize = CONCURRENT_CAPACITY,
            overflowPolicy = OutboundMessageQueue.OverflowPolicy.SUSPEND
        )
        val consumed = CopyOnWriteArrayList<Int>()
        val maxObservedSize = AtomicInteger(0)

        coroutineScope {
            val producers = (0 until PRODUCER_COUNT).map { producerId ->
                async {
                    repeat(ITEMS_PER_PRODUCER) { offset ->
                        val value = producerId * ITEMS_PER_PRODUCER + offset
                        assertTrue(queue.enqueueOutgoingSuspending(value))
                        val sizeNow = queue.size()
                        maxObservedSize.updateAndGet { prev -> maxOf(prev, sizeNow) }
                    }
                }
            }
            val consumers = (0 until CONSUMER_COUNT).map {
                async {
                    while (true) {
                        val item = queue.dequeueOutgoingSuspending() ?: break
                        consumed.add(item)
                    }
                }
            }

            producers.awaitAll()
            queue.close()
            consumers.awaitAll()
        }

        val totalExpected = PRODUCER_COUNT * ITEMS_PER_PRODUCER
        assertEquals(totalExpected, consumed.size)
        assertEquals((0 until totalExpected).toSet(), consumed.toSet())
        assertTrue(maxObservedSize.get() <= CONCURRENT_CAPACITY)
        assertEquals(0, queue.size())
    }

    companion object {
        private const val CAPACITY_FOUR = 4
        private const val COUNT_THREE = 3
        private const val CONCURRENT_CAPACITY = 8
        private const val PRODUCER_COUNT = 4
        private const val CONSUMER_COUNT = 3
        private const val ITEMS_PER_PRODUCER = 50
    }
}
