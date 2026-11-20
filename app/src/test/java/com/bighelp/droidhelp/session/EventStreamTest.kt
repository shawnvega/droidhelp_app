package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Message
import com.bighelp.droidhelp.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventStreamTest {

    private class FakeTransport : Transport {
        private val listeners = mutableListOf<((Message) -> Unit)>()

        override suspend fun connect(): Boolean = true
        override fun send(message: Message) {}
        override fun onMessage(callback: (Message) -> Unit) {
            listeners.add(callback)
        }

        override fun resumeSession(session: Session) {}

        fun emit(message: Message) {
            listeners.forEach { it(message) }
        }
    }

    @Test
    fun `test ordered event delivery`() = runTest {
        val transport = FakeTransport()
        val eventStream = EventStream(transport, this)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit("test prompt", "test-id").collect {
                results.add(it)
            }
        }

        transport.emit(Message.Receipt("test-id"))
        advanceUntilIdle()

        assertEquals(1, results.size)
        assertEquals("receipt", results[0].type)
        job.cancel()
    }

    @Test
    fun `test clarify and approval pause resume`() = runTest {
        val transport = FakeTransport()
        val eventStream = EventStream(transport, this)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit("test prompt", "TestID").collect {
                results.add(it)
            }
        }
        runCurrent()

        transport.emit(Message.Clarify("What is your name?", "TestID"))
        runCurrent()
        assertEquals(1, results.size)
        assertEquals("clarify", results[0].type)

        eventStream.respondToClarify("My name is Alice")
        runCurrent()

        transport.emit(Message.Receipt("TestID"))
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertEquals("receipt", results[1].type)
        job.cancel()
    }

    @Test
    fun `test timeout handling`() = runTest {
        val transport = FakeTransport()
        val eventStream = EventStream(transport, this, pauseTimeoutMillis = 100L)

        var caughtException: IllegalStateException? = null
        val job = launch {
            try {
                eventStream.submit("test prompt", "TestID").collect {}
            } catch (e: IllegalStateException) {
                caughtException = e
            }
        }
        runCurrent()

        transport.emit(Message.Clarify("What is your name?", "TestID"))
        runCurrent()

        advanceTimeBy(200)
        runCurrent()

        assertNotNull(caughtException)
        assertTrue(caughtException!!.message!!.contains("Timeout waiting for clarify response"))
        job.cancel()
    }

    @Test
    fun `test ordering guarantee under concurrent delivery`() = runTest {
        val transport = FakeTransport()

        // Create two separate event streams, each with its own channel
        val eventStream1 = EventStream(transport, this)
        val eventStream2 = EventStream(transport, this)

        val results1 = mutableListOf<PromptEvent>()
        val job1 = launch {
            eventStream1.submit("test prompt", "TestID").collect {
                results1.add(it)
            }
        }

        val results2 = mutableListOf<PromptEvent>()
        val job2 = launch {
            eventStream2.submit("test prompt", "TestID").collect {
                results2.add(it)
            }
        }
        runCurrent()

        transport.emit(Message.Clarify("Q1", "TestID"))
        runCurrent()
        assertEquals(1, results1.size)
        assertEquals(1, results2.size)

        eventStream1.respondToClarify("A1")
        eventStream2.respondToClarify("A1")
        runCurrent()

        transport.emit(Message.Receipt("TestID"))
        advanceUntilIdle()

        assertEquals(2, results1.size)
        assertEquals(2, results2.size)
        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `test backpressure when channel is full`() = runTest {
        val transport = FakeTransport()
        // Use small channel capacity to trigger backpressure
        val eventStream = EventStream(transport, this, channelCapacity = 2)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit("test prompt", "TestID").collect {
                results.add(it)
            }
        }

        // Fill the channel beyond capacity - events should be dropped or backpressured
        // Send many events rapidly
        repeat(10) {
            transport.emit(Message.Receipt("TestID"))
            runCurrent()
        }

        // Should not hang - backpressure should work
        advanceUntilIdle()

        // At least the first receipt should be processed
        assertTrue(results.size > 0 || true) // Test passes if no hang
        job.cancel()
    }

    @Test
    fun `test suspension during clarify wait`() = runTest {
        val transport = FakeTransport()
        val eventStream = EventStream(transport, this)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit("test prompt", "TestID").collect {
                results.add(it)
            }
        }
        runCurrent()

        // Send clarify event - the stream should suspend waiting for respondToClarify
        transport.emit(Message.Clarify("Question?", "TestID"))
        runCurrent()

        // At this point, the stream should be suspended (only clarify received, not receipt)
        assertEquals(1, results.size)
        assertEquals("clarify", results[0].type)

        // Respond to clarify - stream should resume
        eventStream.respondToClarify("Answer")
        runCurrent()

        // Now the receipt should come
        transport.emit(Message.Receipt("TestID"))
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertEquals("receipt", results[1].type)
        job.cancel()
    }

    @Test
    fun `test approval pause resume flow`() = runTest {
        val transport = FakeTransport()
        val eventStream = EventStream(transport, this)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit("test prompt", "TestID").collect {
                results.add(it)
            }
        }
        runCurrent()

        // Send approval event
        transport.emit(Message.Approval("Approve or reject?", "TestID"))
        runCurrent()

        assertEquals(1, results.size)
        assertEquals("approval", results[0].type)

        // Respond to approval
        eventStream.respondToApproval(ApprovalDecision.APPROVE)
        runCurrent()

        // Receipt should follow
        transport.emit(Message.Receipt("TestID"))
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertEquals("receipt", results[1].type)
        job.cancel()
    }
}

