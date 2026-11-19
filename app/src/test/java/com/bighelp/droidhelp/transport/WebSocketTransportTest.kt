package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebSocketTransportTest {
    private val endpoint = Endpoint.build(host = "localhost", port = 8080)

    @Test
    fun testResumeSession() = runTest {
        val transport = WebSocketTransport(this, endpoint)
        try {
            val session = Session(id = "test-id", source = "bighelp", profile = "test-profile")
            
            // We can't easily verify the actual connection because it requires a real server,
            // but we can verify that resumeSession doesn't crash and sets up the profile.
            transport.resumeSession(session)
            
            // In a real test, we would check if the connection is initiated.
            // For now, we just ensure no exceptions were thrown.
            assertEquals(session, ReconnectPolicy.getSession())
        } finally {
            transport.disconnect()
        }
    }

    @Test
    fun testConnectInitialState() = runTest {
        val transport = WebSocketTransport(this, endpoint)
        try {
            assertEquals(false, transport.isTransportConnected())
        } finally {
            transport.disconnect()
        }
    }
}
