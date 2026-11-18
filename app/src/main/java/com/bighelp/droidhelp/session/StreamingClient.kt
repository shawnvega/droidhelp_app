package com.bighelp.droidhelp.session

import kotlinx.coroutines.flow.Flow

/**
 * Interface for the streaming client that handles prompt submission.
 */
interface StreamingClient {
    /**
     * Submits a prompt into the processing pipeline.
     *
     * @param prompt The user prompt to submit.
     * @param entryId The unique ID of the entry.
     * @return A Flow of [PromptEvent]s in the order they are produced by the stream.
     */
    suspend fun submit(prompt: String, entryId: String): Flow<PromptEvent>

    /**
     * Responds to a clarification request.
     *
     * @param response The response text.
     */
    suspend fun respondToClarify(response: String)

    /**
     * Responds to an approval request.
     *
     * @param decision The decision made (APPROVE or REJECT).
     */
    suspend fun respondToApproval(decision: ApprovalDecision)
}

/**
 * Possible decisions for an approval request.
 */
enum class ApprovalDecision {
    APPROVE, REJECT
}
