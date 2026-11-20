package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest
import com.bighelp.droidhelp.session.Session

class UnsupportedMethodIntegrationTest {

    private class FakeTransport : Transport {
        val sentMessages = mutableListOf<Message>()
        var onMessageCallback: ((Message) -> Unit)? = null

        override suspend fun connect(): Boolean = true
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

    @Test
    fun testCapabilitiesClientRejectsUnsupportedMessage() = runTest {
        val transport = FakeTransport()
        val client = CapabilitiesClient(transport)
        client.registerHandlers()

        // Simulate an unsupported message (e.g., a Ping)
        transport.simulateIncoming(Message.Ping)

        assertEquals(1, transport.sentMessages.size)
        assertTrue(transport.sentMessages[0] is UnsupportedMethod)
        assertEquals("Ping", (transport.sentMessages[0] as UnsupportedMethod).method)
    }

    @Test
    fun testHeartbeatRejectsUnsupportedMessage() = runTest {
        val transport = FakeTransport()
        val heartbeat = Heartbeat(
            transport = transport,
            pingIntervalMillis = 1000,
            scope = this
        )
        heartbeat.start()
        
        // Simulate an unsupported message (e.g., a CapabilitiesRequest)
        transport.simulateIncoming(Message.CapabilitiesRequest(serverRequests = true))
        
        assertEquals(2, transport.sentMessages.size)
        assertTrue(transport.sentMessages[0] is Message.Ready)
        assertTrue(transport.sentMessages[1] is UnsupportedMethod)
        assertEquals("CapabilitiesRequest", (transport.sentMessages[1] as UnsupportedMethod).method)
        
        heartbeat.stop()
    }
}
