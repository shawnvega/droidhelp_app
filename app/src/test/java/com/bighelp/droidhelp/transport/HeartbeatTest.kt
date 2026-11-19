package com.bighelp.droidhelp.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.bighelp.droidhelp.session.Session

class HeartbeatTest {

    private class FakeTransport : Transport {
        val sentMessages = mutableListOf<Message>()
        var onMessageCallback: ((Message) -> Unit)? = null

        override fun send(message: Message) {
            sentMessages.add(message)
        }

        override fun onMessage(callback: (Message) -> Unit) {
            onMessageCallback = callback
        }

        override fun resumeSession(session: Session) {}

        fun simulateIncoming(message: Message) {
            onMessageCallback?.invoke(message)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testPingSendsAtCorrectInterval() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(transport, 100L, scope = this, currentTimeMillis = { testScheduler.currentTime })

        heartbeat.start()
        
        // Advance time by 100ms
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        
        // Should have sent Ready and then ~1 ping
        assertTrue(transport.sentMessages.size >= 2)
        assertTrue(transport.sentMessages[0] is Message.Ready)
        assertTrue(transport.sentMessages.drop(1).all { it is Message.Ping })
        
        heartbeat.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testPongRespondsToPing() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(transport, 100L, scope = this, currentTimeMillis = { testScheduler.currentTime })

        heartbeat.start()
        
        // Simulate incoming ping
        transport.simulateIncoming(Message.Ping)
        testScheduler.runCurrent()
        
        // Check if pong was sent
        assertTrue(transport.sentMessages.last() is Message.Pong)
        
        heartbeat.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testTimeoutDetection() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(transport, 100L, timeoutTolerance = 2.0, scope = this, currentTimeMillis = { testScheduler.currentTime })

        var timeoutDetected = false
        heartbeat.onTimeout = {
            timeoutDetected = true
        }

        heartbeat.start()

        // Advance time by 250ms
        testScheduler.advanceTimeBy(250)
        testScheduler.runCurrent()

        assertTrue(timeoutDetected)
        
        heartbeat.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testStartStopLifecycle() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(transport, 100L, scope = this, currentTimeMillis = { testScheduler.currentTime })

        heartbeat.start()
        heartbeat.stop()

        // Advance time by 150ms
        testScheduler.advanceTimeBy(150)
        testScheduler.runCurrent()

        val countAfterStop = transport.sentMessages.size
        
        // Advance more time
        testScheduler.advanceTimeBy(150)
        testScheduler.runCurrent()

        assertEquals(countAfterStop, transport.sentMessages.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testReadySendsOnStart() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(transport, 100L, scope = this, currentTimeMillis = { testScheduler.currentTime })

        heartbeat.start()
        
        // The first message sent should be Ready
        assertTrue(transport.sentMessages.isNotEmpty())
        assertTrue(transport.sentMessages[0] is Message.Ready)
        
        heartbeat.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testReadyIsHandled() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(transport, 100L, scope = this, currentTimeMillis = { testScheduler.currentTime })

        heartbeat.start()
        
        // Simulate incoming Ready
        transport.simulateIncoming(Message.Ready)
        testScheduler.runCurrent()
        
        // Should not have sent any UnsupportedMethod
        assertTrue(transport.sentMessages.none { it is UnsupportedMethod })
        
        heartbeat.stop()
    }
}
