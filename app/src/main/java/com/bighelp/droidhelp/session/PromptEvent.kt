package com.bighelp.droidhelp.session

import java.time.Instant

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
