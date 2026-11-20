package com.bighelp.droidhelp.transport

import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Correlates RPC requests, incoming JSON frames, and responses using request IDs.
 * Delegates thread-safe map tracking and [CorrelationEvent] emission to [FrameCorrelator]
 * while providing an ordered reader/writer loop and serialized callback execution.
 */
class RpcCorrelator {
    private sealed interface QueueItem {
        data class MessageItem(val message: Message) : QueueItem
        data class FrameItem(val frame: ParsedJsonFrame) : QueueItem
    }

    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /** Thread-safe correlator matching incoming [ParsedJsonFrame]s to outgoing request IDs. */
    val frameCorrelator: FrameCorrelator = FrameCorrelator(callbackExecutor)

    /** Hot stream of [CorrelationEvent]s emitted whenever an incoming frame matches a request ID. */
    val correlationEvents: SharedFlow<CorrelationEvent>
        get() = frameCorrelator.events

    // Ordered queue for sequential processing of incoming messages and JSON frames
    private val incomingQueue = LinkedBlockingQueue<QueueItem>()
    private var processingThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    /** Represents a pending RPC operation with its callback and timeout info. */
    data class PendingOperation(
        val callback: (Message) -> Unit,
        val timeoutNs: Long = System.nanoTime() + DEFAULT_TIMEOUT_NANOS,
        var error: Exception? = null
    )

    /** Starts the ordered reader/writer loop. */
    fun start() {
        if (isRunning.getAndSet(true)) return
        processingThread = Thread {
            while (isRunning.get()) {
                try {
                    val item = incomingQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    when (item) {
                        is QueueItem.MessageItem -> {
                            if (item.message is Message.Receipt) {
                                correlate(item.message.entryId, item.message)
                            }
                        }
                        is QueueItem.FrameItem -> frameCorrelator.correlate(item.frame)
                        null -> Unit
                    }
                } catch (_: InterruptedException) {
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
        processingThread?.join(STOP_JOIN_TIMEOUT_MS)
        processingThread = null
    }

    /** Submits a typed [Message] for ordered processing through the reader/writer loop. */
    fun submit(message: Message) {
        incomingQueue.offer(QueueItem.MessageItem(message))
    }

    /** Submits a [ParsedJsonFrame] for ordered correlation through the reader/writer loop. */
    fun submit(frame: ParsedJsonFrame) {
        incomingQueue.offer(QueueItem.FrameItem(frame))
    }

    /** Registers a typed [Message] callback for a given request ID. */
    fun register(requestId: String, callback: (Message) -> Unit) {
        frameCorrelator.registerMessage(requestId, callback = callback)
    }

    /** Looks up a pending message callback by request ID without removing it. */
    fun lookup(requestId: String): ((Message) -> Unit)? {
        return frameCorrelator.lookupMessageCallback(requestId)
    }

    /** Updates the callback for an existing pending request. */
    fun update(requestId: String, newCallback: (Message) -> Unit) {
        frameCorrelator.updateMessageCallback(requestId, newCallback)
    }

    /** Correlates a response message with a pending request by its ID.
     * The callback is executed on a single-threaded executor to ensure serialization.
     * Returns true if correlation succeeded (request was found and removed), false otherwise.
     */
    fun correlate(requestId: String, message: Message): Boolean {
        return frameCorrelator.correlateMessage(requestId, message)
    }

    /** Correlates an incoming [ParsedJsonFrame] with a pending outgoing request ID. */
    fun correlate(frame: ParsedJsonFrame, requestId: String? = null): CorrelationEvent? {
        return frameCorrelator.correlate(frame, requestId)
    }

    /** Cancels a pending request.
     * Removes the request from the map and returns true if it was found, false otherwise.
     */
    fun cancel(requestId: String): Boolean {
        return frameCorrelator.cancel(requestId)
    }

    /** Checks if a request ID has a pending operation. */
    fun contains(requestId: String): Boolean {
        return frameCorrelator.contains(requestId)
    }

    /** Returns the number of pending requests. */
    fun pendingCount(): Int {
        return frameCorrelator.pendingCount()
    }

    /** Removes all pending requests that have timed out. */
    fun cleanupTimeouts(): Int {
        return frameCorrelator.cleanupTimeouts()
    }

    /** Shuts down the correlator, its executor, and the reader/writer loop. */
    fun shutdown() {
        stop()
        frameCorrelator.clear(clearListeners = true)
        callbackExecutor.shutdown()
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 100L
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L
        const val DEFAULT_TIMEOUT_NANOS = FrameCorrelator.DEFAULT_TIMEOUT_NANOS
    }
}
