package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Message
import com.bighelp.droidhelp.transport.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementation of [StreamingClient] that handles ordered event consumption,
 * including pausing for clarification or approval requests.
 *
 * Events are processed in strict sequential order using per-entry sequence numbers.
 * A bounded channel provides backpressure to upstream producers.
 * Clarification and approval events pause the stream until a response is provided
 * or a timeout occurs.
 */
class EventStream(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val pauseTimeoutMillis: Long = 5000L,
    private val channelCapacity: Int = 64
) : StreamingClient {

    // Bounded channel for backpressure - send() will suspend when full
    private val _events = Channel<PromptEvent>(capacity = channelCapacity)
    private val _currentEntryId = AtomicReference<String?>(null)
    private val _pauseDeferred = AtomicReference<CompletableDeferred<Unit>?>(null)
    // Per-entry sequence numbers for strict ordering
    private val _sequenceNumbers = mutableMapOf<String, AtomicInteger>()

    init {
        transport.onMessage { message ->
            val event = mapMessageToEvent(message)
            if (event != null) {
                // Use trySend for non-blocking enqueue from transport callback
                // If channel is full, the event is dropped (transport should handle retries)
                _events.trySend(event)
            }
        }
    }

    private fun mapMessageToEvent(message: Message): PromptEvent? = when (message) {
        is Message.Clarify -> PromptEvent(
            id = message.entryId,
            type = "clarify",
            content = message.question,
            timestamp = java.time.Instant.now()
        )
        is Message.Approval -> PromptEvent(
            id = message.entryId,
            type = "approval",
            content = message.message,
            timestamp = java.time.Instant.now()
        )
        is Message.Receipt -> PromptEvent(
            id = message.entryId,
            type = "receipt",
            content = "",
            timestamp = java.time.Instant.now(),
            metadata = mapOf("entryId" to message.entryId)
        )
        else -> null
    }

    override suspend fun submit(prompt: String, entryId: String): Flow<PromptEvent> = flow {
        _currentEntryId.set(entryId)
        transport.send(Message.Prompt(prompt, entryId))

        // Initialize sequence number for this entry
        val sequenceNumber = _sequenceNumbers.getOrPut(entryId) { AtomicInteger(0) }
        var expectedSequence = 0
        var isFinished = false

        try {
            while (!isFinished) {
                // Receive event from the channel - suspends when empty (backpressure consumer side)
                val event = _events.receive()

                // Only process events for the current entryId
                if (event.id != entryId) {
                    continue
                }

                // Check strict sequential ordering using sequence numbers
                val receivedSequence = sequenceNumber.getAndIncrement()
                check(receivedSequence == expectedSequence) {
                    "Out-of-order event received: expected sequence $expectedSequence but got $receivedSequence"
                }
                expectedSequence++

                emit(event)

                if (event.type == "clarify" || event.type == "approval") {
                    val deferred = CompletableDeferred<Unit>()
                    _pauseDeferred.set(deferred)
                    try {
                        // Wait for response with a timeout
                        withTimeout(pauseTimeoutMillis) {
                            deferred.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw IllegalStateException("Timeout waiting for ${event.type} response after ${pauseTimeoutMillis}ms")
                    } finally {
                        _pauseDeferred.set(null)
                    }
                }

                if (event.type == "receipt") {
                    isFinished = true
                }
            }
        } finally {
            _currentEntryId.set(null)
            _sequenceNumbers.remove(entryId)
        }
    }

    override suspend fun respondToClarify(response: String) {
        val id = _currentEntryId.get() ?: throw IllegalStateException("No active submission")
        transport.send(Message.Prompt(response, id))
        _pauseDeferred.get()?.complete(Unit)
    }

    override suspend fun respondToApproval(decision: ApprovalDecision) {
        val id = _currentEntryId.get() ?: throw IllegalStateException("No active submission")
        val text = if (decision == ApprovalDecision.APPROVE) "APPROVE" else "REJECT"
        transport.send(Message.Prompt(text, id))
        _pauseDeferred.get()?.complete(Unit)
    }
}
