package com.bighelp.droidhelp.transport

import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/** Correlates RPC requests and responses using request IDs.
 * Ensures thread-safe map access and serialized callback execution.
 * Supports pending operation tracking with timeout and error handling.
 */
class RpcCorrelator {
    private val lock = ReentrantReadWriteLock(true) // Fair lock for consistent ordering
    private val pendingRequests = mutableMapOf<String, PendingOperation>()
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /** Represents a pending RPC operation with its callback and timeout info. */
    data class PendingOperation(
        val callback: (Message) -> Unit,
        val timeoutNs: Long = System.nanoTime() + 5_000_000_000L, // 5s default timeout
        var error: Exception? = null
    )

    /** Registers a callback for a given request ID. */
    fun register(requestId: String, callback: (Message) -> Unit) {
        lock.writeLock().withLock {
            pendingRequests[requestId] = PendingOperation(callback)
        }
    }

    /** Looks up a pending operation by request ID without removing it. */
    fun lookup(requestId: String): ((Message) -> Unit)? {
        return lock.readLock().withLock {
            pendingRequests[requestId]?.callback
        }
    }

    /** Updates the callback for an existing pending request. */
    fun update(requestId: String, newCallback: (Message) -> Unit) {
        lock.writeLock().withLock {
            val existing = pendingRequests[requestId]
            if (existing != null) {
                pendingRequests[requestId] = existing.copy(
                    callback = newCallback,
                    timeoutNs = System.nanoTime() + 5_000_000_000L
                )
            }
        }
    }

    /** Correlates a response message with a pending request by its ID.
     * The callback is executed on a single-threaded executor to ensure serialization.
     * Returns true if correlation succeeded (request was found and removed), false otherwise.
     */
    fun correlate(requestId: String, message: Message): Boolean {
        return lock.writeLock().withLock {
            val pending = pendingRequests.remove(requestId)
            pending?.callback?.let {
                callbackExecutor.submit { it(message) }
            }
            pending != null
        }
    }

    /** Cancels a pending request.
     * Removes the request from the map and returns true if it was found, false otherwise.
     */
    fun cancel(requestId: String): Boolean {
        return lock.writeLock().withLock {
            pendingRequests.remove(requestId) != null
        }
    }

    /** Checks if a request ID has a pending operation. */
    fun contains(requestId: String): Boolean {
        return lock.readLock().withLock {
            pendingRequests.containsKey(requestId)
        }
    }

    /** Returns the number of pending requests. */
    fun pendingCount(): Int {
        return lock.readLock().withLock {
            pendingRequests.size
        }
    }

    /** Removes all pending requests that have timed out. */
    fun cleanupTimeouts(): Int {
        return lock.writeLock().withLock {
            val now = System.nanoTime()
            val toRemove = pendingRequests.filter { (_, pending) ->
                pending.timeoutNs <= now
            }.keys.toList()
            toRemove.forEach { pendingRequests.remove(it) }
            toRemove.size
        }
    }

    /** Shuts down the correlator and its executor. */
    fun shutdown() {
        callbackExecutor.shutdown()
    }
}
