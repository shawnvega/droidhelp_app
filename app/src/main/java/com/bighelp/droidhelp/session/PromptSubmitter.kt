package com.bighelp.droidhelp.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.UUID

/**
 * Represents a single event in the prompt submission stream.
 */
data class PromptEvent(
    val id: String,
    val type: String,
    val content: String,
    val timestamp: Instant,
    val metadata: Map<String, String>? = null
)

/**
 * Interface for the streaming client that handles prompt submission.
 *
 * Implementations submit a prompt into the processing pipeline and emit a stream of
 * [PromptEvent]s in the order they are produced by the stream.
 */
interface StreamingClient {
    suspend fun submit(prompt: String, entryId: String): Flow<PromptEvent>
}

/**
 * PromptSubmitter handles the submission of a text-turn user prompt through the session pipeline.
 *
 * It journals the prompt to the session journal before submission, submits the prompt into the
 * processing pipeline, treats the streaming receipt as the admission signal, and consumes the
 * resulting ordered events in sequence.
 *
 * The prompt is only considered admitted once the stream acknowledges it (the receipt event).
 * Callers can await/inspect admission by collecting the returned flow — the first event of type
 * "receipt" signals admission.
 */
class PromptSubmitter(
    private val journal: SubmissionJournal,
    private val streamingClient: StreamingClient
) {

    /**
     * Submits a text-turn user prompt through the session pipeline.
     *
     * @param prompt The user prompt to submit.
     * @return A Flow of [PromptEvent] in the order they are emitted by the stream.
     *
     * The flow journals the prompt before submission, then submits it into the processing
     * pipeline. The first event of type "receipt" is treated as the admission signal: the
     * journal entry is acknowledged only after the receipt is received. All subsequent events
     * are emitted in their original order without reordering or dropping.
     */
    suspend fun submit(prompt: String): Flow<PromptEvent> = flow {
        // 1. Journal the prompt before submission
        val entry = journal.prepare(prompt)
        journal.dispatch(entry.id)

        // 2. Submit the prompt into the processing pipeline
        val eventFlow = streamingClient.submit(prompt, entry.id)

        // 3. Consume the resulting ordered events in sequence
        var receiptReceived = false
        eventFlow.collect { event ->
            if (!receiptReceived && event.type == "receipt") {
                // 4. Acknowledge the entry after receiving the streaming receipt
                journal.acknowledge(entry.id)
                receiptReceived = true
            }
            emit(event)
        }

        // If no receipt was received, mark the entry as uncertain
        if (!receiptReceived) {
            journal.markUncertain(entry.id, "No receipt received")
        }
    }

    /**
     * Submits a text-turn user prompt and returns the result.
     *
     * @param prompt The user prompt to submit.
     * @return A [PromptSubmissionResult] containing the entry ID, prompt, admission status, and events.
     */
    suspend fun submitAndAwait(prompt: String): PromptSubmissionResult {
        val events = mutableListOf<PromptEvent>()
        var entryId: String? = null

        submit(prompt).collect { event ->
            events.add(event)
            if (entryId == null) {
                entryId = event.metadata?.get("entryId")
            }
        }

        val entry = entryId?.let { journal.get(it) }
        val admitted = entry?.state == State.ACKNOWLEDGED

        return PromptSubmissionResult(
            entryId = entryId ?: UUID.randomUUID().toString(),
            prompt = prompt,
            admitted = admitted,
            events = events.toList()
        )
    }
}

/**
 * Represents the result of a prompt submission.
 */
data class PromptSubmissionResult(
    val entryId: String,
    val prompt: String,
    val admitted: Boolean,
    val events: List<PromptEvent>
)
