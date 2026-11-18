package com.bighelp.droidhelp.session

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * PromptSubmitter handles the submission of a text-turn user prompt through the session pipeline.
 *
 * @param journal The submission journal for persistence.
 * @param streamingClient The streaming client for handling the event stream.
 * @param secureInput The store for secure prompt values.
 */
class PromptSubmitter(
    private val journal: SubmissionJournal,
    private val streamingClient: StreamingClient,
    private val secureInput: SecureInput
) {

    /**
     * Submits a secure prompt using a value from the [secureInput] store.
     *
     * @param id The ID of the secure input.
     * @return A Flow of [PromptEvent] in the order they are emitted by the stream.
     */
    suspend fun submitSecure(id: String): Flow<PromptEvent> {
        val value = secureInput.get(id) ?: throw SessionException("Secure input not found for ID: $id")
        return submit(value)
    }

    /**
     * Submits a text-turn user prompt through the session pipeline.
     *
     * @param prompt The user prompt to submit.
     * @return A Flow of [PromptEvent] in the order they are emitted by the stream.
     */
    suspend fun submit(prompt: String): Flow<PromptEvent> = kotlinx.coroutines.flow.flow {
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
            journal.markUncertain(entry.id, "No receipt was received")
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
