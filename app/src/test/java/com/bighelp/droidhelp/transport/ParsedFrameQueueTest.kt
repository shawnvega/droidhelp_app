package com.bighelp.droidhelp.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedFrameQueueTest {

    @Test
    fun testRejectsNonPositiveMaxSize() {
        assertThrows(IllegalArgumentException::class.java) {
            ParsedFrameQueue(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParsedFrameQueue(-1)
        }
    }

    @Test
    fun testFifoEnqueuePeekPollAndDrain() {
        val queue = ParsedFrameQueue(maxSize = QUEUE_CAPACITY)
        val first = sampleFrame("1")
        val second = sampleFrame("2")
        val third = sampleFrame("3")

        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(second))
        assertTrue(queue.enqueue(third))
        assertEquals(EXPECTED_THREE_ITEMS, queue.size())
        assertEquals(first, queue.peek())

        assertEquals(first, queue.poll())
        assertEquals(2, queue.size())
        assertEquals(listOf(second, third), queue.drain())
        assertEquals(0, queue.size())
        assertNull(queue.poll())
    }

    @Test
    fun testBoundedQueueEvictsOldestWhenAtCapacity() {
        val queue = ParsedFrameQueue(maxSize = 2)
        val first = sampleFrame("1")
        val second = sampleFrame("2")
        val third = sampleFrame("3")
        val fourth = sampleFrame("4")

        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(second))
        assertTrue(queue.enqueue(third))
        assertTrue(queue.enqueue(fourth))

        assertEquals(2, queue.size())
        assertEquals(2, queue.droppedCount())
        assertEquals(listOf(third, fourth), queue.drain())
    }

    @Test
    fun testReceiveSuspendsUntilEnqueuedAndUnblocksOnClose() = runTest {
        val queue = ParsedFrameQueue(maxSize = QUEUE_CAPACITY)
        val expected = sampleFrame("async-1")

        val receiver = async { queue.receive() }
        testScheduler.runCurrent()
        assertFalse(receiver.isCompleted)

        assertTrue(queue.enqueue(expected))
        assertEquals(expected, receiver.await())

        val waitingForClose = async { queue.receive() }
        testScheduler.runCurrent()
        assertFalse(waitingForClose.isCompleted)

        queue.close()
        assertTrue(queue.isClosed())
        assertNull(waitingForClose.await())
        assertFalse(queue.enqueue(sampleFrame("after-close")))
    }

    @Test
    fun testClosePreservesAlreadyQueuedFramesForDrain() = runTest {
        val queue = ParsedFrameQueue(maxSize = QUEUE_CAPACITY)
        val queued = sampleFrame("queued-before-close")
        assertTrue(queue.enqueue(queued))

        queue.close()
        assertEquals(queued, queue.receive())
        assertNull(queue.receive())
    }

    private fun sampleFrame(id: String): ParsedJsonFrame {
        val obj = JsonObject(mapOf("id" to JsonPrimitive(id)))
        return ParsedJsonFrame(
            headers = mapOf("x-id" to id),
            payload = obj,
            rawObject = obj,
            byteSize = obj.toString().toByteArray(Charsets.UTF_8).size
        )
    }

    companion object {
        private const val QUEUE_CAPACITY = 4
        private const val EXPECTED_THREE_ITEMS = 3
    }
}
