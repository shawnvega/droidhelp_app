package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Message
import com.bighelp.droidhelp.transport.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class EventStreamTest {

    private lateinit var transport: FakeTransport
    private lateinit var eventStream: EventStream
    private val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
    
    private val fixedInstant = Instant.parse("2026-11-17T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = fixedInstant
    }

    @BeforeEach
    fun setup() {
        transport = FakeTransport()
        eventStream = EventStream(transport, testScope, clock)
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `test ordered event delivery`() = runTest {
        val entryId = "test-id"
        val prompt = "test prompt"

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit(prompt, entryId).collect {
                results.add(it)
            }
        }

        // Emit receipt
        transport.emit(Message.Receipt(entryId))
        yield()

        assertEquals(1, results.size)
        assertEquals("receipt", results[0].type)
        
        job.cancel()
    }

    @Test
    fun `test clarify and approval pause resume`() = runTest {
        val entryId = "test-id"
        val prompt = "test prompt"
        // Re-initialize with the current TestScope
        val eventStream = EventStream(transport, this, clock)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit(prompt, entryId).collect {
                results.add(it)
            }
        }

        // 1. Emit clarify
        transport.emit(Message.Clarify(entryId, "What is your name?"))
        advanceUntilIdle()
        assertEquals(1, results.size)
        assertEquals("clarify", results[0].type)

        // 2. Respond to clarify
        eventStream.respondToClarify("My name is Alice")
        advanceUntilIdle()

        // 3. Emit receipt
        transport.emit(Message.Receipt(entryId))
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertEquals("receipt", results[1].type)

        job.cancel()
    }

    private class FakeTransport : Transport {
        private var callback: ((Message) -> Unit)? = null

        override fun send(message: Message) {}

        override fun onMessage(callback: (Message) -> Unit) {
            this.callback = callback
        }

        suspend fun emit(message: Message) {
            callback?.invoke(message)
        }
    }
}
