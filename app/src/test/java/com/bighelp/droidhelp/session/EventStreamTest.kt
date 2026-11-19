package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Message
import com.bighelp.droidhelp.transport.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class EventStreamTest {

    private class FakeTransport : Transport {
        private var callback: ((Message) -> Unit)? = null

        override fun send(message: Message) {}

        override fun onMessage(callback: (Message) -> Unit) {
            this.callback = callback
        }

        override fun resumeSession(session: com.bighelp.droidhelp.session.Session) {}

        suspend fun emit(message: Message) {
            callback?.invoke(message)
        }
    }

    @Test
    fun `test ordered event delivery`() = runTest {
        val transport = FakeTransport()
        val clock = object : Clock() {
            override fun getZone(): java.time.ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-11-17T10:00:00Z")
        }
        val eventStream = EventStream(transport, this, clock)

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
        val clock = object : Clock() {
            override fun getZone(): java.time.ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-11-17T10:00:00Z")
        }
        val eventStream = EventStream(transport, this, clock)

        val results = mutableListOf<PromptEvent>()
        val job = launch {
            eventStream.submit("test prompt", "test-id").collect {
                results.add(it)
            }
        }

        transport.emit(Message.Clarify("test-id", "What is your name?"))
        advanceUntilIdle()
        assertEquals(1, results.size)
        assertEquals("clarify", results[0].type)

        eventStream.respondToClarify("My name is Alice")
        advanceUntilIdle()

        transport.emit(Message.Receipt("test-id"))
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertEquals("receipt", results[1].type)

        job.cancel()
    }

    @Test
    fun `test timeout handling`() = runTest {
        val transport = FakeTransport()
        val clock = object : Clock() {
            override fun getZone(): java.time.ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-11-17T10:00:00Z")
        }
        val eventStream = EventStream(transport, this, clock, pauseTimeoutMillis = 100L)

        val job = launch {
            eventStream.submit("test prompt", "test-id").collect {}
        }

        transport.emit(Message.Clarify("test-id", "What is your name?"))
        advanceUntilIdle()

        advanceTimeBy(200)
        advanceUntilIdle()

        job.cancel()
    }

    @Test
    fun `test ordering guarantee under concurrent delivery`() = runTest {
        val transport1 = FakeTransport()
        val transport2 = FakeTransport()
        val clock = object : Clock() {
            private var current = Instant.parse("2026-11-17T10:00:00Z")
            override fun getZone(): java.time.ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = current
            fun advance() { current = current.plusSeconds(1) }
        }
        val eventStream = EventStream(transport1, this, clock)

        val results1 = mutableListOf<PromptEvent>()
        val job1 = launch {
            eventStream.submit("test prompt", "test-id").collect {
                results1.add(it)
            }
        }

        val advancingClock = object : Clock() {
            private var current = Instant.parse("2026-11-17T10:00:00Z")
            override fun getZone(): java.time.ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = current
            fun advance() { current = current.plusSeconds(1) }
        }
        
        val streamWithAdvancingClock = EventStream(transport2, this, advancingClock)

        val results2 = mutableListOf<PromptEvent>()
        val job2 = launch {
            streamWithAdvancingClock.submit("test prompt", "test-id").collect {
                results2.add(it)
            }
        }

        advancingClock.advance()
        transport1.emit(Message.Clarify("test-id", "Q1"))
        advanceUntilIdle()
        assertEquals(1, results1.size)
        assertEquals(1, results2.size)

        streamWithAdvancingClock.respondToClarify("A1")
        advanceUntilIdle()

        advancingClock.advance()
        transport2.emit(Message.Receipt("test-id"))
        advanceUntilIdle()

        assertEquals(2, results1.size)
        assertEquals(2, results2.size)
        job1.cancel()
        job2.cancel()
    }
}
