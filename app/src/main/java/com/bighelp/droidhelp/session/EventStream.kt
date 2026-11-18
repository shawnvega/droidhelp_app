package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Message
import com.bighelp.droidhelp.transport.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementation of [StreamingClient] that handles ordered event consumption,
 * including pausing for clarification or approval requests.
 */
class EventStream(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val pauseTimeoutMillis: Long = 5000L // Reduced for testing
) : StreamingClient {

    private val _events = Channel<PromptEvent>(capacity = 10) // Bounded channel for backpressure
    private val _currentEntryId = AtomicReference<String?>(null)
    private val _pauseDeferred = AtomicReference<CompletableDeferred<Unit>?>(null)

    init {
        transport.onMessage { message ->
            val event = mapMessageToEvent(message)
            if (event != null) {
                // Use scope.launch to allow suspension in send() for backpressure
                scope.launch {
                    _events.send(event)
                }
            }
        }
    }

    private fun mapMessageToEvent(message: Message): PromptEvent? = when (message) {
        is Message.Clarify -> PromptEvent(
            id = message.entryId,
            type = "clarify",
            content = message.question,
            timestamp = clock.instant()
        )
        is Message.Approval -> PromptEvent(
            id = message.entryId,
            type = "approval",
            content = message.message,
            timestamp = clock.instant()
        )
        is Message.Receipt -> PromptEvent(
            id = message.entryId,
            type = "receipt",
            content = "",
            timestamp = clock.instant(),
            metadata = mapOf("entryId" to message.entryId)
        )
        else -> null
    }

    override suspend fun submit(prompt: String, entryId: String): Flow<PromptEvent> = flow {
        _currentEntryId.set(entryId)
        transport.send(Message.Prompt(prompt, entryId))

        var lastTimestamp: Instant? = null
        var isFinished = false

        try {
            while (!isFinished) {
                // Receive event from the channel
                val event = _events.receive()

                // Only process events for the current entryId
                if (event.id != entryId) {
                    continue
                }

                // Check ordering
                if (lastTimestamp != null && !event.timestamp.isAfter(lastTimestamp)) {
                    throw IllegalStateException("Out-of-order event received: ${event.timestamp} is not after $lastTimestamp")
                }
                lastTimestamp = event.timestamp

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
