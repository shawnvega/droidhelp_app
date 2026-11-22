package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilitiesClientTest {

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
    fun testStartAdvertisesCapabilities() = runTest {
        val transport = FakeTransport()
        val client = CapabilitiesClient(transport)

        client.start()

        assertEquals(1, transport.sentMessages.size)
        val message = transport.sentMessages[0] as Message.CapabilitiesResponse
        assertEquals("true", message.capabilities["server_requests"])
        assertEquals("1.0", message.capabilities["protocol_version"])
    }

    @Test
    fun testHandleCapabilitiesRequest() = runTest {
        val transport = FakeTransport()
        val client = CapabilitiesClient(transport)
        client.registerHandlers()

        transport.simulateIncoming(Message.CapabilitiesRequest(serverRequests = true))

        assertEquals(1, transport.sentMessages.size)
        val message = transport.sentMessages[0] as Message.CapabilitiesResponse
        assertEquals("true", message.capabilities["server_requests"])
    }
}
