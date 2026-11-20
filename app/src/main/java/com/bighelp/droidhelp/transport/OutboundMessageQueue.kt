package com.bighelp.droidhelp.transport

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe bounded FIFO outbound message queue backed by [ConcurrentLinkedQueue].
 *
 * Enforces a strict maximum capacity ([maxSize]) and respects backpressure by either
 * suspending / rejecting when full ([OverflowPolicy.SUSPEND]) or optionally dropping
 * queued/incoming messages ([OverflowPolicy.DROP_OLDEST], [OverflowPolicy.DROP_LATEST]).
 */
class OutboundMessageQueue<T : Any>(
    val maxSize: Int,
    val overflowPolicy: OverflowPolicy = OverflowPolicy.SUSPEND
) {
    /** Strategy applied when attempting to enqueue into a queue at [maxSize]. */
    enum class OverflowPolicy {
        /**
         * Respect backpressure: non-blocking [enqueueOutgoing] rejects without dropping (`false`),
         * and [enqueueOutgoingSuspending] suspends until capacity is freed by a consumer.
         */
        SUSPEND,

        /** Evict the oldest queued item to make room for the incoming item when full. */
        DROP_OLDEST,

        /** Drop the incoming item when full (increments [droppedCount] and returns `false`). */
        DROP_LATEST
    }

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private val queue = ConcurrentLinkedQueue<T>()
    private val capacityLock = Any()
    private val currentSize = AtomicInteger(0)
    private val droppedCounter = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    // Conflated signals for waking suspended consumers and producers
    private val itemAvailable = Channel<Unit>(Channel.CONFLATED)
    private val spaceAvailable = Channel<Unit>(Channel.CONFLATED)

    /**
     * Attempts to enqueue [item] at the tail of the outbound FIFO queue without suspending.
     *
     * @param item The outgoing item to enqueue.
     * @param policy Overflow policy for this call (defaults to this queue's [overflowPolicy]).
     * @return `true` if [item] was enqueued, or `false` if the queue is closed, full under
     *   [OverflowPolicy.SUSPEND], or dropped under [OverflowPolicy.DROP_LATEST].
     */
    fun enqueueOutgoing(item: T, policy: OverflowPolicy = overflowPolicy): Boolean {
        val enqueued = synchronized(capacityLock) {
            when {
                closed.get() -> false
                currentSize.get() < maxSize -> {
                    queue.offer(item)
                    currentSize.incrementAndGet()
                    true
                }
                policy == OverflowPolicy.DROP_OLDEST -> {
                    if (queue.poll() != null) {
                        currentSize.decrementAndGet()
                        droppedCounter.incrementAndGet()
                    }
                    queue.offer(item)
                    currentSize.incrementAndGet()
                    true
                }
                policy == OverflowPolicy.DROP_LATEST -> {
                    droppedCounter.incrementAndGet()
                    false
                }
                else -> false
            }
        }
        if (enqueued) {
            itemAvailable.trySend(Unit)
        }
        return enqueued
    }

    /**
     * Enqueues [item] respecting backpressure. Under [OverflowPolicy.SUSPEND], suspends until
     * a consumer frees space or the queue is closed. Under drop policies, delegates immediately
     * to [enqueueOutgoing].
     *
     * @return `true` if [item] was enqueued, or `false` if closed or dropped under
     *   [OverflowPolicy.DROP_LATEST].
     */
    suspend fun enqueueOutgoingSuspending(item: T): Boolean {
        if (overflowPolicy != OverflowPolicy.SUSPEND) {
            return enqueueOutgoing(item, overflowPolicy)
        }
        var enqueued = false
        var finished = false
        while (!finished) {
            val (added, hasMoreSpace, isClosed) = synchronized(capacityLock) {
                when {
                    closed.get() -> Triple(false, false, true)
                    currentSize.get() < maxSize -> {
                        queue.offer(item)
                        val newSize = currentSize.incrementAndGet()
                        Triple(true, newSize < maxSize, false)
                    }
                    else -> Triple(false, false, false)
                }
            }
            if (added) {
                itemAvailable.trySend(Unit)
                if (hasMoreSpace) {
                    spaceAvailable.trySend(Unit)
                }
                enqueued = true
                finished = true
            } else if (isClosed || spaceAvailable.receiveCatching().isClosed) {
                finished = true
            }
        }
        return enqueued
    }

    /**
     * Non-blocking FIFO dequeue of the next outgoing item.
     * Signals waiting producers when an item is removed to relieve backpressure.
     *
     * @return The next outgoing item, or `null` if the queue is empty.
     */
    fun dequeueOutgoing(): T? {
        val item = synchronized(capacityLock) {
            queue.poll()?.also {
                currentSize.decrementAndGet()
            }
        }
        if (item != null) {
            spaceAvailable.trySend(Unit)
        }
        return item
    }

    /**
     * Suspends until an outgoing item is available and dequeues it in FIFO order,
     * relieving backpressure for waiting producers, or returns `null` once closed and drained.
     */
    suspend fun dequeueOutgoingSuspending(): T? {
        var result: T? = null
        var finished = false
        while (!finished) {
            val (item, hasMoreItems, isClosedEmpty) = synchronized(capacityLock) {
                val polled = queue.poll()
                when {
                    polled != null -> {
                        val remaining = currentSize.decrementAndGet()
                        Triple(polled, remaining > 0, false)
                    }
                    closed.get() -> Triple(null, false, true)
                    else -> Triple(null, false, false)
                }
            }
            if (item != null) {
                spaceAvailable.trySend(Unit)
                if (hasMoreItems) {
                    itemAvailable.trySend(Unit)
                }
                result = item
                finished = true
            } else if (isClosedEmpty) {
                finished = true
            } else if (itemAvailable.receiveCatching().isClosed) {
                result = dequeueOutgoing()
                finished = true
            }
        }
        return result
    }

    /** Returns the oldest queued outgoing item without removing it, or `null` when empty. */
    fun peek(): T? = synchronized(capacityLock) { queue.peek() }

    /** Drains all currently queued items in FIFO order and notifies waiting producers. */
    fun drain(): List<T> {
        val items = synchronized(capacityLock) {
            if (queue.isEmpty()) {
                emptyList()
            } else {
                buildList(currentSize.get()) {
                    while (true) {
                        val next = queue.poll() ?: break
                        add(next)
                    }
                }.also {
                    currentSize.set(0)
                }
            }
        }
        if (items.isNotEmpty()) {
            spaceAvailable.trySend(Unit)
        }
        return items
    }

    /** Returns the number of outgoing items currently waiting in the queue. */
    fun size(): Int = currentSize.get()

    /** Returns whether the queue currently contains no items. */
    fun isEmpty(): Boolean = currentSize.get() == 0

    /** Returns whether the queue has reached [maxSize]. */
    fun isFull(): Boolean = currentSize.get() >= maxSize

    /** Returns the total number of items dropped due to overflow policies. */
    fun droppedCount(): Int = droppedCounter.get()

    /** Returns whether this queue has been permanently closed. */
    fun isClosed(): Boolean = closed.get()

    /** Removes all currently buffered items without closing the queue. */
    fun clear() {
        val cleared = synchronized(capacityLock) {
            val hadItems = currentSize.get() > 0
            queue.clear()
            currentSize.set(0)
            hadItems
        }
        if (cleared) {
            spaceAvailable.trySend(Unit)
        }
    }

    /** Permanently closes the queue, unblocking all suspended producers and consumers. */
    fun close() {
        synchronized(capacityLock) {
            closed.set(true)
        }
        itemAvailable.close()
        spaceAvailable.close()
    }
}
