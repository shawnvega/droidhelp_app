package com.bighelp.droidhelp.transport

import kotlinx.coroutines.channels.Channel

/**
 * Thread-safe bounded FIFO queue for storing [ParsedJsonFrame] items decoded from
 * incoming WebSocket frames.
 *
 * When the queue reaches [maxSize], the oldest unread frame is evicted so inbound
 * memory remains strictly bounded and WebSocket readers never stall when no consumer
 * is actively draining the queue.
 */
class ParsedFrameQueue(
    val maxSize: Int
) {
    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private val lock = Any()
    private val deque = ArrayDeque<ParsedJsonFrame>(minOf(maxSize, INITIAL_CAPACITY))
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var droppedCount = 0

    /**
     * Enqueues [frame] at the tail of the bounded FIFO queue.
     * Evicts the oldest frame if the queue is already at [maxSize].
     * @return true if enqueued, false if the queue has been closed
     */
    fun enqueue(frame: ParsedJsonFrame): Boolean {
        val enqueued = synchronized(lock) {
            if (closed) {
                false
            } else {
                if (deque.size >= maxSize) {
                    deque.removeFirst()
                    droppedCount++
                }
                deque.addLast(frame)
                true
            }
        }
        if (enqueued) {
            available.trySend(Unit)
        }
        return enqueued
    }

    /** Non-blocking FIFO dequeue. Returns null when empty. */
    fun poll(): ParsedJsonFrame? = synchronized(lock) {
        if (deque.isEmpty()) null else deque.removeFirst()
    }

    /** Returns the oldest queued frame without removing it, or null when empty. */
    fun peek(): ParsedJsonFrame? = synchronized(lock) {
        deque.firstOrNull()
    }

    /**
     * Suspends until a frame is available and dequeues it in FIFO order,
     * or returns null once the queue is closed and empty.
     */
    suspend fun receive(): ParsedJsonFrame? {
        var result: ParsedJsonFrame? = null
        var finished = false
        while (!finished) {
            val (item, isClosedEmpty) = synchronized(lock) {
                when {
                    deque.isNotEmpty() -> deque.removeFirst() to false
                    closed -> null to true
                    else -> null to false
                }
            }
            if (item != null || isClosedEmpty) {
                result = item
                finished = true
            } else if (available.receiveCatching().isClosed) {
                result = poll()
                finished = true
            }
        }
        return result
    }

    /** Drains all currently queued frames in FIFO order. */
    fun drain(): List<ParsedJsonFrame> = synchronized(lock) {
        if (deque.isEmpty()) {
            emptyList()
        } else {
            val items = deque.toList()
            deque.clear()
            items
        }
    }

    /** Returns the number of frames currently waiting in the queue. */
    fun size(): Int = synchronized(lock) { deque.size }

    /** Returns the number of oldest frames evicted due to reaching [maxSize]. */
    fun droppedCount(): Int = synchronized(lock) { droppedCount }

    /** Returns whether this queue has been closed. */
    fun isClosed(): Boolean = synchronized(lock) { closed }

    /** Removes all currently buffered frames without closing the queue. */
    fun clear() = synchronized(lock) { deque.clear() }

    /** Permanently closes the queue, unblocking any suspended [receive] calls. */
    fun close() {
        synchronized(lock) {
            closed = true
        }
        available.close()
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
    }
}
