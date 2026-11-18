package com.bighelp.droidhelp.transport

import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Correlates RPC requests and responses using request IDs.
 * Ensures thread-safe map access and serialized callback execution.
 */
class RpcCorrelator {
    private val lock = ReentrantReadWriteLock(true) // Fair lock for consistent ordering
    private val pendingRequests = mutableMapOf<String, (Message) -> Unit>()
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /**
     * Registers a callback for a given request ID.
     */
    fun register(requestId: String, callback: (Message) -> Unit) {
        lock.writeLock().withLock {
            pendingRequests[requestId] = callback
        }
    }

    /**
     * Correlates a response message with a pending request by its ID.
     * The callback is executed on a single-threaded executor to ensure serialization.
     */
    fun correlate(requestId: String, message: Message) {
        val callback = lock.writeLock().withLock {
            pendingRequests.remove(requestId)
        }
        callback?.let {
            callbackExecutor.submit {
                it(message)
            }
        }
    }

    /**
     * Cancels a pending request.
     */
    fun cancel(requestId: String) {
        lock.writeLock().withLock {
            pendingRequests.remove(requestId)
        }
    }

    /**
     * Shuts down the correlator and its executor.
     */
    fun shutdown() {
        callbackExecutor.shutdown()
    }

    /**
     * Returns the number of pending requests.
     */
    fun pendingCount(): Int {
        return lock.readLock().withLock {
            pendingRequests.size
        }
    }
}
