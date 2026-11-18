package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Message
import com.bighelp.droidhelp.transport.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.time.Clock
import java.time.Instant

/**
 * Implementation of [StreamingClient] that handles ordered event consumption,
 * including pausing for clarification or approval requests.
 */
class EventStream(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC()
) : StreamingClient {

    private val _events = Channel<PromptEvent>(Channel.UNLIMITED)
    private var _pauseDeferred: CompletableDeferred<Unit>? = null
    private var _currentEntryId: String? = null

    init {
        transport.onMessage { message ->
            val event = mapMessageToEvent(message)
            if (event != null) {
                _events.trySend(event)
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
        _currentEntryId = entryId
        transport.send(Message.Prompt(prompt, entryId))

        var lastTimestamp: Instant? = null
        var isFinished = false

        while (!isFinished) {
            val event = _events.receive()
            if (event.id == entryId) {
                if (lastTimestamp != null && !event.timestamp.isAfter(lastTimestamp)) {
                    throw IllegalStateException("Out-of-order event received: ${event.timestamp} is not after $lastTimestamp")
                }
                lastTimestamp = event.timestamp

                emit(event)

                if (event.type == "clarify" || event.type == "approval") {
                    _pauseDeferred = CompletableDeferred()
                    _pauseDeferred?.await()
                }

                if (event.type == "receipt") {
                    isFinished = true
                }
            }
        }
    }

    override suspend fun respondToClarify(response: String) {
        val id = _currentEntryId ?: throw IllegalStateException("No active submission")
        transport.send(Message.Prompt(response, id))
        _pauseDeferred?.complete(Unit)
        _pauseDeferred = null
    }

    override suspend fun respondToApproval(decision: ApprovalDecision) {
        val id = _currentEntryId ?: throw IllegalStateException("No active submission")
        val text = if (decision == ApprovalDecision.APPROVE) "APPROVE" else "REJECT"
        transport.send(Message.Prompt(text, id))
        _pauseDeferred?.complete(Unit)
        _pauseDeferred = null
    }
}
