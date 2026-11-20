package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket

/**
 * Coordinates the bounded FIFO outbound message queue, serialized overflow backpressure,
 * [SocketOwner] fencing validation, socket write-buffer backpressure, and newline-delimited
 * JSON frame encoding for [WebSocketTransport].
 */
internal class WebSocketOutboundPipeline(
    private val scope: CoroutineScope,
    private val config: WebSocketTransport.WebSocketConfig,
    private val owners: Owners,
    private val hooks: Hooks
) {
    interface Hooks {
        fun isClosed(): Boolean
        fun isConnected(): Boolean
        fun currentSocket(): WebSocket?
        fun onMessageSent()
        fun onStaleDropped()
    }

    /** Represents a message paired with its captured socket owner for fencing. */
    private data class FencedMessage(val message: Message, val capturedSocketOwner: SocketOwner)

    private val queue = OutboundMessageQueue<FencedMessage>(
        maxSize = config.maxQueueSize,
        overflowPolicy = config.outboundOverflowPolicy
    )
    private val overflowLock = Any()
    private val processorLock = Any()
    private var lastOverflowJob: Job? = null
    private var processorJob: Job? = null

    fun send(message: Message) {
        val fenced = createFencedMessage(message)
        synchronized(overflowLock) {
            val hasPendingOverflow = lastOverflowJob?.isActive == true
            if (!hasPendingOverflow) {
                val enqueued = queue.enqueueOutgoing(fenced)
                if (!enqueued &&
                    !hooks.isClosed() &&
                    config.outboundOverflowPolicy == OutboundMessageQueue.OverflowPolicy.SUSPEND
                ) {
                    lastOverflowJob = scope.launch {
                        if (!hooks.isClosed()) {
                            queue.enqueueOutgoingSuspending(fenced)
                        }
                    }
                }
            } else if (!hooks.isClosed()) {
                val previousJob = lastOverflowJob
                lastOverflowJob = scope.launch {
                    previousJob?.join()
                    if (!hooks.isClosed()) {
                        queue.enqueueOutgoingSuspending(fenced)
                    }
                }
            }
        }
        if (hooks.isConnected()) {
            ensureProcessorRunning()
        }
    }

    fun enqueue(
        message: Message,
        policy: OutboundMessageQueue.OverflowPolicy = config.outboundOverflowPolicy
    ): Boolean {
        val fenced = createFencedMessage(message)
        val enqueued = queue.enqueueOutgoing(fenced, policy)
        if (enqueued && hooks.isConnected()) {
            ensureProcessorRunning()
        }
        return enqueued
    }

    suspend fun enqueueSuspending(message: Message): Boolean {
        val fenced = createFencedMessage(message)
        val enqueued = queue.enqueueOutgoingSuspending(fenced)
        if (enqueued && hooks.isConnected()) {
            ensureProcessorRunning()
        }
        return enqueued
    }

    fun dequeue(): Message? = queue.dequeueOutgoing()?.message

    fun size(): Int = queue.size()

    fun droppedCount(): Int = queue.droppedCount()

    fun ensureProcessorRunning() {
        synchronized(processorLock) {
            if (!hooks.isClosed() && hooks.isConnected() && processorJob?.isActive != true) {
                processorJob = scope.launch {
                    processQueue()
                }
            }
        }
    }

    fun cancelProcessor() {
        val jobToCancel = synchronized(processorLock) {
            processorJob.also { processorJob = null }
        }
        jobToCancel?.cancel()
    }

    fun close() {
        cancelProcessor()
        queue.close()
    }

    private fun createFencedMessage(message: Message): FencedMessage {
        check(!hooks.isClosed()) { "WebSocketTransport is closed." }
        val socketOwner = checkNotNull(owners.getSocketOwner()) {
            "No SocketOwner available. Call connect() or resumeSession() first."
        }
        return FencedMessage(message, socketOwner)
    }

    private fun isSocketBackpressured(): Boolean {
        val ws = hooks.currentSocket() ?: return false
        return !hooks.isClosed() && hooks.isConnected() && ws.queueSize() >= config.maxBufferedBytes
    }

    private suspend fun awaitSocketBackpressureRelief() {
        while (isSocketBackpressured()) {
            delay(BACKPRESSURE_POLL_MS)
        }
    }

    private suspend fun processQueue() {
        var keepRunning = true
        while (keepRunning && !hooks.isClosed() && hooks.isConnected()) {
            awaitSocketBackpressureRelief()
            val fenced = if (!hooks.isClosed() && hooks.isConnected()) {
                queue.dequeueOutgoingSuspending()
            } else {
                null
            }
            if (fenced == null) {
                keepRunning = false
            } else {
                sendFencedMessage(fenced)
            }
        }
    }

    private fun sendFencedMessage(fenced: FencedMessage) {
        try {
            owners.validateSocketOwner(fenced.capturedSocketOwner)
            if (hooks.isConnected()) {
                val ws = hooks.currentSocket() ?: return
                val jsonString = config.json.encodeToString(Message.serializer(), fenced.message)
                if (ws.send("$jsonString\n")) {
                    hooks.onMessageSent()
                }
            }
        } catch (_: StaleOwnerException) {
            hooks.onStaleDropped()
        }
    }

    companion object {
        private const val BACKPRESSURE_POLL_MS = 10L
    }
}
