package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OwnersTest {
    @Test
    fun testFencing() {
        val owners = Owners()
        val signIn = SignIn("https://api.example.com", "user123", 1L)
        val owner = Owner(signIn, 1L)
        val socketOwner = SocketOwner(owner, 1L)

        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        owners.updateSocketOwner(socketOwner)

        assertEquals(signIn, owners.getSignIn())
        assertEquals(owner, owners.getOwner())
        assertEquals(socketOwner, owners.getSocketOwner())

        // Test validation
        owners.validateOwner(owner)
        owners.validateSocketOwner(socketOwner)

        // Test increment epoch
        owners.incrementSocketEpoch()
        val newSocketOwner = owners.getSocketOwner()
        assertEquals(2L, newSocketOwner?.socketEpoch)
        assertEquals(owner, newSocketOwner?.owner)

        // Test stale detection
        try {
            owners.validateSocketOwner(socketOwner) // This should fail because current is now epoch 2
            throw AssertionError("Should have thrown StaleOwnerException")
        } catch (e: StaleOwnerException) {
            // Success
        }
    }
}
