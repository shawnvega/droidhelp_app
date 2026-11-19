package com.bighelp.droidhelp.transport

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    // Ordered message queue for sequential processing
    private val messageQueue = LinkedBlockingQueue<Message>()
    private var processingThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    /** Represents a pending RPC operation with its callback and timeout info. */
    data class PendingOperation(
        val callback: (Message) -> Unit,
        val timeoutNs: Long = System.nanoTime() + 5_000_000_000L, // 5s default timeout
        var error: Exception? = null
    )

    /** Starts the ordered reader/writer loop. */
    fun start() {
        if (isRunning.getAndSet(true)) return
        processingThread = Thread {
            while (isRunning.get()) {
                try {
                    val message = messageQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (message != null) {
                        processMessage(message)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        processingThread?.start()
    }

    /** Stops the reader/writer loop. */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        processingThread?.interrupt()
        processingThread?.join(5000)
        processingThread = null
    }

    /** Submits a message for ordered processing through the reader/writer loop. */
    fun submit(message: Message) {
        messageQueue.offer(message)
    }

    /** Processes a single message in order, correlating responses with pending requests. */
    private fun processMessage(message: Message) {
        if (message is Message.Receipt) {
            correlate(message.entryId, message)
        }
    }

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

    /** Shuts down the correlator, its executor, and the reader/writer loop. */
    fun shutdown() {
        stop()
        callbackExecutor.shutdown()
    }
}
