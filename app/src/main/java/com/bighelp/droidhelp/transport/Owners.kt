package com.bighelp.droidhelp.transport

import kotlinx.serialization.Serializable

@Serializable
data class SignIn(
    val canonicalEndpoint: String,
    val verifiedPrincipal: String,
    val authenticationGeneration: Long
)

@Serializable
data class Owner(
    val signIn: SignIn,
    val workspaceConnectionGeneration: Long
)

@Serializable
data class SocketOwner(
    val owner: Owner,
    val socketEpoch: Long
)

class StaleOwnerException(message: String) : Exception(message)

class Owners {
    private var _currentSignIn: SignIn? = null
    private var _currentOwner: Owner? = null
    private var _currentSocketOwner: SocketOwner? = null

    @Synchronized
    fun getSignIn(): SignIn? = _currentSignIn

    @Synchronized
    fun getOwner(): Owner? = _currentOwner

    @Synchronized
    fun getSocketOwner(): SocketOwner? = _currentSocketOwner

    @Synchronized
    fun updateSignIn(signIn: SignIn) {
        _currentSignIn = signIn
    }

    @Synchronized
    fun updateOwner(owner: Owner) {
        _currentOwner = owner
    }

    @Synchronized
    fun updateSocketOwner(socketOwner: SocketOwner) {
        _currentSocketOwner = socketOwner
    }

    /**
     * Increments the socket epoch for the current owner.
     */
    @Synchronized
    fun incrementSocketEpoch() {
        val owner = _currentOwner ?: return
        val newEpoch = (_currentSocketOwner?.socketEpoch ?: 0L) + 1L
        _currentSocketOwner = SocketOwner(owner, newEpoch)
    }

    /**
     * Validates that the captured owner matches the current owner.
     * @param captured The owner captured before an async operation.
     * @throws StaleOwnerException if there is a mismatch.
     */
    @Synchronized
    fun validateOwner(captured: Owner?) {
        if (captured != _currentOwner) {
            throw StaleOwnerException("Owner mismatch: captured $captured, current $_currentOwner")
        }
    }

    /**
     * Validates that the captured socket owner matches the current socket owner.
     * @param captured The socket owner captured before an async operation.
     * @throws StaleOwnerException if there is a mismatch.
     */
    @Synchronized
    fun validateSocketOwner(captured: SocketOwner?) {
        if (captured != _currentSocketOwner) {
            throw StaleOwnerException("SocketOwner mismatch: captured $captured, current $_currentSocketOwner")
        }
    }
}
