package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OwnersTest {

    private lateinit var owners: Owners

    @BeforeEach
    fun setUp() {
        owners = Owners()
    }

    @Test
    fun testValidTransitions() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        assertEquals(signIn, owners.getSignIn())

        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        assertEquals(owner, owners.getOwner())

        val socketOwner = SocketOwner(owner, 1L)
        owners.updateSocketOwner(socketOwner)
        assertEquals(socketOwner, owners.getSocketOwner())
    }

    @Test
    fun testIncrementSocketEpoch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        
        // Initial socket owner should be null or we set it
        val initialSocketOwner = SocketOwner(owner, 0L)
        owners.updateSocketOwner(initialSocketOwner)
        
        owners.incrementSocketEpoch()
        val updatedSocketOwner = owners.getSocketOwner()
        assertEquals(1L, updatedSocketOwner?.socketEpoch)
        assertEquals(owner, updatedSocketOwner?.owner)
    }

    @Test
    fun testValidateOwnerSuccess() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)

        owners.validateOwner(owner) // Should not throw
    }

    @Test
    fun testValidateOwnerFailure() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner1 = Owner(signIn, 1L)
        owners.updateOwner(owner1)

        val owner2 = Owner(signIn, 2L)
        
        assertThrows(StaleOwnerException::class.java) {
            owners.validateOwner(owner2)
        }
    }

    @Test
    fun testValidateSocketOwnerSuccess() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        val socketOwner = SocketOwner(owner, 5L)
        owners.updateSocketOwner(socketOwner)

        owners.validateSocketOwner(socketOwner) // Should not throw
    }

    @Test
    fun testValidateSocketOwnerFailure() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        val socketOwner1 = SocketOwner(owner, 5L)
        owners.updateSocketOwner(socketOwner1)

        val socketOwner2 = SocketOwner(owner, 6L)

        assertThrows(StaleOwnerException::class.java) {
            owners.validateSocketOwner(socketOwner2)
        }
    }

    @Test
    fun testSocketMismatch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        val socketOwner = SocketOwner(owner, 5L)
        owners.updateSocketOwner(socketOwner)

        // Capture a different socket owner
        val staleSocketOwner = SocketOwner(owner, 4L)
        
        assertThrows(StaleOwnerException::class.java) {
            owners.validateSocketOwner(staleSocketOwner)
        }
    }
    
    @Test
    fun testOwnerMismatch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner1 = Owner(signIn, 1L)
        owners.updateOwner(owner1)

        val owner2 = Owner(signIn, 2L)
        
        assertThrows(StaleOwnerException::class.java) {
            owners.validateOwner(owner2)
        }
    }
}
