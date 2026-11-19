package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {
    @Test
    fun testDelay() {
        assertEquals(1.seconds, ReconnectPolicy.delay(0))
        assertEquals(2.seconds, ReconnectPolicy.delay(1))
        assertEquals(4.seconds, ReconnectPolicy.delay(2))
        assertEquals(8.seconds, ReconnectPolicy.delay(3))
        assertEquals(15.seconds, ReconnectPolicy.delay(4))
        assertEquals(30.seconds, ReconnectPolicy.delay(5))
        assertEquals(30.seconds, ReconnectPolicy.delay(10)) // Cap at last delay
    }

    @Test
    fun testIsQuiet() {
        assertEquals(true, ReconnectPolicy.isQuiet(0))
        assertEquals(true, ReconnectPolicy.isQuiet(1))
        assertEquals(true, ReconnectPolicy.isQuiet(2))
        assertEquals(false, ReconnectPolicy.isQuiet(3))
        assertEquals(false, ReconnectPolicy.isQuiet(10))
    }

    @Test
    fun testSessionStorage() {
        val session = Session(id = "test-id", source = "bighelp", profile = "test-profile")
        ReconnectPolicy.setSession(null)
        assertEquals(null, ReconnectPolicy.getSession())
        
        ReconnectPolicy.setSession(session)
        assertEquals(session, ReconnectPolicy.getSession())
        
        ReconnectPolicy.setSession(null)
        assertEquals(null, ReconnectPolicy.getSession())
    }
}
