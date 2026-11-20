package com.bighelp.droidhelp.transport

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emitted when an incoming [ParsedJsonFrame] matches a registered outgoing request ID.
 */
data class CorrelationEvent(
    val requestId: String,
    val frame: ParsedJsonFrame,
    val isError: Boolean = false,
    val correlatedAtNanos: Long = System.nanoTime()
)

/**
 * Thread-safe correlator that associates incoming [ParsedJsonFrame] objects with outgoing
 * request IDs using a [ConcurrentHashMap] keyed by request ID, emitting [CorrelationEvent]s
 * to listeners and [events] whenever a frame matches a pending request.
 */
class FrameCorrelator(
    private val callbackExecutor: Executor? = null,
    private val defaultTimeoutNanos: Long = DEFAULT_TIMEOUT_NANOS
) {
    init {
        require(defaultTimeoutNanos > 0L) { "defaultTimeoutNanos must be positive" }
    }

    internal data class PendingEntry(
        val frameCallback: ((CorrelationEvent) -> Unit)? = null,
        val messageCallback: ((Message) -> Unit)? = null,
        val deadlineNanos: Long
    )

    private val pendingRequests = ConcurrentHashMap<String, PendingEntry>()
    private val listeners = CopyOnWriteArrayList<(CorrelationEvent) -> Unit>()
    private val _events = MutableSharedFlow<CorrelationEvent>(
        extraBufferCapacity = DEFAULT_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Hot stream of [CorrelationEvent]s emitted whenever an incoming frame matches a request ID. */
    val events: SharedFlow<CorrelationEvent> = _events.asSharedFlow()

    private val correlatedCounter = AtomicInteger(0)
    private val unmatchedCounter = AtomicInteger(0)

    /** Total number of incoming frames or messages successfully correlated to a pending request. */
    val correlatedCount: Int
        get() = correlatedCounter.get()

    /** Total number of incoming frames that did not match any pending request ID. */
    val unmatchedCount: Int
        get() = unmatchedCounter.get()

    /**
     * Registers an outgoing [requestId] so a matching incoming JSON frame emits a
     * [CorrelationEvent] and optionally invokes [onMatch].
     */
    fun register(
        requestId: String,
        timeoutNanos: Long = defaultTimeoutNanos,
        onMatch: ((CorrelationEvent) -> Unit)? = null
    ) {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        require(timeoutNanos > 0L) { "timeoutNanos must be positive" }
        pendingRequests[requestId] = PendingEntry(
            frameCallback = onMatch,
            deadlineNanos = System.nanoTime() + timeoutNanos
        )
    }

    internal fun registerMessage(
        requestId: String,
        timeoutNanos: Long = defaultTimeoutNanos,
        callback: (Message) -> Unit
    ) {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        require(timeoutNanos > 0L) { "timeoutNanos must be positive" }
        pendingRequests[requestId] = PendingEntry(
            messageCallback = callback,
            deadlineNanos = System.nanoTime() + timeoutNanos
        )
    }

    internal fun lookupMessageCallback(requestId: String): ((Message) -> Unit)? {
        return pendingRequests[requestId]?.messageCallback
    }

    internal fun updateMessageCallback(requestId: String, newCallback: (Message) -> Unit) {
        pendingRequests.computeIfPresent(requestId) { _, existing ->
            existing.copy(
                messageCallback = newCallback,
                deadlineNanos = System.nanoTime() + defaultTimeoutNanos
            )
        }
    }

    /**
     * Attempts to correlate [frame] with a pending outgoing request keyed by [requestId]
     * (or the request ID extracted from [frame] when [requestId] is omitted).
     *
     * Atomically removes the matched request and emits a [CorrelationEvent] to [events],
     * the per-request callback, and all registered listeners.
     *
     * @return The emitted [CorrelationEvent], or `null` if no pending request matched.
     */
    fun correlate(frame: ParsedJsonFrame, requestId: String? = null): CorrelationEvent? {
        val resolvedId = requestId ?: FrameRequestIdExtractor.extractRequestId(frame)
        val pending = if (resolvedId != null) pendingRequests.remove(resolvedId) else null
        return if (resolvedId == null || pending == null) {
            unmatchedCounter.incrementAndGet()
            null
        } else {
            correlatedCounter.incrementAndGet()
            val event = CorrelationEvent(
                requestId = resolvedId,
                frame = frame,
                isError = FrameRequestIdExtractor.isErrorFrame(frame)
            )
            dispatchTask {
                _events.tryEmit(event)
                pending.frameCallback?.invoke(event)
                frame.message?.let { message ->
                    pending.messageCallback?.invoke(message)
                }
                listeners.forEach { listener -> listener(event) }
            }
            event
        }
    }

    internal fun correlateMessage(requestId: String, message: Message): Boolean {
        val pending = pendingRequests.remove(requestId) ?: return false
        correlatedCounter.incrementAndGet()
        pending.messageCallback?.let { callback ->
            dispatchTask { callback(message) }
        }
        return true
    }

    /** Cancels and removes a pending request by [requestId]. Returns `true` if it was present. */
    fun cancel(requestId: String): Boolean = pendingRequests.remove(requestId) != null

    /** Returns `true` if [requestId] is currently pending correlation. */
    fun contains(requestId: String): Boolean = pendingRequests.containsKey(requestId)

    /** Returns the number of currently pending requests awaiting a matching frame. */
    fun pendingCount(): Int = pendingRequests.size

    /**
     * Removes all pending requests whose deadline has expired as of [nowNanos].
     * Uses conditional removal so concurrently re-registered entries are preserved.
     */
    fun cleanupTimeouts(nowNanos: Long = System.nanoTime()): Int {
        var removed = 0
        for ((requestId, entry) in pendingRequests) {
            if (entry.deadlineNanos <= nowNanos && pendingRequests.remove(requestId, entry)) {
                removed++
            }
        }
        return removed
    }

    /** Adds a thread-safe listener invoked for every emitted [CorrelationEvent]. */
    fun addListener(listener: (CorrelationEvent) -> Unit) {
        listeners.add(listener)
    }

    /** Removes a previously registered correlation event [listener]. */
    fun removeListener(listener: (CorrelationEvent) -> Unit) {
        listeners.remove(listener)
    }

    /** Clears all pending requests and optionally removes all event listeners. */
    fun clear(clearListeners: Boolean = false) {
        pendingRequests.clear()
        if (clearListeners) {
            listeners.clear()
        }
    }

    private fun dispatchTask(task: () -> Unit) {
        val executor = callbackExecutor
        if (executor == null) {
            task()
        } else {
            try {
                executor.execute(task)
            } catch (_: RejectedExecutionException) {
                // Executor has already been shut down.
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_NANOS = 5_000_000_000L
        const val DEFAULT_EVENT_BUFFER_CAPACITY = 256
    }
}
